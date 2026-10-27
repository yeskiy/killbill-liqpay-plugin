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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.sailkit.billing.plugin.liqpay.LogCapture;
import io.sailkit.billing.plugin.liqpay.api.Fixtures;
import io.sailkit.billing.plugin.liqpay.api.InMemoryStore;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.crypto.CardTokenCipher;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.HppStatus;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.InvoicePaymentApi;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PaymentApi;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class VerificationCallbackTest {

    private static final String TOKEN = "C7C0D11B99B11ED11A11D1D1";

    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private WireMockServer server;
    private LogCapture logs;
    private InMemoryStore store;
    private PaymentApi paymentApi;
    private LiqPayConfig config;
    private LiqPayCallbackServlet servlet;

    @BeforeClass
    public void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        logs = new LogCapture();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        logs.close();
        server.stop();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        server.resetAll();
        store = new InMemoryStore();
        paymentApi = mock(PaymentApi.class);
        final AccountUserApi accounts = mock(AccountUserApi.class);
        when(accounts.getAccountById(any(UUID.class), any(TenantContext.class))).thenReturn(mock(Account.class));
        final InvoicePaymentApi invoices = mock(InvoicePaymentApi.class);
        when(invoices.getInvoicePayments(any(UUID.class), any(TenantContext.class))).thenReturn(List.of());
        final OSGIKillbillAPI killbill = mock(OSGIKillbillAPI.class);
        when(killbill.getAccountUserApi()).thenReturn(accounts);
        when(killbill.getPaymentApi()).thenReturn(paymentApi);
        when(killbill.getInvoicePaymentApi()).thenReturn(invoices);
        config = Fixtures.tokenConfig("http://localhost:" + server.port());
        servlet = new LiqPayCallbackServlet(Fixtures.handler(tenant, config), store, killbill, new Fixtures.MutableClock());
    }

    // --- Helpers ---

    private void answer(final String json) {
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(200)
                                                                              .withHeader("Content-Type", "application/json")
                                                                              .withBody(json)));
    }

    private List<JsonNode> sent() {
        return server.findAll(postRequestedFor(urlEqualTo("/api/request"))).stream()
                     .map(r -> LiqPayEnvelope.decode(URLDecoder.decode(r.getBodyAsString().split("&")[0].substring(5), StandardCharsets.UTF_8)))
                     .collect(Collectors.toList());
    }

    /** A token payment method that waits for its verification, and its request. */
    private LiqpayHppRequestsRecord request() throws Exception {
        final UUID pm = UUID.randomUUID();
        final LiqpayPaymentMethodsRecord m = new LiqpayPaymentMethodsRecord();
        m.setKbAccountId(account.toString());
        m.setKbPaymentMethodId(pm.toString());
        m.setKbTenantId(tenant.toString());
        m.setMode("token");
        m.setVerified(false);
        m.setIsDeleted(false);
        store.upsertPaymentMethod(m);
        final LiqpayHppRequestsRecord r = new LiqpayHppRequestsRecord();
        r.setKbAccountId(account.toString());
        r.setKbPaymentMethodId(pm.toString());
        r.setKbTenantId(tenant.toString());
        r.setOrderId("verify-" + UUID.randomUUID());
        r.setAmount(new BigDecimal("1.00"));
        r.setCurrency("UAH");
        r.setStatus(HppStatus.PENDING);
        store.insertHppRequest(r);
        return r;
    }

    private static Map<String, Object> callback(final String orderId, final String action, final String status, final Object amount,
                                                final String currency, final long endDate) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 7);
        m.put("public_key", Fixtures.PUBLIC_KEY);
        m.put("action", action);
        m.put("status", status);
        m.put("order_id", orderId);
        m.put("payment_id", 7788);
        m.put("amount", amount);
        m.put("currency", currency);
        m.put("end_date", endDate);
        return m;
    }

    private static Map<String, Object> holdWait(final LiqpayHppRequestsRecord r, final long endDate) {
        final Map<String, Object> m = callback(r.getOrderId(), "hold", "hold_wait", 1, "UAH", endDate);
        m.put("card_token", TOKEN);
        m.put("sender_card_mask2", "424242*42");
        m.put("sender_card_type", "visa");
        return m;
    }

    private static String body(final Map<String, Object> fields, final String key) {
        final String data = LiqPayEnvelope.encode(fields);
        return "data=" + URLEncoder.encode(data, StandardCharsets.UTF_8)
               + "&signature=" + URLEncoder.encode(LiqPaySigner.sign(key, data), StandardCharsets.UTF_8);
    }

    private int send(final Map<String, Object> fields) throws Exception {
        return send(body(fields, Fixtures.PRIVATE_KEY));
    }

    private int send(final String body) throws Exception {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        final HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getPathInfo()).thenReturn("/callback");
        when(req.getQueryString()).thenReturn("kbTenantId=" + tenant);
        when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(req.getContentLengthLong()).thenReturn((long) bytes.length);
        when(req.getInputStream()).thenReturn(new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(final ReadListener listener) {}

            @Override
            public int read() {
                return in.read();
            }
        });
        final HttpServletResponse resp = mock(HttpServletResponse.class);
        servlet.service(req, resp);
        final ArgumentCaptor<Integer> code = ArgumentCaptor.forClass(Integer.class);
        verify(resp).setStatus(code.capture());
        return code.getValue();
    }

    private LiqpayPaymentMethodsRecord method(final LiqpayHppRequestsRecord r) {
        return store.method(UUID.fromString(r.getKbPaymentMethodId()));
    }

    private String status(final LiqpayHppRequestsRecord r) throws Exception {
        return store.findHppRequestByOrderId(r.getOrderId(), tenant).orElseThrow().getStatus();
    }

    // --- Tests ---

    @Test
    public void aHoldStoresTheEncryptedTokenAndReleasesTheHold() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        assertEquals(send(holdWait(r, 1000)), 200);
        final LiqpayPaymentMethodsRecord m = method(r);
        assertTrue(m.getVerified());
        assertEquals(m.getCardMask(), "424242*42");
        assertEquals(m.getCardType(), "visa");
        assertEquals(m.getCardTokenKeyId(), "1");
        assertFalse(new String(m.getCardTokenEnc(), StandardCharsets.ISO_8859_1).contains(TOKEN), "the stored token is encrypted");
        assertEquals(new CardTokenCipher(config.cardTokenKey(), "1").decrypt(m.getCardTokenEnc()), TOKEN);
        assertEquals(sent().size(), 1);
        assertEquals(sent().get(0).get("action").asText(), "refund");
        assertEquals(sent().get(0).get("order_id").asText(), r.getOrderId());
        assertEquals(sent().get(0).get("amount").asText(), "1.00");
        assertEquals(status(r), HppStatus.RELEASED);
        assertFalse(logs.text().contains(TOKEN), "no log line holds the token");
        assertFalse(logs.text().contains("424242"), "no log line holds the card mask");
    }

    @Test
    public void aReplayMakesNoSecondRelease() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        final String b = body(holdWait(r, 1000), Fixtures.PRIVATE_KEY);
        assertEquals(send(b), 200);
        assertEquals(send(b), 200);
        assertEquals(sent().size(), 1);
    }

    @Test
    public void aFailedReleaseKeepsTheTokenAndTheVerifiedState() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(500)));
        assertEquals(send(holdWait(r, 1000)), 200);
        assertTrue(method(r).getVerified());
        assertEquals(status(r), HppStatus.VERIFIED);
        assertTrue(logs.text().contains("liqpay_hold_release_failed order_id=" + r.getOrderId()));
        assertEquals(send(callback(r.getOrderId(), "hold", "reversed", 1, "UAH", 2000)), 200);
        assertEquals(status(r), HppStatus.RELEASED, "the reversal callback closes the request");
    }

    @Test
    public void aFailureMarksTheRequestFailed() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        assertEquals(send(callback(r.getOrderId(), "hold", "failure", 1, "UAH", 1000)), 200);
        assertEquals(status(r), HppStatus.FAILED);
        assertFalse(method(r).getVerified());
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aHoldWithoutATokenFailsAndReleasesTheHold() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        assertEquals(send(callback(r.getOrderId(), "hold", "hold_wait", 1, "UAH", 1000)), 200);
        assertEquals(status(r), HppStatus.FAILED);
        assertFalse(method(r).getVerified());
        assertNull(method(r).getCardTokenEnc());
        assertEquals(sent().get(0).get("action").asText(), "refund");
    }

    @Test
    public void aPendingStatusChangesNothing() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        assertEquals(send(callback(r.getOrderId(), "hold", "3ds_verify", 1, "UAH", 1000)), 200);
        assertEquals(status(r), HppStatus.PENDING);
    }

    @Test
    public void aBadVerificationCallbackIsRejected() throws Exception {
        final LiqpayHppRequestsRecord r = request();
        assertEquals(send(body(holdWait(r, 1000), "another_private_key")), 400);
        final Map<String, Object> amount = holdWait(r, 1000);
        amount.put("amount", 100);
        assertEquals(send(amount), 400);
        final Map<String, Object> currency = holdWait(r, 1000);
        currency.put("currency", "USD");
        assertEquals(send(currency), 400);
        final Map<String, Object> action = holdWait(r, 1000);
        action.put("action", "pay");
        assertEquals(send(action), 400);
        assertEquals(status(r), HppStatus.PENDING);
        assertFalse(method(r).getVerified());
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aTokenPurchaseCallbackReportsActionPay() throws Exception {
        final LiqpayTransactionsRecord row = new LiqpayTransactionsRecord();
        final String tx = UUID.randomUUID().toString();
        row.setKbAccountId(account.toString());
        row.setKbPaymentId(UUID.randomUUID().toString());
        row.setKbTransactionId(tx);
        row.setKbPaymentMethodId(UUID.randomUUID().toString());
        row.setKbTenantId(tenant.toString());
        row.setTransactionType("PURCHASE");
        row.setLiqpayAction("paytoken");
        row.setOrderId(tx);
        row.setAmount(new BigDecimal("35.00"));
        row.setCurrency("USD");
        row.setPluginStatus("PENDING");
        store.insertTransaction(row);
        final PaymentTransaction kbTransaction = mock(PaymentTransaction.class);
        when(kbTransaction.getId()).thenReturn(UUID.fromString(tx));
        when(kbTransaction.getTransactionStatus()).thenReturn(TransactionStatus.PENDING);
        final Payment kbPayment = mock(Payment.class);
        when(kbPayment.getTransactions()).thenReturn(List.of(kbTransaction));
        when(paymentApi.getPayment(any(UUID.class), anyBoolean(), anyBoolean(), any(), any(TenantContext.class))).thenReturn(kbPayment);
        assertEquals(send(callback(tx, "pay", "success", 35, "USD", 1000)), 200);
        assertEquals(store.row(tx).getPluginStatus(), "PROCESSED");
        verify(paymentApi).notifyPendingTransactionOfStateChanged(any(), eq(UUID.fromString(tx)), eq(true), any(CallContext.class));
        final Map<String, Object> hold = callback(tx, "hold", "success", 35, "USD", 2000);
        assertEquals(send(hold), 400, "a paytoken order never reports hold");
    }
}
