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
 * Unit tests for LiqPayResponse.
 */
class LiqPayResponseTest {

    @Test
    void testFromMapSuccess() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("result", "ok");
        data.put("status", "success");
        data.put("action", "pay");
        data.put("order_id", "test-order-123");
        data.put("payment_id", 12345678L);
        data.put("amount", 100.50);
        data.put("currency", "UAH");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertEquals("success", response.getStatus());
        assertEquals("pay", response.getAction());
        assertEquals("test-order-123", response.getOrderId());
        assertEquals(Long.valueOf(12345678L), response.getPaymentId());
        assertEquals(new BigDecimal("100.5"), response.getAmount());
        assertEquals("UAH", response.getCurrency());
        assertTrue(response.isSuccess());
        assertFalse(response.isError());
        assertFalse(response.isPending());
    }

    @Test
    void testFromMapFailure() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "failure");
        data.put("action", "pay");
        data.put("order_id", "test-order-456");
        data.put("err_code", "insufficient_funds");
        data.put("err_description", "Not enough funds");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertEquals("failure", response.getStatus());
        assertEquals("insufficient_funds", response.getErrCode());
        assertEquals("Not enough funds", response.getErrDescription());
        assertFalse(response.isSuccess());
        assertTrue(response.isError());
    }

    @Test
    void testFromMapError() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "error");
        data.put("err_code", "invalid_signature");
        data.put("err_description", "Signature validation failed");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.isError());
        assertFalse(response.isSuccess());
    }

    @Test
    void testFromMapHoldWait() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "hold_wait");
        data.put("action", "hold");
        data.put("order_id", "test-hold-123");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.isHoldWait());
        assertFalse(response.isSuccess());
        assertFalse(response.isPending());
    }

    @Test
    void testFromMapPending3ds() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "3ds_verify");
        data.put("action", "pay");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.isPending());
        assertTrue(response.requires3DS());
        assertFalse(response.isSuccess());
    }

    @Test
    void testFromMapSandbox() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("result", "ok");
        data.put("status", "sandbox");
        data.put("action", "pay");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.isSuccess());
    }

    @Test
    void testCardToken() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("result", "ok");
        data.put("status", "success");
        data.put("card_token", "test_card_token_abc123");
        data.put("sender_card_mask2", "4242*4242");
        data.put("sender_card_type", "visa");
        data.put("sender_card_bank", "Test Bank");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.hasCardToken());
        assertEquals("test_card_token_abc123", response.getCardToken());
        assertEquals("4242*4242", response.getSenderCardMask());
        assertEquals("visa", response.getSenderCardType());
        assertEquals("Test Bank", response.getSenderCardBank());
    }

    @Test
    void testNoCardToken() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "success");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertFalse(response.hasCardToken());
        assertNull(response.getCardToken());
    }

    @Test
    void testEmptyCardToken() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "success");
        data.put("card_token", "");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertFalse(response.hasCardToken());
    }

    @Test
    void testLiqpayOrderId() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "success");
        data.put("liqpay_order_id", "liqpay-internal-id-123");
        data.put("order_id", "our-order-id");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertEquals("liqpay-internal-id-123", response.getLiqpayOrderId());
        assertEquals("our-order-id", response.getOrderId());
    }

    @Test
    void testTransactionId() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "success");
        data.put("transaction_id", 987654321L);
        data.put("acq_id", 123456L);

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertEquals(Long.valueOf(987654321L), response.getTransactionId());
        assertEquals(Long.valueOf(123456L), response.getAcqId());
    }

    @Test
    void testPendingStatuses() {
        String[] pendingStatuses = {"processing", "3ds_verify", "otp_verify", "cvv_verify",
                "wait_accept", "wait_secure", "prepared", "invoice_wait", "cash_wait"};

        for (String status : pendingStatuses) {
            Map<String, Object> data = new HashMap<>();
            data.put("status", status);
            LiqPayResponse response = LiqPayResponse.fromMap(data);
            assertTrue(response.isPending(), "Status " + status + " should be pending");
        }
    }

    @Test
    void testFromJson() {
        // Given
        String json = "{\"result\":\"ok\",\"status\":\"success\",\"action\":\"pay\",\"order_id\":\"json-test-123\",\"amount\":500.00,\"currency\":\"EUR\"}";

        // When
        LiqPayResponse response = LiqPayResponse.fromJson(json);

        // Then
        assertEquals("success", response.getStatus());
        assertEquals("pay", response.getAction());
        assertEquals("json-test-123", response.getOrderId());
        assertEquals(0, new BigDecimal("500.00").compareTo(response.getAmount()));
        assertEquals("EUR", response.getCurrency());
    }

    @Test
    void testReversedStatus() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "reversed");
        data.put("action", "refund");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.isReversed());
        assertEquals("reversed", response.getStatus());
    }

    @Test
    void testCommissionFields() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "success");
        data.put("sender_commission", 2.50);
        data.put("receiver_commission", 1.25);

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertEquals(new BigDecimal("2.5"), response.getSenderCommission());
        assertEquals(new BigDecimal("1.25"), response.getReceiverCommission());
    }

    @Test
    void testSenderInfo() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "success");
        data.put("sender_first_name", "John");
        data.put("sender_last_name", "Doe");
        data.put("sender_phone", "+380501234567");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertEquals("John", response.getSenderFirstName());
        assertEquals("Doe", response.getSenderLastName());
        assertEquals("+380501234567", response.getSenderPhone());
    }

    @Test
    void test3DSRedirect() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("status", "3ds_verify");
        data.put("redirect_to", "https://bank.com/3ds");

        // When
        LiqPayResponse response = LiqPayResponse.fromMap(data);

        // Then
        assertTrue(response.requires3DS());
        assertEquals("https://bank.com/3ds", response.getRedirectTo());
    }

    @Test
    void testFromMapNull() {
        // When / Then - fromMap with null should throw NullPointerException
        assertThrows(NullPointerException.class, () -> {
            LiqPayResponse.fromMap(null);
        });
    }

    @Test
    void testFromMapEmpty() {
        // When
        LiqPayResponse response = LiqPayResponse.fromMap(new HashMap<>());

        // Then
        assertNotNull(response);
        assertNull(response.getStatus());
    }

    @Test
    void testSettersAndGetters() {
        // Given
        LiqPayResponse response = new LiqPayResponse();

        // When
        response.setStatus("success");
        response.setAction("pay");
        response.setOrderId("test-123");
        response.setAmount(new BigDecimal("100.00"));
        response.setCurrency("UAH");

        // Then
        assertEquals("success", response.getStatus());
        assertEquals("pay", response.getAction());
        assertEquals("test-123", response.getOrderId());
        assertEquals(new BigDecimal("100.00"), response.getAmount());
        assertEquals("UAH", response.getCurrency());
    }

    @Test
    void testToString() {
        // Given
        LiqPayResponse response = new LiqPayResponse();
        response.setResult("ok");
        response.setStatus("success");
        response.setAction("pay");
        response.setOrderId("test-123");

        // When
        String str = response.toString();

        // Then
        assertTrue(str.contains("success"));
        assertTrue(str.contains("pay"));
        assertTrue(str.contains("test-123"));
    }
}
