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

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import io.sailkit.billing.plugin.liqpay.LiqPayActivator;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountApiException;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentOptions;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.plugin.api.PluginCallContext;
import org.killbill.billing.security.api.SecurityApi;
import org.killbill.billing.util.callcontext.CallContext;

/**
 * Tells Kill Bill that a PENDING transaction is final. The notify needs permission payment:transition, so it runs
 * as the Kill Bill user of the tenant config. An invoice payment goes through the invoice control plugin.
 */
final class PendingNotifier {

    static final String INVOICE_CONTROL_PLUGIN = "__INVOICE_PAYMENT_CONTROL_PLUGIN__";

    private static final PaymentOptions INVOICE_OPTIONS = new PaymentOptions() {
        @Override
        public boolean isExternalPayment() {
            return false;
        }

        @Override
        public List<String> getPaymentControlPluginNames() {
            return List.of(INVOICE_CONTROL_PLUGIN);
        }
    };

    private final OSGIKillbillAPI killbill;
    private final Clock clock;

    PendingNotifier(final OSGIKillbillAPI killbill, final Clock clock) {
        this.killbill = killbill;
        this.clock = clock;
    }

    /** Returns false when the Kill Bill transaction is not PENDING any more, for example after the Janitor settled it. */
    boolean notify(final LiqPayConfig c, final LiqpayTransactionsRecord row, final boolean success)
            throws AccountApiException, PaymentApiException {
        final SecurityApi security = c.hasKillbillLogin() ? killbill.getSecurityApi() : null;
        if (security != null) {
            security.login(c.killbillUser(), c.killbillPassword());
        }
        try {
            return notifyAsCurrentUser(row, success);
        } finally {
            if (security != null) {
                security.logout();
            }
        }
    }

    private boolean notifyAsCurrentUser(final LiqpayTransactionsRecord row, final boolean success)
            throws AccountApiException, PaymentApiException {
        final UUID accountId = UUID.fromString(row.getKbAccountId());
        final UUID paymentId = UUID.fromString(row.getKbPaymentId());
        final UUID transactionId = UUID.fromString(row.getKbTransactionId());
        final CallContext context = new PluginCallContext(LiqPayActivator.PLUGIN_NAME, new DateTime(clock.millis(), DateTimeZone.UTC),
                                                          accountId, UUID.fromString(row.getKbTenantId()));
        final boolean pending = killbill.getPaymentApi().getPayment(paymentId, false, false, List.of(), context)
                                        .getTransactions().stream()
                                        .anyMatch(t -> transactionId.equals(t.getId()) && t.getTransactionStatus() == TransactionStatus.PENDING);
        if (!pending) {
            return false;
        }
        final Account account = killbill.getAccountUserApi().getAccountById(accountId, context);
        if (killbill.getInvoicePaymentApi().getInvoicePayments(paymentId, context).isEmpty()) {
            killbill.getPaymentApi().notifyPendingTransactionOfStateChanged(account, transactionId, success, context);
        } else {
            killbill.getPaymentApi().notifyPendingTransactionOfStateChangedWithPaymentControl(account, transactionId, success,
                                                                                              INVOICE_OPTIONS, context);
        }
        return true;
    }
}
