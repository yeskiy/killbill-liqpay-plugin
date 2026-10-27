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
import com.github.tomakehurst.wiremock.WireMockServer;
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
}
