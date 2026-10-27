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

import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maps a LiqPay status to a Kill Bill status for the action of the transaction. */
public final class LiqPayStatusMapper {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayStatusMapper.class);

    private LiqPayStatusMapper() {}

    public static PaymentPluginStatus map(final LiqPayAction action, final String liqpayStatus, final boolean sandboxTenant) {
        if (action == LiqPayAction.STATUS) {
            throw new IllegalArgumentException("map the action of the transaction, not status");
        }
        final boolean pay = action == LiqPayAction.PAY || action == LiqPayAction.PAYTOKEN;
        final boolean refund = action == LiqPayAction.REFUND || action == LiqPayAction.UNHOLD;
        final boolean hold = action == LiqPayAction.HOLD;
        final String status = liqpayStatus == null ? "" : liqpayStatus;
        switch (status) {
            case "success":
                return hold ? PaymentPluginStatus.PENDING : PaymentPluginStatus.PROCESSED;
            case "wait_compensation":
                return hold || refund ? PaymentPluginStatus.PENDING : PaymentPluginStatus.PROCESSED;
            case "hold_wait":
                return hold ? PaymentPluginStatus.PROCESSED : PaymentPluginStatus.PENDING;
            case "sandbox":
                return sandboxTenant ? PaymentPluginStatus.PROCESSED : PaymentPluginStatus.ERROR;
            case "reversed":
                // A reversed purchase without a Kill Bill refund is never paid.
                return refund ? PaymentPluginStatus.PROCESSED : PaymentPluginStatus.ERROR;
            case "failure":
            case "error":
            case "try_again":
            case "unsubscribed":
                return PaymentPluginStatus.ERROR;
            case "processing":
            case "prepared":
            case "wait_secure":
            case "wait_accept":
            case "wait_lc":
            case "wait_reserve":
            case "wait_card":
            case "invoice_wait":
            case "cash_wait":
            case "wait_qr":
            case "wait_sender":
                return PaymentPluginStatus.PENDING;
            default:
                if (status.endsWith("_verify")) {
                    return PaymentPluginStatus.PENDING;
                }
                logger.warn("liqpay status not mapped status={} action={} result=PENDING", status, action.wireName());
                return PaymentPluginStatus.PENDING;
        }
    }

    /** PROCESSED, ERROR and CANCELED are final. A final row never goes back to PENDING. */
    public static boolean isFinal(final PaymentPluginStatus status) {
        return status == PaymentPluginStatus.PROCESSED || status == PaymentPluginStatus.ERROR
               || status == PaymentPluginStatus.CANCELED;
    }
}
