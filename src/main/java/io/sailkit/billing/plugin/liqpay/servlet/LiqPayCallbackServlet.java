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

package io.sailkit.billing.plugin.liqpay.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import io.sailkit.billing.plugin.liqpay.api.LiqPayStatusMapper;
import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.client.LiqPayTransientException;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfigurationHandler;
import io.sailkit.billing.plugin.liqpay.config.LiqPayNotConfiguredException;
import io.sailkit.billing.plugin.liqpay.crypto.CardTokenCipher;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.HppStatus;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** `POST /plugins/killbill-liqpay/callback?kbTenantId=<uuid>`. Checks run in the order of the security design. */
public class LiqPayCallbackServlet extends HttpServlet {

    static final int MAX_BODY = 16 * 1024;
    static final int MAX_FIELD = 12 * 1024;
    static final int MAX_CARD_TOKEN = 512;

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(LiqPayCallbackServlet.class);
    private static final String FORM = "application/x-www-form-urlencoded";

    private final transient LiqPayConfigurationHandler configs;
    private final transient LiqPayStore store;
    private final transient PendingNotifier notifier;
    private final transient Map<Duration, LiqPayClient> clients = new ConcurrentHashMap<>();

    public LiqPayCallbackServlet(final LiqPayConfigurationHandler configs, final LiqPayStore store,
                                 final OSGIKillbillAPI killbill, final Clock clock) {
        this.configs = configs;
        this.store = store;
        this.notifier = new PendingNotifier(killbill, clock);
    }

    /** A rejected callback: the HTTP code and the reason code for the log. */
    private static final class Rejection extends Exception {

        private static final long serialVersionUID = 1L;
        private final int code;

        Rejection(final int code, final String reason) {
            super(reason, null, false, false);
            this.code = code;
        }
    }

    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        if (!"/callback".equals(req.getPathInfo())) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final String tenantForLog = tenantForLog(req.getQueryString());
        try {
            handle(req);
            resp.setStatus(HttpServletResponse.SC_OK);
        } catch (final Rejection r) {
            if (r.code == HttpServletResponse.SC_BAD_REQUEST) {
                logger.warn("liqpay_callback_rejected kbTenantId={} reason={}", tenantForLog, r.getMessage());
            }
            resp.setStatus(r.code);
        }
    }

    private void handle(final HttpServletRequest req) throws Rejection {
        // 1: method, content type and size.
        if (!"POST".equals(req.getMethod())) {
            throw new Rejection(400, "method");
        }
        final String contentType = req.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(FORM)) {
            throw new Rejection(400, "content_type");
        }
        final Map<String, String> form = parseForm(readBody(req));
        // 2: the tenant.
        final UUID tenantId = tenant(req.getQueryString()).orElseThrow(() -> new Rejection(400, "tenant"));
        final LiqPayConfig c;
        try {
            c = configs.get(tenantId);
        } catch (final LiqPayNotConfiguredException e) {
            throw new Rejection(400, "tenant");
        }
        // 3 and 4: the fields and the signature.
        final String data = form.get("data");
        final String signature = form.get("signature");
        if (data == null || signature == null || data.length() > MAX_FIELD || signature.length() > MAX_FIELD) {
            throw new Rejection(400, "fields");
        }
        if (!LiqPaySigner.verify(c.privateKey(), data, signature)) {
            throw new Rejection(400, "signature");
        }
        // 5 and 6: decode and the public key.
        final JsonNode body;
        try {
            body = LiqPayEnvelope.decode(data);
        } catch (final IllegalArgumentException e) {
            throw new Rejection(400, "data");
        }
        if (!c.publicKey().equals(text(body, "public_key"))) {
            throw new Rejection(400, "public_key");
        }
        // 7: a known order of this tenant, a payment or a card verification.
        final String orderId = text(body, "order_id");
        final Optional<LiqpayTransactionsRecord> row = find(orderId, tenantId);
        final Optional<LiqpayHppRequestsRecord> hpp = row.isPresent() ? Optional.empty() : findHpp(orderId, tenantId);
        if (row.isEmpty() && hpp.isEmpty()) {
            throw new Rejection(400, "order_id");
        }
        // 8 and 9: the stored action, amount and currency, and the version.
        final LiqPayResult result = LiqPayResult.from(body);
        final String storedAction = row.map(LiqpayTransactionsRecord::getLiqpayAction).orElse(LiqPayAction.HOLD.wireName());
        if (!actionMatches(storedAction, result.action())) {
            throw new Rejection(400, "action");
        }
        final BigDecimal storedAmount = row.isPresent() ? row.get().getAmount() : hpp.get().getAmount();
        if (result.amount() == null || result.amount().compareTo(storedAmount) != 0) {
            throw new Rejection(400, "amount");
        }
        final String storedCurrency = row.isPresent() ? row.get().getCurrency() : hpp.get().getCurrency();
        if (result.currency() == null || !result.currency().equals(storedCurrency)) {
            throw new Rejection(400, "currency");
        }
        if (!body.hasNonNull("version") || body.get("version").asInt() != LiqPayClient.API_VERSION) {
            throw new Rejection(400, "version");
        }
        if (row.isPresent()) {
            apply(c, tenantId, row.get(), result);
        } else {
            verify(c, tenantId, hpp.get(), body, result);
        }
    }

    /** A token purchase is sent as `paytoken`, and LiqPay reports it as `pay`. */
    private static boolean actionMatches(final String stored, final String received) {
        return stored.equals(received)
               || (LiqPayAction.PAYTOKEN.wireName().equals(stored) && LiqPayAction.PAY.wireName().equals(received));
    }

    // --- Card verification ---

    /**
     * A verification hold stores the encrypted card token, then the plugin releases the hold.
     * A replay or an older callback changes nothing.
     */
    private void verify(final LiqPayConfig c, final UUID tenantId, final LiqpayHppRequestsRecord hpp, final JsonNode body,
                        final LiqPayResult result) throws Rejection {
        final String orderId = hpp.getOrderId();
        try {
            if ("reversed".equals(result.status())) {
                final boolean changed = store.updateHppRequest(orderId, tenantId, HppStatus.RELEASED, result.endDate());
                logger.info("liqpay verification order_id={} status=reversed result={} changed={}", orderId, HppStatus.RELEASED, changed);
                return;
            }
            final PaymentPluginStatus mapped = LiqPayStatusMapper.map(LiqPayAction.HOLD, result.status(), c.sandbox());
            if (mapped == PaymentPluginStatus.PENDING) {
                logger.info("liqpay verification order_id={} status={} result=PENDING", orderId, result.status());
                return;
            }
            if (mapped != PaymentPluginStatus.PROCESSED) {
                final boolean changed = store.updateHppRequest(orderId, tenantId, HppStatus.FAILED, result.endDate());
                logger.info("liqpay verification order_id={} status={} result={} changed={}", orderId, result.status(), HppStatus.FAILED, changed);
                return;
            }
            if (!HppStatus.PENDING.equals(hpp.getStatus())) {
                logger.info("liqpay verification order_id={} status={} changed=false", orderId, result.status());
                return;
            }
            final String token = text(body, "card_token");
            final Optional<LiqpayPaymentMethodsRecord> method = store.findPaymentMethod(UUID.fromString(hpp.getKbPaymentMethodId()), tenantId)
                                                                     .filter(m -> hpp.getKbAccountId().equals(m.getKbAccountId()))
                                                                     .filter(m -> "token".equals(m.getMode()));
            final String reason = token == null || token.isEmpty() || token.length() > MAX_CARD_TOKEN ? "no_card_token"
                                  : method.isEmpty() ? "payment_method"
                                  : !c.hasCardTokenKey() ? "card_token_key_missing"
                                  : null;
            if (reason != null) {
                store.updateHppRequest(orderId, tenantId, HppStatus.FAILED, result.endDate());
                logger.warn("liqpay_verification_failed order_id={} reason={}", orderId, reason);
                release(c, tenantId, hpp);
                return;
            }
            final CardTokenCipher cipher = new CardTokenCipher(c.cardTokenKey(), c.cardTokenKeyId());
            final LiqpayPaymentMethodsRecord pm = method.get();
            pm.setCardTokenEnc(cipher.encrypt(token));
            pm.setCardTokenKeyId(cipher.keyId());
            pm.setVerified(true);
            pm.setCardMask(limit(text(body, "sender_card_mask2"), 32));
            pm.setCardType(limit(text(body, "sender_card_type"), 32));
            store.upsertPaymentMethod(pm);
            if (!store.updateHppRequest(orderId, tenantId, HppStatus.VERIFIED, result.endDate())) {
                logger.info("liqpay verification order_id={} status={} changed=false", orderId, result.status());
                return;
            }
            logger.info("liqpay verification order_id={} status={} result={}", orderId, result.status(), HppStatus.VERIFIED);
            release(c, tenantId, hpp);
        } catch (final SQLException e) {
            logger.warn("liqpay callback database error order_id={}", orderId);
            throw new Rejection(503, "database");
        } catch (final GeneralSecurityException e) {
            logger.warn("liqpay card token not stored order_id={} error={}", orderId, e.getClass().getSimpleName());
            throw new Rejection(503, "card_token");
        }
    }

    /** Releases the verification hold with a refund of the held amount. The bank also releases it after 7 to 10 days. */
    private void release(final LiqPayConfig c, final UUID tenantId, final LiqpayHppRequestsRecord hpp) throws SQLException {
        final LiqPayResult r;
        try {
            r = clients.computeIfAbsent(c.connectTimeout(), LiqPayClient::new).releaseHold(c, hpp.getOrderId(), hpp.getAmount());
        } catch (final LiqPayTransientException e) {
            logger.warn("liqpay_hold_release_failed order_id={} error=transient", hpp.getOrderId());
            return;
        }
        if (LiqPayStatusMapper.map(LiqPayAction.REFUND, r.status(), c.sandbox()) == PaymentPluginStatus.PROCESSED && !"error".equals(r.result())) {
            store.updateHppRequest(hpp.getOrderId(), tenantId, HppStatus.RELEASED, null);
            logger.info("liqpay verification hold released order_id={}", hpp.getOrderId());
        } else {
            logger.warn("liqpay_hold_release_failed order_id={} status={} err_code={}", hpp.getOrderId(), r.status(), r.errCode());
        }
    }

    private void apply(final LiqPayConfig c, final UUID tenantId, final LiqpayTransactionsRecord row, final LiqPayResult result)
            throws Rejection {
        final PaymentPluginStatus mapped = LiqPayStatusMapper.map(LiqPayAction.fromWireName(row.getLiqpayAction()), result.status(), c.sandbox());
        final boolean changed;
        try {
            if ("reversed".equals(result.status()) && PaymentPluginStatus.PROCESSED.name().equals(row.getPluginStatus())) {
                logReversal(row, tenantId);
                return;
            }
            changed = store.updateFromLiqPay(row.getOrderId(), tenantId, result, mapped, Map.of());
        } catch (final SQLException e) {
            logger.warn("liqpay callback database error order_id={}", row.getOrderId());
            throw new Rejection(503, "database");
        }
        logger.info("liqpay callback order_id={} status={} result={} changed={}", row.getOrderId(), result.status(), mapped, changed);
        // A row that an earlier callback made final is notified again, so a retry heals a failed notify.
        final String settled = changed ? mapped.name() : row.getPluginStatus();
        if (PaymentPluginStatus.PROCESSED.name().equals(settled) || PaymentPluginStatus.ERROR.name().equals(settled)) {
            try {
                final boolean notified = notifier.notify(c, row, PaymentPluginStatus.PROCESSED.name().equals(settled));
                logger.info("liqpay notify order_id={} result={} notified={}", row.getOrderId(), settled, notified);
            } catch (final Exception e) {
                logger.warn("liqpay_notify_failed order_id={} error={}", row.getOrderId(), e.getClass().getSimpleName());
                throw new Rejection(503, "killbill");
            }
        }
    }

    /** LiqPay reports a refund on the paid order. A reversal with no refund row is logged for staff. */
    private void logReversal(final LiqpayTransactionsRecord row, final UUID tenantId) throws SQLException {
        final List<LiqpayTransactionsRecord> rows = store.findByPayment(UUID.fromString(row.getKbPaymentId()), tenantId);
        final boolean refunded = rows.stream().anyMatch(r -> (TransactionType.REFUND.name().equals(r.getTransactionType())
                                                              || TransactionType.VOID.name().equals(r.getTransactionType()))
                                                             && row.getOrderId().equals(r.getOriginalOrderId()));
        if (!refunded) {
            logger.warn("liqpay_reversed_unmatched order_id={}", row.getOrderId());
        }
    }

    private Optional<LiqpayTransactionsRecord> find(final String orderId, final UUID tenantId) throws Rejection {
        if (orderId == null) {
            return Optional.empty();
        }
        try {
            return store.findByOrderId(orderId, tenantId);
        } catch (final SQLException e) {
            logger.warn("liqpay callback database error");
            throw new Rejection(503, "database");
        }
    }

    private Optional<LiqpayHppRequestsRecord> findHpp(final String orderId, final UUID tenantId) throws Rejection {
        if (orderId == null) {
            return Optional.empty();
        }
        try {
            return store.findHppRequestByOrderId(orderId, tenantId);
        } catch (final SQLException e) {
            logger.warn("liqpay callback database error");
            throw new Rejection(503, "database");
        }
    }

    private static String limit(final String s, final int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // --- Request parsing ---

    private static byte[] readBody(final HttpServletRequest req) throws Rejection {
        if (req.getContentLengthLong() > MAX_BODY) {
            throw new Rejection(400, "size");
        }
        try (InputStream in = req.getInputStream()) {
            final byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                throw new Rejection(400, "size");
            }
            return body;
        } catch (final IOException e) {
            throw new Rejection(400, "body");
        }
    }

    /** Parses a form body. A repeated key or a key with no value is a bad request. */
    private static Map<String, String> parseForm(final byte[] body) throws Rejection {
        final Map<String, String> form = new HashMap<>();
        for (final String pair : new String(body, StandardCharsets.US_ASCII).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new Rejection(400, "form");
            }
            try {
                final String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (form.put(key, URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)) != null) {
                    throw new Rejection(400, "form");
                }
            } catch (final IllegalArgumentException e) {
                throw new Rejection(400, "form");
            }
        }
        return form;
    }

    private static Optional<UUID> tenant(final String query) {
        if (query == null) {
            return Optional.empty();
        }
        final String[] values = Arrays.stream(query.split("&"))
                                      .filter(p -> p.startsWith("kbTenantId="))
                                      .map(p -> p.substring("kbTenantId=".length()))
                                      .toArray(String[]::new);
        if (values.length != 1) {
            return Optional.empty();
        }
        try {
            final UUID id = UUID.fromString(values[0]);
            return id.toString().equals(values[0].toLowerCase(Locale.ROOT)) ? Optional.of(id) : Optional.empty();
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String tenantForLog(final String query) {
        return tenant(query).map(UUID::toString).orElse("invalid");
    }

    private static String text(final JsonNode n, final String field) {
        final JsonNode v = n.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : v.asText();
    }
}
