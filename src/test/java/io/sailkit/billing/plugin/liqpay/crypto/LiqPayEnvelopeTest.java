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

package io.sailkit.billing.plugin.liqpay.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

public class LiqPayEnvelopeTest {

    private static String b64(final String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String nested(final int depth) {
        return "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }

    @Test
    public void decodesTheDocVector() {
        final JsonNode n = LiqPayEnvelope.decode(LiqPaySignerTest.DOC_DATA);
        assertEquals(n.get("version").asInt(), 7);
        assertEquals(n.get("action").asText(), "pay");
        assertEquals(n.get("order_id").asText(), "000001");
    }

    @Test
    public void encodesAndDecodes() {
        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", 7);
        p.put("description", "a \"quoted\" text");
        final JsonNode n = LiqPayEnvelope.decode(LiqPayEnvelope.encode(p));
        assertEquals(n.get("description").asText(), "a \"quoted\" text");
    }

    @Test
    public void acceptsNestingOfTen() {
        assertFalse(LiqPayEnvelope.decode(b64(nested(10))).isMissingNode());
    }

    @Test
    public void rejectsNestingDeeperThanTen() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayEnvelope.decode(b64(nested(11))));
    }

    @Test
    public void rejectsAStringLongerThan4096() {
        assertThrows(IllegalArgumentException.class,
                     () -> LiqPayEnvelope.decode(b64("{\"a\":\"" + "x".repeat(4097) + "\"}")));
    }

    @Test
    public void rejectsInputThatIsNotBase64() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayEnvelope.decode("not base64 !!"));
    }

    @Test
    public void rejectsJsonThatIsNotAnObject() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayEnvelope.decode(b64("[1,2]")));
        assertThrows(IllegalArgumentException.class, () -> LiqPayEnvelope.decode(b64("{\"a\":")));
    }

    @Test
    public void errorMessageNeverHoldsTheInput() {
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                                                        () -> LiqPayEnvelope.decode("secret-value-123"));
        assertFalse(e.getMessage().contains("secret-value-123"));
    }
}
