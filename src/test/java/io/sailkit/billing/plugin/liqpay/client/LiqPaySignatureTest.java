/*
 * Copyright 2024 Sailkit.io
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
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LiqPaySignature.
 */
class LiqPaySignatureTest {

    private static final String TEST_PRIVATE_KEY = "test_private_key_12345";
    private static final String TEST_PUBLIC_KEY = "sandbox_test_public_key";

    @Test
    void testCreateSignature() {
        // Given
        String data = "eyJ2ZXJzaW9uIjozLCJwdWJsaWNfa2V5IjoicHVibGljX2tleSIsImFjdGlvbiI6InBheSJ9";

        // When
        String signature = LiqPaySignature.createSignature(TEST_PRIVATE_KEY, data);

        // Then
        assertNotNull(signature);
        assertFalse(signature.isEmpty());
        // Signature should be base64 encoded
        assertTrue(signature.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    void testCreateSignatureConsistency() {
        // Given
        String data = "eyJ2ZXJzaW9uIjozfQ==";

        // When
        String signature1 = LiqPaySignature.createSignature(TEST_PRIVATE_KEY, data);
        String signature2 = LiqPaySignature.createSignature(TEST_PRIVATE_KEY, data);

        // Then
        assertEquals(signature1, signature2, "Signature should be deterministic");
    }

    @Test
    void testVerifySignature() {
        // Given
        String data = "eyJ2ZXJzaW9uIjozfQ==";
        String signature = LiqPaySignature.createSignature(TEST_PRIVATE_KEY, data);

        // When & Then
        assertTrue(LiqPaySignature.verifySignature(TEST_PRIVATE_KEY, data, signature));
    }

    @Test
    void testVerifySignatureInvalid() {
        // Given
        String data = "eyJ2ZXJzaW9uIjozfQ==";
        String wrongSignature = "invalid_signature";

        // When & Then
        assertFalse(LiqPaySignature.verifySignature(TEST_PRIVATE_KEY, data, wrongSignature));
    }

    @Test
    void testVerifySignatureWrongKey() {
        // Given
        String data = "eyJ2ZXJzaW9uIjozfQ==";
        String signature = LiqPaySignature.createSignature(TEST_PRIVATE_KEY, data);
        String wrongKey = "wrong_private_key";

        // When & Then
        assertFalse(LiqPaySignature.verifySignature(wrongKey, data, signature));
    }

    @Test
    void testEncodeData() {
        // Given
        Map<String, Object> params = new HashMap<>();
        params.put("version", 7);
        params.put("public_key", TEST_PUBLIC_KEY);
        params.put("action", "pay");
        params.put("amount", new BigDecimal("100.50"));
        params.put("currency", "UAH");

        // When
        String encoded = LiqPaySignature.encodeData(params);

        // Then
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
        // Should be base64 encoded
        assertTrue(encoded.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    void testDecodeData() {
        // Given
        Map<String, Object> original = new HashMap<>();
        original.put("version", 7);
        original.put("public_key", TEST_PUBLIC_KEY);
        original.put("action", "pay");
        original.put("amount", 100);
        original.put("currency", "UAH");

        String encoded = LiqPaySignature.encodeData(original);

        // When
        Map<String, Object> decoded = LiqPaySignature.decodeData(encoded);

        // Then
        assertNotNull(decoded);
        assertEquals(7, decoded.get("version"));
        assertEquals(TEST_PUBLIC_KEY, decoded.get("public_key"));
        assertEquals("pay", decoded.get("action"));
        assertEquals(100, decoded.get("amount"));
        assertEquals("UAH", decoded.get("currency"));
    }

    @Test
    void testEncodeDecodeRoundTrip() {
        // Given
        Map<String, Object> original = new HashMap<>();
        original.put("order_id", "test-order-123");
        original.put("status", "success");
        original.put("card_token", "ABC123DEF456");

        // When
        String encoded = LiqPaySignature.encodeData(original);
        Map<String, Object> decoded = LiqPaySignature.decodeData(encoded);

        // Then
        assertEquals(original.get("order_id"), decoded.get("order_id"));
        assertEquals(original.get("status"), decoded.get("status"));
        assertEquals(original.get("card_token"), decoded.get("card_token"));
    }

    @Test
    void testDecodeToJson() {
        // Given
        String json = "{\"test\":\"value\"}";
        String encoded = LiqPaySignature.encodeJson(json);

        // When
        String decoded = LiqPaySignature.decodeToJson(encoded);

        // Then
        assertEquals(json, decoded);
    }

    @Test
    void testCreateSignatureNullPrivateKey() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            LiqPaySignature.createSignature(null, "data");
        });
    }

    @Test
    void testCreateSignatureEmptyPrivateKey() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            LiqPaySignature.createSignature("", "data");
        });
    }

    @Test
    void testCreateSignatureNullData() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            LiqPaySignature.createSignature(TEST_PRIVATE_KEY, null);
        });
    }

    @Test
    void testVerifySignatureNullSignature() {
        // When & Then
        assertFalse(LiqPaySignature.verifySignature(TEST_PRIVATE_KEY, "data", null));
    }

    @Test
    void testVerifySignatureEmptySignature() {
        // When & Then
        assertFalse(LiqPaySignature.verifySignature(TEST_PRIVATE_KEY, "data", ""));
    }
}
