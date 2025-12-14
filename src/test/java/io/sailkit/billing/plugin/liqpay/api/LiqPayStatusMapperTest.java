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

package io.sailkit.billing.plugin.liqpay.api;

import org.junit.jupiter.api.Test;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LiqPayStatusMapper.
 */
class LiqPayStatusMapperTest {

    // Success status tests

    @Test
    void testMapStatusSuccess() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("success", "pay"));
    }

    @Test
    void testMapStatusSandbox() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("sandbox", "pay"));
    }

    @Test
    void testMapStatusSubscribed() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("subscribed", "subscribe"));
    }

    @Test
    void testMapStatusUnsubscribed() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("unsubscribed", "unsubscribe"));
    }

    @Test
    void testMapStatusReversed() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("reversed", "refund"));
    }

    // Hold status tests

    @Test
    void testMapStatusHoldWaitForHoldAction() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("hold_wait", "hold"));
    }

    @Test
    void testMapStatusHoldWaitForAuthorizeAction() {
        assertEquals(PaymentPluginStatus.PROCESSED, LiqPayStatusMapper.mapStatus("hold_wait", "authorize"));
    }

    @Test
    void testMapStatusHoldWaitForOtherAction() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("hold_wait", "pay"));
    }

    // Pending status tests

    @Test
    void testMapStatusProcessing() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("processing", "pay"));
    }

    @Test
    void testMapStatus3dsVerify() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("3ds_verify", "pay"));
    }

    @Test
    void testMapStatusOtpVerify() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("otp_verify", "pay"));
    }

    @Test
    void testMapStatusCvvVerify() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("cvv_verify", "pay"));
    }

    @Test
    void testMapStatusWaitAccept() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("wait_accept", "pay"));
    }

    @Test
    void testMapStatusWaitSecure() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("wait_secure", "pay"));
    }

    @Test
    void testMapStatusPrepared() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("prepared", "pay"));
    }

    @Test
    void testMapStatusInvoiceWait() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("invoice_wait", "pay"));
    }

    @Test
    void testMapStatusCashWait() {
        assertEquals(PaymentPluginStatus.PENDING, LiqPayStatusMapper.mapStatus("cash_wait", "pay"));
    }

    // Error status tests

    @Test
    void testMapStatusFailure() {
        assertEquals(PaymentPluginStatus.ERROR, LiqPayStatusMapper.mapStatus("failure", "pay"));
    }

    @Test
    void testMapStatusError() {
        assertEquals(PaymentPluginStatus.ERROR, LiqPayStatusMapper.mapStatus("error", "pay"));
    }

    // Undefined status tests

    @Test
    void testMapStatusNull() {
        assertEquals(PaymentPluginStatus.UNDEFINED, LiqPayStatusMapper.mapStatus(null, "pay"));
    }

    @Test
    void testMapStatusUnknown() {
        assertEquals(PaymentPluginStatus.UNDEFINED, LiqPayStatusMapper.mapStatus("unknown_status", "pay"));
    }

    // isFinalStatus tests

    @Test
    void testIsFinalStatusSuccess() {
        assertTrue(LiqPayStatusMapper.isFinalStatus("success"));
    }

    @Test
    void testIsFinalStatusFailure() {
        assertTrue(LiqPayStatusMapper.isFinalStatus("failure"));
    }

    @Test
    void testIsFinalStatusError() {
        assertTrue(LiqPayStatusMapper.isFinalStatus("error"));
    }

    @Test
    void testIsFinalStatusReversed() {
        assertTrue(LiqPayStatusMapper.isFinalStatus("reversed"));
    }

    @Test
    void testIsFinalStatusHoldWait() {
        assertTrue(LiqPayStatusMapper.isFinalStatus("hold_wait"));
    }

    @Test
    void testIsFinalStatusProcessing() {
        assertFalse(LiqPayStatusMapper.isFinalStatus("processing"));
    }

    @Test
    void testIsFinalStatus3dsVerify() {
        assertFalse(LiqPayStatusMapper.isFinalStatus("3ds_verify"));
    }

    // requires3DS tests

    @Test
    void testRequires3DSTrue() {
        assertTrue(LiqPayStatusMapper.requires3DS("3ds_verify"));
    }

    @Test
    void testRequires3DSFalse() {
        assertFalse(LiqPayStatusMapper.requires3DS("success"));
        assertFalse(LiqPayStatusMapper.requires3DS("otp_verify"));
    }

    // requiresOTP tests

    @Test
    void testRequiresOTPTrue() {
        assertTrue(LiqPayStatusMapper.requiresOTP("otp_verify"));
    }

    @Test
    void testRequiresOTPFalse() {
        assertFalse(LiqPayStatusMapper.requiresOTP("success"));
        assertFalse(LiqPayStatusMapper.requiresOTP("3ds_verify"));
    }
}
