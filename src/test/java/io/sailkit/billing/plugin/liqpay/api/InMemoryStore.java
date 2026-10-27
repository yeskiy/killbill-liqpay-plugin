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
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.dao.HppStatus;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayStore;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;

/** A store in memory with the update rules of the DAO. `failing` makes every call throw. */
public class InMemoryStore implements LiqPayStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final List<LiqpayTransactionsRecord> rows = new ArrayList<>();
    public final List<LiqpayPaymentMethodsRecord> methods = new ArrayList<>();
    public final List<LiqpayHppRequestsRecord> hppRequests = new ArrayList<>();
    public boolean failing;

    private void check() throws SQLException {
        if (failing) {
            throw new SQLException("database is down");
        }
    }

    @Override
    public synchronized void insertTransaction(final LiqpayTransactionsRecord row) throws SQLException {
        check();
        if (rows.stream().anyMatch(r -> r.getKbTenantId().equals(row.getKbTenantId())
                                        && (r.getOrderId().equals(row.getOrderId())
                                            || r.getKbTransactionId().equals(row.getKbTransactionId())))) {
            throw new SQLIntegrityConstraintViolationException("duplicate order id");
        }
        rows.add(row.copy());
    }

    @Override
    public synchronized Optional<LiqpayTransactionsRecord> findByTransactionId(final UUID kbTransactionId, final UUID tenantId) throws SQLException {
        check();
        return rows.stream().filter(r -> r.getKbTransactionId().equals(kbTransactionId.toString())
                                         && r.getKbTenantId().equals(tenantId.toString())).findFirst().map(LiqpayTransactionsRecord::copy);
    }

    @Override
    public synchronized Optional<LiqpayTransactionsRecord> findByOrderId(final String orderId, final UUID tenantId) throws SQLException {
        check();
        return find(orderId, tenantId).map(LiqpayTransactionsRecord::copy);
    }

    @Override
    public synchronized List<LiqpayTransactionsRecord> findByPayment(final UUID kbPaymentId, final UUID tenantId) throws SQLException {
        check();
        return rows.stream().filter(r -> r.getKbPaymentId().equals(kbPaymentId.toString()) && r.getKbTenantId().equals(tenantId.toString()))
                   .map(LiqpayTransactionsRecord::copy).collect(Collectors.toList());
    }

    @Override
    public synchronized boolean updateFromLiqPay(final String orderId, final UUID tenantId, final LiqPayResult result,
                                                 final PaymentPluginStatus newStatus, final Map<String, Object> kept) throws SQLException {
        check();
        final Optional<LiqpayTransactionsRecord> row = find(orderId, tenantId)
                .filter(r -> isOpen(r.getPluginStatus()))
                .filter(r -> result.endDate() == null || r.getLiqpayEndDate() == null || r.getLiqpayEndDate() < result.endDate());
        row.ifPresent(r -> {
            final Map<String, Object> fields = new LinkedHashMap<>(kept);
            fields.putAll(result.toStoredFields());
            r.setPluginStatus(newStatus.name());
            r.setLiqpayStatus(result.status());
            r.setLiqpayPaymentId(result.paymentId());
            r.setLiqpayEndDate(result.endDate() == null ? r.getLiqpayEndDate() : result.endDate());
            r.setErrCode(result.errCode());
            r.setErrDescription(result.errDescription());
            r.setAdditionalData(json(fields));
        });
        return row.isPresent();
    }

    @Override
    public synchronized boolean cancelPending(final String orderId, final UUID tenantId, final String errCode) throws SQLException {
        check();
        final Optional<LiqpayTransactionsRecord> row = find(orderId, tenantId).filter(r -> isOpen(r.getPluginStatus()));
        row.ifPresent(r -> {
            r.setPluginStatus(PaymentPluginStatus.CANCELED.name());
            r.setErrCode(errCode);
        });
        return row.isPresent();
    }

    @Override
    public synchronized void upsertPaymentMethod(final LiqpayPaymentMethodsRecord row) throws SQLException {
        check();
        methods.removeIf(m -> m.getKbPaymentMethodId().equals(row.getKbPaymentMethodId()) && m.getKbTenantId().equals(row.getKbTenantId()));
        methods.add(row.copy());
    }

    @Override
    public synchronized Optional<LiqpayPaymentMethodsRecord> findPaymentMethod(final UUID kbPaymentMethodId, final UUID tenantId) throws SQLException {
        check();
        return methods.stream().filter(m -> m.getKbPaymentMethodId().equals(kbPaymentMethodId.toString())
                                            && m.getKbTenantId().equals(tenantId.toString())
                                            && !Boolean.TRUE.equals(m.getIsDeleted())).findFirst().map(LiqpayPaymentMethodsRecord::copy);
    }

    @Override
    public synchronized void insertHppRequest(final LiqpayHppRequestsRecord row) throws SQLException {
        check();
        if (hppRequests.stream().anyMatch(r -> r.getKbTenantId().equals(row.getKbTenantId()) && r.getOrderId().equals(row.getOrderId()))) {
            throw new SQLIntegrityConstraintViolationException("duplicate order id");
        }
        hppRequests.add(row.copy());
    }

    @Override
    public synchronized Optional<LiqpayHppRequestsRecord> findHppRequestByOrderId(final String orderId, final UUID tenantId) throws SQLException {
        check();
        return findHpp(orderId, tenantId).map(LiqpayHppRequestsRecord::copy);
    }

    @Override
    public synchronized boolean updateHppRequest(final String orderId, final UUID tenantId, final String status, final Long endDate)
            throws SQLException {
        check();
        final Optional<LiqpayHppRequestsRecord> row = findHpp(orderId, tenantId)
                .filter(r -> !HppStatus.FINAL.contains(r.getStatus()))
                .filter(r -> endDate == null || r.getLiqpayEndDate() == null || r.getLiqpayEndDate() < endDate);
        row.ifPresent(r -> {
            r.setStatus(status);
            r.setLiqpayEndDate(endDate == null ? r.getLiqpayEndDate() : endDate);
        });
        return row.isPresent();
    }

    /** The live payment method row, for tests that read it. */
    public synchronized LiqpayPaymentMethodsRecord method(final UUID kbPaymentMethodId) {
        return methods.stream().filter(m -> m.getKbPaymentMethodId().equals(kbPaymentMethodId.toString())).findFirst().orElseThrow();
    }

    private Optional<LiqpayHppRequestsRecord> findHpp(final String orderId, final UUID tenantId) {
        return hppRequests.stream().filter(r -> r.getOrderId().equals(orderId) && r.getKbTenantId().equals(tenantId.toString())).findFirst();
    }

    /** The live row, for tests that change it in place. */
    public synchronized LiqpayTransactionsRecord row(final String orderId) {
        return rows.stream().filter(r -> r.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private Optional<LiqpayTransactionsRecord> find(final String orderId, final UUID tenantId) {
        return rows.stream().filter(r -> r.getOrderId().equals(orderId) && r.getKbTenantId().equals(tenantId.toString())).findFirst();
    }

    private static boolean isOpen(final String status) {
        return PaymentPluginStatus.PENDING.name().equals(status) || PaymentPluginStatus.UNDEFINED.name().equals(status);
    }

    private static String json(final Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
