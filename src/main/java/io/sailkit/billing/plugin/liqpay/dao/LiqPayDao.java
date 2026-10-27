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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.plugin.dao.PluginDao;

import static io.sailkit.billing.plugin.liqpay.dao.gen.Tables.LIQPAY_HPP_REQUESTS;
import static io.sailkit.billing.plugin.liqpay.dao.gen.Tables.LIQPAY_PAYMENT_METHODS;
import static io.sailkit.billing.plugin.liqpay.dao.gen.Tables.LIQPAY_TRANSACTIONS;

/** Rows of the plugin. Every query is scoped to one Kill Bill tenant. */
public class LiqPayDao extends PluginDao implements LiqPayStore {

    private static final List<String> OPEN_STATUSES = List.of(PaymentPluginStatus.PENDING.name(),
                                                              PaymentPluginStatus.UNDEFINED.name());

    private final Clock clock;

    public LiqPayDao(final DataSource dataSource, final Clock clock) throws SQLException {
        super(dataSource);
        this.clock = clock;
    }

    // --- Transactions ---

    /** Sets the created and updated dates, then inserts. A second row for one order id fails. */
    @Override
    public void insertTransaction(final LiqpayTransactionsRecord row) throws SQLException {
        final LocalDateTime now = now();
        row.setCreatedDate(now);
        row.setUpdatedDate(now);
        withDsl(dsl -> dsl.insertInto(LIQPAY_TRANSACTIONS).set(row).execute());
    }

    @Override
    public Optional<LiqpayTransactionsRecord> findByTransactionId(final UUID kbTransactionId, final UUID tenantId) throws SQLException {
        return withDsl(dsl -> dsl.selectFrom(LIQPAY_TRANSACTIONS)
                                 .where(LIQPAY_TRANSACTIONS.KB_TRANSACTION_ID.eq(kbTransactionId.toString()))
                                 .and(LIQPAY_TRANSACTIONS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .fetchOptional());
    }

    @Override
    public Optional<LiqpayTransactionsRecord> findByOrderId(final String orderId, final UUID tenantId) throws SQLException {
        return withDsl(dsl -> dsl.selectFrom(LIQPAY_TRANSACTIONS)
                                 .where(LIQPAY_TRANSACTIONS.ORDER_ID.eq(orderId))
                                 .and(LIQPAY_TRANSACTIONS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .fetchOptional());
    }

    @Override
    public List<LiqpayTransactionsRecord> findByPayment(final UUID kbPaymentId, final UUID tenantId) throws SQLException {
        return withDsl(dsl -> dsl.selectFrom(LIQPAY_TRANSACTIONS)
                                 .where(LIQPAY_TRANSACTIONS.KB_PAYMENT_ID.eq(kbPaymentId.toString()))
                                 .and(LIQPAY_TRANSACTIONS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .orderBy(LIQPAY_TRANSACTIONS.RECORD_ID)
                                 .fetch());
    }

    public List<LiqpayTransactionsRecord> findPendingByPayment(final UUID kbPaymentId, final UUID tenantId) throws SQLException {
        return withDsl(dsl -> dsl.selectFrom(LIQPAY_TRANSACTIONS)
                                 .where(LIQPAY_TRANSACTIONS.KB_PAYMENT_ID.eq(kbPaymentId.toString()))
                                 .and(LIQPAY_TRANSACTIONS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .and(LIQPAY_TRANSACTIONS.PLUGIN_STATUS.eq(PaymentPluginStatus.PENDING.name()))
                                 .orderBy(LIQPAY_TRANSACTIONS.RECORD_ID)
                                 .fetch());
    }

    /**
     * Applies a LiqPay answer or callback in one conditional update. Returns false when the row is final,
     * or when the stored end_date is newer than or equal to the new one.
     */
    public boolean updateFromLiqPay(final String orderId, final UUID tenantId, final LiqPayResult result,
                                    final PaymentPluginStatus newStatus) throws SQLException {
        return updateFromLiqPay(orderId, tenantId, result, newStatus, Map.of());
    }

    @Override
    public boolean updateFromLiqPay(final String orderId, final UUID tenantId, final LiqPayResult result,
                                    final PaymentPluginStatus newStatus, final Map<String, Object> kept) throws SQLException {
        final Long endDate = result.endDate();
        final Map<String, Object> fields = new LinkedHashMap<>(kept);
        fields.putAll(result.toStoredFields());
        final String stored = asString(fields);
        return withDsl(dsl -> dsl.update(LIQPAY_TRANSACTIONS)
                                 .set(LIQPAY_TRANSACTIONS.PLUGIN_STATUS, newStatus.name())
                                 .set(LIQPAY_TRANSACTIONS.LIQPAY_STATUS, result.status())
                                 .set(LIQPAY_TRANSACTIONS.LIQPAY_PAYMENT_ID, result.paymentId())
                                 .set(LIQPAY_TRANSACTIONS.LIQPAY_END_DATE,
                                      DSL.coalesce(DSL.val(endDate, LIQPAY_TRANSACTIONS.LIQPAY_END_DATE), LIQPAY_TRANSACTIONS.LIQPAY_END_DATE))
                                 .set(LIQPAY_TRANSACTIONS.ERR_CODE, result.errCode())
                                 .set(LIQPAY_TRANSACTIONS.ERR_DESCRIPTION, result.errDescription())
                                 .set(LIQPAY_TRANSACTIONS.ADDITIONAL_DATA, stored)
                                 .set(LIQPAY_TRANSACTIONS.UPDATED_DATE, now())
                                 .where(LIQPAY_TRANSACTIONS.ORDER_ID.eq(orderId))
                                 .and(LIQPAY_TRANSACTIONS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .and(LIQPAY_TRANSACTIONS.PLUGIN_STATUS.in(OPEN_STATUSES))
                                 .and(endDate == null
                                      ? DSL.noCondition()
                                      : LIQPAY_TRANSACTIONS.LIQPAY_END_DATE.isNull().or(LIQPAY_TRANSACTIONS.LIQPAY_END_DATE.lt(endDate)))
                                 .execute()) == 1;
    }

    /** Marks an open row CANCELED, for example after the checkout expired. Returns false for a final row. */
    @Override
    public boolean cancelPending(final String orderId, final UUID tenantId, final String errCode) throws SQLException {
        return withDsl(dsl -> dsl.update(LIQPAY_TRANSACTIONS)
                                 .set(LIQPAY_TRANSACTIONS.PLUGIN_STATUS, PaymentPluginStatus.CANCELED.name())
                                 .set(LIQPAY_TRANSACTIONS.ERR_CODE, errCode)
                                 .set(LIQPAY_TRANSACTIONS.UPDATED_DATE, now())
                                 .where(LIQPAY_TRANSACTIONS.ORDER_ID.eq(orderId))
                                 .and(LIQPAY_TRANSACTIONS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .and(LIQPAY_TRANSACTIONS.PLUGIN_STATUS.in(OPEN_STATUSES))
                                 .execute()) == 1;
    }

    // --- Payment methods ---

    @Override
    public void upsertPaymentMethod(final LiqpayPaymentMethodsRecord row) throws SQLException {
        final LocalDateTime now = now();
        row.setCreatedDate(now);
        row.setUpdatedDate(now);
        // jOOQ inserts only changed fields, and a record that was read has none. The table key stays out of the insert.
        row.changed(true);
        row.changed(LIQPAY_PAYMENT_METHODS.RECORD_ID, false);
        withDsl(dsl -> dsl.insertInto(LIQPAY_PAYMENT_METHODS)
                          .set(row)
                          .onConflict(LIQPAY_PAYMENT_METHODS.KB_PAYMENT_METHOD_ID, LIQPAY_PAYMENT_METHODS.KB_TENANT_ID)
                          .doUpdate()
                          .set(LIQPAY_PAYMENT_METHODS.MODE, row.getMode())
                          .set(LIQPAY_PAYMENT_METHODS.CARD_TOKEN_ENC, row.getCardTokenEnc())
                          .set(LIQPAY_PAYMENT_METHODS.CARD_TOKEN_KEY_ID, row.getCardTokenKeyId())
                          .set(LIQPAY_PAYMENT_METHODS.VERIFIED, row.getVerified())
                          .set(LIQPAY_PAYMENT_METHODS.CARD_MASK, row.getCardMask())
                          .set(LIQPAY_PAYMENT_METHODS.CARD_TYPE, row.getCardType())
                          .set(LIQPAY_PAYMENT_METHODS.IS_DELETED, row.getIsDeleted())
                          .set(LIQPAY_PAYMENT_METHODS.UPDATED_DATE, now)
                          .execute());
    }

    @Override
    public Optional<LiqpayPaymentMethodsRecord> findPaymentMethod(final UUID kbPaymentMethodId, final UUID tenantId) throws SQLException {
        return withDsl(dsl -> dsl.selectFrom(LIQPAY_PAYMENT_METHODS)
                                 .where(LIQPAY_PAYMENT_METHODS.KB_PAYMENT_METHOD_ID.eq(kbPaymentMethodId.toString()))
                                 .and(LIQPAY_PAYMENT_METHODS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .and(LIQPAY_PAYMENT_METHODS.IS_DELETED.isFalse())
                                 .fetchOptional());
    }

    // --- Hosted page requests ---

    @Override
    public void insertHppRequest(final LiqpayHppRequestsRecord row) throws SQLException {
        final LocalDateTime now = now();
        row.setCreatedDate(now);
        row.setUpdatedDate(now);
        withDsl(dsl -> dsl.insertInto(LIQPAY_HPP_REQUESTS).set(row).execute());
    }

    @Override
    public Optional<LiqpayHppRequestsRecord> findHppRequestByOrderId(final String orderId, final UUID tenantId) throws SQLException {
        return withDsl(dsl -> dsl.selectFrom(LIQPAY_HPP_REQUESTS)
                                 .where(LIQPAY_HPP_REQUESTS.ORDER_ID.eq(orderId))
                                 .and(LIQPAY_HPP_REQUESTS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .fetchOptional());
    }

    @Override
    public boolean updateHppRequest(final String orderId, final UUID tenantId, final String status, final Long endDate) throws SQLException {
        return withDsl(dsl -> dsl.update(LIQPAY_HPP_REQUESTS)
                                 .set(LIQPAY_HPP_REQUESTS.STATUS, status)
                                 .set(LIQPAY_HPP_REQUESTS.LIQPAY_END_DATE,
                                      DSL.coalesce(DSL.val(endDate, LIQPAY_HPP_REQUESTS.LIQPAY_END_DATE), LIQPAY_HPP_REQUESTS.LIQPAY_END_DATE))
                                 .set(LIQPAY_HPP_REQUESTS.UPDATED_DATE, now())
                                 .where(LIQPAY_HPP_REQUESTS.ORDER_ID.eq(orderId))
                                 .and(LIQPAY_HPP_REQUESTS.KB_TENANT_ID.eq(tenantId.toString()))
                                 .and(LIQPAY_HPP_REQUESTS.STATUS.notIn(HppStatus.FINAL))
                                 .and(endDate == null
                                      ? DSL.noCondition()
                                      : LIQPAY_HPP_REQUESTS.LIQPAY_END_DATE.isNull().or(LIQPAY_HPP_REQUESTS.LIQPAY_END_DATE.lt(endDate)))
                                 .execute()) == 1;
    }

    // --- Helpers ---

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private interface DslCallback<T> {

        T apply(DSLContext dsl) throws SQLException;
    }

    /** Runs one statement. A jOOQ error becomes an SQLException, so each caller handles one database error type. */
    private <T> T withDsl(final DslCallback<T> callback) throws SQLException {
        try (final Connection conn = dataSource.getConnection()) {
            return callback.apply(DSL.using(conn, dialect, settings));
        } catch (final DataAccessException e) {
            throw new SQLException("liqpay database error", e);
        }
    }
}
