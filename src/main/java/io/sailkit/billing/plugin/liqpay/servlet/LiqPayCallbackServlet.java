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

package io.sailkit.billing.plugin.liqpay.servlet;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.joda.time.DateTime;
import org.jooby.Result;
import org.jooby.Results;
import org.jooby.Status;
import org.jooby.mvc.Consumes;
import org.jooby.mvc.POST;
import org.jooby.mvc.Path;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountApiException;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PaymentApi;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.plugin.core.resources.PluginHealthcheck;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.CallOrigin;
import org.killbill.billing.util.callcontext.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sailkit.billing.plugin.liqpay.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.LiqPayConfigurationHandler;
import io.sailkit.billing.plugin.liqpay.api.LiqPayStatusMapper;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResponse;
import io.sailkit.billing.plugin.liqpay.client.LiqPaySignature;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayDao;
import io.sailkit.billing.plugin.liqpay.dao.model.PendingTransactionRecord;

/**
 * Servlet to handle LiqPay payment callbacks/webhooks.
 *
 * LiqPay sends POST requests to this endpoint with payment status updates.
 * URL: /plugins/killbill-liqpay/callback
 *
 * This endpoint does NOT require Kill Bill API key headers.
 */
@Singleton
@Path("/callback")
public class LiqPayCallbackServlet extends PluginHealthcheck {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayCallbackServlet.class);

    private final OSGIKillbillAPI killbillAPI;
    private final LiqPayConfigurationHandler configurationHandler;
    private final LiqPayDao dao;

    @Inject
    public LiqPayCallbackServlet(final OSGIKillbillAPI killbillAPI,
                                  final LiqPayConfigurationHandler configurationHandler,
                                  final LiqPayDao dao) {
        this.killbillAPI = killbillAPI;
        this.configurationHandler = configurationHandler;
        this.dao = dao;
    }

    /**
     * Handles LiqPay callback POST requests.
     *
     * LiqPay sends:
     * - data: Base64-encoded JSON with payment information
     * - signature: HMAC signature for verification
     *
     * @param req The HTTP request containing form data
     * @return Result (200 OK on success)
     */
    @POST
    @Consumes("application/x-www-form-urlencoded")
    public Result handleCallback(final org.jooby.Request req) {
        logger.info("Received LiqPay callback at /plugins/killbill-liqpay/callback");

        try {
            // Parse form parameters
            String data = req.param("data").toOptional().orElse(null);
            String signature = req.param("signature").toOptional().orElse(null);

            if (data == null || data.isEmpty()) {
                logger.warn("Callback received with empty data");
                return Results.with("Missing data", Status.BAD_REQUEST);
            }

            if (signature == null || signature.isEmpty()) {
                logger.warn("Callback received with empty signature");
                return Results.with("Missing signature", Status.BAD_REQUEST);
            }

            logger.debug("Received data length: {}, signature: {}...",
                    data.length(), signature.substring(0, Math.min(10, signature.length())));

            // Decode the callback data to get order_id and determine tenant
            Map<String, Object> callbackData = LiqPaySignature.decodeData(data);
            String orderId = (String) callbackData.get("order_id");
            String publicKey = (String) callbackData.get("public_key");

            logger.info("Callback for order_id: {}, public_key: {}", orderId, publicKey);

            if (orderId == null) {
                logger.warn("Callback missing order_id");
                return Results.with("Missing order_id", Status.BAD_REQUEST);
            }

            // Get the pending transaction to find tenant and KB IDs
            PendingTransactionRecord pendingTx = dao.getPendingTransaction(orderId);
            if (pendingTx == null) {
                logger.warn("No pending transaction found for order_id: {}", orderId);
                // Return 200 to prevent LiqPay retries
                return Results.with("OK", Status.OK);
            }

            UUID tenantId = pendingTx.getKbTenantId();
            logger.info("Found pending transaction: kbAccountId={}, kbPaymentId={}, kbTransactionId={}, tenantId={}",
                    pendingTx.getKbAccountId(), pendingTx.getKbPaymentId(),
                    pendingTx.getKbTransactionId(), tenantId);

            // Get configuration for tenant
            LiqPayConfig config = configurationHandler.getConfigForTenant(tenantId);
            if (config == null || !config.isConfigured()) {
                logger.error("LiqPay not configured for tenant {}", tenantId);
                // Return 200 to prevent LiqPay retries, but log error
                return Results.with("OK", Status.OK);
            }

            // Verify signature
            String expectedSignature = LiqPaySignature.createSignature(config.getPrivateKey(), data);
            logger.debug("Signature verification: received={}, expected={}", signature, expectedSignature);

            if (!expectedSignature.equals(signature)) {
                logger.warn("Invalid signature for callback, order_id: {}", orderId);
                return Results.with("Invalid signature", Status.UNAUTHORIZED);
            }

            logger.info("Signature verified successfully for order_id: {}", orderId);

            // Parse the full response
            LiqPayResponse response = LiqPayResponse.fromMap(callbackData);
            logger.info("LiqPay callback: status={}, action={}, order_id={}, payment_id={}",
                    response.getStatus(), response.getAction(), response.getOrderId(), response.getPaymentId());

            // Process the callback - update DB and notify KillBill
            processCallback(pendingTx, response, tenantId, LiqPaySignature.decodeToJson(data));

            return Results.with("OK", Status.OK);

        } catch (Exception e) {
            logger.error("Error processing LiqPay callback", e);
            // Return 200 to prevent LiqPay from retrying
            return Results.with("OK", Status.OK);
        }
    }

    /**
     * Processes the callback, updates the database, and notifies KillBill.
     */
    private void processCallback(PendingTransactionRecord pendingTx, LiqPayResponse response,
                                  UUID tenantId, String rawJson) {
        String orderId = response.getOrderId();
        if (orderId == null) {
            orderId = response.getLiqpayOrderId();
        }

        try {
            // Determine if payment was successful
            boolean isSuccess = LiqPayStatusMapper.isSuccessStatus(response.getStatus(), response.getAction());
            boolean isFinal = LiqPayStatusMapper.isFinalStatus(response.getStatus());

            logger.info("Processing callback for order {}: status={}, isSuccess={}, isFinal={}",
                    orderId, response.getStatus(), isSuccess, isFinal);

            // Update pending transaction status
            String status = isSuccess ? "COMPLETED" : (response.isError() ? "FAILED" : "PENDING");
            dao.updatePendingTransactionStatus(orderId, tenantId, status);

            // Save the response for audit
            dao.saveResponse(
                    pendingTx.getKbAccountId(),
                    pendingTx.getKbPaymentId(),
                    pendingTx.getKbTransactionId(),
                    pendingTx.getKbPaymentMethodId(),
                    tenantId,
                    mapActionToTransactionType(response.getAction()),
                    response,
                    rawJson
            );

            // If we received a card token, update the payment method
            if (response.hasCardToken() && pendingTx.getKbPaymentMethodId() != null) {
                logger.info("Updating payment method {} with card token", pendingTx.getKbPaymentMethodId());
                dao.updatePaymentMethodToken(
                        pendingTx.getKbPaymentMethodId(),
                        tenantId,
                        response.getCardToken(),
                        response.getSenderCardMask(),
                        response.getSenderCardType(),
                        response.getSenderCardBank(),
                        response.getSenderCardCountry()
                );
            }

            // Notify KillBill about the payment state change
            if (isFinal && pendingTx.getKbPaymentId() != null && pendingTx.getKbTransactionId() != null) {
                notifyKillBillPaymentStateChange(pendingTx, isSuccess);
            }

            logger.info("Callback processed successfully for order {}, status={}", orderId, response.getStatus());

        } catch (SQLException e) {
            logger.error("Database error processing callback", e);
        }
    }

    /**
     * Notifies KillBill about the payment transaction state change.
     * This updates the payment status from PENDING to SUCCESS or FAILURE.
     */
    private void notifyKillBillPaymentStateChange(PendingTransactionRecord pendingTx, boolean isSuccess) {
        try {
            logger.info("Notifying KillBill about payment state change: paymentId={}, transactionId={}, isSuccess={}",
                    pendingTx.getKbPaymentId(), pendingTx.getKbTransactionId(), isSuccess);

            // Create a CallContext for the API call
            CallContext context = createCallContext(pendingTx.getKbTenantId(), pendingTx.getKbAccountId());

            // Get the Account object (required by the API)
            Account account = killbillAPI.getAccountUserApi().getAccountById(
                    pendingTx.getKbAccountId(), context);

            // Get the PaymentApi
            PaymentApi paymentApi = killbillAPI.getPaymentApi();

            // Notify KillBill that the pending transaction has completed
            // API signature: notifyPendingTransactionOfStateChanged(Account, UUID transactionId, boolean isSuccess, CallContext)
            paymentApi.notifyPendingTransactionOfStateChanged(
                    account,
                    pendingTx.getKbTransactionId(),
                    isSuccess,
                    context
            );

            logger.info("Successfully notified KillBill about payment state change");

        } catch (PaymentApiException e) {
            logger.error("Failed to notify KillBill about payment state change: {}",
                    e.getMessage(), e);
        } catch (AccountApiException e) {
            logger.error("Failed to get account for payment state change notification: {}",
                    e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error notifying KillBill", e);
        }
    }

    /**
     * Creates a CallContext for KillBill API calls.
     */
    private CallContext createCallContext(UUID tenantId, UUID accountId) {
        final DateTime now = new DateTime();
        return new CallContext() {
            @Override
            public UUID getUserToken() {
                return UUID.randomUUID();
            }

            @Override
            public String getUserName() {
                return "LiqPayCallback";
            }

            @Override
            public CallOrigin getCallOrigin() {
                return CallOrigin.EXTERNAL;
            }

            @Override
            public UserType getUserType() {
                return UserType.SYSTEM;
            }

            @Override
            public String getReasonCode() {
                return "LiqPay payment callback";
            }

            @Override
            public String getComments() {
                return "Payment status updated via LiqPay callback";
            }

            @Override
            public DateTime getCreatedDate() {
                return now;
            }

            @Override
            public DateTime getUpdatedDate() {
                return now;
            }

            @Override
            public UUID getTenantId() {
                return tenantId;
            }

            @Override
            public UUID getAccountId() {
                return accountId;
            }
        };
    }

    /**
     * Maps LiqPay action to KillBill transaction type.
     */
    private String mapActionToTransactionType(String action) {
        if (action == null) {
            return "PURCHASE";
        }
        switch (action) {
            case "hold":
                return "AUTHORIZE";
            case "hold_completion":
                return "CAPTURE";
            case "refund":
                return "REFUND";
            case "unhold":
                return "VOID";
            case "pay":
            case "paytoken":
            default:
                return "PURCHASE";
        }
    }
}
