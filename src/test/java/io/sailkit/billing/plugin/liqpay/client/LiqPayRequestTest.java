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
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LiqPayRequest builder.
 */
class LiqPayRequestTest {

    private static final String TEST_PUBLIC_KEY = "sandbox_test_public_key";
    private static final String TEST_ORDER_ID = "test-order-123";

    @Test
    void testPayRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.pay(
                TEST_PUBLIC_KEY,
                TEST_ORDER_ID,
                new BigDecimal("100.50"),
                "UAH",
                "Test payment"
        ).build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals(7, params.get("version"));
        assertEquals(TEST_PUBLIC_KEY, params.get("public_key"));
        assertEquals("pay", params.get("action"));
        assertEquals(TEST_ORDER_ID, params.get("order_id"));
        assertEquals(new BigDecimal("100.50"), params.get("amount"));
        assertEquals("UAH", params.get("currency"));
        assertEquals("Test payment", params.get("description"));
    }

    @Test
    void testPayTokenRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.payToken(
                TEST_PUBLIC_KEY,
                TEST_ORDER_ID,
                "test_card_token_123",
                new BigDecimal("50.00"),
                "USD",
                "Token payment"
        ).build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("paytoken", params.get("action"));
        assertEquals("test_card_token_123", params.get("card_token"));
        assertEquals(new BigDecimal("50.00"), params.get("amount"));
    }

    @Test
    void testHoldRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.hold(
                TEST_PUBLIC_KEY,
                TEST_ORDER_ID,
                new BigDecimal("200.00"),
                "EUR",
                "Hold payment"
        ).build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("hold", params.get("action"));
        assertEquals(new BigDecimal("200.00"), params.get("amount"));
        assertEquals("EUR", params.get("currency"));
    }

    @Test
    void testHoldCompletionRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.holdCompletion(
                TEST_PUBLIC_KEY,
                TEST_ORDER_ID,
                new BigDecimal("150.00")
        ).build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("hold_completion", params.get("action"));
        assertEquals(new BigDecimal("150.00"), params.get("amount"));
    }

    @Test
    void testUnholdRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.unhold(TEST_PUBLIC_KEY, TEST_ORDER_ID)
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("unhold", params.get("action"));
        assertEquals(TEST_ORDER_ID, params.get("order_id"));
    }

    @Test
    void testRefundRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.refund(
                TEST_PUBLIC_KEY,
                TEST_ORDER_ID,
                new BigDecimal("75.00")
        ).build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("refund", params.get("action"));
        assertEquals(new BigDecimal("75.00"), params.get("amount"));
    }

    @Test
    void testStatusRequestBuilder() {
        // When
        LiqPayRequest request = LiqPayRequest.status(TEST_PUBLIC_KEY, TEST_ORDER_ID)
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("status", params.get("action"));
        assertEquals(TEST_ORDER_ID, params.get("order_id"));
    }

    @Test
    void testBuilderWithRecurringByToken() {
        // When
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(TEST_PUBLIC_KEY)
                .action("pay")
                .orderId(TEST_ORDER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("UAH")
                .description("Test")
                .recurringByToken(true)
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("1", params.get("recurringbytoken"));
    }

    @Test
    void testBuilderWithServerUrl() {
        // When
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(TEST_PUBLIC_KEY)
                .action("pay")
                .orderId(TEST_ORDER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("UAH")
                .serverUrl("https://example.com/callback")
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("https://example.com/callback", params.get("server_url"));
    }

    @Test
    void testBuilderWithResultUrl() {
        // When
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(TEST_PUBLIC_KEY)
                .action("pay")
                .orderId(TEST_ORDER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("UAH")
                .resultUrl("https://example.com/result")
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("https://example.com/result", params.get("result_url"));
    }

    @Test
    void testBuilderWithLanguage() {
        // When
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(TEST_PUBLIC_KEY)
                .action("pay")
                .orderId(TEST_ORDER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("UAH")
                .language("uk")
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("uk", params.get("language"));
    }

    @Test
    void testBuilderWithSandbox() {
        // When
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(TEST_PUBLIC_KEY)
                .action("pay")
                .orderId(TEST_ORDER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("UAH")
                .sandbox(true)
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals(1, params.get("sandbox"));
    }

    @Test
    void testBuilderWithCustomParam() {
        // When
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(TEST_PUBLIC_KEY)
                .action("pay")
                .orderId(TEST_ORDER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("UAH")
                .param("custom_field", "custom_value")
                .build();

        // Then
        Map<String, Object> params = request.getParams();
        assertEquals("custom_value", params.get("custom_field"));
    }

    @Test
    void testAPIVersionConstant() {
        assertEquals(7, LiqPayRequest.API_VERSION);
    }
}
