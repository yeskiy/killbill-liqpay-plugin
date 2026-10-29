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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sailkit.billing.plugin.liqpay.LogCapture;
import io.sailkit.billing.plugin.liqpay.api.Fixtures;
import io.sailkit.billing.plugin.liqpay.api.InMemoryStore;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.invoice.api.InvoicePayment;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.InvoicePaymentApi;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PaymentApi;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentOptions;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.security.api.SecurityApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LiqPayCallbackServletTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UUID tenant = UUID.randomUUID();
    private final List<String> secrets = new ArrayList<>();
    private LogCapture logs;
    private InMemoryStore store;
    private PaymentApi paymentApi;
    private InvoicePaymentApi invoicePaymentApi;
    private Account account;
    private OSGIKillbillAPI killbill;
    private SecurityApi security;
    private final Map<UUID, TransactionStatus> kbStatus = new ConcurrentHashMap<>();
    private LiqPayCallbackServlet servlet;

    @BeforeClass
    public void captureLogs() {
        logs = new LogCapture();
    }

    @AfterClass(alwaysRun = true)
    public void releaseLogs() {
        logs.close();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        store = new InMemoryStore();
        paymentApi = mock(PaymentApi.class);
        invoicePaymentApi = mock(InvoicePaymentApi.class);
        account = mock(Account.class);
        final AccountUserApi accounts = mock(AccountUserApi.class);
        when(accounts.getAccountById(any(UUID.class), any(TenantContext.class))).thenReturn(account);
        killbill = mock(OSGIKillbillAPI.class);
        security = mock(SecurityApi.class);
        kbStatus.clear();
        when(killbill.getAccountUserApi()).thenReturn(accounts);
        when(killbill.getPaymentApi()).thenReturn(paymentApi);
        when(killbill.getInvoicePaymentApi()).thenReturn(invoicePaymentApi);
        when(killbill.getSecurityApi()).thenReturn(security);
        when(invoicePaymentApi.getInvoicePayments(any(UUID.class), any(TenantContext.class))).thenReturn(List.of(mock(InvoicePayment.class)));
        when(paymentApi.getPayment(any(UUID.class), anyBoolean(), anyBoolean(), any(), any(TenantContext.class))).thenAnswer(i -> kbPayment());
        final Answer<Payment> settle = i -> {
            kbStatus.put(i.getArgument(1), (Boolean) i.getArgument(2) ? TransactionStatus.SUCCESS : TransactionStatus.PAYMENT_FAILURE);
            return null;
        };
        doAnswer(settle).when(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), any(), any());
        doAnswer(settle).when(paymentApi).notifyPendingTransactionOfStateChanged(any(), any(), anyBoolean(), any());
        servlet = servlet(Fixtures.config("https://www.liqpay.ua"));
    }

    private LiqPayCallbackServlet servlet(final LiqPayConfig c) throws Exception {
        return new LiqPayCallbackServlet(Fixtures.handler(tenant, c), store, killbill, new Fixtures.MutableClock());
    }

    /** The Kill Bill view of every transaction that a test created. */
    private Payment kbPayment() {
        final List<PaymentTransaction> transactions = kbStatus.entrySet().stream().map(e -> {
            final PaymentTransaction t = mock(PaymentTransaction.class);
            when(t.getId()).thenReturn(e.getKey());
            when(t.getTransactionStatus()).thenReturn(e.getValue());
            return t;
        }).collect(Collectors.toList());
        final Payment p = mock(Payment.class);
        when(p.getTransactions()).thenReturn(transactions);
        return p;
    }

    private static LiqPayConfig loginConfig() {
        final Properties p = Fixtures.properties("https://www.liqpay.ua");
        p.setProperty(PREFIX + "killbillUser", "liqpay-plugin");
        p.setProperty(PREFIX + "killbillPasswordEnv", "LP_KB");
        return LiqPayConfig.parse(p, Map.of("LP_PRIVATE", Fixtures.PRIVATE_KEY, "LP_KB", "kb-plugin-secret")::get);
    }

    // --- Helpers ---

    private LiqpayTransactionsRecord pending(final String action, final String status) throws Exception {
        final LiqpayTransactionsRecord r = new LiqpayTransactionsRecord();
        final String tx = UUID.randomUUID().toString();
        r.setKbAccountId(UUID.randomUUID().toString());
        r.setKbPaymentId(UUID.randomUUID().toString());
        r.setKbTransactionId(tx);
        r.setKbPaymentMethodId(UUID.randomUUID().toString());
        r.setKbTenantId(tenant.toString());
        r.setTransactionType("PURCHASE");
        r.setLiqpayAction(action);
        r.setOrderId(tx);
        r.setAmount(new BigDecimal("100.00"));
        r.setCurrency("USD");
        r.setPluginStatus(status);
        r.setExpiresAt(LocalDateTime.of(2026, 10, 18, 12, 30));
        store.insertTransaction(r);
        kbStatus.put(UUID.fromString(tx), "PENDING".equals(status) ? TransactionStatus.PENDING : TransactionStatus.SUCCESS);
        return r;
    }

    private Map<String, Object> callback(final LiqpayTransactionsRecord row, final String status, final long endDate) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 7);
        m.put("public_key", Fixtures.PUBLIC_KEY);
        m.put("action", "pay");
        m.put("status", status);
        m.put("order_id", row.getOrderId());
        m.put("payment_id", 4455);
        m.put("amount", 100);
        m.put("currency", "USD");
        m.put("end_date", endDate);
        m.put("sender_card_mask2", "424242*42");
        return m;
    }

    private String body(final Map<String, Object> fields, final String key) {
        final String data = LiqPayEnvelope.encode(fields);
        final String signature = LiqPaySigner.sign(key, data);
        secrets.add(data);
        secrets.add(signature);
        return "data=" + URLEncoder.encode(data, StandardCharsets.UTF_8) + "&signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8);
    }

    private String body(final Map<String, Object> fields) {
        return body(fields, Fixtures.PRIVATE_KEY);
    }

    private int post(final String query, final String contentType, final String body) throws Exception {
        return send("POST", "/callback", query, contentType, body);
    }

    private int post(final String body) throws Exception {
        return post("kbTenantId=" + tenant, "application/x-www-form-urlencoded; charset=UTF-8", body);
    }

    private int send(final String method, final String path, final String query, final String contentType, final String body) throws Exception {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        final HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getPathInfo()).thenReturn(path);
        when(req.getQueryString()).thenReturn(query);
        when(req.getContentType()).thenReturn(contentType);
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

    private void verifyNotified(final LiqpayTransactionsRecord row, final boolean success, final int count) throws PaymentApiException {
        verify(paymentApi, times(count)).notifyPendingTransactionOfStateChangedWithPaymentControl(
                eq(account), eq(UUID.fromString(row.getKbTransactionId())), eq(success), any(PaymentOptions.class), any(CallContext.class));
    }

    private void verifyNoNotify() throws PaymentApiException {
        verify(paymentApi, never()).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), any(), any());
        verify(paymentApi, never()).notifyPendingTransactionOfStateChanged(any(), any(), anyBoolean(), any());
    }

    private String status(final LiqpayTransactionsRecord row) {
        return store.row(row.getOrderId()).getPluginStatus();
    }

    // --- Accepted callbacks ---

    @Test
    public void aSuccessIsProcessedAndKillBillIsNotified() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        assertEquals(status(row), "PROCESSED");
        assertEquals(store.row(row.getOrderId()).getLiqpayPaymentId(), "4455");
        verifyNotified(row, true, 1);
        final ArgumentCaptor<PaymentOptions> options = ArgumentCaptor.forClass(PaymentOptions.class);
        verify(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), options.capture(), any());
        assertEquals(options.getValue().getPaymentControlPluginNames(), List.of("__INVOICE_PAYMENT_CONTROL_PLUGIN__"));
        assertFalse(store.row(row.getOrderId()).getAdditionalData().contains("424242"), "only allow-listed fields are stored");
    }

    @Test
    public void aPaymentWithoutAnInvoiceUsesThePlainNotify() throws Exception {
        when(invoicePaymentApi.getInvoicePayments(any(UUID.class), any(TenantContext.class))).thenReturn(List.of());
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        verify(paymentApi).notifyPendingTransactionOfStateChanged(eq(account), eq(UUID.fromString(row.getKbTransactionId())), eq(true),
                                                                  any(CallContext.class));
    }

    @Test
    public void aFailureIsAnErrorAndKillBillIsNotified() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final Map<String, Object> m = callback(row, "failure", 1000);
        m.put("err_code", "limit");
        assertEquals(post(body(m)), 200);
        assertEquals(status(row), "ERROR");
        assertEquals(store.row(row.getOrderId()).getErrCode(), "limit");
        verifyNotified(row, false, 1);
    }

    @Test
    public void aStatusThatIsNotFinalUpdatesTheRowWithoutNotify() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "processing", 1000))), 200);
        assertEquals(status(row), "PENDING");
        assertEquals(store.row(row.getOrderId()).getLiqpayStatus(), "processing");
        verifyNoNotify();
    }

    @Test
    public void aReplayChangesNothing() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final String b = body(callback(row, "success", 1000));
        assertEquals(post(b), 200);
        assertEquals(post(b), 200);
        verifyNotified(row, true, 1);
    }

    @Test
    public void anOlderEndDateChangesNothing() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "processing", 2000))), 200);
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        assertEquals(status(row), "PENDING");
        assertEquals(store.row(row.getOrderId()).getLiqpayStatus(), "processing");
        verifyNoNotify();
    }

    @Test
    public void aFinalStatusNeverGoesBackToPending() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        assertEquals(post(body(callback(row, "processing", 2000))), 200);
        assertEquals(status(row), "PROCESSED");
        verifyNotified(row, true, 1);
    }

    @Test
    public void aReversalWithoutARefundKeepsThePurchasePaid() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PROCESSED");
        assertEquals(post(body(callback(row, "reversed", 3000))), 200);
        assertEquals(status(row), "PROCESSED");
        verifyNoNotify();
        assertTrue(logs.text().contains("liqpay_reversed_unmatched order_id=" + row.getOrderId()));
    }

    @Test
    public void aReversalWithARefundIsNotLogged() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PROCESSED");
        final LiqpayTransactionsRecord refund = row.copy();
        final String tx = UUID.randomUUID().toString();
        refund.setKbTransactionId(tx);
        refund.setOrderId(tx);
        refund.setOriginalOrderId(row.getOrderId());
        refund.setTransactionType("REFUND");
        refund.setLiqpayAction("refund");
        store.insertTransaction(refund);
        assertEquals(post(body(callback(row, "reversed", 3000))), 200);
        assertFalse(logs.text().contains("liqpay_reversed_unmatched order_id=" + row.getOrderId()));
    }

    // --- Rejected callbacks ---

    private void assertRejected(final LiqpayTransactionsRecord row, final int code) throws Exception {
        assertEquals(code, 400);
        assertEquals(status(row), "PENDING");
        assertEquals(store.row(row.getOrderId()).getLiqpayStatus(), null);
        verifyNoNotify();
    }

    @Test
    public void aBadSignatureIsRejected() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertRejected(row, post(body(callback(row, "success", 1000), "another_private_key")));
        assertTrue(logs.text().contains("liqpay_callback_rejected kbTenantId=" + tenant + " reason=signature"));
    }

    @Test
    public void aMismatchOfAmountCurrencyActionKeyOrVersionIsRejected() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final Map<String, Object> amount = callback(row, "success", 1000);
        amount.put("amount", "99.99");
        assertRejected(row, post(body(amount)));
        final Map<String, Object> currency = callback(row, "success", 1000);
        currency.put("currency", "UAH");
        assertRejected(row, post(body(currency)));
        final Map<String, Object> action = callback(row, "success", 1000);
        action.put("action", "hold");
        assertRejected(row, post(body(action)));
        final Map<String, Object> key = callback(row, "success", 1000);
        key.put("public_key", "sandbox_i999");
        assertRejected(row, post(body(key)));
        final Map<String, Object> version = callback(row, "success", 1000);
        version.put("version", 3);
        assertRejected(row, post(body(version)));
        final String text = logs.text();
        assertTrue(text.contains("reason=amount") && text.contains("reason=currency") && text.contains("reason=action")
                   && text.contains("reason=public_key") && text.contains("reason=version"));
    }

    @Test
    public void anUnknownOrderIsRejected() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final Map<String, Object> m = callback(row, "success", 1000);
        m.put("order_id", UUID.randomUUID().toString());
        assertRejected(row, post(body(m)));
    }

    @Test
    public void anOrderOfAnotherTenantIsRejected() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        row.setKbTenantId(UUID.randomUUID().toString());
        store.row(row.getOrderId()).setKbTenantId(row.getKbTenantId());
        assertEquals(post(body(callback(row, "success", 1000))), 400);
        verifyNoNotify();
    }

    @Test
    public void aBodyOver16KibIsRejected() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final Map<String, Object> m = callback(row, "success", 1000);
        m.put("description", "x".repeat(17_000));
        assertRejected(row, post(body(m)));
    }

    @Test
    public void aBadRequestShapeIsRejected() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final String good = body(callback(row, "success", 1000));
        assertRejected(row, send("GET", "/callback", "kbTenantId=" + tenant, "application/x-www-form-urlencoded", good));
        assertRejected(row, post("kbTenantId=" + tenant, "application/json", good));
        assertRejected(row, post("kbTenantId=" + UUID.randomUUID(), "application/x-www-form-urlencoded", good));
        assertRejected(row, post("kbTenantId=not-a-uuid", "application/x-www-form-urlencoded", good));
        assertRejected(row, post(null, "application/x-www-form-urlencoded", good));
        assertRejected(row, post(good.substring(0, good.indexOf("&signature"))));
        assertRejected(row, post("data=" + "A".repeat(12_400) + "&signature=abc"));
        assertRejected(row, post(good + "&data=again"));
        final String notBase64 = "data=%%%%&signature=" + URLEncoder.encode(LiqPaySigner.sign(Fixtures.PRIVATE_KEY, "%%%%"), StandardCharsets.UTF_8);
        assertRejected(row, post("data=" + URLEncoder.encode("@@@@", StandardCharsets.UTF_8) + "&signature="
                                 + URLEncoder.encode(LiqPaySigner.sign(Fixtures.PRIVATE_KEY, "@@@@"), StandardCharsets.UTF_8)));
        assertRejected(row, post(notBase64));
    }

    @Test
    public void anotherPathIsNotFound() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(send("POST", "/other", "kbTenantId=" + tenant, "application/x-www-form-urlencoded",
                          body(callback(row, "success", 1000))), 404);
    }

    // --- Server errors ---

    @Test
    public void aDatabaseErrorAnswers503() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        store.failing = true;
        assertEquals(post(body(callback(row, "success", 1000))), 503);
        store.failing = false;
        assertEquals(status(row), "PENDING");
        verifyNoNotify();
    }

    @Test
    public void aKillBillErrorAnswers503() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        doThrow(new RuntimeException("kill bill is down"))
                .when(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), any(), any());
        assertEquals(post(body(callback(row, "success", 1000))), 503);
        assertTrue(logs.text().contains("liqpay_notify_failed order_id=" + row.getOrderId()));
    }

    // --- Kill Bill login and retry ---

    @Test
    public void theNotifyRunsAsTheKillBillUserOfTheTenant() throws Exception {
        servlet = servlet(loginConfig());
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        final InOrder order = inOrder(security, paymentApi);
        order.verify(security).login("liqpay-plugin", "kb-plugin-secret");
        order.verify(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(
                eq(account), eq(UUID.fromString(row.getKbTransactionId())), eq(true), any(PaymentOptions.class), any(CallContext.class));
        order.verify(security).logout();
    }

    @Test
    public void aTenantWithoutAKillBillUserDoesNotLogIn() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        verify(killbill, never()).getSecurityApi();
        verifyNotified(row, true, 1);
    }

    @Test
    public void aFailedNotifyIsRetriedByTheNextCallback() throws Exception {
        servlet = servlet(loginConfig());
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        doThrow(new RuntimeException("This subject is anonymous"))
                .doAnswer(i -> {
                    kbStatus.put(i.getArgument(1), TransactionStatus.SUCCESS);
                    return null;
                })
                .when(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), any(), any());
        final String b = body(callback(row, "success", 1000));
        assertEquals(post(b), 503);
        assertEquals(status(row), "PROCESSED");
        assertEquals(post(b), 200);
        assertEquals(kbStatus.get(UUID.fromString(row.getKbTransactionId())), TransactionStatus.SUCCESS);
        verifyNotified(row, true, 2);
        verify(security, times(2)).logout();
        assertEquals(post(b), 200);
        verifyNotified(row, true, 2);
    }

    @Test
    public void aFailedLoginAnswers503AndTheRowStaysForTheRetry() throws Exception {
        servlet = servlet(loginConfig());
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        doThrow(new IllegalStateException("bad credentials")).when(security).login(any(), any());
        assertEquals(post(body(callback(row, "success", 1000))), 503);
        assertEquals(status(row), "PROCESSED");
        verifyNoNotify();
        verify(security, never()).logout();
        assertTrue(logs.text().contains("liqpay_notify_failed order_id=" + row.getOrderId() + " error=IllegalStateException"));
        assertFalse(logs.text().contains("kb-plugin-secret"));
    }

    @Test
    public void aTransactionThatKillBillSettledIsNotNotified() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        kbStatus.put(UUID.fromString(row.getKbTransactionId()), TransactionStatus.SUCCESS);
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        assertEquals(status(row), "PROCESSED");
        verifyNoNotify();
    }

    // --- A settle by the Janitor and a callback at the same time ---

    private static PaymentApiException invoiceAlreadyPaid() {
        return new PaymentApiException(ErrorCode.PAYMENT_PLUGIN_API_ABORTED, PendingNotifier.INVOICE_CONTROL_PLUGIN);
    }

    /** The Janitor settles the row and the Kill Bill transaction while the callback notify waits for the account lock. */
    private void janitorSettlesDuringTheNotify(final LiqpayTransactionsRecord row, final TransactionStatus kbFinal) throws Exception {
        store.updateFromLiqPay(row.getOrderId(), tenant, LiqPayResult.from(JSON.valueToTree(callback(row, "success", 900))),
                               PaymentPluginStatus.PROCESSED, Map.of());
        doAnswer(i -> {
            kbStatus.put(i.getArgument(1), kbFinal);
            throw invoiceAlreadyPaid();
        }).when(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    public void aCallbackForAPaymentThatTheJanitorSettledAnswers200() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        janitorSettlesDuringTheNotify(row, TransactionStatus.SUCCESS);
        final String b = body(callback(row, "success", 1000));
        assertEquals(post(b), 200);
        assertEquals(status(row), "PROCESSED");
        assertEquals(kbStatus.get(UUID.fromString(row.getKbTransactionId())), TransactionStatus.SUCCESS);
        verifyNotified(row, true, 1);
        assertEquals(post(b), 200, "a replay");
        verifyNotified(row, true, 1);
    }

    @Test
    public void aPlainNotifyForAPaymentThatTheJanitorSettledAnswers200() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        when(invoicePaymentApi.getInvoicePayments(any(UUID.class), any(TenantContext.class))).thenReturn(List.of());
        doAnswer(i -> {
            kbStatus.put(i.getArgument(1), TransactionStatus.SUCCESS);
            throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_SUCCESS_PAYMENT, row.getKbPaymentId());
        }).when(paymentApi).notifyPendingTransactionOfStateChanged(any(), any(), anyBoolean(), any());
        assertEquals(post(body(callback(row, "success", 1000))), 200);
        assertEquals(status(row), "PROCESSED");
    }

    @Test
    public void anAbortForATransactionThatStaysPendingAnswers503() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        janitorSettlesDuringTheNotify(row, TransactionStatus.PENDING);
        assertEquals(post(body(callback(row, "success", 1000))), 503);
        assertTrue(logs.text().contains("liqpay_notify_failed order_id=" + row.getOrderId() + " error=PaymentApiException"));
    }

    @Test
    public void anAbortForAnotherFinalStateAnswers503() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        janitorSettlesDuringTheNotify(row, TransactionStatus.PAYMENT_FAILURE);
        assertEquals(post(body(callback(row, "success", 1000))), 503);
    }

    @Test
    public void anotherKillBillErrorAfterTheJanitorSettledAnswers503() throws Exception {
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        doAnswer(i -> {
            kbStatus.put(i.getArgument(1), TransactionStatus.SUCCESS);
            throw new PaymentApiException(ErrorCode.PAYMENT_INTERNAL_ERROR, "internal");
        }).when(paymentApi).notifyPendingTransactionOfStateChangedWithPaymentControl(any(), any(), anyBoolean(), any(), any());
        assertEquals(post(body(callback(row, "success", 1000))), 503);
    }

    @Test
    public void twoCallbacksAtTheSameTimeSettleThePaymentOnce() throws Exception {
        final CyclicBarrier bothRead = new CyclicBarrier(2);
        store = new InMemoryStore() {
            @Override
            public Optional<LiqpayTransactionsRecord> findByOrderId(final String orderId, final UUID tenantId) throws SQLException {
                final Optional<LiqpayTransactionsRecord> found = super.findByOrderId(orderId, tenantId);
                try {
                    bothRead.await(10, TimeUnit.SECONDS);
                } catch (final Exception e) {
                    throw new SQLException(e);
                }
                return found;
            }
        };
        servlet = servlet(Fixtures.config("https://www.liqpay.ua"));
        final LiqpayTransactionsRecord row = pending("pay", "PENDING");
        final String b = body(callback(row, "success", 1000));
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final List<Future<Integer>> codes = pool.invokeAll(List.of(() -> post(b), () -> post(b)));
            assertEquals(codes.get(0).get(), Integer.valueOf(200));
            assertEquals(codes.get(1).get(), Integer.valueOf(200));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(status(row), "PROCESSED");
        assertEquals(kbStatus.get(UUID.fromString(row.getKbTransactionId())), TransactionStatus.SUCCESS);
        verifyNotified(row, true, 1);
    }

    // --- Logs ---

    @Test(priority = 100)
    public void theLogHoldsNoDataSignatureOrKey() {
        final String text = logs.text();
        assertFalse(secrets.isEmpty());
        secrets.forEach(s -> assertFalse(text.contains(s), "the log holds a data or signature value"));
        assertFalse(text.contains(Fixtures.PRIVATE_KEY));
        assertFalse(text.contains("kb-plugin-secret"));
        assertFalse(text.contains("424242"));
    }
}
