/*
 * Copyright 2025-2026 Yehor Brodskyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sailkit.billing.plugin.liqpay.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.client.LiqPayTransientException;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.crypto.CardTokenCipher;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.HppStatus;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.plugin.api.payment.PluginHostedPaymentPageFormDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Card verification form, hold, capture, void and purchase by a stored card token. Payments decrypt the token only here. */
final class TokenFlow {

    static final String PAYMENT_METHOD_ID = "liqpay_payment_method_id";
    static final String IP = "liqpay_ip";
    static final String PHONE = "liqpay_phone";
    static final String VERIFY_PREFIX = "verify-";
    static final String NO_CARD_TOKEN = "no_card_token";
    static final String KEY_MISSING = "card_token_key_missing";

    private static final Logger logger = LoggerFactory.getLogger(TokenFlow.class);
    private static final DateTimeFormatter EXPIRED_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern IPV4 = Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern PHONE_NUMBER = Pattern.compile("\\+?\\d{7,15}");

    private final LiqPayStore store;
    private final Clock clock;

    TokenFlow(final LiqPayStore store, final Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    // --- Hosted verification form ---

    /** Signs a hosted `hold` of the verification amount with `recurringbytoken=1`. Caller amount, currency and action are ignored. */
    HostedPaymentPageFormDescriptor form(final LiqPayConfig c, final UUID tenantId, final UUID kbAccountId,
                                         final Iterable<PluginProperty> customFields, final Iterable<PluginProperty> properties)
            throws PaymentPluginApiException, SQLException {
        if (!c.hasCardTokenKey()) {
            throw new PaymentPluginApiException(KEY_MISSING, "the tenant config names no card token key");
        }
        final UUID pmId = uuid(Props.find(PAYMENT_METHOD_ID, properties, customFields))
                .orElseThrow(() -> new PaymentPluginApiException("invalid_payment_method", "liqpay_payment_method_id is not a UUID"));
        final boolean usable = store.findPaymentMethod(pmId, tenantId)
                                    .filter(m -> kbAccountId.toString().equals(m.getKbAccountId()))
                                    .filter(m -> LiqPayPaymentPluginApi.MODE_TOKEN.equals(m.getMode()))
                                    .isPresent();
        if (!usable) {
            throw new PaymentPluginApiException("invalid_payment_method", "liqpay_payment_method_id names no token payment method of the account");
        }
        final String resultUrl = Props.find(CheckoutFlow.RESULT_URL, properties, customFields);
        if (!c.isResultUrlAllowed(resultUrl)) {
            throw new PaymentPluginApiException("result_url_not_allowed", "liqpay_result_url is not in resultUrlAllowlist");
        }
        final String orderId = VERIFY_PREFIX + UUID.randomUUID();
        final BigDecimal amount = c.verificationAmount().setScale(2, RoundingMode.HALF_UP);
        final LiqpayHppRequestsRecord row = new LiqpayHppRequestsRecord();
        row.setKbAccountId(kbAccountId.toString());
        row.setKbPaymentMethodId(pmId.toString());
        row.setKbTenantId(tenantId.toString());
        row.setOrderId(orderId);
        row.setAmount(amount);
        row.setCurrency(c.verificationCurrency());
        row.setStatus(HppStatus.PENDING);
        store.insertHppRequest(row);

        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", LiqPayClient.API_VERSION);
        p.put("public_key", c.publicKey());
        p.put("action", LiqPayAction.HOLD.wireName());
        p.put("amount", LiqPayClient.money(amount));
        p.put("currency", c.verificationCurrency());
        p.put("description", CheckoutFlow.description(c, Props.find(CheckoutFlow.DESCRIPTION, properties, customFields)));
        p.put("order_id", orderId);
        p.put("language", CheckoutFlow.language(c, Props.find(CheckoutFlow.LANGUAGE, properties, customFields)));
        p.put("result_url", resultUrl);
        p.put("server_url", CheckoutFlow.serverUrl(c, tenantId));
        p.put("recurringbytoken", "1");
        p.put("expired_date", EXPIRED_DATE.format(clock.instant().plus(c.checkoutTtl())));
        final String data = LiqPayEnvelope.encode(p);
        final String url = CheckoutFlow.checkoutUrl(c);
        logger.info("liqpay verification form order_id={} status=PENDING", orderId);
        return new PluginHostedPaymentPageFormDescriptor(kbAccountId, PluginHostedPaymentPageFormDescriptor.POST, url,
                                                         List.of(new PluginProperty("data", data, false),
                                                                 new PluginProperty("signature", LiqPaySigner.sign(c.privateKey(), data), false),
                                                                 new PluginProperty("checkout_url", url, false)),
                                                         List.of(new PluginProperty(TransactionInfos.ORDER_ID, orderId, false)));
    }

    // --- Payments by token ---

    LiqpayTransactionsRecord purchase(final LiqPayConfig c, final LiqPayClient client, final Call call) throws SQLException {
        return charge(c, client, call, TransactionType.PURCHASE, LiqPayAction.PAYTOKEN);
    }

    LiqpayTransactionsRecord authorize(final LiqPayConfig c, final LiqPayClient client, final Call call) throws SQLException {
        return charge(c, client, call, TransactionType.AUTHORIZE, LiqPayAction.HOLD);
    }

    /** Completes the held amount, or a part of it. LiqPay releases the rest of a partial completion. */
    LiqpayTransactionsRecord capture(final LiqPayConfig c, final LiqPayClient client, final Call call) throws SQLException {
        final Optional<LiqpayTransactionsRecord> existing = store.findByTransactionId(call.kbTransactionId, call.tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        final List<LiqpayTransactionsRecord> rows = store.findByPayment(call.kbPaymentId, call.tenantId);
        final Optional<LiqpayTransactionsRecord> hold = heldAuthorization(rows);
        final BigDecimal amount = scale(call.amount);
        final String errCode = hold.isEmpty() ? "no_authorization"
                               : isClosed(rows) ? "hold_already_closed"
                               : amount == null ? "amount_not_allowed"
                               : !hold.get().getCurrency().equals(call.currency.name()) ? "currency_mismatch"
                               : amount.compareTo(hold.get().getAmount()) > 0 ? "capture_exceeds_hold"
                               : null;
        if (errCode != null) {
            return rejected(call, TransactionType.CAPTURE, LiqPayAction.HOLD_COMPLETION, errCode);
        }
        final LiqpayTransactionsRecord row = row(call, TransactionType.CAPTURE, LiqPayAction.HOLD_COMPLETION, amount, call.currency.name());
        row.setOriginalOrderId(hold.get().getOrderId());
        store.insertTransaction(row);
        return apply(c, call, row, LiqPayAction.HOLD_COMPLETION, () -> client.holdCompletion(c, hold.get().getOrderId(), amount));
    }

    /** Releases the full held amount. LiqPay has no unhold action, so the release is a refund of the hold order. */
    LiqpayTransactionsRecord voidHold(final LiqPayConfig c, final LiqPayClient client, final Call call) throws SQLException {
        final Optional<LiqpayTransactionsRecord> existing = store.findByTransactionId(call.kbTransactionId, call.tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        final List<LiqpayTransactionsRecord> rows = store.findByPayment(call.kbPaymentId, call.tenantId);
        final Optional<LiqpayTransactionsRecord> hold = heldAuthorization(rows);
        if (hold.isEmpty()) {
            return null;
        }
        final Call held = call.with(hold.get().getAmount(), Currency.valueOf(hold.get().getCurrency()));
        if (isClosed(rows)) {
            return rejected(held, TransactionType.VOID, LiqPayAction.REFUND, "hold_already_closed");
        }
        final LiqpayTransactionsRecord row = row(held, TransactionType.VOID, LiqPayAction.REFUND, hold.get().getAmount(), hold.get().getCurrency());
        row.setOriginalOrderId(hold.get().getOrderId());
        store.insertTransaction(row);
        return apply(c, held, row, LiqPayAction.REFUND, () -> client.releaseHold(c, hold.get().getOrderId(), hold.get().getAmount()));
    }

    private LiqpayTransactionsRecord charge(final LiqPayConfig c, final LiqPayClient client, final Call call, final TransactionType type,
                                            final LiqPayAction action) throws SQLException {
        final Optional<LiqpayTransactionsRecord> existing = store.findByTransactionId(call.kbTransactionId, call.tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        final Optional<LiqpayPaymentMethodsRecord> method = store.findPaymentMethod(call.kbPaymentMethodId, call.tenantId)
                                                                 .filter(m -> LiqPayPaymentPluginApi.MODE_TOKEN.equals(m.getMode()))
                                                                 .filter(m -> Boolean.TRUE.equals(m.getVerified()))
                                                                 .filter(m -> m.getCardTokenEnc() != null);
        final BigDecimal amount = scale(call.amount);
        final String errCode = method.isEmpty() ? NO_CARD_TOKEN
                               : !c.hasCardTokenKey() ? KEY_MISSING
                               : !c.currencies().contains(call.currency.name()) ? "currency_not_allowed"
                               : amount == null ? "amount_not_allowed"
                               : null;
        if (errCode != null) {
            return rejected(call, type, action, errCode);
        }
        final String token;
        try {
            token = new CardTokenCipher(c.cardTokenKey(), method.get().getCardTokenKeyId()).decrypt(method.get().getCardTokenEnc());
        } catch (final GeneralSecurityException | IllegalArgumentException e) {
            logger.warn("liqpay card token not readable order_id={} key_id={}", call.kbTransactionId, method.get().getCardTokenKeyId());
            return rejected(call, type, action, "card_token_key_mismatch");
        }
        final LiqpayTransactionsRecord row = row(call, type, action, amount, call.currency.name());
        store.insertTransaction(row);
        final Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("description", CheckoutFlow.description(c, Props.find(CheckoutFlow.DESCRIPTION, call.properties)));
        extra.put("ip", match(IP, call.properties, ip -> IPV4.matcher(ip).matches() || (ip.contains(":") && IPV6.matcher(ip).matches())));
        extra.put("phone", match(PHONE, call.properties, ph -> PHONE_NUMBER.matcher(ph).matches()));
        extra.put("language", CheckoutFlow.language(c, Props.find(CheckoutFlow.LANGUAGE, call.properties)));
        extra.put("server_url", CheckoutFlow.serverUrl(c, call.tenantId));
        final String orderId = row.getOrderId();
        return apply(c, call, row, action, action == LiqPayAction.HOLD
                                           ? () -> client.holdToken(c, orderId, amount, call.currency.name(), token, extra)
                                           : () -> client.payToken(c, orderId, amount, call.currency.name(), token, extra));
    }

    // --- Helpers ---

    private interface LiqPayCall {

        LiqPayResult send() throws LiqPayTransientException;
    }

    /** Applies the direct answer. A transient error keeps the row PENDING, and `getPaymentInfo` polls it later. */
    private LiqpayTransactionsRecord apply(final LiqPayConfig c, final Call call, final LiqpayTransactionsRecord row,
                                           final LiqPayAction action, final LiqPayCall send) throws SQLException {
        final LiqPayResult r;
        try {
            r = send.send();
        } catch (final LiqPayTransientException e) {
            logger.warn("liqpay {} not confirmed order_id={} status=PENDING", action.wireName(), row.getOrderId());
            return store.findByTransactionId(call.kbTransactionId, call.tenantId).orElse(row);
        }
        final PaymentPluginStatus mapped = "error".equals(r.result()) ? PaymentPluginStatus.ERROR
                                                                      : LiqPayStatusMapper.map(action, r.status(), c.sandbox());
        if (mapped == PaymentPluginStatus.PROCESSED && action != LiqPayAction.REFUND && !Amounts.match(row, r)) {
            logger.warn("liqpay {} amount mismatch order_id={} status={}", action.wireName(), row.getOrderId(), r.status());
            return store.findByTransactionId(call.kbTransactionId, call.tenantId).orElse(row);
        }
        store.updateFromLiqPay(row.getOrderId(), call.tenantId, r, mapped, Map.of());
        return store.findByTransactionId(call.kbTransactionId, call.tenantId).orElse(row);
    }

    /** A call that LiqPay never saw is stored as CANCELED, so a repeated call gives the same answer. */
    private LiqpayTransactionsRecord rejected(final Call call, final TransactionType type, final LiqPayAction action, final String errCode)
            throws SQLException {
        final LiqpayTransactionsRecord row = row(call, type, action, call.amount, call.currency.name());
        row.setPluginStatus(PaymentPluginStatus.CANCELED.name());
        row.setErrCode(errCode);
        store.insertTransaction(row);
        logger.warn("liqpay {} rejected order_id={} err_code={}", action.wireName(), call.kbTransactionId, errCode);
        return row;
    }

    private static LiqpayTransactionsRecord row(final Call call, final TransactionType type, final LiqPayAction action,
                                                final BigDecimal amount, final String currency) {
        final LiqpayTransactionsRecord row = new LiqpayTransactionsRecord();
        row.setKbAccountId(call.kbAccountId.toString());
        row.setKbPaymentId(call.kbPaymentId.toString());
        row.setKbTransactionId(call.kbTransactionId.toString());
        row.setKbPaymentMethodId(call.kbPaymentMethodId.toString());
        row.setKbTenantId(call.tenantId.toString());
        row.setTransactionType(type.name());
        row.setLiqpayAction(action.wireName());
        row.setOrderId(call.kbTransactionId.toString());
        row.setAmount(amount == null ? BigDecimal.ZERO : amount);
        row.setCurrency(currency);
        row.setPluginStatus(PaymentPluginStatus.PENDING.name());
        return row;
    }

    private static Optional<LiqpayTransactionsRecord> heldAuthorization(final List<LiqpayTransactionsRecord> rows) {
        return rows.stream()
                   .filter(r -> TransactionType.AUTHORIZE.name().equals(r.getTransactionType()))
                   .filter(r -> PaymentPluginStatus.PROCESSED.name().equals(r.getPluginStatus()))
                   .findFirst();
    }

    /** True when a capture or a void of the hold is done or still open. */
    private static boolean isClosed(final List<LiqpayTransactionsRecord> rows) {
        return rows.stream()
                   .filter(r -> TransactionType.CAPTURE.name().equals(r.getTransactionType())
                                || TransactionType.VOID.name().equals(r.getTransactionType()))
                   .anyMatch(r -> PaymentPluginStatus.PROCESSED.name().equals(r.getPluginStatus())
                                  || PaymentPluginStatus.PENDING.name().equals(r.getPluginStatus()));
    }

    private static String match(final String key, final Iterable<PluginProperty> properties,
                                final Predicate<String> valid) {
        final String v = Props.find(key, properties);
        return v != null && valid.test(v.trim()) ? v.trim() : null;
    }

    private static BigDecimal scale(final BigDecimal amount) {
        try {
            return amount == null || amount.signum() <= 0 ? null : amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (final ArithmeticException e) {
            return null;
        }
    }

    private static Optional<UUID> uuid(final String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value.trim().toLowerCase(Locale.ROOT)));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The Kill Bill ids and the amount of one payment call. */
    static final class Call {

        final UUID tenantId;
        final UUID kbAccountId;
        final UUID kbPaymentId;
        final UUID kbTransactionId;
        final UUID kbPaymentMethodId;
        final BigDecimal amount;
        final Currency currency;
        final Iterable<PluginProperty> properties;

        Call(final UUID tenantId, final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId,
             final BigDecimal amount, final Currency currency, final Iterable<PluginProperty> properties) {
            this.tenantId = tenantId;
            this.kbAccountId = kbAccountId;
            this.kbPaymentId = kbPaymentId;
            this.kbTransactionId = kbTransactionId;
            this.kbPaymentMethodId = kbPaymentMethodId;
            this.amount = amount;
            this.currency = currency;
            this.properties = properties;
        }

        Call with(final BigDecimal newAmount, final Currency newCurrency) {
            return new Call(tenantId, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, newAmount, newCurrency, properties);
        }
    }
}
