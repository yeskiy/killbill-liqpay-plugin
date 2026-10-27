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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import org.killbill.billing.catalog.api.Currency;
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
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.RESULT_URL;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.context;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.prop;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.props;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PendingResolverTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private WireMockServer server;
    private InMemoryStore store;
    private Fixtures.MutableClock clock;
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
        clock = new Fixtures.MutableClock();
        api = new LiqPayPaymentPluginApi(Fixtures.handler(tenant, Fixtures.config("http://localhost:" + server.port())), store, clock);
    }

    private void answer(final String json) {
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(200)
                                                                              .withHeader("Content-Type", "application/json")
                                                                              .withBody(json)));
    }

    private UUID pendingPurchase(final UUID payment) throws Exception {
        final UUID tx = UUID.randomUUID();
        api.purchasePayment(account, payment, tx, UUID.randomUUID(), new BigDecimal("100.00"), Currency.USD,
                            List.of(prop("liqpay_result_url", RESULT_URL)), context(account, tenant));
        return tx;
    }

    private List<PaymentTransactionInfoPlugin> info(final UUID payment) throws Exception {
        return api.getPaymentInfo(account, payment, List.of(), context(account, tenant));
    }

    private int statusCalls() {
        return server.findAll(postRequestedFor(urlEqualTo("/api/request"))).size();
    }

    @Test
    public void aFinalAnswerUpdatesTheRow() throws Exception {
        final UUID payment = UUID.randomUUID();
        final UUID tx = pendingPurchase(payment);
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"success\",\"order_id\":\"" + tx
               + "\",\"payment_id\":\"901\",\"amount\":\"100\",\"currency\":\"USD\",\"end_date\":\"1760788800000\"}");
        final List<PaymentTransactionInfoPlugin> infos = info(payment);
        assertEquals(infos.size(), 1);
        assertEquals(infos.get(0).getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(props(infos.get(0)).get("liqpay_status"), "success");
        assertEquals(props(infos.get(0)).get("liqpay_payment_id"), "901");
        assertEquals(infos.get(0).getFirstPaymentReferenceId(), "901");
        assertEquals(store.row(tx.toString()).getPluginStatus(), "PROCESSED");
        assertTrue(!props(infos.get(0)).containsKey("liqpay_data"), "a settled purchase shows no form");
        final String sent = server.findAll(postRequestedFor(urlEqualTo("/api/request"))).get(0).getBodyAsString();
        final String data = java.net.URLDecoder.decode(sent.split("&")[0].substring(5), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(LiqPayEnvelope.decode(data).get("action").asText(), "status");
        assertEquals(LiqPayEnvelope.decode(data).get("order_id").asText(), tx.toString());
    }

    @Test
    public void aFailedPaymentIsAnError() throws Exception {
        final UUID payment = UUID.randomUUID();
        final UUID tx = pendingPurchase(payment);
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"failure\",\"err_code\":\"limit\",\"order_id\":\"" + tx + "\"}");
        final PaymentTransactionInfoPlugin i = info(payment).get(0);
        assertEquals(i.getStatus(), PaymentPluginStatus.ERROR);
        assertEquals(i.getGatewayErrorCode(), "limit");
    }

    @Test
    public void aPendingAnswerStaysPendingAfterTheExpiry() throws Exception {
        final UUID payment = UUID.randomUUID();
        final UUID tx = pendingPurchase(payment);
        clock.advance(Duration.ofHours(3));
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"processing\",\"order_id\":\"" + tx + "\",\"end_date\":\"5\"}");
        final PaymentTransactionInfoPlugin i = info(payment).get(0);
        assertEquals(i.getStatus(), PaymentPluginStatus.PENDING);
        assertEquals(props(i).get("liqpay_status"), "processing");
        assertTrue(props(i).containsKey("liqpay_data"), "the checkout form survives a pending answer");
        assertTrue(props(i).containsKey("liqpay_signature"));
    }

    @Test
    public void anUnknownOrderIsCanceledOnlyAfterExpiryAndGrace() throws Exception {
        final UUID payment = UUID.randomUUID();
        final UUID tx = pendingPurchase(payment);
        answer("{\"result\":\"error\",\"status\":\"error\",\"err_code\":\"payment_not_found\",\"err_description\":\"Payment is not found\"}");
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING);
        clock.advance(Duration.ofMinutes(44));
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING, "30 minutes TTL plus 15 minutes grace");
        clock.advance(Duration.ofMinutes(2));
        final PaymentTransactionInfoPlugin i = info(payment).get(0);
        assertEquals(i.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(i.getGatewayErrorCode(), "expired");
        assertEquals(store.row(tx.toString()).getPluginStatus(), "CANCELED");
    }

    @Test
    public void anUnreachableLiqPayReturnsTheStoredStatus() throws Exception {
        final UUID payment = UUID.randomUUID();
        pendingPurchase(payment);
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING);
        server.resetAll();
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(500)));
        clock.advance(Duration.ofHours(5));
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING);
    }

    @Test
    public void aFinalRowMakesNoCall() throws Exception {
        final UUID payment = UUID.randomUUID();
        final UUID tx = pendingPurchase(payment);
        store.row(tx.toString()).setPluginStatus("PROCESSED");
        answer("{\"result\":\"ok\",\"status\":\"failure\"}");
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(statusCalls(), 0);
    }

    @Test
    public void aSuccessWithAnotherAmountStaysPending() throws Exception {
        final UUID payment = UUID.randomUUID();
        final UUID tx = pendingPurchase(payment);
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"success\",\"order_id\":\"" + tx + "\",\"amount\":\"1.00\",\"currency\":\"USD\"}");
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING);
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"success\",\"order_id\":\"" + tx + "\",\"amount\":\"100\",\"currency\":\"UAH\"}");
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING);
    }

    @Test
    public void anotherApiErrorKeepsTheStoredStatus() throws Exception {
        final UUID payment = UUID.randomUUID();
        pendingPurchase(payment);
        clock.advance(Duration.ofHours(5));
        answer("{\"result\":\"error\",\"status\":\"error\",\"err_code\":\"err_access\"}");
        assertEquals(info(payment).get(0).getStatus(), PaymentPluginStatus.PENDING);
    }
}
