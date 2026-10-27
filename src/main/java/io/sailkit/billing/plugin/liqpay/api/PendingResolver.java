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

import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
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
                resolveOne(c, client, tenantId, row);
            }
        }
    }

    private void resolveOne(final LiqPayConfig c, final LiqPayClient client, final UUID tenantId,
                            final LiqpayTransactionsRecord row) throws SQLException {
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
                store.cancelPending(row.getOrderId(), tenantId, EXPIRED);
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
        // The status of the paid order settles a pending refund only when LiqPay reversed it.
        final PaymentPluginStatus mapped = refund
                                           ? ("reversed".equals(r.status()) ? PaymentPluginStatus.PROCESSED : PaymentPluginStatus.PENDING)
                                           : LiqPayStatusMapper.map(LiqPayAction.fromWireName(row.getLiqpayAction()), r.status(), c.sandbox());
        if (!refund && mapped == PaymentPluginStatus.PROCESSED && !Amounts.match(row, r)) {
            logger.warn("liqpay status amount mismatch order_id={} status={}", polled, r.status());
            return;
        }
        if (refund && mapped == PaymentPluginStatus.PENDING) {
            return;
        }
        store.updateFromLiqPay(row.getOrderId(), tenantId, r, mapped, TransactionInfos.checkoutForm(row));
    }

    private boolean isExpired(final LiqPayConfig c, final LiqpayTransactionsRecord row) {
        return row.getExpiresAt() != null
               && clock.instant().isAfter(row.getExpiresAt().toInstant(ZoneOffset.UTC).plus(c.expiryGrace()));
    }
}
