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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.client.LiqPayTransientException;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Asks LiqPay for the status of each PENDING row once, so the Janitor and the on-the-fly fix settle it. */
final class PendingResolver {

    static final String NOT_FOUND = "payment_not_found";
    static final String EXPIRED = "expired";
    static final String REFUND_NOT_RECEIVED = "refund_not_received";

    /** Order statuses that show the money still paid to the merchant. */
    private static final Set<String> NOT_REFUNDED = Set.of("success", "wait_compensation", "hold_wait", "sandbox");

    private static final Logger logger = LoggerFactory.getLogger(PendingResolver.class);

    private final LiqPayStore store;
    private final Clock clock;

    PendingResolver(final LiqPayStore store, final Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    void resolve(final LiqPayConfig c, final LiqPayClient client, final UUID tenantId,
                 final List<LiqpayTransactionsRecord> rows) throws SQLException {
        for (final LiqpayTransactionsRecord row : rows) {
            if (PaymentPluginStatus.PENDING.name().equals(row.getPluginStatus())) {
                resolveOne(c, client, tenantId, row, rows);
            }
        }
    }

    private void resolveOne(final LiqPayConfig c, final LiqPayClient client, final UUID tenantId,
                            final LiqpayTransactionsRecord row, final List<LiqpayTransactionsRecord> rows) throws SQLException {
        final boolean refund = LiqPayAction.REFUND.wireName().equals(row.getLiqpayAction());
        final String polled = row.getOriginalOrderId() == null ? row.getOrderId() : row.getOriginalOrderId();
        final LiqPayResult r;
        try {
            r = client.status(c, polled);
        } catch (final LiqPayTransientException e) {
            return;
        }
        if (NOT_FOUND.equals(r.errCode())) {
            if (!refund && isExpired(c, row)) {
                store.closePending(row.getOrderId(), tenantId, PaymentPluginStatus.CANCELED, EXPIRED, null);
                logger.info("liqpay checkout expired order_id={} status=CANCELED", row.getOrderId());
            }
            return;
        }
        if ("error".equals(r.result())) {
            logger.warn("liqpay status call failed order_id={} err_code={}", polled, r.errCode());
            return;
        }
        if (r.orderId() != null && !polled.equals(r.orderId())) {
            logger.warn("liqpay status answer for another order order_id={}", polled);
            return;
        }
        if (refund) {
            resolveRefund(c, tenantId, row, rows, polled, r);
            return;
        }
        final PaymentPluginStatus mapped = LiqPayStatusMapper.map(LiqPayAction.fromWireName(row.getLiqpayAction()), r.status(), c.sandbox());
        if (mapped == PaymentPluginStatus.PROCESSED && !Amounts.match(row, r)) {
            logger.warn("liqpay status amount mismatch order_id={} status={}", polled, r.status());
            return;
        }
        store.updateFromLiqPay(row.getOrderId(), tenantId, r, mapped, TransactionInfos.kept(row));
    }

    /** Settles a pending refund by the status of the paid order. It never sends the refund again. */
    private void resolveRefund(final LiqPayConfig c, final UUID tenantId, final LiqpayTransactionsRecord row,
                               final List<LiqpayTransactionsRecord> rows, final String polled, final LiqPayResult r) throws SQLException {
        final String wait = refundWait(c, row, rows, polled, r);
        if (wait == null) {
            store.updateFromLiqPay(row.getOrderId(), tenantId, r, PaymentPluginStatus.PROCESSED, TransactionInfos.kept(row));
        } else if (wait.isEmpty()) {
            store.closePending(row.getOrderId(), tenantId, PaymentPluginStatus.ERROR, REFUND_NOT_RECEIVED,
                               "LiqPay shows no refund of order " + polled + " " + c.refundGrace() + " after the refund request."
                               + " Check the LiqPay merchant account before a new refund");
            logger.warn("liqpay refund not received order_id={} refund_order_id={} status=ERROR err_code={} liqpay_status={}",
                        polled, row.getOrderId(), REFUND_NOT_RECEIVED, r.status());
        } else {
            logger.warn("liqpay refund pending order_id={} refund_order_id={} status={} reason={} created={}",
                        polled, row.getOrderId(), r.status(), wait, row.getCreatedDate());
        }
    }

    /** Null when LiqPay ran the refund, empty when it never got it and the grace is over, else the reason to wait. */
    private String refundWait(final LiqPayConfig c, final LiqpayTransactionsRecord row, final List<LiqpayTransactionsRecord> rows,
                              final String polled, final LiqPayResult r) {
        if ("reversed".equals(r.status())) {
            return null;
        }
        if (r.waitReserve() || "wait_reserve".equals(r.status())) {
            return "reserved";
        }
        final BigDecimal earlier = refundsOf(rows, row, polled, PaymentPluginStatus.PROCESSED);
        final boolean shown = r.refundAmount() != null && r.refundAmount().compareTo(earlier) > 0;
        if (shown) {
            final boolean covers = r.refundAmount().compareTo(earlier.add(row.getAmount())) >= 0
                                   && refundsOf(rows, row, polled, PaymentPluginStatus.PENDING).signum() == 0;
            return covers ? null : "refund_amount_unclear";
        }
        if (row.getLiqpayStatus() != null) {
            return "accepted";
        }
        if (!NOT_REFUNDED.contains(r.status())) {
            return "order_status";
        }
        final boolean graceOver = row.getCreatedDate() != null
                                  && clock.instant().isAfter(row.getCreatedDate().toInstant(ZoneOffset.UTC).plus(c.refundGrace()));
        return graceOver ? "" : "no_answer";
    }

    /** The sum of the other refunds of the paid order in the given status. */
    private static BigDecimal refundsOf(final List<LiqpayTransactionsRecord> rows, final LiqpayTransactionsRecord row,
                                        final String polled, final PaymentPluginStatus status) {
        return rows.stream()
                   .filter(o -> !o.getOrderId().equals(row.getOrderId()))
                   .filter(o -> LiqPayAction.REFUND.wireName().equals(o.getLiqpayAction()))
                   .filter(o -> polled.equals(o.getOriginalOrderId()))
                   .filter(o -> status.name().equals(o.getPluginStatus()))
                   .map(LiqpayTransactionsRecord::getAmount)
                   .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isExpired(final LiqPayConfig c, final LiqpayTransactionsRecord row) {
        return row.getExpiresAt() != null
               && clock.instant().isAfter(row.getExpiresAt().toInstant(ZoneOffset.UTC).plus(c.expiryGrace()));
    }
}
