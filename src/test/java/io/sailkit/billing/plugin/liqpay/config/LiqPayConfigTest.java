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

package io.sailkit.billing.plugin.liqpay.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import org.testng.annotations.Test;

import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.assertTrue;

public class LiqPayConfigTest {

    static final String PRIVATE_KEY = "sandbox_test_private_value_1";
    static final String CARD_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final Function<String, String> ENV = Map.of("LP_PRIVATE", PRIVATE_KEY, "LP_CARD", CARD_KEY)::get;

    static Properties base() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "publicKey", "sandbox_i000");
        p.setProperty(PREFIX + "privateKeyEnv", "LP_PRIVATE");
        p.setProperty(PREFIX + "serverUrl", "https://pay.example.com/plugins/killbill-liqpay/callback");
        p.setProperty(PREFIX + "resultUrlAllowlist", "https://my.example.com/checkout/return, https://shop.example.com/");
        return p;
    }

    private static Properties with(final String key, final String value) {
        final Properties p = base();
        p.setProperty(PREFIX + key, value);
        return p;
    }

    @Test
    public void appliesTheDefaults() {
        final LiqPayConfig c = LiqPayConfig.parse(base(), ENV);
        assertEquals(c.privateKey(), PRIVATE_KEY);
        assertEquals(c.cardTokenKeyId(), "1");
        assertFalse(c.sandbox());
        assertFalse(c.hasCardTokenKey());
        assertEquals(c.currencies(), Set.of("USD"));
        assertEquals(c.languages(), Set.of("en", "uk"));
        assertEquals(c.language(), "en");
        assertEquals(c.description(), "Payment");
        assertEquals(c.checkoutTtl(), Duration.ofMinutes(30));
        assertEquals(c.expiryGrace(), Duration.ofMinutes(15));
        assertEquals(c.refundGrace(), Duration.ofHours(24));
        assertEquals(c.verificationAmount(), new BigDecimal("1.00"));
        assertEquals(c.verificationCurrency(), "UAH");
        assertEquals(c.apiBaseUrl().toString(), "https://www.liqpay.ua");
        assertEquals(c.connectTimeout(), Duration.ofSeconds(10));
        assertEquals(c.requestTimeout(), Duration.ofSeconds(30));
    }

    @Test
    public void rejectsALiteralPrivateKey() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("privateKey", "abc"), ENV));
    }

    @Test
    public void rejectsALiteralCardTokenKey() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("cardTokenKey", CARD_KEY), ENV));
    }

    @Test
    public void rejectsAMissingPrivateKeyEnvVar() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("privateKeyEnv", "LP_ABSENT"), ENV));
    }

    @Test
    public void rejectsAMissingPublicKey() {
        final Properties p = base();
        p.remove(PREFIX + "publicKey");
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(p, ENV));
    }

    @Test
    public void rejectsAnHttpServerUrl() {
        assertThrows(IllegalArgumentException.class,
                     () -> LiqPayConfig.parse(with("serverUrl", "http://pay.example.com/callback"), ENV));
    }

    @Test
    public void rejectsAnHttpAllowlistEntry() {
        assertThrows(IllegalArgumentException.class,
                     () -> LiqPayConfig.parse(with("resultUrlAllowlist", "http://my.example.com/"), ENV));
    }

    @Test
    public void acceptsALocalHttpApiBaseUrl() {
        assertEquals(LiqPayConfig.parse(with("apiBaseUrl", "http://localhost:8089"), ENV).apiBaseUrl().toString(),
                     "http://localhost:8089");
        assertEquals(LiqPayConfig.parse(with("apiBaseUrl", "http://127.0.0.1:8089"), ENV).apiBaseUrl().getPort(), 8089);
    }

    @Test
    public void rejectsARemoteHttpApiBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                     () -> LiqPayConfig.parse(with("apiBaseUrl", "http://liqpay.example.com"), ENV));
    }

    @Test
    public void readsTheCardTokenKeyFromEnv() {
        final LiqPayConfig c = LiqPayConfig.parse(with("cardTokenKeyEnv", "LP_CARD"), ENV);
        assertTrue(c.hasCardTokenKey());
        assertEquals(c.cardTokenKey().length, 32);
    }

    @Test
    public void readsTheCardTokenKeyFromAFile() throws Exception {
        final Path file = Files.createTempFile("card-token", ".key");
        try {
            Files.writeString(file, CARD_KEY + "\n", StandardCharsets.UTF_8);
            final LiqPayConfig c = LiqPayConfig.parse(with("cardTokenKeyFile", file.toAbsolutePath().toString()), ENV);
            assertTrue(c.hasCardTokenKey());
            assertEquals(c.cardTokenKey().length, 32);
            assertFalse(c.toString().contains(CARD_KEY));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void rejectsABadCardTokenKeyFile() throws Exception {
        final Path file = Files.createTempFile("card-token", ".key");
        try {
            Files.writeString(file, Base64.getEncoder().encodeToString(new byte[16]), StandardCharsets.UTF_8);
            final IllegalArgumentException shortKey = expectThrows(
                    IllegalArgumentException.class, () -> LiqPayConfig.parse(with("cardTokenKeyFile", file.toAbsolutePath().toString()), ENV));
            assertEquals(shortKey.getMessage(), "cardTokenKeyFile must hold a 32-byte key");
            Files.writeString(file, "x".repeat(2000), StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class,
                         () -> LiqPayConfig.parse(with("cardTokenKeyFile", file.toAbsolutePath().toString()), ENV));
        } finally {
            Files.delete(file);
        }
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("cardTokenKeyFile", "relative/card.key"), ENV));
        assertThrows(IllegalArgumentException.class,
                     () -> LiqPayConfig.parse(with("cardTokenKeyFile", file.toAbsolutePath().toString()), ENV));
    }

    @Test
    public void rejectsBothCardTokenKeySources() throws Exception {
        final Path file = Files.createTempFile("card-token", ".key");
        try {
            Files.writeString(file, CARD_KEY, StandardCharsets.UTF_8);
            final Properties p = with("cardTokenKeyEnv", "LP_CARD");
            p.setProperty(PREFIX + "cardTokenKeyFile", file.toAbsolutePath().toString());
            assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(p, ENV));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void rejectsAShortCardTokenKey() {
        final Function<String, String> env = Map.of("LP_PRIVATE", PRIVATE_KEY,
                                                    "LP_CARD", Base64.getEncoder().encodeToString(new byte[16]))::get;
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("cardTokenKeyEnv", "LP_CARD"), env));
    }

    // --- Kill Bill login ---

    @Test
    public void theKillBillLoginIsOptional() {
        final LiqPayConfig c = LiqPayConfig.parse(base(), ENV);
        assertFalse(c.hasKillbillLogin());
        assertEquals(c.killbillUser(), null);
        assertEquals(c.killbillPassword(), null);
    }

    @Test
    public void theKillBillPasswordComesFromAnEnvVar() {
        final Properties p = with("killbillUser", "liqpay-plugin");
        p.setProperty(PREFIX + "killbillPasswordEnv", "LP_KB");
        final LiqPayConfig c = LiqPayConfig.parse(p, Map.of("LP_PRIVATE", PRIVATE_KEY, "LP_KB", "kb-plugin-password")::get);
        assertTrue(c.hasKillbillLogin());
        assertEquals(c.killbillUser(), "liqpay-plugin");
        assertEquals(c.killbillPassword(), "kb-plugin-password");
        assertFalse(c.toString().contains("kb-plugin-password"));
        assertTrue(c.toString().contains("killbillUser=liqpay-plugin"));
    }

    @Test
    public void theKillBillPasswordComesFromAFile() throws Exception {
        final Path file = Files.createTempFile("kb-plugin", ".password");
        try {
            Files.writeString(file, "kb-file-password\n",StandardCharsets.UTF_8);
            final Properties p = with("killbillUser", "liqpay-plugin");
            p.setProperty(PREFIX + "killbillPasswordFile", file.toAbsolutePath().toString());
            assertEquals(LiqPayConfig.parse(p, ENV).killbillPassword(), "kb-file-password");
            p.setProperty(PREFIX + "killbillPasswordEnv", "LP_PRIVATE");
            assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(p, ENV));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void rejectsAKillBillUserWithoutAPassword() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("killbillUser", "liqpay-plugin"), ENV));
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("killbillPasswordEnv", "LP_PRIVATE"), ENV));
    }

    @Test
    public void rejectsALiteralKillBillPassword() {
        final Properties p = with("killbillUser", "liqpay-plugin");
        p.setProperty(PREFIX + "killbillPassword", "kb-literal-value");
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(p, ENV));
        assertFalse(e.getMessage().contains("kb-literal-value"));
    }

    @Test
    public void rejectsADefaultLanguageOutsideTheList() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayConfig.parse(with("language", "de"), ENV));
    }

    @Test
    public void toStringHoldsNoKeyValue() {
        final String s = LiqPayConfig.parse(with("cardTokenKeyEnv", "LP_CARD"), ENV).toString();
        assertFalse(s.contains(PRIVATE_KEY));
        assertFalse(s.contains(CARD_KEY));
    }

    @Test
    public void errorMessagesHoldNoValue() {
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                                                        () -> LiqPayConfig.parse(with("privateKey", PRIVATE_KEY), ENV));
        assertFalse(e.getMessage().contains(PRIVATE_KEY));
    }

    @Test
    public void allowlistMatchesAtAPathBoundary() {
        final LiqPayConfig c = LiqPayConfig.parse(base(), ENV);
        assertTrue(c.isResultUrlAllowed("https://my.example.com/checkout/return"));
        assertTrue(c.isResultUrlAllowed("https://my.example.com/checkout/return?checkout=7f0c"));
        assertTrue(c.isResultUrlAllowed("https://shop.example.com/orders/1"));
        assertFalse(c.isResultUrlAllowed("https://my.example.com/checkout/returnx"));
        assertFalse(c.isResultUrlAllowed("https://my.example.com.evil.test/checkout/return"));
        assertFalse(c.isResultUrlAllowed("http://my.example.com/checkout/return"));
        assertFalse(c.isResultUrlAllowed("https://user@my.example.com/checkout/return"));
        assertFalse(c.isResultUrlAllowed(null));
    }
}
