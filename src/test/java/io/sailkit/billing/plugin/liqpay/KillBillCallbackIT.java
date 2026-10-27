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

package io.sailkit.billing.plugin.liqpay;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Kill Bill 0.24.22 with the plugin bundle: a signed callback settles a PENDING purchase. Needs Docker. */
@Test(groups = "killbill")
public class KillBillCallbackIT {

    private static final String KILLBILL_IMAGE = "killbill/killbill:0.24.22@sha256:e135615bd14f8ff52d258a4b6a936b4cd0276e30a874a7e2c4690f7a1e28f7f1";
    private static final String DB_IMAGE = "killbill/postgresql:0.24@sha256:7f42ffe26b89b1ff48e841b2691dbd45d7c530fb9bbf5eed0449b54450e7e519";
    private static final String BUNDLE_PATH = "/var/lib/killbill/bundles/plugins/java/killbill-liqpay/2.0.0/liqpay-plugin-2.0.0.jar";
    private static final String PLUGIN_USER = "liqpay-plugin";
    private static final String PLUGIN_ROLE = "liqpay_plugin";
    private static final String PUBLIC_KEY = "sandbox_it_public";
    private static final String RESULT_URL = "https://my.example.com/checkout/return";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String dbPassword = random();
    private final String privateKey = random();
    private final String pluginPassword = random();
    private final String apiKey = "liqpay-it";
    private final String apiSecret = random();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Network network;
    private GenericContainer<?> db;
    private GenericContainer<?> killbill;
    private String baseUrl;

    @BeforeClass
    public void startKillBill() throws Exception {
        final File bundle = new File(System.getProperty("bundle.jar", "target/liqpay-plugin.jar"));
        assertTrue(bundle.isFile(), "the bundle jar exists: " + bundle);
        network = Network.newNetwork();
        db = new GenericContainer<>(DockerImageName.parse(DB_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("db")
                .withEnv("POSTGRES_PASSWORD", dbPassword)
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                                .withStartupTimeout(Duration.ofMinutes(3)));
        db.start();
        applyPluginDdl();
        final String jdbc = "jdbc:postgresql://db:5432/killbill";
        killbill = new GenericContainer<>(DockerImageName.parse(KILLBILL_IMAGE))
                .withNetwork(network)
                .withEnv("KILLBILL_DAO_URL", jdbc)
                .withEnv("KILLBILL_DAO_USER", "postgres")
                .withEnv("KILLBILL_DAO_PASSWORD", dbPassword)
                .withEnv("KB_org_killbill_billing_osgi_dao_url", jdbc)
                .withEnv("KB_org_killbill_billing_osgi_dao_user", "postgres")
                .withEnv("KB_org_killbill_billing_osgi_dao_password", dbPassword)
                .withEnv("KB_org_killbill_server_updateCheck_skip", "true")
                .withEnv("LP_IT_PRIVATE", privateKey)
                .withEnv("LP_IT_KB_PASSWORD", pluginPassword)
                .withCopyFileToContainer(MountableFile.forHostPath(bundle.getAbsolutePath(), 0644), BUNDLE_PATH)
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/1.0/healthcheck").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(8)));
        killbill.start();
        baseUrl = "http://" + killbill.getHost() + ":" + killbill.getMappedPort(8080);
        final Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (send(HttpRequest.newBuilder(URI.create(baseUrl + "/plugins/killbill-liqpay/callback")).GET()).statusCode() == 404) {
            assertTrue(Instant.now().isBefore(deadline), "the plugin servlet starts\n" + logTail());
            Thread.sleep(2000);
        }
    }

    @AfterClass(alwaysRun = true)
    public void stopKillBill() {
        if (killbill != null) {
            killbill.stop();
        }
        if (db != null) {
            db.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    public void aSignedCallbackSettlesThePurchaseAsThePluginUser() throws Exception {
        final String tenantId = lastSegment(expect(admin("POST", "/1.0/kb/tenants", json(Map.of("apiKey", apiKey, "apiSecret", apiSecret))), 201));
        expect(admin("POST", "/1.0/kb/security/roles",
                      json(Map.of("role", PLUGIN_ROLE, "permissions", new String[]{"payment:transition"}))), 201);
        expect(admin("POST", "/1.0/kb/security/users",
                      json(Map.of("username", PLUGIN_USER, "password", pluginPassword, "roles", new String[]{PLUGIN_ROLE}))), 201);
        uploadConfig(false);

        final String accountId = lastSegment(expect(tenant("POST", "/1.0/kb/accounts", json(Map.of("name", "Callback IT", "currency", "USD"))), 201));
        expect(tenant("POST", "/1.0/kb/accounts/" + accountId + "/paymentMethods?isDefault=true",
                      json(Map.of("pluginName", LiqPayActivator.PLUGIN_NAME,
                                  "pluginInfo", Map.of("properties", new Object[]{Map.of("key", "liqpay_mode", "value", "checkout")})))), 201);
        final String paymentPath = URI.create(expect(tenant("POST", "/1.0/kb/accounts/" + accountId + "/payments?pluginProperty="
                                                                    + URLEncoder.encode("liqpay_result_url=" + RESULT_URL, StandardCharsets.UTF_8),
                                                            json(Map.of("transactionType", "PURCHASE", "amount", 10, "currency", "USD"))), 201))
                                      .getPath();
        assertEquals(transactionStatus(paymentPath), "PENDING");
        final String paymentId = lastSegment(paymentPath);
        final String orderId = orderId(paymentId);
        final String callback = signedCallback(orderId);

        // Without a plugin user the notify is anonymous, Kill Bill refuses it, and the row waits for a retry.
        assertEquals(postCallback(tenantId, callback), 503, logTail());
        assertTrue(logTail().contains("liqpay_notify_failed order_id=" + orderId + " error=UnauthenticatedException"), logTail());
        assertEquals(transactionStatus(paymentPath), "PENDING");
        assertEquals(pluginStatus(paymentId), "PROCESSED");

        uploadConfig(true);
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        int code = postCallback(tenantId, callback);
        while (code != 200 && Instant.now().isBefore(deadline)) {
            Thread.sleep(2000);
            code = postCallback(tenantId, callback);
        }
        assertEquals(code, 200, logTail());
        assertEquals(transactionStatus(paymentPath), "SUCCESS", logTail());
        assertEquals(postCallback(tenantId, callback), 200, "a replay after the settlement");
    }

    // --- Kill Bill calls ---

    private void uploadConfig(final boolean withPluginUser) throws Exception {
        final Map<String, String> p = new LinkedHashMap<>();
        p.put("publicKey", PUBLIC_KEY);
        p.put("privateKeyEnv", "LP_IT_PRIVATE");
        p.put("sandbox", "true");
        p.put("serverUrl", "https://pay.example.com/plugins/killbill-liqpay/callback");
        p.put("resultUrlAllowlist", RESULT_URL);
        p.put("currencies", "USD");
        // The status polling of the Janitor fails fast and changes nothing.
        p.put("apiBaseUrl", "http://localhost:9");
        if (withPluginUser) {
            p.put("killbillUser", PLUGIN_USER);
            p.put("killbillPasswordEnv", "LP_IT_KB_PASSWORD");
        }
        final String body = p.entrySet().stream().map(e -> PREFIX + e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
        expect(tenantRequest("POST", "/1.0/kb/tenants/uploadPluginConfig/" + LiqPayActivator.PLUGIN_NAME, body, "text/plain"), 201);
    }

    private String transactionStatus(final String paymentPath) throws Exception {
        final HttpResponse<String> r = send(tenantRequest("GET", paymentPath, null, null));
        assertEquals(r.statusCode(), 200, r.body());
        return JSON.readTree(r.body()).get("transactions").get(0).get("status").asText();
    }

    private int postCallback(final String tenantId, final String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/plugins/killbill-liqpay/callback?kbTenantId=" + tenantId))
                               .header("Content-Type", "application/x-www-form-urlencoded")
                               .POST(HttpRequest.BodyPublishers.ofString(body))).statusCode();
    }

    private String signedCallback(final String orderId) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", LiqPayClient.API_VERSION);
        m.put("public_key", PUBLIC_KEY);
        m.put("action", "pay");
        m.put("status", "success");
        m.put("order_id", orderId);
        m.put("payment_id", 4455);
        m.put("amount", 10);
        m.put("currency", "USD");
        m.put("end_date", System.currentTimeMillis());
        final String data = LiqPayEnvelope.encode(m);
        return "data=" + URLEncoder.encode(data, StandardCharsets.UTF_8)
               + "&signature=" + URLEncoder.encode(LiqPaySigner.sign(privateKey, data), StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder admin(final String method, final String path, final String body) {
        return request(method, path, body, "application/json");
    }

    private HttpRequest.Builder tenant(final String method, final String path, final String body) {
        return tenantRequest(method, path, body, "application/json");
    }

    private HttpRequest.Builder tenantRequest(final String method, final String path, final String body, final String contentType) {
        return request(method, path, body, contentType).header("X-Killbill-ApiKey", apiKey).header("X-Killbill-ApiSecret", apiSecret);
    }

    private HttpRequest.Builder request(final String method, final String path, final String body, final String contentType) {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                                                 .timeout(Duration.ofSeconds(60))
                                                 .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                                                         "admin:password".getBytes(StandardCharsets.UTF_8)))
                                                 .header("X-Killbill-CreatedBy", "liqpay-it")
                                                 .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                                                              : HttpRequest.BodyPublishers.ofString(body));
        return contentType == null ? b : b.header("Content-Type", contentType);
    }

    private HttpResponse<String> send(final HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends the request, checks the code, and returns the Location header. */
    private String expect(final HttpRequest.Builder b, final int code) throws Exception {
        final HttpResponse<String> r = send(b);
        assertEquals(r.statusCode(), code, r.request().uri().getPath() + ": " + r.body() + "\n" + logTail());
        return r.headers().firstValue("Location").orElse("");
    }

    // --- Database ---

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/killbill",
                                           "postgres", dbPassword);
    }

    private void applyPluginDdl() throws Exception {
        final String ddl;
        try (InputStream in = getClass().getResourceAsStream("/ddl-postgresql.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (final String sql : Arrays.stream(ddl.split(";"))
                                          .map(s -> s.lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n")).trim())
                                          .filter(s -> !s.isEmpty())
                                          .toArray(String[]::new)) {
                st.execute(sql);
            }
        }
    }

    private String orderId(final String paymentId) throws Exception {
        return column(paymentId, "order_id");
    }

    private String pluginStatus(final String paymentId) throws Exception {
        return column(paymentId, "plugin_status");
    }

    private String column(final String paymentId, final String column) throws Exception {
        try (Connection conn = connect();
             PreparedStatement st = conn.prepareStatement("select order_id, plugin_status from liqpay_transactions where kb_payment_id = ?")) {
            st.setString(1, paymentId);
            try (ResultSet rs = st.executeQuery()) {
                assertTrue(rs.next(), "the plugin stored the purchase\n" + logTail());
                return rs.getString(column).trim();
            }
        }
    }

    // --- Helpers ---

    private static String json(final Object value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    private static String lastSegment(final String uri) {
        final String path = URI.create(uri).getPath().replaceAll("/+$", "");
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String random() {
        final byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The plugin and error lines of the Kill Bill log, for a failed assertion. */
    private String logTail() {
        if (killbill == null) {
            return "";
        }
        final String[] lines = killbill.getLogs().lines()
                                       .filter(l -> l.contains("liqpay") || l.contains("ERROR") || l.contains("Exception"))
                                       .toArray(String[]::new);
        return String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 40), lines.length));
    }
}
