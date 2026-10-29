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
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.client.LiqPayTransientException;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Full and partial refunds of a paid purchase or capture, never above the paid rest. */
final class RefundFlow {

    private static final Logger logger = LoggerFactory.getLogger(RefundFlow.class);

    private final LiqPayStore store;

    RefundFlow(final LiqPayStore store) {
        this.store = store;
    }

    /** Returns the stored refund row. A rejected refund is stored as ERROR and makes no LiqPay call.
     *  Every refund row stores its request fields, so a refusal shows what was sent. */
    LiqpayTransactionsRecord refund(final LiqPayConfig c, final LiqPayClient client, final UUID tenantId, final UUID kbAccountId,
                                    final UUID kbPaymentId, final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                    final BigDecimal amount, final Currency currency) throws SQLException {
        final Optional<LiqpayTransactionsRecord> existing = store.findByTransactionId(kbTransactionId, tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        final List<LiqpayTransactionsRecord> rows = store.findByPayment(kbPaymentId, tenantId);
        final Optional<LiqpayTransactionsRecord> paid = rows.stream()
                                                            .filter(r -> isPaid(r.getTransactionType()))
                                                            .filter(r -> PaymentPluginStatus.PROCESSED.name().equals(r.getPluginStatus()))
                                                            .findFirst();
        final BigDecimal refunded = rows.stream()
                                        .filter(r -> TransactionType.REFUND.name().equals(r.getTransactionType()))
                                        .filter(r -> PaymentPluginStatus.PROCESSED.name().equals(r.getPluginStatus())
                                                     || PaymentPluginStatus.PENDING.name().equals(r.getPluginStatus()))
                                        .map(LiqpayTransactionsRecord::getAmount)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal rest = paid.map(p -> p.getAmount().subtract(refunded)).orElse(BigDecimal.ZERO);
        final String errCode = paid.isEmpty() ? "no_paid_transaction"
                               : amount == null || amount.signum() <= 0 || amount.scale() > 2 ? "amount_not_allowed"
                               : !paid.get().getCurrency().equals(currency.name()) ? "currency_mismatch"
                               : amount.compareTo(rest) > 0 ? "refund_exceeds_paid"
                               : null;
        final String paidOrderId = paid.map(RefundFlow::liqpayOrderId).orElse(null);
        final Map<String, Object> request = request(paidOrderId, amount, currency);
        final LiqpayTransactionsRecord row = new LiqpayTransactionsRecord();
        row.setKbAccountId(kbAccountId.toString());
        row.setKbPaymentId(kbPaymentId.toString());
        row.setKbTransactionId(kbTransactionId.toString());
        row.setKbPaymentMethodId(kbPaymentMethodId.toString());
        row.setKbTenantId(tenantId.toString());
        row.setTransactionType(TransactionType.REFUND.name());
        row.setLiqpayAction(LiqPayAction.REFUND.wireName());
        row.setOrderId(kbTransactionId.toString());
        row.setOriginalOrderId(paidOrderId);
        row.setAmount(amount == null ? BigDecimal.ZERO : amount);
        row.setCurrency(currency.name());
        row.setPluginStatus(errCode == null ? PaymentPluginStatus.PENDING.name() : PaymentPluginStatus.ERROR.name());
        row.setErrCode(errCode);
        row.setErrDescription(errCode == null ? null : describe(errCode, amount, currency, paid.orElse(null), rest));
        row.setAdditionalData(TransactionInfos.json(Map.of(TransactionInfos.STORED_REQUEST, request)));
        store.insertTransaction(row);
        if (errCode != null) {
            logRefusal(paidOrderId == null ? kbTransactionId.toString() : paidOrderId, "not_sent", errCode, row.getErrDescription());
            return row;
        }
        final LiqPayResult r;
        try {
            r = client.refund(c, paidOrderId, amount);
        } catch (final LiqPayTransientException e) {
            logger.warn("liqpay refund not confirmed order_id={} status=PENDING", paidOrderId);
            return store.findByTransactionId(kbTransactionId, tenantId).orElse(row);
        }
        final PaymentPluginStatus mapped = "error".equals(r.result())
                                           ? PaymentPluginStatus.ERROR
                                           : LiqPayStatusMapper.map(LiqPayAction.REFUND, r.status(), c.sandbox());
        store.updateFromLiqPay(kbTransactionId.toString(), tenantId, r, mapped, Map.of(TransactionInfos.STORED_REQUEST, request));
        if (mapped == PaymentPluginStatus.ERROR) {
            logRefusal(paidOrderId, r.status(), r.errCode(), r.errDescription());
        }
        return store.findByTransactionId(kbTransactionId, tenantId).orElse(row);
    }

    /** The refund request fields that LiqPay gets, plus the Kill Bill currency. It holds no key and no signature. */
    private static Map<String, Object> request(final String orderId, final BigDecimal amount, final Currency currency) {
        final boolean sendable = amount != null && amount.scale() <= 2;
        final Map<String, Object> fields = new LinkedHashMap<>(
                sendable ? LiqPayClient.refundFields(orderId, amount)
                         : LiqPayClient.requestFields(LiqPayAction.REFUND, orderId, amount == null ? Map.of() : Map.of("amount", amount.toPlainString())));
        fields.put("currency", currency.name());
        return fields;
    }

    private static String describe(final String errCode, final BigDecimal amount, final Currency currency,
                                   final LiqpayTransactionsRecord paid, final BigDecimal rest) {
        switch (errCode) {
            case "no_paid_transaction":
                return "The payment has no paid transaction to refund";
            case "amount_not_allowed":
                return "The refund amount " + (amount == null ? "null" : amount.toPlainString()) + " is not above 0 with 2 decimals or less";
            case "currency_mismatch":
                return "The refund currency " + currency.name() + " is not the paid currency " + paid.getCurrency();
            default:
                return "The refund amount " + LiqPayClient.money(amount) + " " + currency.name() + " is above the paid rest "
                       + LiqPayClient.money(rest) + " " + currency.name();
        }
    }

    private static void logRefusal(final String orderId, final String status, final String errCode, final String errDescription) {
        logger.warn("liqpay refund refused action={} order_id={} status={} err_code={} err_description={}",
                    LiqPayAction.REFUND.wireName(), orderId, status, errCode, errDescription);
    }

    /** The LiqPay order of a paid row. A capture completes the order of its hold. */
    private static String liqpayOrderId(final LiqpayTransactionsRecord paid) {
        return paid.getOriginalOrderId() == null ? paid.getOrderId() : paid.getOriginalOrderId();
    }

    private static boolean isPaid(final String type) {
        return TransactionType.PURCHASE.name().equals(type) || TransactionType.CAPTURE.name().equals(type);
    }
}
