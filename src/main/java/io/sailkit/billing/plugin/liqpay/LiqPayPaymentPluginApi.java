/*
 * Copyright 2024 Sailkit.io
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

package io.sailkit.billing.plugin.liqpay;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.joda.time.DateTime;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.GatewayNotification;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.entity.Pagination;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sailkit.billing.plugin.liqpay.api.LiqPayHostedPaymentPageFormDescriptor;
import io.sailkit.billing.plugin.liqpay.api.LiqPayPaymentMethodInfoPlugin;
import io.sailkit.billing.plugin.liqpay.api.LiqPayPaymentMethodPlugin;
import io.sailkit.billing.plugin.liqpay.api.LiqPayStatusMapper;
import io.sailkit.billing.plugin.liqpay.api.LiqPayTransactionInfoPlugin;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.client.LiqPayException;
import io.sailkit.billing.plugin.liqpay.client.LiqPayRequest;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResponse;
import io.sailkit.billing.plugin.liqpay.client.LiqPaySignature;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayDao;
import io.sailkit.billing.plugin.liqpay.dao.model.LiqPayPaymentMethodRecord;
import io.sailkit.billing.plugin.liqpay.dao.model.LiqPayResponseRecord;

/**
 * Main implementation of PaymentPluginApi for LiqPay payment gateway.
 */
public class LiqPayPaymentPluginApi implements PaymentPluginApi {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayPaymentPluginApi.class);

    private final OSGIKillbillAPI killbillAPI;
    private final LiqPayConfigurationHandler configurationHandler;
    private final Clock clock;
    private final LiqPayDao dao;

    public LiqPayPaymentPluginApi(OSGIKillbillAPI killbillAPI,
                                   LiqPayConfigurationHandler configurationHandler,
                                   Clock clock,
                                   DataSource dataSource) {
        this.killbillAPI = killbillAPI;
        this.configurationHandler = configurationHandler;
        this.clock = clock;
        this.dao = new LiqPayDao(dataSource);
    }

    @Override
    public PaymentTransactionInfoPlugin authorizePayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                          UUID kbPaymentMethodId, BigDecimal amount, Currency currency,
                                                          Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.info("authorizePayment: kbPaymentId={}, amount={}, currency={}", kbPaymentId, amount, currency);

        DateTime now = clock.getUTCNow();
        UUID tenantId = context.getTenantId();

        try {
            // Get card token
            String cardToken = dao.getCardToken(kbPaymentMethodId, tenantId);

            if (cardToken == null) {
                // No token - return PENDING, customer must use hosted page
                logger.info("No card token for payment method {}, returning PENDING for hosted flow", kbPaymentMethodId);

                dao.createPendingTransaction(kbAccountId, kbPaymentId, kbTransactionId,
                        kbPaymentMethodId, tenantId, kbTransactionId.toString(),
                        TransactionType.AUTHORIZE.name(), amount, currency.name());

                return LiqPayTransactionInfoPlugin.pending(kbPaymentId, kbTransactionId,
                        TransactionType.AUTHORIZE, amount, currency, now);
            }

            // Perform hold with token
            LiqPayClient client = configurationHandler.createClientForTenant(tenantId);
            String description = getPropertyValue("description", properties, "Authorization");

            LiqPayResponse response = client.holdWithToken(
                    kbTransactionId.toString(),
                    cardToken,
                    amount,
                    currency.name(),
                    description
            );

            // Save response
            dao.saveResponse(kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, tenantId,
                    TransactionType.AUTHORIZE.name(), response, response.toString());

            return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                    TransactionType.AUTHORIZE, amount, currency, response, now);

        } catch (LiqPayException e) {
            logger.error("LiqPay authorize failed", e);
            if (e.getResponse() != null) {
                return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                        TransactionType.AUTHORIZE, amount, currency, e.getResponse(), now);
            }
            return LiqPayTransactionInfoPlugin.canceled(kbPaymentId, kbTransactionId,
                    TransactionType.AUTHORIZE, amount, currency, e.getMessage(), now);
        } catch (SQLException e) {
            logger.error("Database error in authorizePayment", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin capturePayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                        UUID kbPaymentMethodId, BigDecimal amount, Currency currency,
                                                        Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.info("capturePayment: kbPaymentId={}, amount={}", kbPaymentId, amount);

        DateTime now = clock.getUTCNow();
        UUID tenantId = context.getTenantId();

        try {
            // Get the original authorize order ID (we need to find the original transaction)
            String originalOrderId = getPropertyValue("original_order_id", properties, null);
            if (originalOrderId == null) {
                // Use the original payment's first transaction ID
                List<LiqPayResponseRecord> responses = dao.getResponsesForPayment(kbPaymentId, tenantId);
                for (LiqPayResponseRecord resp : responses) {
                    if ("AUTHORIZE".equals(resp.getTransactionType()) && "hold_wait".equals(resp.getStatus())) {
                        originalOrderId = resp.getLiqpayOrderId();
                        break;
                    }
                }
            }

            if (originalOrderId == null) {
                throw new PaymentPluginApiException("", "No authorization found for capture");
            }

            LiqPayClient client = configurationHandler.createClientForTenant(tenantId);
            LiqPayResponse response = client.holdCompletion(originalOrderId, amount);

            dao.saveResponse(kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, tenantId,
                    TransactionType.CAPTURE.name(), response, response.toString());

            return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                    TransactionType.CAPTURE, amount, currency, response, now);

        } catch (LiqPayException e) {
            logger.error("LiqPay capture failed", e);
            if (e.getResponse() != null) {
                return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                        TransactionType.CAPTURE, amount, currency, e.getResponse(), now);
            }
            return LiqPayTransactionInfoPlugin.canceled(kbPaymentId, kbTransactionId,
                    TransactionType.CAPTURE, amount, currency, e.getMessage(), now);
        } catch (SQLException e) {
            logger.error("Database error in capturePayment", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin purchasePayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                         UUID kbPaymentMethodId, BigDecimal amount, Currency currency,
                                                         Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.info("purchasePayment: kbPaymentId={}, amount={}, currency={}", kbPaymentId, amount, currency);

        DateTime now = clock.getUTCNow();
        UUID tenantId = context.getTenantId();

        try {
            // Get card token
            String cardToken = dao.getCardToken(kbPaymentMethodId, tenantId);

            if (cardToken == null) {
                // No token - return PENDING, customer must use hosted page
                logger.info("No card token for payment method {}, returning PENDING for hosted flow", kbPaymentMethodId);

                dao.createPendingTransaction(kbAccountId, kbPaymentId, kbTransactionId,
                        kbPaymentMethodId, tenantId, kbTransactionId.toString(),
                        TransactionType.PURCHASE.name(), amount, currency.name());

                return LiqPayTransactionInfoPlugin.pending(kbPaymentId, kbTransactionId,
                        TransactionType.PURCHASE, amount, currency, now);
            }

            // Perform token payment
            LiqPayClient client = configurationHandler.createClientForTenant(tenantId);
            String description = getPropertyValue("description", properties, "Payment");

            LiqPayResponse response = client.payToken(
                    kbTransactionId.toString(),
                    cardToken,
                    amount,
                    currency.name(),
                    description
            );

            // Save response
            dao.saveResponse(kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, tenantId,
                    TransactionType.PURCHASE.name(), response, response.toString());

            // Update token if new one received
            if (response.hasCardToken()) {
                dao.updatePaymentMethodToken(kbPaymentMethodId, tenantId,
                        response.getCardToken(), response.getSenderCardMask(),
                        response.getSenderCardType(), response.getSenderCardBank(),
                        response.getSenderCardCountry());
            }

            return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                    TransactionType.PURCHASE, amount, currency, response, now);

        } catch (LiqPayException e) {
            logger.error("LiqPay purchase failed", e);
            if (e.getResponse() != null) {
                return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                        TransactionType.PURCHASE, amount, currency, e.getResponse(), now);
            }
            return LiqPayTransactionInfoPlugin.canceled(kbPaymentId, kbTransactionId,
                    TransactionType.PURCHASE, amount, currency, e.getMessage(), now);
        } catch (SQLException e) {
            logger.error("Database error in purchasePayment", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin voidPayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                     UUID kbPaymentMethodId, Iterable<PluginProperty> properties,
                                                     CallContext context) throws PaymentPluginApiException {

        logger.info("voidPayment: kbPaymentId={}", kbPaymentId);

        DateTime now = clock.getUTCNow();
        UUID tenantId = context.getTenantId();

        try {
            // Find the original hold order ID
            String originalOrderId = getPropertyValue("original_order_id", properties, null);
            if (originalOrderId == null) {
                List<LiqPayResponseRecord> responses = dao.getResponsesForPayment(kbPaymentId, tenantId);
                for (LiqPayResponseRecord resp : responses) {
                    if ("AUTHORIZE".equals(resp.getTransactionType()) && "hold_wait".equals(resp.getStatus())) {
                        originalOrderId = resp.getLiqpayOrderId();
                        break;
                    }
                }
            }

            if (originalOrderId == null) {
                throw new PaymentPluginApiException("", "No authorization found to void");
            }

            LiqPayClient client = configurationHandler.createClientForTenant(tenantId);
            LiqPayResponse response = client.unhold(originalOrderId);

            dao.saveResponse(kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, tenantId,
                    TransactionType.VOID.name(), response, response.toString());

            return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                    TransactionType.VOID, null, null, response, now);

        } catch (LiqPayException e) {
            logger.error("LiqPay void failed", e);
            if (e.getResponse() != null) {
                return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                        TransactionType.VOID, null, null, e.getResponse(), now);
            }
            return LiqPayTransactionInfoPlugin.canceled(kbPaymentId, kbTransactionId,
                    TransactionType.VOID, null, null, e.getMessage(), now);
        } catch (SQLException e) {
            logger.error("Database error in voidPayment", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin creditPayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                       UUID kbPaymentMethodId, BigDecimal amount, Currency currency,
                                                       Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        // Credit is not directly supported by LiqPay
        logger.warn("creditPayment not supported, returning CANCELED");
        DateTime now = clock.getUTCNow();
        return LiqPayTransactionInfoPlugin.canceled(kbPaymentId, kbTransactionId,
                TransactionType.CREDIT, amount, currency, "Credit not supported by LiqPay", now);
    }

    @Override
    public PaymentTransactionInfoPlugin refundPayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                       UUID kbPaymentMethodId, BigDecimal amount, Currency currency,
                                                       Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.info("refundPayment: kbPaymentId={}, amount={}", kbPaymentId, amount);

        DateTime now = clock.getUTCNow();
        UUID tenantId = context.getTenantId();

        try {
            // Find the original payment order ID
            String originalOrderId = getPropertyValue("original_order_id", properties, null);
            if (originalOrderId == null) {
                List<LiqPayResponseRecord> responses = dao.getResponsesForPayment(kbPaymentId, tenantId);
                for (LiqPayResponseRecord resp : responses) {
                    if ("success".equals(resp.getStatus()) &&
                            ("PURCHASE".equals(resp.getTransactionType()) || "CAPTURE".equals(resp.getTransactionType()))) {
                        originalOrderId = resp.getLiqpayOrderId();
                        break;
                    }
                }
            }

            if (originalOrderId == null) {
                throw new PaymentPluginApiException("", "No completed payment found to refund");
            }

            LiqPayClient client = configurationHandler.createClientForTenant(tenantId);
            LiqPayResponse response = client.refund(originalOrderId, amount);

            dao.saveResponse(kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, tenantId,
                    TransactionType.REFUND.name(), response, response.toString());

            return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                    TransactionType.REFUND, amount, currency, response, now);

        } catch (LiqPayException e) {
            logger.error("LiqPay refund failed", e);
            if (e.getResponse() != null) {
                return LiqPayTransactionInfoPlugin.fromResponse(kbPaymentId, kbTransactionId,
                        TransactionType.REFUND, amount, currency, e.getResponse(), now);
            }
            return LiqPayTransactionInfoPlugin.canceled(kbPaymentId, kbTransactionId,
                    TransactionType.REFUND, amount, currency, e.getMessage(), now);
        } catch (SQLException e) {
            logger.error("Database error in refundPayment", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public List<PaymentTransactionInfoPlugin> getPaymentInfo(UUID kbAccountId, UUID kbPaymentId,
                                                              Iterable<PluginProperty> properties, TenantContext context)
            throws PaymentPluginApiException {

        logger.debug("getPaymentInfo: kbPaymentId={}", kbPaymentId);

        try {
            List<LiqPayResponseRecord> responses = dao.getResponsesForPayment(kbPaymentId, context.getTenantId());
            List<PaymentTransactionInfoPlugin> result = new ArrayList<>();

            for (LiqPayResponseRecord record : responses) {
                LiqPayResponse response = new LiqPayResponse();
                response.setStatus(record.getStatus());
                response.setAction(record.getAction());
                response.setOrderId(record.getLiqpayOrderId());
                response.setLiqpayOrderId(record.getLiqpayOrderId());
                response.setPaymentId(record.getLiqpayPaymentId() != null ? Long.parseLong(record.getLiqpayPaymentId()) : null);
                response.setAmount(record.getAmount());
                response.setCurrency(record.getCurrency());
                response.setErrCode(record.getErrCode());
                response.setErrDescription(record.getErrDescription());

                TransactionType txType = TransactionType.valueOf(record.getTransactionType());
                Currency currency = record.getCurrency() != null ? Currency.valueOf(record.getCurrency()) : null;
                DateTime date = record.getApiCallDate() != null
                        ? new DateTime(record.getApiCallDate().getTime())
                        : new DateTime(record.getCreatedDate().getTime());

                result.add(LiqPayTransactionInfoPlugin.fromResponse(
                        kbPaymentId,
                        record.getKbTransactionId(),
                        txType,
                        record.getAmount(),
                        currency,
                        response,
                        date
                ));
            }

            return result;
        } catch (SQLException e) {
            logger.error("Database error in getPaymentInfo", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public Pagination<PaymentTransactionInfoPlugin> searchPayments(String searchKey, Long offset, Long limit,
                                                                    Iterable<PluginProperty> properties, TenantContext context)
            throws PaymentPluginApiException {
        // Search is not implemented - return empty pagination
        return new Pagination<PaymentTransactionInfoPlugin>() {
            @Override
            public Long getCurrentOffset() { return 0L; }
            @Override
            public Long getNextOffset() { return null; }
            @Override
            public Long getMaxNbRecords() { return 0L; }
            @Override
            public Long getTotalNbRecords() { return 0L; }
            @Override
            public java.util.Iterator<PaymentTransactionInfoPlugin> iterator() { return List.<PaymentTransactionInfoPlugin>of().iterator(); }
            @Override
            public void close() { }
        };
    }

    @Override
    public void addPaymentMethod(UUID kbAccountId, UUID kbPaymentMethodId, PaymentMethodPlugin paymentMethodProps,
                                  boolean setDefault, Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.info("addPaymentMethod: kbAccountId={}, kbPaymentMethodId={}", kbAccountId, kbPaymentMethodId);

        try {
            LiqPayPaymentMethodRecord record = new LiqPayPaymentMethodRecord();
            record.setKbAccountId(kbAccountId);
            record.setKbPaymentMethodId(kbPaymentMethodId);
            record.setKbTenantId(context.getTenantId());
            record.setIsDefault(setDefault);
            record.setIsDeleted(false);

            // Extract token and card info from properties
            String cardToken = getPropertyValue("card_token", properties, null);
            if (cardToken != null) {
                record.setLiqpayCardToken(cardToken);
                record.setCardMask(getPropertyValue("card_mask", properties, null));
                record.setCardType(getPropertyValue("card_type", properties, null));
                record.setCardBank(getPropertyValue("card_bank", properties, null));
                record.setCardCountry(getPropertyValue("card_country", properties, null));
            }

            dao.insertPaymentMethod(record);

            if (setDefault) {
                dao.setDefaultPaymentMethod(kbAccountId, kbPaymentMethodId, context.getTenantId());
            }

        } catch (SQLException e) {
            logger.error("Database error in addPaymentMethod", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public void deletePaymentMethod(UUID kbAccountId, UUID kbPaymentMethodId, Iterable<PluginProperty> properties,
                                     CallContext context) throws PaymentPluginApiException {

        logger.info("deletePaymentMethod: kbPaymentMethodId={}", kbPaymentMethodId);

        try {
            dao.deletePaymentMethod(kbPaymentMethodId, context.getTenantId());
        } catch (SQLException e) {
            logger.error("Database error in deletePaymentMethod", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public PaymentMethodPlugin getPaymentMethodDetail(UUID kbAccountId, UUID kbPaymentMethodId,
                                                       Iterable<PluginProperty> properties, TenantContext context)
            throws PaymentPluginApiException {

        logger.debug("getPaymentMethodDetail: kbPaymentMethodId={}", kbPaymentMethodId);

        try {
            LiqPayPaymentMethodRecord record = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
            if (record == null) {
                return LiqPayPaymentMethodPlugin.empty(kbPaymentMethodId, false);
            }
            return LiqPayPaymentMethodPlugin.fromRecord(record);
        } catch (SQLException e) {
            logger.error("Database error in getPaymentMethodDetail", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public void setDefaultPaymentMethod(UUID kbAccountId, UUID kbPaymentMethodId, Iterable<PluginProperty> properties,
                                         CallContext context) throws PaymentPluginApiException {

        logger.info("setDefaultPaymentMethod: kbPaymentMethodId={}", kbPaymentMethodId);

        try {
            dao.setDefaultPaymentMethod(kbAccountId, kbPaymentMethodId, context.getTenantId());
        } catch (SQLException e) {
            logger.error("Database error in setDefaultPaymentMethod", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public List<PaymentMethodInfoPlugin> getPaymentMethods(UUID kbAccountId, boolean refreshFromGateway,
                                                            Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.debug("getPaymentMethods: kbAccountId={}", kbAccountId);

        try {
            List<LiqPayPaymentMethodRecord> records = dao.getPaymentMethodsForAccount(kbAccountId, context.getTenantId());
            List<PaymentMethodInfoPlugin> result = new ArrayList<>();

            for (LiqPayPaymentMethodRecord record : records) {
                result.add(LiqPayPaymentMethodInfoPlugin.fromRecord(record));
            }

            return result;
        } catch (SQLException e) {
            logger.error("Database error in getPaymentMethods", e);
            throw new PaymentPluginApiException("Database error", e);
        }
    }

    @Override
    public Pagination<PaymentMethodPlugin> searchPaymentMethods(String searchKey, Long offset, Long limit,
                                                                 Iterable<PluginProperty> properties, TenantContext context)
            throws PaymentPluginApiException {
        // Search is not implemented - return empty pagination
        return new Pagination<PaymentMethodPlugin>() {
            @Override
            public Long getCurrentOffset() { return 0L; }
            @Override
            public Long getNextOffset() { return null; }
            @Override
            public Long getMaxNbRecords() { return 0L; }
            @Override
            public Long getTotalNbRecords() { return 0L; }
            @Override
            public java.util.Iterator<PaymentMethodPlugin> iterator() { return List.<PaymentMethodPlugin>of().iterator(); }
            @Override
            public void close() { }
        };
    }

    @Override
    public void resetPaymentMethods(UUID kbAccountId, List<PaymentMethodInfoPlugin> paymentMethods,
                                     Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {
        // Not implemented - payment methods are managed via callbacks
    }

    @Override
    public HostedPaymentPageFormDescriptor buildFormDescriptor(UUID kbAccountId, Iterable<PluginProperty> customFields,
                                                                Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {

        logger.info("buildFormDescriptor: kbAccountId={}", kbAccountId);

        UUID tenantId = context.getTenantId();
        LiqPayConfig config = configurationHandler.getConfigForTenant(tenantId);

        if (!config.isConfigured()) {
            throw new PaymentPluginApiException("", "LiqPay is not configured for this tenant");
        }

        // Extract parameters from custom fields
        Map<String, Object> params = new HashMap<>();
        params.put("version", LiqPayRequest.API_VERSION);
        params.put("public_key", config.getPublicKey());

        // Required parameters
        String orderId = getPropertyValue("order_id", customFields, null);
        String amount = getPropertyValue("amount", customFields, null);
        String currency = getPropertyValue("currency", customFields, "UAH");
        String description = getPropertyValue("description", customFields, "Payment");
        String action = getPropertyValue("action", customFields, "pay");

        if (orderId == null) {
            throw new PaymentPluginApiException("", "order_id is required");
        }
        if (amount == null) {
            throw new PaymentPluginApiException("", "amount is required");
        }

        params.put("order_id", orderId);
        params.put("amount", new BigDecimal(amount));
        params.put("currency", currency);
        params.put("description", description);
        params.put("action", action);

        // Optional parameters (result_url must be passed per request, not from config)
        String resultUrl = getPropertyValue("result_url", customFields, null);
        String serverUrl = getPropertyValue("server_url", customFields, config.getServerUrl());
        String language = getPropertyValue("language", customFields, config.getLanguage());

        if (serverUrl != null) params.put("server_url", serverUrl);
        if (resultUrl != null) params.put("result_url", resultUrl);
        if (language != null) params.put("language", language);

        // Always request token
        params.put("recurringbytoken", "1");

        // Sandbox mode
        if (config.isSandbox()) {
            params.put("sandbox", 1);
        }

        // Generate data and signature
        String data = LiqPaySignature.encodeData(params);
        String signature = LiqPaySignature.createSignature(config.getPrivateKey(), data);

        return new LiqPayHostedPaymentPageFormDescriptor(kbAccountId, Map.of(
                "data", data,
                "signature", signature
        ));
    }

    /**
     * Process notification is NOT used for LiqPay webhooks.
     *
     * LiqPay callbacks are handled by the servlet at /plugins/killbill-liqpay/callback
     * which doesn't require Kill Bill API key headers.
     *
     * @throws PaymentPluginApiException always - use the servlet endpoint instead
     */
    @Override
    public GatewayNotification processNotification(String notification, Iterable<PluginProperty> properties,
                                                    CallContext context) throws PaymentPluginApiException {
        throw new PaymentPluginApiException("INTERNAL",
                "Use /plugins/killbill-liqpay/callback endpoint for LiqPay webhooks. " +
                "The processNotification method requires Kill Bill API key headers which LiqPay doesn't send.");
    }

    // Helper methods

    private String getPropertyValue(String key, Iterable<PluginProperty> properties, String defaultValue) {
        if (properties == null) {
            return defaultValue;
        }
        for (PluginProperty prop : properties) {
            if (key.equals(prop.getKey()) && prop.getValue() != null) {
                return prop.getValue().toString();
            }
        }
        return defaultValue;
    }
}
