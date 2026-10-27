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

package io.sailkit.billing.plugin.liqpay.client;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.sailkit.billing.plugin.liqpay.LogCapture;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class LiqPayClientTest {

    private static final String PRIVATE_KEY = "sandbox_private_value_for_client_test";
    private static final String CARD_TOKEN = "B5B0D00B88B00ED00A00D0D0SECRET";

    private WireMockServer server;
    private LiqPayConfig config;
    private LiqPayClient client;

    @BeforeClass
    public void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        final Properties p = new Properties();
        p.setProperty(PREFIX + "publicKey", "sandbox_i777");
        p.setProperty(PREFIX + "privateKeyEnv", "LP_PRIVATE");
        p.setProperty(PREFIX + "serverUrl", "https://pay.example.com/callback");
        p.setProperty(PREFIX + "apiBaseUrl", "http://localhost:" + server.port());
        p.setProperty(PREFIX + "requestTimeout", "PT1S");
        final Function<String, String> env = Map.of("LP_PRIVATE", PRIVATE_KEY)::get;
        config = LiqPayConfig.parse(p, env);
        client = new LiqPayClient(Duration.ofSeconds(2));
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        server.stop();
    }

    @BeforeMethod
    public void reset() {
        server.resetAll();
    }

    private void answer(final String json) {
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(200)
                                                                              .withHeader("Content-Type", "application/json")
                                                                              .withBody(json)));
    }

    private Map<String, String> lastForm() {
        final List<LoggedRequest> requests = server.findAll(postRequestedFor(urlEqualTo("/api/request")));
        assertEquals(requests.size(), 1);
        assertTrue(requests.get(0).getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"));
        return Arrays.stream(requests.get(0).getBodyAsString().split("&"))
                     .map(kv -> kv.split("=", 2))
                     .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
    }

    private JsonNode lastData() {
        final Map<String, String> form = lastForm();
        assertEquals(form.keySet(), java.util.Set.of("data", "signature"));
        assertTrue(LiqPaySigner.verify(PRIVATE_KEY, form.get("data"), form.get("signature")));
        final JsonNode data = LiqPayEnvelope.decode(form.get("data"));
        assertEquals(data.get("version").asInt(), 7);
        assertEquals(data.get("public_key").asText(), "sandbox_i777");
        return data;
    }

    @Test
    public void statusPostsASignedV7Request() throws Exception {
        answer("{\"result\":\"ok\",\"status\":\"success\",\"action\":\"pay\",\"payment_id\":\"13291813\","
               + "\"order_id\":\"o-1\",\"amount\":\"0.1\",\"currency\":\"UAH\",\"end_date\":\"1715598023416\"}");
        final LiqPayResult r = client.status(config, "o-1");
        final JsonNode data = lastData();
        assertEquals(data.get("action").asText(), "status");
        assertEquals(data.get("order_id").asText(), "o-1");
        assertEquals(r.status(), "success");
        assertEquals(r.paymentId(), "13291813");
        assertEquals(r.amount(), new BigDecimal("0.1"));
        assertEquals(r.endDate(), Long.valueOf(1715598023416L));
        assertFalse(r.isError());
    }

    @Test
    public void refundSendsTheAmount() throws Exception {
        answer("{\"result\":\"ok\",\"wait_amount\":\"true\",\"payment_id\":\"2417662437\",\"status\":\"reversed\"}");
        assertEquals(client.refund(config, "o-2", new BigDecimal("5.5")).status(), "reversed");
        final JsonNode data = lastData();
        assertEquals(data.get("action").asText(), "refund");
        assertEquals(data.get("amount").asText(), "5.50");
    }

    @Test
    public void payTokenSendsTheTokenAndTheIp() throws Exception {
        answer("{\"result\":\"ok\",\"status\":\"success\"}");
        client.payToken(config, "o-3", new BigDecimal("35"), "USD", CARD_TOKEN, Map.of("description", "Renewal", "ip", "203.0.113.20"));
        final JsonNode data = lastData();
        assertEquals(data.get("action").asText(), "paytoken");
        assertEquals(data.get("card_token").asText(), CARD_TOKEN);
        assertEquals(data.get("ip").asText(), "203.0.113.20");
        assertEquals(data.get("amount").asText(), "35.00");
        assertEquals(data.get("currency").asText(), "USD");
    }

    @Test
    public void holdTokenSendsHold() throws Exception {
        answer("{\"result\":\"ok\",\"status\":\"hold_wait\"}");
        client.holdToken(config, "o-4", new BigDecimal("1.00"), "UAH", CARD_TOKEN, Map.of("description", "Verify", "ip", "203.0.113.20"));
        assertEquals(lastData().get("action").asText(), "hold");
    }

    @Test
    public void holdCompletionSendsHoldCompletion() throws Exception {
        answer("{\"result\":\"ok\",\"status\":\"success\"}");
        client.holdCompletion(config, "o-5", new BigDecimal("45"));
        final JsonNode data = lastData();
        assertEquals(data.get("action").asText(), "hold_completion");
        assertEquals(data.get("amount").asText(), "45.00");
    }

    @Test
    public void releaseHoldSendsRefund() throws Exception {
        answer("{\"result\":\"ok\",\"status\":\"reversed\"}");
        client.releaseHold(config, "o-6", new BigDecimal("1"));
        assertEquals(lastData().get("action").asText(), "refund");
    }

    @Test
    public void aServerErrorIsTransient() {
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(500)));
        assertThrows(LiqPayTransientException.class, () -> client.status(config, "o-7"));
    }

    @Test
    public void aTimeoutIsTransient() {
        server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));
        assertThrows(LiqPayTransientException.class, () -> client.status(config, "o-8"));
    }

    @Test
    public void anAnswerThatIsNotJsonIsTransient() {
        answer("<html>maintenance</html>");
        assertThrows(LiqPayTransientException.class, () -> client.status(config, "o-9"));
    }

    @Test
    public void anErrorAnswerReturnsTheErrCode() throws Exception {
        answer("{\"result\":\"error\",\"status\":\"error\",\"err_code\":\"payment_not_found\",\"err_description\":\"Payment is not found\"}");
        final LiqPayResult r = client.status(config, "o-10");
        assertTrue(r.isError());
        assertEquals(r.errCode(), "payment_not_found");
        assertNull(r.paymentId());
    }

    @Test
    public void theLogHoldsNoSecret() throws Exception {
        answer("{\"result\":\"ok\",\"status\":\"success\",\"card_token\":\"" + CARD_TOKEN + "\"}");
        final String log;
        try (LogCapture capture = new LogCapture()) {
            client.payToken(config, "o-11", new BigDecimal("1"), "USD", CARD_TOKEN, Map.of("description", "Renewal", "ip", "203.0.113.20"));
            server.stubFor(post(urlEqualTo("/api/request")).willReturn(aResponse().withStatus(503)));
            assertThrows(LiqPayTransientException.class, () -> client.status(config, "o-12"));
            log = capture.text();
        }
        assertTrue(log.contains("order_id=o-11"), "the log names the order id");
        assertFalse(log.contains(CARD_TOKEN));
        assertFalse(log.contains(PRIVATE_KEY));
        assertFalse(log.contains("signature="));
        assertFalse(log.contains("data="));
    }
}
