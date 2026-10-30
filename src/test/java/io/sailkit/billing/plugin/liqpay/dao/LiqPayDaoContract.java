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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/** One contract for every database. A subclass starts the database and names the DDL file. */
public abstract class LiqPayDaoContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected LiqPayDao dao;

    protected abstract DataSource dataSource();

    protected abstract String ddlResource();

    @BeforeClass
    public void applyDdlTwice() throws Exception {
        applyDdl();
        applyDdl();
        dao = new LiqPayDao(dataSource(), Clock.systemUTC());
    }

    private void applyDdl() throws IOException, SQLException {
        final String ddl;
        try (InputStream in = getClass().getResourceAsStream("/" + ddlResource())) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource().getConnection(); Statement st = conn.createStatement()) {
            for (final String sql : Arrays.stream(ddl.split(";"))
                                          .map(s -> s.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim())
                                          .filter(s -> !s.isEmpty())
                                          .toArray(String[]::new)) {
                st.execute(sql);
            }
        }
    }

    private static LiqpayTransactionsRecord row(final UUID tenant, final UUID payment, final String orderId) {
        final LiqpayTransactionsRecord r = new LiqpayTransactionsRecord();
        r.setKbAccountId(UUID.randomUUID().toString());
        r.setKbPaymentId(payment.toString());
        r.setKbTransactionId(UUID.randomUUID().toString());
        r.setKbPaymentMethodId(UUID.randomUUID().toString());
        r.setKbTenantId(tenant.toString());
        r.setTransactionType("PURCHASE");
        r.setLiqpayAction("pay");
        r.setOrderId(orderId);
        r.setAmount(new BigDecimal("100.00"));
        r.setCurrency("USD");
        r.setPluginStatus(PaymentPluginStatus.PENDING.name());
        r.setExpiresAt(LocalDateTime.of(2026, 10, 18, 12, 30));
        return r;
    }

    private static LiqPayResult result(final String status, final Long endDate) {
        final StringBuilder json = new StringBuilder("{\"status\":\"" + status + "\",\"payment_id\":\"77\",\"action\":\"pay\"");
        if (endDate != null) {
            json.append(",\"end_date\":\"").append(endDate).append('"');
        }
        try {
            return LiqPayResult.from(MAPPER.readTree(json.append('}').toString()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void insertsAndReads() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayTransactionsRecord r = row(tenant, UUID.randomUUID(), UUID.randomUUID().toString());
        dao.insertTransaction(r);
        final LiqpayTransactionsRecord read = dao.findByTransactionId(UUID.fromString(r.getKbTransactionId()), tenant).orElseThrow();
        assertEquals(read.getOrderId(), r.getOrderId());
        assertEquals(read.getAmount().compareTo(new BigDecimal("100")), 0);
        assertEquals(read.getPluginStatus(), "PENDING");
        assertEquals(read.getExpiresAt(), LocalDateTime.of(2026, 10, 18, 12, 30));
        assertTrue(dao.findByOrderId(r.getOrderId(), tenant).isPresent());
        assertFalse(dao.findByOrderId(r.getOrderId(), UUID.randomUUID()).isPresent(), "another tenant sees nothing");
    }

    @Test
    public void orderIdIsUniqueForATenant() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final String orderId = UUID.randomUUID().toString();
        dao.insertTransaction(row(tenant, UUID.randomUUID(), orderId));
        assertThrows(Exception.class, () -> dao.insertTransaction(row(tenant, UUID.randomUUID(), orderId)));
        dao.insertTransaction(row(UUID.randomUUID(), UUID.randomUUID(), orderId));
    }

    @Test
    public void findsPendingRowsOfAPayment() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final UUID payment = UUID.randomUUID();
        final LiqpayTransactionsRecord first = row(tenant, payment, UUID.randomUUID().toString());
        final LiqpayTransactionsRecord second = row(tenant, payment, UUID.randomUUID().toString());
        dao.insertTransaction(first);
        dao.insertTransaction(second);
        dao.insertTransaction(row(tenant, UUID.randomUUID(), UUID.randomUUID().toString()));
        assertTrue(dao.updateFromLiqPay(first.getOrderId(), tenant, result("success", 10L), PaymentPluginStatus.PROCESSED));
        assertEquals(dao.findPendingByPayment(payment, tenant).size(), 1);
        assertEquals(dao.findPendingByPayment(payment, tenant).get(0).getOrderId(), second.getOrderId());
        assertEquals(dao.findByPayment(payment, tenant).size(), 2);
    }

    @Test
    public void updateIgnoresAnOlderOrEqualEndDate() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayTransactionsRecord r = row(tenant, UUID.randomUUID(), UUID.randomUUID().toString());
        dao.insertTransaction(r);
        assertTrue(dao.updateFromLiqPay(r.getOrderId(), tenant, result("processing", 2000L), PaymentPluginStatus.PENDING));
        assertFalse(dao.updateFromLiqPay(r.getOrderId(), tenant, result("prepared", 1000L), PaymentPluginStatus.PENDING));
        assertFalse(dao.updateFromLiqPay(r.getOrderId(), tenant, result("prepared", 2000L), PaymentPluginStatus.PENDING));
        final LiqpayTransactionsRecord read = dao.findByOrderId(r.getOrderId(), tenant).orElseThrow();
        assertEquals(read.getLiqpayStatus(), "processing");
        assertEquals(read.getLiqpayEndDate(), Long.valueOf(2000L));
        assertEquals(read.getLiqpayPaymentId(), "77");
        assertTrue(read.getAdditionalData().contains("\"status\":\"processing\""));
    }

    @Test
    public void updateNeverLeavesAFinalStatus() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayTransactionsRecord r = row(tenant, UUID.randomUUID(), UUID.randomUUID().toString());
        dao.insertTransaction(r);
        assertTrue(dao.updateFromLiqPay(r.getOrderId(), tenant, result("success", 1000L), PaymentPluginStatus.PROCESSED));
        assertFalse(dao.updateFromLiqPay(r.getOrderId(), tenant, result("processing", 5000L), PaymentPluginStatus.PENDING));
        assertFalse(dao.updateFromLiqPay(r.getOrderId(), tenant, result("processing", null), PaymentPluginStatus.PENDING));
        assertFalse(dao.closePending(r.getOrderId(), tenant, PaymentPluginStatus.CANCELED, "expired", null));
        assertEquals(dao.findByOrderId(r.getOrderId(), tenant).orElseThrow().getPluginStatus(), "PROCESSED");
    }

    @Test
    public void cancelsAnOpenRow() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayTransactionsRecord r = row(tenant, UUID.randomUUID(), UUID.randomUUID().toString());
        dao.insertTransaction(r);
        assertTrue(dao.closePending(r.getOrderId(), tenant, PaymentPluginStatus.CANCELED, "expired", null));
        final LiqpayTransactionsRecord read = dao.findByOrderId(r.getOrderId(), tenant).orElseThrow();
        assertEquals(read.getPluginStatus(), "CANCELED");
        assertEquals(read.getErrCode(), "expired");
    }

    @Test
    public void failsAnOpenRowWithADescription() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayTransactionsRecord r = row(tenant, UUID.randomUUID(), UUID.randomUUID().toString());
        dao.insertTransaction(r);
        assertTrue(dao.closePending(r.getOrderId(), tenant, PaymentPluginStatus.ERROR, "refund_not_received", "No refund"));
        final LiqpayTransactionsRecord read = dao.findByOrderId(r.getOrderId(), tenant).orElseThrow();
        assertEquals(read.getPluginStatus(), "ERROR");
        assertEquals(read.getErrCode(), "refund_not_received");
        assertEquals(read.getErrDescription(), "No refund");
    }

    @Test
    public void upsertsAPaymentMethod() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final UUID pm = UUID.randomUUID();
        final LiqpayPaymentMethodsRecord r = new LiqpayPaymentMethodsRecord();
        r.setKbAccountId(UUID.randomUUID().toString());
        r.setKbPaymentMethodId(pm.toString());
        r.setKbTenantId(tenant.toString());
        r.setMode("checkout");
        r.setVerified(false);
        r.setIsDeleted(false);
        dao.upsertPaymentMethod(r);
        assertEquals(dao.findPaymentMethod(pm, tenant).orElseThrow().getMode(), "checkout");
        assertNull(dao.findPaymentMethod(pm, tenant).orElseThrow().getCardTokenEnc());

        r.setMode("token");
        r.setCardTokenEnc(new byte[]{1, 2, 3, 4});
        r.setCardTokenKeyId("1");
        r.setVerified(true);
        r.setCardMask("424242*42");
        dao.upsertPaymentMethod(r);
        final LiqpayPaymentMethodsRecord read = dao.findPaymentMethod(pm, tenant).orElseThrow();
        assertEquals(read.getMode(), "token");
        assertEquals(read.getCardTokenEnc(), new byte[]{1, 2, 3, 4});
        assertTrue(read.getVerified());
        assertEquals(read.getCardMask(), "424242*42");
        assertFalse(dao.findPaymentMethod(pm, UUID.randomUUID()).isPresent());
    }

    @Test
    public void upsertsAPaymentMethodThatWasRead() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final UUID pm = UUID.randomUUID();
        final LiqpayPaymentMethodsRecord r = new LiqpayPaymentMethodsRecord();
        r.setKbAccountId(UUID.randomUUID().toString());
        r.setKbPaymentMethodId(pm.toString());
        r.setKbTenantId(tenant.toString());
        r.setMode("token");
        r.setVerified(false);
        r.setIsDeleted(false);
        dao.upsertPaymentMethod(r);
        final LiqpayPaymentMethodsRecord read = dao.findPaymentMethod(pm, tenant).orElseThrow();
        read.setCardTokenEnc(new byte[]{9, 8, 7});
        read.setCardTokenKeyId("1");
        read.setVerified(true);
        dao.upsertPaymentMethod(read);
        final LiqpayPaymentMethodsRecord again = dao.findPaymentMethod(pm, tenant).orElseThrow();
        assertTrue(again.getVerified());
        assertEquals(again.getCardTokenEnc(), new byte[]{9, 8, 7});
        assertEquals(again.getKbAccountId(), r.getKbAccountId());
        again.setIsDeleted(true);
        again.setCardTokenEnc(null);
        dao.upsertPaymentMethod(again);
        assertFalse(dao.findPaymentMethod(pm, tenant).isPresent());
    }

    @Test
    public void aDatabaseErrorIsAnSqlException() throws Exception {
        final LiqpayTransactionsRecord bad = new LiqpayTransactionsRecord();
        bad.setKbTransactionId(UUID.randomUUID().toString());
        assertThrows(SQLException.class, () -> dao.insertTransaction(bad));
    }

    @Test
    public void insertsAndFindsAnHppRequest() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayHppRequestsRecord r = new LiqpayHppRequestsRecord();
        r.setKbAccountId(UUID.randomUUID().toString());
        r.setKbPaymentMethodId(UUID.randomUUID().toString());
        r.setKbTenantId(tenant.toString());
        r.setOrderId(UUID.randomUUID().toString());
        r.setAmount(new BigDecimal("1.00"));
        r.setCurrency("UAH");
        r.setStatus("PENDING");
        dao.insertHppRequest(r);
        assertEquals(dao.findHppRequestByOrderId(r.getOrderId(), tenant).orElseThrow().getCurrency(), "UAH");
        assertThrows(Exception.class, () -> dao.insertHppRequest(r.copy()));
    }

    @Test
    public void updatesAnHppRequestUntilItIsFinal() throws Exception {
        final UUID tenant = UUID.randomUUID();
        final LiqpayHppRequestsRecord r = new LiqpayHppRequestsRecord();
        r.setKbAccountId(UUID.randomUUID().toString());
        r.setKbPaymentMethodId(UUID.randomUUID().toString());
        r.setKbTenantId(tenant.toString());
        r.setOrderId(UUID.randomUUID().toString());
        r.setAmount(new BigDecimal("1.00"));
        r.setCurrency("UAH");
        r.setStatus(HppStatus.PENDING);
        dao.insertHppRequest(r);
        assertFalse(dao.updateHppRequest(r.getOrderId(), UUID.randomUUID(), HppStatus.VERIFIED, 10L), "another tenant");
        assertTrue(dao.updateHppRequest(r.getOrderId(), tenant, HppStatus.VERIFIED, 10L));
        assertFalse(dao.updateHppRequest(r.getOrderId(), tenant, HppStatus.VERIFIED, 10L), "the same end_date is a replay");
        assertTrue(dao.updateHppRequest(r.getOrderId(), tenant, HppStatus.RELEASED, null));
        assertFalse(dao.updateHppRequest(r.getOrderId(), tenant, HppStatus.PENDING, 99L), "RELEASED is final");
        final LiqpayHppRequestsRecord read = dao.findHppRequestByOrderId(r.getOrderId(), tenant).orElseThrow();
        assertEquals(read.getStatus(), HppStatus.RELEASED);
        assertEquals(read.getLiqpayEndDate(), Long.valueOf(10L));
    }
}
