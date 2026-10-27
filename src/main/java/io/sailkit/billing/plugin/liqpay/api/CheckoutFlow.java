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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Token-free purchase: the plugin signs a hosted checkout form and the transaction stays PENDING. */
final class CheckoutFlow {

    static final String RESULT_URL = "liqpay_result_url";
    static final String LANGUAGE = "liqpay_language";
    static final String DESCRIPTION = "liqpay_description";

    private static final Logger logger = LoggerFactory.getLogger(CheckoutFlow.class);
    private static final DateTimeFormatter EXPIRED_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int MAX_DESCRIPTION = 255;

    private final LiqPayStore store;
    private final Clock clock;

    CheckoutFlow(final LiqPayStore store, final Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    LiqpayTransactionsRecord purchase(final LiqPayConfig c, final UUID tenantId, final UUID kbAccountId, final UUID kbPaymentId,
                                      final UUID kbTransactionId, final UUID kbPaymentMethodId, final BigDecimal amount,
                                      final Currency currency, final Iterable<PluginProperty> properties) throws SQLException {
        final Optional<LiqpayTransactionsRecord> existing = store.findByTransactionId(kbTransactionId, tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        final String resultUrl = Props.find(RESULT_URL, properties);
        final BigDecimal scaled = scale(amount);
        final String errCode = !c.currencies().contains(currency.name()) ? "currency_not_allowed"
                               : scaled == null ? "amount_not_allowed"
                               : !c.isResultUrlAllowed(resultUrl) ? "result_url_not_allowed"
                               : null;
        final Instant expiresAt = clock.instant().plus(c.checkoutTtl());
        final LiqpayTransactionsRecord row = new LiqpayTransactionsRecord();
        row.setKbAccountId(kbAccountId.toString());
        row.setKbPaymentId(kbPaymentId.toString());
        row.setKbTransactionId(kbTransactionId.toString());
        row.setKbPaymentMethodId(kbPaymentMethodId.toString());
        row.setKbTenantId(tenantId.toString());
        row.setTransactionType(TransactionType.PURCHASE.name());
        row.setLiqpayAction(LiqPayAction.PAY.wireName());
        row.setOrderId(kbTransactionId.toString());
        row.setAmount(scaled == null ? amount : scaled);
        row.setCurrency(currency.name());
        if (errCode != null) {
            row.setPluginStatus(PaymentPluginStatus.CANCELED.name());
            row.setErrCode(errCode);
            logger.warn("liqpay checkout rejected order_id={} err_code={}", kbTransactionId, errCode);
        } else {
            final String data = LiqPayEnvelope.encode(params(c, tenantId, kbTransactionId, scaled, currency, resultUrl, expiresAt, properties));
            final Map<String, Object> form = new LinkedHashMap<>();
            form.put(TransactionInfos.STORED_URL, checkoutUrl(c));
            form.put(TransactionInfos.STORED_DATA, data);
            form.put(TransactionInfos.STORED_SIGNATURE, LiqPaySigner.sign(c.privateKey(), data));
            row.setPluginStatus(PaymentPluginStatus.PENDING.name());
            row.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            row.setAdditionalData(TransactionInfos.json(form));
            logger.info("liqpay checkout created order_id={} status=PENDING", kbTransactionId);
        }
        store.insertTransaction(row);
        return row;
    }

    private static Map<String, Object> params(final LiqPayConfig c, final UUID tenantId, final UUID orderId, final BigDecimal amount,
                                              final Currency currency, final String resultUrl, final Instant expiresAt,
                                              final Iterable<PluginProperty> properties) {
        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", LiqPayClient.API_VERSION);
        p.put("public_key", c.publicKey());
        p.put("action", LiqPayAction.PAY.wireName());
        p.put("amount", LiqPayClient.money(amount));
        p.put("currency", currency.name());
        p.put("description", description(c, Props.find(DESCRIPTION, properties)));
        p.put("order_id", orderId.toString());
        p.put("language", language(c, Props.find(LANGUAGE, properties)));
        p.put("result_url", resultUrl);
        p.put("server_url", serverUrl(c, tenantId));
        p.put("expired_date", EXPIRED_DATE.format(expiresAt));
        return p;
    }

    /** The callback URL of the config with the tenant id. */
    static String serverUrl(final LiqPayConfig c, final UUID tenantId) {
        final String server = c.serverUrl().toString();
        return server + (server.contains("?") ? "&" : "?") + "kbTenantId=" + tenantId;
    }

    static String checkoutUrl(final LiqPayConfig c) {
        return c.apiBaseUrl().toString().replaceAll("/+$", "") + "/api/3/checkout";
    }

    /** Scale 2 for a positive amount, else null. */
    private static BigDecimal scale(final BigDecimal amount) {
        try {
            return amount == null || amount.signum() <= 0 ? null : amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (final ArithmeticException e) {
            return null;
        }
    }

    static String language(final LiqPayConfig c, final String requested) {
        final String l = requested == null ? null : requested.trim().toLowerCase(Locale.ROOT);
        return l != null && c.languages().contains(l) ? l : c.language();
    }

    /** Removes control characters, trims, and keeps 255 characters at most. */
    static String description(final LiqPayConfig c, final String requested) {
        final String clean = requested == null ? "" : requested.replaceAll("\\p{Cntrl}", "").trim();
        return clean.isEmpty() ? c.description() : clean.substring(0, Math.min(clean.length(), MAX_DESCRIPTION)).trim();
    }
}
