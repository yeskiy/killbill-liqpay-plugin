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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.sailkit.billing.plugin.liqpay.LogCapture;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.context;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.props;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class RefundTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private WireMockServer server;
    private InMemoryStore store;
    private LiqPayPaymentPluginApi api;

    @BeforeClass
    public void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        server.stop();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        server.resetAll();
        store = new InMemoryStore();
        api = new LiqPayPaymentPluginApi(Fixtures.handler(tenant, Fixtures.config("http://localhost:" + server.port())), store,
                                         new Fixtures.MutableClock());
    }

    private void answer(final String json) {
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(200)
                                                                              .withHeader("Content-Type", "application/json")
                                                                              .withBody(json)));
    }

    /** Stores a settled transaction of the payment and returns its order id. */
    private String paid(final UUID payment, final String type, final String status) throws Exception {
        final LiqpayTransactionsRecord r = new LiqpayTransactionsRecord();
        final String tx = UUID.randomUUID().toString();
        r.setKbAccountId(account.toString());
        r.setKbPaymentId(payment.toString());
        r.setKbTransactionId(tx);
        r.setKbPaymentMethodId(UUID.randomUUID().toString());
        r.setKbTenantId(tenant.toString());
        r.setTransactionType(type);
        r.setLiqpayAction("PURCHASE".equals(type) ? "pay" : "hold_completion");
        r.setOrderId(tx);
        r.setAmount(new BigDecimal("100.00"));
        r.setCurrency("USD");
        r.setPluginStatus(status);
        store.insertTransaction(r);
        return tx;
    }

    private PaymentTransactionInfoPlugin refund(final UUID payment, final UUID tx, final String amount) throws Exception {
        return api.refundPayment(account, payment, tx, UUID.randomUUID(), new BigDecimal(amount), Currency.USD, List.of(),
                                 context(account, tenant));
    }

    private List<JsonNode> sent() {
        return server.findAll(postRequestedFor(urlEqualTo("/api/request"))).stream()
                     .map(r -> LiqPayEnvelope.decode(URLDecoder.decode(r.getBodyAsString().split("&")[0].substring(5), StandardCharsets.UTF_8)))
                     .collect(java.util.stream.Collectors.toList());
    }

    @Test
    public void aRefundSendsThePaidOrderIdAndTheAmount() throws Exception {
        final UUID payment = UUID.randomUUID();
        final String order = paid(payment, "PURCHASE", "PROCESSED");
        answer("{\"result\":\"ok\",\"status\":\"reversed\",\"payment_id\":\"77\",\"wait_amount\":false}");
        final UUID tx = UUID.randomUUID();
        final PaymentTransactionInfoPlugin i = refund(payment, tx, "100.00");
        assertEquals(i.getStatus(), PaymentPluginStatus.PROCESSED, "reversed maps to PROCESSED for a refund");
        assertEquals(i.getTransactionType(), TransactionType.REFUND);
        assertEquals(i.getKbTransactionPaymentId(), tx);
        assertEquals(sent().size(), 1);
        assertEquals(sent().get(0).get("action").asText(), "refund");
        assertEquals(sent().get(0).get("order_id").asText(), order);
        assertEquals(sent().get(0).get("amount").asText(), "100.00");
        final LiqpayTransactionsRecord row = store.findByTransactionId(tx, tenant).orElseThrow();
        assertEquals(row.getOriginalOrderId(), order);
        assertEquals(row.getTransactionType(), "REFUND");
        assertEquals(props(i).get("liqpay_order_id"), order);
    }

    @Test
    public void aPartialRefundSendsThePartialAmount() throws Exception {
        final UUID payment = UUID.randomUUID();
        paid(payment, "PURCHASE", "PROCESSED");
        answer("{\"result\":\"ok\",\"status\":\"success\",\"payment_id\":\"77\"}");
        assertEquals(refund(payment, UUID.randomUUID(), "40.00").getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(sent().get(0).get("amount").asText(), "40.00");
    }

    @Test
    public void aCaptureCanBeRefunded() throws Exception {
        final UUID payment = UUID.randomUUID();
        final String order = paid(payment, "CAPTURE", "PROCESSED");
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        assertEquals(refund(payment, UUID.randomUUID(), "10").getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(sent().get(0).get("order_id").asText(), order);
    }

    @Test
    public void aRefundAboveThePaidRestIsAnErrorWithNoCall() throws Exception {
        final UUID payment = UUID.randomUUID();
        paid(payment, "PURCHASE", "PROCESSED");
        answer("{\"result\":\"ok\",\"status\":\"success\"}");
        assertEquals(refund(payment, UUID.randomUUID(), "60.00").getStatus(), PaymentPluginStatus.PROCESSED);
        final PaymentTransactionInfoPlugin over = refund(payment, UUID.randomUUID(), "40.01");
        assertEquals(over.getStatus(), PaymentPluginStatus.ERROR);
        assertEquals(over.getGatewayErrorCode(), "refund_exceeds_paid");
        assertEquals(sent().size(), 1);
        assertEquals(refund(payment, UUID.randomUUID(), "40.00").getStatus(), PaymentPluginStatus.PROCESSED);
    }

    @Test
    public void aRefundWithoutAPaidTransactionIsAnError() throws Exception {
        final UUID payment = UUID.randomUUID();
        paid(payment, "PURCHASE", "PENDING");
        final PaymentTransactionInfoPlugin i = refund(payment, UUID.randomUUID(), "1.00");
        assertEquals(i.getStatus(), PaymentPluginStatus.ERROR);
        assertEquals(i.getGatewayErrorCode(), "no_paid_transaction");
        assertEquals(sent().size(), 0);
    }

    @Test
    public void anErrorAnswerIsAnError() throws Exception {
        final UUID payment = UUID.randomUUID();
        paid(payment, "PURCHASE", "PROCESSED");
        answer("{\"result\":\"error\",\"status\":\"error\",\"err_code\":\"payment_err_status\"}");
        final PaymentTransactionInfoPlugin i = refund(payment, UUID.randomUUID(), "10.00");
        assertEquals(i.getStatus(), PaymentPluginStatus.ERROR);
        assertEquals(i.getGatewayErrorCode(), "payment_err_status");
    }

    @Test
    public void aPendingRefundIsStoredAndResolvedLater() throws Exception {
        final UUID payment = UUID.randomUUID();
        final String order = paid(payment, "PURCHASE", "PROCESSED");
        answer("{\"result\":\"ok\",\"status\":\"wait_compensation\"}");
        final UUID tx = UUID.randomUUID();
        assertEquals(refund(payment, tx, "100.00").getStatus(), PaymentPluginStatus.PENDING);
        assertEquals(store.findByTransactionId(tx, tenant).orElseThrow().getPluginStatus(), "PENDING");

        server.resetAll();
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"reversed\",\"order_id\":\"" + order + "\",\"end_date\":\"9\"}");
        final PaymentTransactionInfoPlugin resolved = api.getPaymentInfo(account, payment, List.of(), context(account, tenant)).stream()
                                                         .filter(p -> p.getKbTransactionPaymentId().equals(tx)).findFirst().orElseThrow();
        assertEquals(resolved.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(sent().get(0).get("action").asText(), "status");
        assertEquals(sent().get(0).get("order_id").asText(), order);
    }

    @Test
    public void anUnreachableLiqPayKeepsTheRefundPending() throws Exception {
        final UUID payment = UUID.randomUUID();
        paid(payment, "PURCHASE", "PROCESSED");
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(502)));
        final UUID tx = UUID.randomUUID();
        final PaymentTransactionInfoPlugin i = refund(payment, tx, "100.00");
        assertEquals(i.getStatus(), PaymentPluginStatus.PENDING);
        assertEquals(store.findByTransactionId(tx, tenant).orElseThrow().getPluginStatus(), "PENDING");
    }

    // --- Refusals ---

    private static final String REFUSED = "{\"result\":\"error\",\"status\":\"error\",\"action\":\"refund\","
                                          + "\"err_code\":\"payment_err_status\","
                                          + "\"err_description\":\"The payment system did not complete the transaction\"}";

    private static JsonNode stored(final LiqpayTransactionsRecord row) throws Exception {
        return new ObjectMapper().readTree(row.getAdditionalData());
    }

    private static void assertNoSecret(final String text) {
        java.util.stream.Stream.of("public_key", "private_key", "\"data\"", "signature", Fixtures.PUBLIC_KEY, Fixtures.PRIVATE_KEY)
                               .forEach(s -> assertFalse(text.contains(s), "no " + s + " in " + text));
    }

    @Test
    public void refusalStoresRequestWithoutSecrets() throws Exception {
        final UUID payment = UUID.randomUUID();
        final String order = paid(payment, "PURCHASE", "PROCESSED");
        answer(REFUSED);
        final UUID tx = UUID.randomUUID();
        assertEquals(refund(payment, tx, "100.00").getStatus(), PaymentPluginStatus.ERROR);
        final LiqpayTransactionsRecord row = store.findByTransactionId(tx, tenant).orElseThrow();
        final JsonNode request = stored(row).get("request");
        assertEquals(request.get("version").asInt(), 7);
        assertEquals(request.get("action").asText(), "refund");
        assertEquals(request.get("order_id").asText(), order);
        assertEquals(request.get("amount").asText(), "100.00");
        assertEquals(request.get("currency").asText(), "USD");
        assertEquals(stored(row).get("err_description").asText(), "The payment system did not complete the transaction");
        assertNoSecret(row.getAdditionalData());

        final UUID over = UUID.randomUUID();
        assertEquals(refund(payment, over, "100.01").getStatus(), PaymentPluginStatus.ERROR);
        final LiqpayTransactionsRecord local = store.findByTransactionId(over, tenant).orElseThrow();
        assertEquals(stored(local).get("request").get("order_id").asText(), order);
        assertEquals(stored(local).get("request").get("amount").asText(), "100.01");
        assertEquals(local.getErrDescription(), "The refund amount 100.01 USD is above the paid rest 100.00 USD");
        final UUID whole = UUID.randomUUID();
        refund(payment, whole, "101");
        assertEquals(store.findByTransactionId(whole, tenant).orElseThrow().getErrDescription(),
                     "The refund amount 101.00 USD is above the paid rest 100.00 USD");
        assertNoSecret(local.getAdditionalData());
    }

    @Test
    public void refusalLogsErrDescriptionAtWarn() throws Exception {
        final UUID payment = UUID.randomUUID();
        final String order = paid(payment, "PURCHASE", "PROCESSED");
        answer(REFUSED);
        try (LogCapture logs = new LogCapture()) {
            refund(payment, UUID.randomUUID(), "100.00");
            refund(payment, UUID.randomUUID(), "100.01");
            final List<String> lines = java.util.Arrays.stream(logs.text().split("\\R"))
                                                       .filter(l -> l.contains("liqpay refund refused"))
                                                       .collect(java.util.stream.Collectors.toList());
            assertEquals(lines.size(), 2, logs.text());
            assertTrue(lines.get(0).contains("WARN"), lines.get(0));
            assertTrue(lines.get(0).contains("action=refund order_id=" + order + " status=error err_code=payment_err_status "
                                             + "err_description=The payment system did not complete the transaction"), lines.get(0));
            assertTrue(lines.get(1).contains("WARN"), lines.get(1));
            assertTrue(lines.get(1).contains("err_code=refund_exceeds_paid err_description="), lines.get(1));
            lines.forEach(RefundTest::assertNoSecret);
        }
    }

    @Test
    public void refusalPropertiesReachKillBill() throws Exception {
        final UUID payment = UUID.randomUUID();
        final String order = paid(payment, "PURCHASE", "PROCESSED");
        answer(REFUSED);
        final PaymentTransactionInfoPlugin i = refund(payment, UUID.randomUUID(), "100.00");
        assertEquals(props(i).get("liqpay_status"), "error");
        assertEquals(props(i).get("liqpay_err_code"), "payment_err_status");
        assertEquals(props(i).get("liqpay_err_description"), "The payment system did not complete the transaction");
        final JsonNode request = new ObjectMapper().readTree((String) props(i).get("liqpay_request"));
        assertEquals(request.get("order_id").asText(), order);
        assertEquals(request.get("action").asText(), "refund");
        assertNoSecret((String) props(i).get("liqpay_request"));
        assertEquals(i.getGatewayError(), "The payment system did not complete the transaction");

        final PaymentTransactionInfoPlugin local = refund(payment, UUID.randomUUID(), "100.01");
        assertNull(props(local).get("liqpay_status"));
        assertEquals(props(local).get("liqpay_err_code"), "refund_exceeds_paid");
        assertTrue(((String) props(local).get("liqpay_err_description")).contains("100.01"));
        assertEquals(new ObjectMapper().readTree((String) props(local).get("liqpay_request")).get("amount").asText(), "100.01");
    }
}
