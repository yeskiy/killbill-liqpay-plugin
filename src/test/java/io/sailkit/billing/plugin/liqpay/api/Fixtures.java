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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfigurationHandler;
import io.sailkit.billing.plugin.liqpay.config.LiqPayNotConfiguredException;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import org.joda.time.DateTime;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.plugin.api.PluginCallContext;
import org.killbill.billing.util.callcontext.CallContext;

import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public final class Fixtures {

    public static final String PRIVATE_KEY = "sandbox_private_value_for_api_test";
    public static final String PUBLIC_KEY = "sandbox_i111";
    public static final String RESULT_URL = "https://my.example.com/checkout/return?checkout=c1";
    public static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    public static final String CARD_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    private Fixtures() {}

    public static Properties properties(final String apiBaseUrl) {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "publicKey", PUBLIC_KEY);
        p.setProperty(PREFIX + "privateKeyEnv", "LP_PRIVATE");
        p.setProperty(PREFIX + "serverUrl", "https://pay.example.com/plugins/killbill-liqpay/callback");
        p.setProperty(PREFIX + "resultUrlAllowlist", "https://my.example.com/checkout/return");
        p.setProperty(PREFIX + "apiBaseUrl", apiBaseUrl);
        p.setProperty(PREFIX + "requestTimeout", "PT10S");
        p.setProperty(PREFIX + "connectTimeout", "PT10S");
        return p;
    }

    public static LiqPayConfig config(final Properties p) {
        return LiqPayConfig.parse(p, Map.of("LP_PRIVATE", PRIVATE_KEY)::get);
    }

    public static LiqPayConfig config(final String apiBaseUrl) {
        return config(properties(apiBaseUrl));
    }

    /** A config for the token flows: the card token key comes from env var LP_CARD. */
    public static LiqPayConfig tokenConfig(final String apiBaseUrl) {
        final Properties p = properties(apiBaseUrl);
        p.setProperty(PREFIX + "cardTokenKeyEnv", "LP_CARD");
        return LiqPayConfig.parse(p, Map.of("LP_PRIVATE", PRIVATE_KEY, "LP_CARD", CARD_KEY)::get);
    }

    /** A handler that knows one tenant. Every other tenant is not configured. */
    public static LiqPayConfigurationHandler handler(final UUID tenant, final LiqPayConfig c) throws LiqPayNotConfiguredException {
        final LiqPayConfigurationHandler h = mock(LiqPayConfigurationHandler.class);
        doThrow(new LiqPayNotConfiguredException(null)).when(h).get(any());
        doReturn(c).when(h).get(eq(tenant));
        return h;
    }

    public static CallContext context(final UUID account, final UUID tenant) {
        return new PluginCallContext("test", new DateTime(NOW.toEpochMilli()), account, tenant);
    }

    public static PluginProperty prop(final String key, final String value) {
        return new PluginProperty(key, value, false);
    }

    public static Map<String, Object> props(final PaymentTransactionInfoPlugin info) {
        return info.getProperties().stream()
                   .collect(Collectors.toMap(PluginProperty::getKey, PluginProperty::getValue, (a, b) -> b));
    }

    public static JsonNode data(final PaymentTransactionInfoPlugin info) {
        return LiqPayEnvelope.decode((String) props(info).get("liqpay_data"));
    }

    public static LiqPayResult result(final String json) {
        return LiqPayResult.from(LiqPayEnvelope.parseJson(json.getBytes(StandardCharsets.UTF_8)));
    }

    public static List<String> keys(final PaymentTransactionInfoPlugin info) {
        return info.getProperties().stream().map(PluginProperty::getKey).collect(Collectors.toList());
    }

    /** A clock that a test can move forward. */
    public static final class MutableClock extends Clock {

        private Instant now = NOW;

        public void advance(final Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
