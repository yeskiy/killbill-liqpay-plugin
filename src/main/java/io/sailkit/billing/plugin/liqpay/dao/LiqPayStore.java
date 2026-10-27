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

package io.sailkit.billing.plugin.liqpay.dao;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;

/** The rows that the payment flows and the callback read and write. */
public interface LiqPayStore {

    void insertTransaction(LiqpayTransactionsRecord row) throws SQLException;

    Optional<LiqpayTransactionsRecord> findByTransactionId(UUID kbTransactionId, UUID tenantId) throws SQLException;

    Optional<LiqpayTransactionsRecord> findByOrderId(String orderId, UUID tenantId) throws SQLException;

    List<LiqpayTransactionsRecord> findByPayment(UUID kbPaymentId, UUID tenantId) throws SQLException;

    /** Applies an answer. `kept` holds stored fields that the answer must not drop, for example the checkout form. */
    boolean updateFromLiqPay(String orderId, UUID tenantId, LiqPayResult result, PaymentPluginStatus newStatus,
                             Map<String, Object> kept) throws SQLException;

    boolean cancelPending(String orderId, UUID tenantId, String errCode) throws SQLException;

    void upsertPaymentMethod(LiqpayPaymentMethodsRecord row) throws SQLException;

    Optional<LiqpayPaymentMethodsRecord> findPaymentMethod(UUID kbPaymentMethodId, UUID tenantId) throws SQLException;

    void insertHppRequest(LiqpayHppRequestsRecord row) throws SQLException;

    Optional<LiqpayHppRequestsRecord> findHppRequestByOrderId(String orderId, UUID tenantId) throws SQLException;

    /** Sets the status of an open request. Returns false for a final request or an end_date that is not newer. */
    boolean updateHppRequest(String orderId, UUID tenantId, String status, Long endDate) throws SQLException;
}
