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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.sailkit.billing.plugin.liqpay.LogCapture;
import io.sailkit.billing.plugin.liqpay.crypto.CardTokenCipher;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.HppStatus;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayHppRequestsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayPaymentMethodsRecord;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.util.callcontext.CallContext;
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
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.prop;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TokenFlowTest {

    private static final String TOKEN = "B5B0D00B88B00ED00A00D0D0";

    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private WireMockServer server;
    private LogCapture logs;
    private InMemoryStore store;
    private LiqPayPaymentPluginApi api;
    private CallContext ctx;

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
        api = new LiqPayPaymentPluginApi(Fixtures.handler(tenant, Fixtures.tokenConfig(base())), store, new Fixtures.MutableClock());
        ctx = context(account, tenant);
    }

    // --- Helpers ---

    private String base() {
        return "http://localhost:" + server.port();
    }

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

    /** A payment method of the account. A null token stores a method that is not verified. */
    private UUID method(final String mode, final String token) throws Exception {
        final UUID pm = UUID.randomUUID();
        api.addPaymentMethod(account, pm, null, false, List.of(prop("liqpay_mode", mode)), ctx);
        if (token != null) {
            final LiqpayPaymentMethodsRecord row = store.findPaymentMethod(pm, tenant).orElseThrow();
            row.setCardTokenEnc(new CardTokenCipher(Fixtures.tokenConfig(base()).cardTokenKey(), "1").encrypt(token));
            row.setCardTokenKeyId("1");
            row.setVerified(true);
            row.setCardMask("424242*42");
            row.setCardType("visa");
            store.upsertPaymentMethod(row);
        }
        return pm;
    }

    private PaymentTransactionInfoPlugin purchase(final UUID payment, final UUID pm, final String amount,
                                                  final List<PluginProperty> props) throws Exception {
        return api.purchasePayment(account, payment, UUID.randomUUID(), pm, new BigDecimal(amount), Currency.USD, props, ctx);
    }

    private PaymentTransactionInfoPlugin authorize(final UUID payment, final UUID pm, final String amount) throws Exception {
        return api.authorizePayment(account, payment, UUID.randomUUID(), pm, new BigDecimal(amount), Currency.USD, List.of(), ctx);
    }

    private PaymentTransactionInfoPlugin capture(final UUID payment, final UUID pm, final String amount) throws Exception {
        return api.capturePayment(account, payment, UUID.randomUUID(), pm, new BigDecimal(amount), Currency.USD, List.of(), ctx);
    }

    private static Map<String, String> fields(final HostedPaymentPageFormDescriptor d) {
        return d.getFormFields().stream().collect(Collectors.toMap(PluginProperty::getKey, p -> p.getValue().toString()));
    }

    private List<PluginProperty> formProps(final UUID pm) {
        return List.of(prop("liqpay_payment_method_id", pm.toString()), prop("liqpay_result_url", Fixtures.RESULT_URL));
    }

    // --- Hosted verification form ---

    @Test
    public void theFormHoldsTheVerificationAmountOfTheConfig() throws Exception {
        final UUID pm = method("token", null);
        final HostedPaymentPageFormDescriptor d = api.buildFormDescriptor(
                account, List.of(prop("amount", "999"), prop("currency", "USD"), prop("action", "pay")), formProps(pm), ctx);
        final Map<String, String> f = fields(d);
        assertEquals(d.getKbAccountId(), account);
        assertEquals(d.getFormMethod(), "POST");
        assertEquals(d.getFormUrl(), base() + "/api/3/checkout");
        assertEquals(f.get("checkout_url"), base() + "/api/3/checkout");
        assertTrue(LiqPaySigner.verify(Fixtures.PRIVATE_KEY, f.get("data"), f.get("signature")));
        final JsonNode data = LiqPayEnvelope.decode(f.get("data"));
        assertEquals(data.get("action").asText(), "hold");
        assertEquals(data.get("amount").asText(), "1.00");
        assertEquals(data.get("currency").asText(), "UAH");
        assertEquals(data.get("recurringbytoken").asText(), "1");
        assertEquals(data.get("version").asInt(), 7);
        assertEquals(data.get("result_url").asText(), Fixtures.RESULT_URL);
        assertEquals(data.get("server_url").asText(), "https://pay.example.com/plugins/killbill-liqpay/callback?kbTenantId=" + tenant);
        final String orderId = data.get("order_id").asText();
        assertTrue(orderId.startsWith("verify-"));
        final LiqpayHppRequestsRecord hpp = store.findHppRequestByOrderId(orderId, tenant).orElseThrow();
        assertEquals(hpp.getStatus(), HppStatus.PENDING);
        assertEquals(hpp.getKbPaymentMethodId(), pm.toString());
        assertEquals(hpp.getAmount().compareTo(new BigDecimal("1.00")), 0);
        assertTrue(sent().isEmpty(), "the form makes no LiqPay call");
    }

    @Test
    public void theFormRejectsABadRequest() throws Exception {
        final UUID checkout = method("checkout", null);
        final UUID token = method("token", null);
        final List<List<PluginProperty>> bad = List.of(
                formProps(checkout),
                formProps(UUID.randomUUID()),
                List.of(prop("liqpay_payment_method_id", "not-a-uuid"), prop("liqpay_result_url", Fixtures.RESULT_URL)),
                List.of(prop("liqpay_payment_method_id", token.toString()), prop("liqpay_result_url", "https://evil.example.com/")),
                List.of(prop("liqpay_result_url", Fixtures.RESULT_URL)));
        bad.forEach(props -> expectThrows(PaymentPluginApiException.class, () -> api.buildFormDescriptor(account, List.of(), props, ctx)));
        expectThrows(PaymentPluginApiException.class, () -> api.buildFormDescriptor(UUID.randomUUID(), List.of(), formProps(token), ctx));
        assertTrue(store.hppRequests.isEmpty());
    }

    @Test
    public void theFormNeedsACardTokenKey() throws Exception {
        api = new LiqPayPaymentPluginApi(Fixtures.handler(tenant, Fixtures.config(base())), store, new Fixtures.MutableClock());
        final UUID pm = method("token", null);
        final PaymentPluginApiException e = expectThrows(PaymentPluginApiException.class,
                                                         () -> api.buildFormDescriptor(account, List.of(), formProps(pm), ctx));
        assertEquals(e.getErrorType(), "card_token_key_missing");
    }

    // --- Purchase by token ---

    @Test
    public void aTokenPurchaseSendsPaytoken() throws Exception {
        final UUID pm = method("token", TOKEN);
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"success\",\"payment_id\":\"91\",\"amount\":35,\"currency\":\"USD\"}");
        final UUID payment = UUID.randomUUID();
        final PaymentTransactionInfoPlugin i = purchase(payment, pm, "35", List.of(prop("liqpay_ip", "203.0.113.20"), prop("amount", "0.01"),
                                                                                  prop("liqpay_description", "Renewal")));
        assertEquals(i.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(i.getTransactionType(), TransactionType.PURCHASE);
        final JsonNode d = sent().get(0);
        assertEquals(d.get("action").asText(), "paytoken");
        assertEquals(d.get("card_token").asText(), TOKEN);
        assertEquals(d.get("amount").asText(), "35.00");
        assertEquals(d.get("currency").asText(), "USD");
        assertEquals(d.get("ip").asText(), "203.0.113.20");
        assertEquals(d.get("description").asText(), "Renewal");
        assertEquals(d.get("order_id").asText(), i.getKbTransactionPaymentId().toString());
        assertEquals(d.get("server_url").asText(), "https://pay.example.com/plugins/killbill-liqpay/callback?kbTenantId=" + tenant);
        assertEquals(store.findByPayment(payment, tenant).get(0).getLiqpayAction(), "paytoken");
        assertFalse(logs.text().contains(TOKEN), "no log line holds the token");
    }

    @Test
    public void aBadIpIsNotSent() throws Exception {
        final UUID pm = method("token", TOKEN);
        answer("{\"result\":\"ok\",\"status\":\"success\"}");
        purchase(UUID.randomUUID(), pm, "5", List.of(prop("liqpay_ip", "203.0.113.20\" x")));
        assertFalse(sent().get(0).has("ip"));
    }

    @Test
    public void aTokenCallWithoutATokenIsCanceled() throws Exception {
        final UUID unverified = method("token", null);
        final UUID checkout = method("checkout", null);
        final PaymentTransactionInfoPlugin p = purchase(UUID.randomUUID(), unverified, "5", List.of());
        assertEquals(p.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(p.getGatewayErrorCode(), "no_card_token");
        final PaymentTransactionInfoPlugin a = authorize(UUID.randomUUID(), checkout, "5");
        assertEquals(a.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(a.getGatewayErrorCode(), "no_card_token");
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aTokenCallWithoutTheCardTokenKeyIsCanceled() throws Exception {
        final UUID pm = method("token", TOKEN);
        api = new LiqPayPaymentPluginApi(Fixtures.handler(tenant, Fixtures.config(base())), store, new Fixtures.MutableClock());
        final PaymentTransactionInfoPlugin p = purchase(UUID.randomUUID(), pm, "5", List.of());
        assertEquals(p.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(p.getGatewayErrorCode(), "card_token_key_missing");
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aTransientErrorKeepsTheTokenPurchasePendingUntilTheStatusSettlesIt() throws Exception {
        final UUID pm = method("token", TOKEN);
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(502)));
        final UUID payment = UUID.randomUUID();
        final PaymentTransactionInfoPlugin i = purchase(payment, pm, "35", List.of());
        assertEquals(i.getStatus(), PaymentPluginStatus.PENDING);
        server.resetAll();
        answer("{\"result\":\"ok\",\"action\":\"pay\",\"status\":\"success\",\"order_id\":\"" + i.getKbTransactionPaymentId()
               + "\",\"amount\":35,\"currency\":\"USD\",\"end_date\":\"5\"}");
        final PaymentTransactionInfoPlugin settled = api.getPaymentInfo(account, payment, List.of(), ctx).get(0);
        assertEquals(settled.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(sent().get(0).get("action").asText(), "status");
    }

    @Test
    public void aRepeatedTransactionMakesNoSecondCall() throws Exception {
        final UUID pm = method("token", TOKEN);
        answer("{\"result\":\"ok\",\"status\":\"success\"}");
        final UUID payment = UUID.randomUUID();
        final UUID tx = UUID.randomUUID();
        api.purchasePayment(account, payment, tx, pm, new BigDecimal("5"), Currency.USD, List.of(), ctx);
        api.purchasePayment(account, payment, tx, pm, new BigDecimal("5"), Currency.USD, List.of(), ctx);
        assertEquals(sent().size(), 1);
    }

    @Test
    public void anErrorAnswerIsAnError() throws Exception {
        final UUID pm = method("token", TOKEN);
        answer("{\"result\":\"error\",\"status\":\"error\",\"err_code\":\"108\"}");
        final PaymentTransactionInfoPlugin p = purchase(UUID.randomUUID(), pm, "5", List.of());
        assertEquals(p.getStatus(), PaymentPluginStatus.ERROR);
        assertEquals(p.getGatewayErrorCode(), "108");
    }

    // --- Authorize, capture, void ---

    @Test
    public void anAuthorizeHoldsByToken() throws Exception {
        final UUID pm = method("token", TOKEN);
        answer("{\"result\":\"ok\",\"action\":\"hold\",\"status\":\"hold_wait\",\"amount\":50,\"currency\":\"USD\"}");
        final PaymentTransactionInfoPlugin a = authorize(UUID.randomUUID(), pm, "50");
        assertEquals(a.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(a.getTransactionType(), TransactionType.AUTHORIZE);
        assertEquals(sent().get(0).get("action").asText(), "hold");
        assertEquals(sent().get(0).get("card_token").asText(), TOKEN);
        assertEquals(sent().get(0).get("amount").asText(), "50.00");
    }

    @Test
    public void aCaptureCompletesTheHoldAndARefundUsesTheHoldOrder() throws Exception {
        final UUID pm = method("token", TOKEN);
        final UUID payment = UUID.randomUUID();
        answer("{\"result\":\"ok\",\"status\":\"hold_wait\"}");
        final String holdOrder = authorize(payment, pm, "50").getKbTransactionPaymentId().toString();
        server.resetAll();
        answer("{\"result\":\"ok\",\"action\":\"hold\",\"status\":\"success\",\"amount\":45,\"currency\":\"USD\"}");
        final PaymentTransactionInfoPlugin c = capture(payment, pm, "45");
        assertEquals(c.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(c.getTransactionType(), TransactionType.CAPTURE);
        assertEquals(sent().get(0).get("action").asText(), "hold_completion");
        assertEquals(sent().get(0).get("order_id").asText(), holdOrder);
        assertEquals(sent().get(0).get("amount").asText(), "45.00");

        server.resetAll();
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        final PaymentTransactionInfoPlugin r = api.refundPayment(account, payment, UUID.randomUUID(), pm, new BigDecimal("20"), Currency.USD,
                                                                 List.of(), ctx);
        assertEquals(r.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(sent().get(0).get("action").asText(), "refund");
        assertEquals(sent().get(0).get("order_id").asText(), holdOrder);
    }

    @Test
    public void aCaptureIsCheckedAgainstTheHold() throws Exception {
        final UUID pm = method("token", TOKEN);
        final UUID payment = UUID.randomUUID();
        final PaymentTransactionInfoPlugin none = capture(payment, pm, "10");
        assertEquals(none.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(none.getGatewayErrorCode(), "no_authorization");
        answer("{\"result\":\"ok\",\"status\":\"hold_wait\"}");
        authorize(payment, pm, "50");
        server.resetAll();
        final PaymentTransactionInfoPlugin over = capture(payment, pm, "50.01");
        assertEquals(over.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(over.getGatewayErrorCode(), "capture_exceeds_hold");
        final PaymentTransactionInfoPlugin eur = api.capturePayment(account, payment, UUID.randomUUID(), pm, new BigDecimal("5"), Currency.EUR,
                                                                    List.of(), ctx);
        assertEquals(eur.getGatewayErrorCode(), "currency_mismatch");
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aVoidReleasesTheHeldAmountAndClosesTheHold() throws Exception {
        final UUID pm = method("token", TOKEN);
        final UUID payment = UUID.randomUUID();
        answer("{\"result\":\"ok\",\"status\":\"hold_wait\"}");
        final String holdOrder = authorize(payment, pm, "50").getKbTransactionPaymentId().toString();
        server.resetAll();
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        final PaymentTransactionInfoPlugin v = api.voidPayment(account, payment, UUID.randomUUID(), pm, List.of(), ctx);
        assertEquals(v.getStatus(), PaymentPluginStatus.PROCESSED);
        assertEquals(v.getTransactionType(), TransactionType.VOID);
        assertEquals(sent().get(0).get("action").asText(), "refund");
        assertEquals(sent().get(0).get("order_id").asText(), holdOrder);
        assertEquals(sent().get(0).get("amount").asText(), "50.00");
        server.resetAll();
        final PaymentTransactionInfoPlugin late = capture(payment, pm, "10");
        assertEquals(late.getGatewayErrorCode(), "hold_already_closed");
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aVoidWithoutAHoldIsCanceled() throws Exception {
        final PaymentTransactionInfoPlugin v = api.voidPayment(account, UUID.randomUUID(), UUID.randomUUID(), method("token", TOKEN),
                                                               List.of(), ctx);
        assertEquals(v.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(v.getGatewayErrorCode(), "no_authorization");
    }

    @Test
    public void aCreditIsNotSupported() throws Exception {
        final PaymentTransactionInfoPlugin c = api.creditPayment(account, UUID.randomUUID(), UUID.randomUUID(), method("token", TOKEN),
                                                                 new BigDecimal("5"), Currency.USD, List.of(), ctx);
        assertEquals(c.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(c.getGatewayErrorCode(), "not_supported");
        assertTrue(sent().isEmpty());
    }

    // --- Payment method ---

    @Test
    public void theMethodDetailHoldsNoToken() throws Exception {
        final UUID pm = method("token", TOKEN);
        assertFalse(new String(store.method(pm).getCardTokenEnc(), StandardCharsets.ISO_8859_1).contains(TOKEN), "the stored token is encrypted");
        final PaymentMethodPlugin detail = api.getPaymentMethodDetail(account, pm, List.of(), ctx);
        final Map<String, Object> props = detail.getProperties().stream()
                                                .collect(Collectors.toMap(PluginProperty::getKey, PluginProperty::getValue));
        assertEquals(props.get("verified"), "true");
        assertEquals(props.get("cardMask"), "424242*42");
        assertEquals(props.get("cardType"), "visa");
        assertFalse(props.values().stream().anyMatch(v -> String.valueOf(v).contains(TOKEN)));
    }

    @Test
    public void aDeletedMethodLosesItsToken() throws Exception {
        final UUID pm = method("token", TOKEN);
        api.deletePaymentMethod(account, pm, List.of(), ctx);
        final LiqpayPaymentMethodsRecord row = store.method(pm);
        assertTrue(row.getIsDeleted());
        assertNull(row.getCardTokenEnc());
        assertFalse(row.getVerified());
        final PaymentTransactionInfoPlugin p = purchase(UUID.randomUUID(), pm, "5", List.of());
        assertEquals(p.getStatus(), PaymentPluginStatus.CANCELED);
        assertTrue(sent().isEmpty());
    }

    @Test
    public void aStoredRowNeverHoldsTheToken() throws Exception {
        final UUID pm = method("token", TOKEN);
        answer("{\"result\":\"ok\",\"status\":\"success\",\"card_token\":\"" + TOKEN + "\"}");
        final UUID payment = UUID.randomUUID();
        purchase(payment, pm, "5", List.of());
        final LiqpayTransactionsRecord row = store.findByPayment(payment, tenant).get(0);
        assertFalse(String.valueOf(row.getAdditionalData()).contains(TOKEN));
    }
}
