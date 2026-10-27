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
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfigurationHandler;
import io.sailkit.billing.plugin.liqpay.config.LiqPayNotConfiguredException;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.GatewayNotification;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.plugin.api.payment.PluginPaymentMethodPlugin;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.entity.Pagination;

/** The Kill Bill payment API of the plugin: checkout purchases, token flows, refunds and status polling. */
public class LiqPayPaymentPluginApi implements PaymentPluginApi {

    public static final String MODE = "liqpay_mode";
    public static final String MODE_CHECKOUT = "checkout";
    public static final String MODE_TOKEN = "token";
    static final String NOT_SUPPORTED = "not_supported";

    private final LiqPayConfigurationHandler configs;
    private final LiqPayStore store;
    private final CheckoutFlow checkout;
    private final PendingResolver resolver;
    private final RefundFlow refunds;
    private final TokenFlow tokens;
    private final Map<Duration, LiqPayClient> clients = new ConcurrentHashMap<>();

    public LiqPayPaymentPluginApi(final LiqPayConfigurationHandler configs, final LiqPayStore store, final Clock clock) {
        this.configs = configs;
        this.store = store;
        this.checkout = new CheckoutFlow(store, clock);
        this.resolver = new PendingResolver(store, clock);
        this.refunds = new RefundFlow(store);
        this.tokens = new TokenFlow(store, clock);
    }

    // --- Transactions ---

    @Override
    public PaymentTransactionInfoPlugin purchasePayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                                       final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency,
                                                       final Iterable<PluginProperty> properties, final CallContext context)
            throws PaymentPluginApiException {
        final UUID tenantId = context.getTenantId();
        final Optional<LiqPayConfig> c = config(tenantId);
        if (c.isEmpty()) {
            return notConfigured(kbPaymentId, kbTransactionId, TransactionType.PURCHASE, amount, currency);
        }
        try {
            if (MODE_TOKEN.equals(mode(kbPaymentMethodId, tenantId))) {
                return TransactionInfos.of(tokens.purchase(c.get(), client(c.get()), call(context, kbAccountId, kbPaymentId, kbTransactionId,
                                                                                         kbPaymentMethodId, amount, currency, properties)));
            }
            return TransactionInfos.of(checkout.purchase(c.get(), tenantId, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId,
                                                         amount, currency, properties));
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin refundPayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                                     final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency,
                                                     final Iterable<PluginProperty> properties, final CallContext context)
            throws PaymentPluginApiException {
        final UUID tenantId = context.getTenantId();
        final Optional<LiqPayConfig> c = config(tenantId);
        if (c.isEmpty()) {
            return notConfigured(kbPaymentId, kbTransactionId, TransactionType.REFUND, amount, currency);
        }
        try {
            return TransactionInfos.of(refunds.refund(c.get(), client(c.get()), tenantId, kbAccountId, kbPaymentId, kbTransactionId,
                                                      kbPaymentMethodId, amount, currency));
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public List<PaymentTransactionInfoPlugin> getPaymentInfo(final UUID kbAccountId, final UUID kbPaymentId,
                                                             final Iterable<PluginProperty> properties, final TenantContext context)
            throws PaymentPluginApiException {
        final UUID tenantId = context.getTenantId();
        try {
            final Optional<LiqPayConfig> c = config(tenantId);
            if (c.isPresent()) {
                resolver.resolve(c.get(), client(c.get()), tenantId, store.findByPayment(kbPaymentId, tenantId));
            }
            return store.findByPayment(kbPaymentId, tenantId).stream().map(TransactionInfos::of).collect(Collectors.toUnmodifiableList());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin authorizePayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                                        final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency,
                                                        final Iterable<PluginProperty> properties, final CallContext context)
            throws PaymentPluginApiException {
        final Optional<LiqPayConfig> c = config(context.getTenantId());
        if (c.isEmpty()) {
            return notConfigured(kbPaymentId, kbTransactionId, TransactionType.AUTHORIZE, amount, currency);
        }
        try {
            return TransactionInfos.of(tokens.authorize(c.get(), client(c.get()), call(context, kbAccountId, kbPaymentId, kbTransactionId,
                                                                                      kbPaymentMethodId, amount, currency, properties)));
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin capturePayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                                      final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency,
                                                      final Iterable<PluginProperty> properties, final CallContext context)
            throws PaymentPluginApiException {
        final Optional<LiqPayConfig> c = config(context.getTenantId());
        if (c.isEmpty()) {
            return notConfigured(kbPaymentId, kbTransactionId, TransactionType.CAPTURE, amount, currency);
        }
        try {
            return TransactionInfos.of(tokens.capture(c.get(), client(c.get()), call(context, kbAccountId, kbPaymentId, kbTransactionId,
                                                                                    kbPaymentMethodId, amount, currency, properties)));
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin voidPayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                                   final UUID kbPaymentMethodId, final Iterable<PluginProperty> properties,
                                                   final CallContext context) throws PaymentPluginApiException {
        final Optional<LiqPayConfig> c = config(context.getTenantId());
        if (c.isEmpty()) {
            return notConfigured(kbPaymentId, kbTransactionId, TransactionType.VOID, null, null);
        }
        try {
            final LiqpayTransactionsRecord row = tokens.voidHold(c.get(), client(c.get()), call(context, kbAccountId, kbPaymentId,
                                                                                               kbTransactionId, kbPaymentMethodId,
                                                                                               null, null, properties));
            return row == null ? TransactionInfos.rejected(kbPaymentId, kbTransactionId, TransactionType.VOID, null, null,
                                                           PaymentPluginStatus.CANCELED, "no_authorization")
                               : TransactionInfos.of(row);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin creditPayment(final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                                     final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency,
                                                     final Iterable<PluginProperty> properties, final CallContext context) {
        return TransactionInfos.rejected(kbPaymentId, kbTransactionId, TransactionType.CREDIT, amount, currency,
                                         PaymentPluginStatus.CANCELED, NOT_SUPPORTED);
    }

    @Override
    public Pagination<PaymentTransactionInfoPlugin> searchPayments(final String searchKey, final Long offset, final Long limit,
                                                                   final Iterable<PluginProperty> properties, final TenantContext context) {
        return new EmptyPagination<>(offset);
    }

    // --- Payment methods ---

    @Override
    public void addPaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId, final PaymentMethodPlugin paymentMethodProps,
                                 final boolean setDefault, final Iterable<PluginProperty> properties, final CallContext context)
            throws PaymentPluginApiException {
        final String requested = Props.find(MODE, paymentMethodProps == null ? null : paymentMethodProps.getProperties(), properties);
        final String mode = requested == null ? MODE_CHECKOUT : requested.trim().toLowerCase(Locale.ROOT);
        if (!MODE_CHECKOUT.equals(mode) && !MODE_TOKEN.equals(mode)) {
            throw new PaymentPluginApiException("invalid_mode", "liqpay_mode must be checkout or token");
        }
        final LiqpayPaymentMethodsRecord row = new LiqpayPaymentMethodsRecord();
        row.setKbAccountId(kbAccountId.toString());
        row.setKbPaymentMethodId(kbPaymentMethodId.toString());
        row.setKbTenantId(context.getTenantId().toString());
        row.setMode(mode);
        row.setVerified(false);
        row.setIsDeleted(false);
        try {
            store.upsertPaymentMethod(row);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public void deletePaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId, final Iterable<PluginProperty> properties,
                                    final CallContext context) throws PaymentPluginApiException {
        try {
            final Optional<LiqpayPaymentMethodsRecord> row = store.findPaymentMethod(kbPaymentMethodId, context.getTenantId());
            if (row.isPresent()) {
                row.get().setIsDeleted(true);
                row.get().setVerified(false);
                row.get().setCardTokenEnc(null);
                row.get().setCardTokenKeyId(null);
                store.upsertPaymentMethod(row.get());
            }
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public PaymentMethodPlugin getPaymentMethodDetail(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                                      final Iterable<PluginProperty> properties, final TenantContext context)
            throws PaymentPluginApiException {
        try {
            final Optional<LiqpayPaymentMethodsRecord> row = store.findPaymentMethod(kbPaymentMethodId, context.getTenantId());
            final List<PluginProperty> props = row.map(r -> List.of(
                    new PluginProperty(MODE, r.getMode(), false),
                    new PluginProperty("verified", String.valueOf(Boolean.TRUE.equals(r.getVerified())), false),
                    new PluginProperty("cardMask", r.getCardMask() == null ? "" : r.getCardMask(), false),
                    new PluginProperty("cardType", r.getCardType() == null ? "" : r.getCardType(), false))).orElse(List.of());
            return new PluginPaymentMethodPlugin(kbPaymentMethodId, null, false, props);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public void setDefaultPaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId, final Iterable<PluginProperty> properties,
                                        final CallContext context) {
        // Kill Bill keeps the default payment method. The plugin stores no default.
    }

    @Override
    public List<PaymentMethodInfoPlugin> getPaymentMethods(final UUID kbAccountId, final boolean refreshFromGateway,
                                                           final Iterable<PluginProperty> properties, final CallContext context) {
        return List.of();
    }

    @Override
    public Pagination<PaymentMethodPlugin> searchPaymentMethods(final String searchKey, final Long offset, final Long limit,
                                                                final Iterable<PluginProperty> properties, final TenantContext context) {
        return new EmptyPagination<>(offset);
    }

    @Override
    public void resetPaymentMethods(final UUID kbAccountId, final List<PaymentMethodInfoPlugin> paymentMethods,
                                    final Iterable<PluginProperty> properties, final CallContext context) {
        // LiqPay holds no list of payment methods to sync with.
    }

    // --- Hosted form and gateway notifications ---

    @Override
    public HostedPaymentPageFormDescriptor buildFormDescriptor(final UUID kbAccountId, final Iterable<PluginProperty> customFields,
                                                               final Iterable<PluginProperty> properties, final CallContext context)
            throws PaymentPluginApiException {
        final Optional<LiqPayConfig> c = config(context.getTenantId());
        if (c.isEmpty()) {
            throw new PaymentPluginApiException(LiqPayNotConfiguredException.ERR_CODE, "liqpay is not configured for the tenant");
        }
        try {
            return tokens.form(c.get(), context.getTenantId(), kbAccountId, customFields, properties);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("liqpay database error", e);
        }
    }

    @Override
    public GatewayNotification processNotification(final String notification, final Iterable<PluginProperty> properties,
                                                   final CallContext context) throws PaymentPluginApiException {
        throw new PaymentPluginApiException(NOT_SUPPORTED, "use POST /plugins/killbill-liqpay/callback");
    }

    // --- Helpers ---

    private Optional<LiqPayConfig> config(final UUID tenantId) {
        try {
            return Optional.of(configs.get(tenantId));
        } catch (final LiqPayNotConfiguredException e) {
            return Optional.empty();
        }
    }

    private static TokenFlow.Call call(final CallContext context, final UUID kbAccountId, final UUID kbPaymentId, final UUID kbTransactionId,
                                       final UUID kbPaymentMethodId, final BigDecimal amount, final Currency currency,
                                       final Iterable<PluginProperty> properties) {
        return new TokenFlow.Call(context.getTenantId(), kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency,
                                  properties);
    }

    private static PaymentTransactionInfoPlugin notConfigured(final UUID kbPaymentId, final UUID kbTransactionId, final TransactionType type,
                                                              final BigDecimal amount, final Currency currency) {
        return TransactionInfos.rejected(kbPaymentId, kbTransactionId, type, amount, currency, PaymentPluginStatus.CANCELED,
                                         LiqPayNotConfiguredException.ERR_CODE);
    }

    private LiqPayClient client(final LiqPayConfig c) {
        return clients.computeIfAbsent(c.connectTimeout(), LiqPayClient::new);
    }

    /** A payment method with no row or no mode uses the checkout mode. */
    private String mode(final UUID kbPaymentMethodId, final UUID tenantId) throws SQLException {
        return kbPaymentMethodId == null ? MODE_CHECKOUT
                                         : store.findPaymentMethod(kbPaymentMethodId, tenantId)
                                                .map(LiqpayPaymentMethodsRecord::getMode).orElse(MODE_CHECKOUT);
    }

    private static final class EmptyPagination<T> implements Pagination<T> {

        private final Long offset;

        EmptyPagination(final Long offset) {
            this.offset = offset == null ? Long.valueOf(0L) : offset;
        }

        @Override
        public Long getCurrentOffset() { return offset; }

        @Override
        public Long getNextOffset() { return null; }

        @Override
        public Long getMaxNbRecords() { return 0L; }

        @Override
        public Long getTotalNbRecords() { return 0L; }

        @Override
        public Iterator<T> iterator() { return List.<T>of().iterator(); }

        @Override
        public void close() {}
    }
}
