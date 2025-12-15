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

package io.sailkit.billing.plugin.liqpay.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sailkit.billing.plugin.liqpay.client.LiqPayResponse;
import io.sailkit.billing.plugin.liqpay.dao.model.HppRequestRecord;
import io.sailkit.billing.plugin.liqpay.dao.model.LiqPayPaymentMethodRecord;
import io.sailkit.billing.plugin.liqpay.dao.model.LiqPayResponseRecord;
import io.sailkit.billing.plugin.liqpay.dao.model.PendingTransactionRecord;

/**
 * Data Access Object for LiqPay plugin database operations.
 */
public class LiqPayDao {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayDao.class);

    private final DataSource dataSource;

    public LiqPayDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // Payment Method Operations

    /**
     * Inserts a new payment method record.
     */
    public void insertPaymentMethod(LiqPayPaymentMethodRecord record) throws SQLException {
        String sql = "INSERT INTO liqpay_payment_methods " +
                "(kb_account_id, kb_payment_method_id, kb_tenant_id, liqpay_card_token, " +
                "card_mask, card_type, card_bank, card_country, is_default, is_deleted, additional_data) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, record.getKbAccountId().toString());
            stmt.setString(2, record.getKbPaymentMethodId().toString());
            stmt.setString(3, record.getKbTenantId().toString());
            stmt.setString(4, record.getLiqpayCardToken());
            stmt.setString(5, record.getCardMask());
            stmt.setString(6, record.getCardType());
            stmt.setString(7, record.getCardBank());
            stmt.setString(8, record.getCardCountry());
            stmt.setBoolean(9, record.getIsDefault() != null && record.getIsDefault());
            stmt.setBoolean(10, record.getIsDeleted() != null && record.getIsDeleted());
            stmt.setString(11, record.getAdditionalData());

            stmt.executeUpdate();
            logger.debug("Inserted payment method record for KB payment method {}", record.getKbPaymentMethodId());
        }
    }

    /**
     * Gets a payment method record by KillBill payment method ID.
     */
    public LiqPayPaymentMethodRecord getPaymentMethod(UUID kbPaymentMethodId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_payment_methods " +
                "WHERE kb_payment_method_id = ? AND kb_tenant_id = ? AND is_deleted = FALSE";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbPaymentMethodId.toString());
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapPaymentMethodRecord(rs);
                }
            }
        }
        return null;
    }

    /**
     * Gets all payment methods for an account.
     */
    public List<LiqPayPaymentMethodRecord> getPaymentMethodsForAccount(UUID kbAccountId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_payment_methods " +
                "WHERE kb_account_id = ? AND kb_tenant_id = ? AND is_deleted = FALSE " +
                "ORDER BY created_date DESC";

        List<LiqPayPaymentMethodRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbAccountId.toString());
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapPaymentMethodRecord(rs));
                }
            }
        }
        return records;
    }

    /**
     * Updates the card token for a payment method.
     */
    public void updatePaymentMethodToken(UUID kbPaymentMethodId, UUID kbTenantId,
                                         String cardToken, String cardMask, String cardType,
                                         String cardBank, String cardCountry) throws SQLException {
        String sql = "UPDATE liqpay_payment_methods SET " +
                "liqpay_card_token = ?, card_mask = ?, card_type = ?, card_bank = ?, card_country = ?, " +
                "updated_date = CURRENT_TIMESTAMP " +
                "WHERE kb_payment_method_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, cardToken);
            stmt.setString(2, cardMask);
            stmt.setString(3, cardType);
            stmt.setString(4, cardBank);
            stmt.setString(5, cardCountry);
            stmt.setString(6, kbPaymentMethodId.toString());
            stmt.setString(7, kbTenantId.toString());

            stmt.executeUpdate();
            logger.debug("Updated token for payment method {}", kbPaymentMethodId);
        }
    }

    /**
     * Soft deletes a payment method.
     */
    public void deletePaymentMethod(UUID kbPaymentMethodId, UUID kbTenantId) throws SQLException {
        String sql = "UPDATE liqpay_payment_methods SET " +
                "is_deleted = TRUE, updated_date = CURRENT_TIMESTAMP " +
                "WHERE kb_payment_method_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbPaymentMethodId.toString());
            stmt.setString(2, kbTenantId.toString());

            stmt.executeUpdate();
            logger.debug("Soft deleted payment method {}", kbPaymentMethodId);
        }
    }

    /**
     * Sets a payment method as default.
     */
    public void setDefaultPaymentMethod(UUID kbAccountId, UUID kbPaymentMethodId, UUID kbTenantId) throws SQLException {
        // First, unset all defaults for this account
        String unselectSql = "UPDATE liqpay_payment_methods SET is_default = FALSE, updated_date = CURRENT_TIMESTAMP " +
                "WHERE kb_account_id = ? AND kb_tenant_id = ?";

        // Then set the new default
        String setSql = "UPDATE liqpay_payment_methods SET is_default = TRUE, updated_date = CURRENT_TIMESTAMP " +
                "WHERE kb_payment_method_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(unselectSql)) {
                    stmt.setString(1, kbAccountId.toString());
                    stmt.setString(2, kbTenantId.toString());
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(setSql)) {
                    stmt.setString(1, kbPaymentMethodId.toString());
                    stmt.setString(2, kbTenantId.toString());
                    stmt.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Gets the card token for a payment method.
     */
    public String getCardToken(UUID kbPaymentMethodId, UUID kbTenantId) throws SQLException {
        LiqPayPaymentMethodRecord record = getPaymentMethod(kbPaymentMethodId, kbTenantId);
        return record != null ? record.getLiqpayCardToken() : null;
    }

    // Response Operations

    /**
     * Saves a LiqPay API response.
     */
    public void saveResponse(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                             UUID kbPaymentMethodId, UUID kbTenantId,
                             String transactionType, LiqPayResponse response, String rawJson) throws SQLException {
        String sql = "INSERT INTO liqpay_responses " +
                "(kb_account_id, kb_payment_id, kb_transaction_id, kb_payment_method_id, kb_tenant_id, " +
                "liqpay_order_id, liqpay_payment_id, liqpay_transaction_id, transaction_type, action, status, " +
                "amount, currency, err_code, err_description, raw_response, api_call_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbAccountId != null ? kbAccountId.toString() : null);
            stmt.setString(2, kbPaymentId != null ? kbPaymentId.toString() : null);
            stmt.setString(3, kbTransactionId != null ? kbTransactionId.toString() : null);
            stmt.setString(4, kbPaymentMethodId != null ? kbPaymentMethodId.toString() : null);
            stmt.setString(5, kbTenantId.toString());
            stmt.setString(6, response.getOrderId() != null ? response.getOrderId() : response.getLiqpayOrderId());
            stmt.setString(7, response.getPaymentId() != null ? response.getPaymentId().toString() : null);
            stmt.setString(8, response.getTransactionId() != null ? response.getTransactionId().toString() : null);
            stmt.setString(9, transactionType);
            stmt.setString(10, response.getAction());
            stmt.setString(11, response.getStatus());
            stmt.setBigDecimal(12, response.getAmount());
            stmt.setString(13, response.getCurrency());
            stmt.setString(14, response.getErrCode());
            stmt.setString(15, response.getErrDescription());
            stmt.setString(16, rawJson);

            stmt.executeUpdate();
            logger.debug("Saved LiqPay response for transaction {}", kbTransactionId);
        }
    }

    /**
     * Gets the latest response for a transaction.
     */
    public LiqPayResponseRecord getLatestResponse(UUID kbTransactionId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_responses " +
                "WHERE kb_transaction_id = ? AND kb_tenant_id = ? " +
                "ORDER BY created_date DESC LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbTransactionId.toString());
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResponseRecord(rs);
                }
            }
        }
        return null;
    }

    /**
     * Gets all responses for a payment.
     */
    public List<LiqPayResponseRecord> getResponsesForPayment(UUID kbPaymentId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_responses " +
                "WHERE kb_payment_id = ? AND kb_tenant_id = ? " +
                "ORDER BY created_date ASC";

        List<LiqPayResponseRecord> records = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbPaymentId.toString());
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResponseRecord(rs));
                }
            }
        }
        return records;
    }

    /**
     * Gets a response by LiqPay order ID.
     */
    public LiqPayResponseRecord getResponseByOrderId(String orderId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_responses " +
                "WHERE liqpay_order_id = ? AND kb_tenant_id = ? " +
                "ORDER BY created_date DESC LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, orderId);
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResponseRecord(rs);
                }
            }
        }
        return null;
    }

    // Pending Transaction Operations

    /**
     * Creates a pending transaction record.
     */
    public void createPendingTransaction(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                         UUID kbPaymentMethodId, UUID kbTenantId, String orderId,
                                         String transactionType, BigDecimal amount, String currency) throws SQLException {
        String sql = "INSERT INTO liqpay_pending_transactions " +
                "(kb_account_id, kb_payment_id, kb_transaction_id, kb_payment_method_id, kb_tenant_id, " +
                "order_id, transaction_type, amount, currency, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbAccountId.toString());
            stmt.setString(2, kbPaymentId != null ? kbPaymentId.toString() : null);
            stmt.setString(3, kbTransactionId.toString());
            stmt.setString(4, kbPaymentMethodId != null ? kbPaymentMethodId.toString() : null);
            stmt.setString(5, kbTenantId.toString());
            stmt.setString(6, orderId);
            stmt.setString(7, transactionType);
            stmt.setBigDecimal(8, amount);
            stmt.setString(9, currency);

            stmt.executeUpdate();
            logger.debug("Created pending transaction for order {}", orderId);
        }
    }

    /**
     * Gets a pending transaction by order ID.
     */
    public UUID getPendingTransactionId(String orderId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT kb_transaction_id FROM liqpay_pending_transactions " +
                "WHERE order_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, orderId);
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("kb_transaction_id"));
                }
            }
        }
        return null;
    }

    /**
     * Updates pending transaction status.
     */
    public void updatePendingTransactionStatus(String orderId, UUID kbTenantId, String status) throws SQLException {
        String sql;
        if (kbTenantId != null) {
            sql = "UPDATE liqpay_pending_transactions SET " +
                    "status = ?, updated_date = CURRENT_TIMESTAMP " +
                    "WHERE order_id = ? AND kb_tenant_id = ?";
        } else {
            sql = "UPDATE liqpay_pending_transactions SET " +
                    "status = ?, updated_date = CURRENT_TIMESTAMP " +
                    "WHERE order_id = ?";
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, status);
            stmt.setString(2, orderId);
            if (kbTenantId != null) {
                stmt.setString(3, kbTenantId.toString());
            }

            stmt.executeUpdate();
        }
    }

    /**
     * Gets tenant ID for a pending transaction by order ID.
     * Used in callback handling to determine which tenant config to use.
     */
    public UUID getTenantIdForOrder(String orderId) throws SQLException {
        String sql = "SELECT kb_tenant_id FROM liqpay_pending_transactions WHERE order_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, orderId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String tenantId = rs.getString("kb_tenant_id");
                    return tenantId != null ? UUID.fromString(tenantId) : null;
                }
            }
        }
        return null;
    }

    /**
     * Gets the full pending transaction record by order ID.
     * Used in callback handling to get all transaction details for KillBill API calls.
     */
    public PendingTransactionRecord getPendingTransaction(String orderId) throws SQLException {
        String sql = "SELECT * FROM liqpay_pending_transactions WHERE order_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, orderId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapPendingTransactionRecord(rs);
                }
            }
        }
        return null;
    }

    /**
     * Gets the full pending transaction record by order ID and tenant.
     */
    public PendingTransactionRecord getPendingTransaction(String orderId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_pending_transactions WHERE order_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, orderId);
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapPendingTransactionRecord(rs);
                }
            }
        }
        return null;
    }

    // Mapping helpers

    private LiqPayPaymentMethodRecord mapPaymentMethodRecord(ResultSet rs) throws SQLException {
        LiqPayPaymentMethodRecord record = new LiqPayPaymentMethodRecord();
        record.setRecordId(rs.getLong("record_id"));
        record.setKbAccountId(UUID.fromString(rs.getString("kb_account_id")));
        record.setKbPaymentMethodId(UUID.fromString(rs.getString("kb_payment_method_id")));
        record.setKbTenantId(UUID.fromString(rs.getString("kb_tenant_id")));
        record.setLiqpayCardToken(rs.getString("liqpay_card_token"));
        record.setCardMask(rs.getString("card_mask"));
        record.setCardType(rs.getString("card_type"));
        record.setCardBank(rs.getString("card_bank"));
        record.setCardCountry(rs.getString("card_country"));
        record.setIsDefault(rs.getBoolean("is_default"));
        record.setIsDeleted(rs.getBoolean("is_deleted"));
        record.setAdditionalData(rs.getString("additional_data"));
        record.setCreatedDate(rs.getTimestamp("created_date"));
        record.setUpdatedDate(rs.getTimestamp("updated_date"));
        return record;
    }

    private LiqPayResponseRecord mapResponseRecord(ResultSet rs) throws SQLException {
        LiqPayResponseRecord record = new LiqPayResponseRecord();
        record.setRecordId(rs.getLong("record_id"));

        String accountId = rs.getString("kb_account_id");
        record.setKbAccountId(accountId != null ? UUID.fromString(accountId) : null);

        String paymentId = rs.getString("kb_payment_id");
        record.setKbPaymentId(paymentId != null ? UUID.fromString(paymentId) : null);

        String transactionId = rs.getString("kb_transaction_id");
        record.setKbTransactionId(transactionId != null ? UUID.fromString(transactionId) : null);

        String paymentMethodId = rs.getString("kb_payment_method_id");
        record.setKbPaymentMethodId(paymentMethodId != null ? UUID.fromString(paymentMethodId) : null);

        record.setKbTenantId(UUID.fromString(rs.getString("kb_tenant_id")));
        record.setLiqpayOrderId(rs.getString("liqpay_order_id"));
        record.setLiqpayPaymentId(rs.getString("liqpay_payment_id"));
        record.setLiqpayTransactionId(rs.getString("liqpay_transaction_id"));
        record.setTransactionType(rs.getString("transaction_type"));
        record.setAction(rs.getString("action"));
        record.setStatus(rs.getString("status"));
        record.setAmount(rs.getBigDecimal("amount"));
        record.setCurrency(rs.getString("currency"));
        record.setErrCode(rs.getString("err_code"));
        record.setErrDescription(rs.getString("err_description"));
        record.setRawResponse(rs.getString("raw_response"));
        record.setApiCallDate(rs.getTimestamp("api_call_date"));
        record.setCreatedDate(rs.getTimestamp("created_date"));
        return record;
    }

    private PendingTransactionRecord mapPendingTransactionRecord(ResultSet rs) throws SQLException {
        PendingTransactionRecord record = new PendingTransactionRecord();
        record.setRecordId(rs.getLong("record_id"));

        String accountId = rs.getString("kb_account_id");
        record.setKbAccountId(accountId != null ? UUID.fromString(accountId) : null);

        String paymentId = rs.getString("kb_payment_id");
        record.setKbPaymentId(paymentId != null ? UUID.fromString(paymentId) : null);

        String transactionId = rs.getString("kb_transaction_id");
        record.setKbTransactionId(transactionId != null ? UUID.fromString(transactionId) : null);

        String paymentMethodId = rs.getString("kb_payment_method_id");
        record.setKbPaymentMethodId(paymentMethodId != null ? UUID.fromString(paymentMethodId) : null);

        String tenantId = rs.getString("kb_tenant_id");
        record.setKbTenantId(tenantId != null ? UUID.fromString(tenantId) : null);

        record.setOrderId(rs.getString("order_id"));
        record.setTransactionType(rs.getString("transaction_type"));
        record.setAmount(rs.getBigDecimal("amount"));
        record.setCurrency(rs.getString("currency"));
        record.setStatus(rs.getString("status"));
        record.setCreatedDate(rs.getTimestamp("created_date"));
        record.setUpdatedDate(rs.getTimestamp("updated_date"));

        return record;
    }

    // HPP (Hosted Payment Page) Request Operations

    /**
     * Creates an HPP request record for buildFormDescriptor sessions.
     * Used to track checkout redirect/embed flows and verification mode.
     *
     * @param kbAccountId      KillBill account ID
     * @param kbPaymentMethodId KillBill payment method ID (optional)
     * @param kbTenantId       KillBill tenant ID
     * @param sessionId        Internal session ID (UUID)
     * @param orderId          LiqPay order ID (hpp-{uuid})
     * @param mode             Display mode: redirect, embed, popup
     * @param isVerification   True if this is a card verification (auto-unhold)
     * @param amount           Transaction amount
     * @param currency         Currency code
     * @param additionalData   JSON with data, signature, urls
     * @return The session ID
     */
    public String addHppRequest(UUID kbAccountId, UUID kbPaymentMethodId, UUID kbTenantId,
                                String sessionId, String orderId, String mode, boolean isVerification,
                                BigDecimal amount, String currency, String additionalData) throws SQLException {
        String sql = "INSERT INTO liqpay_hpp_requests " +
                "(kb_account_id, kb_payment_method_id, kb_tenant_id, session_id, order_id, " +
                "mode, is_verification, amount, currency, additional_data, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED')";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, kbAccountId.toString());
            stmt.setString(2, kbPaymentMethodId != null ? kbPaymentMethodId.toString() : null);
            stmt.setString(3, kbTenantId.toString());
            stmt.setString(4, sessionId);
            stmt.setString(5, orderId);
            stmt.setString(6, mode);
            stmt.setBoolean(7, isVerification);
            stmt.setBigDecimal(8, amount);
            stmt.setString(9, currency);
            stmt.setString(10, additionalData);

            stmt.executeUpdate();
            logger.debug("Created HPP request: sessionId={}, orderId={}, mode={}, verification={}",
                    sessionId, orderId, mode, isVerification);
        }
        return sessionId;
    }

    /**
     * Gets an HPP request by order ID.
     * Used in callback handling to check verification mode.
     */
    public HppRequestRecord getHppRequestByOrderId(String orderId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_hpp_requests WHERE order_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, orderId);
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapHppRequestRecord(rs);
                }
            }
        }
        return null;
    }

    /**
     * Gets an HPP request by session ID.
     */
    public HppRequestRecord getHppRequestBySessionId(String sessionId, UUID kbTenantId) throws SQLException {
        String sql = "SELECT * FROM liqpay_hpp_requests WHERE session_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, sessionId);
            stmt.setString(2, kbTenantId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapHppRequestRecord(rs);
                }
            }
        }
        return null;
    }

    /**
     * Updates HPP request status.
     */
    public void updateHppRequestStatus(String orderId, UUID kbTenantId, String status) throws SQLException {
        String sql = "UPDATE liqpay_hpp_requests SET " +
                "status = ?, updated_date = CURRENT_TIMESTAMP " +
                "WHERE order_id = ? AND kb_tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, status);
            stmt.setString(2, orderId);
            stmt.setString(3, kbTenantId.toString());

            stmt.executeUpdate();
            logger.debug("Updated HPP request status: orderId={}, status={}", orderId, status);
        }
    }

    private HppRequestRecord mapHppRequestRecord(ResultSet rs) throws SQLException {
        HppRequestRecord record = new HppRequestRecord();
        record.setRecordId(rs.getLong("record_id"));

        String accountId = rs.getString("kb_account_id");
        record.setKbAccountId(accountId != null ? UUID.fromString(accountId) : null);

        String paymentMethodId = rs.getString("kb_payment_method_id");
        record.setKbPaymentMethodId(paymentMethodId != null ? UUID.fromString(paymentMethodId) : null);

        String tenantId = rs.getString("kb_tenant_id");
        record.setKbTenantId(tenantId != null ? UUID.fromString(tenantId) : null);

        record.setSessionId(rs.getString("session_id"));
        record.setOrderId(rs.getString("order_id"));
        record.setMode(rs.getString("mode"));
        record.setVerification(rs.getBoolean("is_verification"));
        record.setAmount(rs.getBigDecimal("amount"));
        record.setCurrency(rs.getString("currency"));
        record.setAdditionalData(rs.getString("additional_data"));
        record.setStatus(rs.getString("status"));
        record.setCreatedDate(rs.getTimestamp("created_date"));
        record.setUpdatedDate(rs.getTimestamp("updated_date"));

        return record;
    }
}
