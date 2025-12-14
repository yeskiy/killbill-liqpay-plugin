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

import java.util.Set;

import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;

/**
 * Maps LiqPay statuses to KillBill PaymentPluginStatus.
 */
public class LiqPayStatusMapper {

    private static final Set<String> SUCCESS_STATUSES = Set.of(
            "success",
            "sandbox",
            "subscribed",
            "unsubscribed"
    );

    private static final Set<String> PENDING_STATUSES = Set.of(
            "processing",
            "3ds_verify",
            "otp_verify",
            "cvv_verify",
            "wait_accept",
            "wait_secure",
            "wait_card",
            "wait_compensation",
            "wait_lc",
            "wait_reserve",
            "prepared",
            "invoice_wait",
            "cash_wait",
            "wait_qr",
            "captcha_verify",
            "ivr_verify",
            "password_verify",
            "phone_verify",
            "pin_verify",
            "receiver_verify",
            "sender_verify",
            "senderapp_verify",
            "p24_verify",
            "mp_verify"
    );

    private static final Set<String> ERROR_STATUSES = Set.of(
            "failure",
            "error"
    );

    private LiqPayStatusMapper() {
        // Utility class
    }

    /**
     * Maps a LiqPay status to KillBill PaymentPluginStatus.
     *
     * @param liqPayStatus LiqPay status string
     * @param action LiqPay action (hold, pay, etc.)
     * @return KillBill PaymentPluginStatus
     */
    public static PaymentPluginStatus mapStatus(String liqPayStatus, String action) {
        if (liqPayStatus == null) {
            return PaymentPluginStatus.UNDEFINED;
        }

        // Special case: hold_wait is success for authorize/hold actions
        if ("hold_wait".equals(liqPayStatus)) {
            if ("hold".equals(action) || "authorize".equals(action)) {
                return PaymentPluginStatus.PROCESSED;
            }
            return PaymentPluginStatus.PENDING;
        }

        // Special case: reversed is success for refund
        if ("reversed".equals(liqPayStatus)) {
            return PaymentPluginStatus.PROCESSED;
        }

        if (SUCCESS_STATUSES.contains(liqPayStatus)) {
            return PaymentPluginStatus.PROCESSED;
        }

        if (PENDING_STATUSES.contains(liqPayStatus)) {
            return PaymentPluginStatus.PENDING;
        }

        if (ERROR_STATUSES.contains(liqPayStatus)) {
            return PaymentPluginStatus.ERROR;
        }

        // Unknown status
        return PaymentPluginStatus.UNDEFINED;
    }

    /**
     * Checks if the status is final (not pending).
     */
    public static boolean isFinalStatus(String status) {
        return SUCCESS_STATUSES.contains(status) ||
               ERROR_STATUSES.contains(status) ||
               "reversed".equals(status) ||
               "hold_wait".equals(status);
    }

    /**
     * Checks if the status indicates a successful payment.
     *
     * @param status LiqPay status string
     * @param action LiqPay action (hold, pay, etc.)
     * @return true if the payment was successful
     */
    public static boolean isSuccessStatus(String status, String action) {
        if (status == null) {
            return false;
        }

        // Direct success statuses
        if (SUCCESS_STATUSES.contains(status)) {
            return true;
        }

        // hold_wait is success for authorize/hold actions
        if ("hold_wait".equals(status)) {
            return "hold".equals(action) || "authorize".equals(action);
        }

        // reversed is success for refund
        if ("reversed".equals(status)) {
            return "refund".equals(action);
        }

        return false;
    }

    /**
     * Checks if the status requires 3DS verification.
     */
    public static boolean requires3DS(String status) {
        return "3ds_verify".equals(status);
    }

    /**
     * Checks if the status requires OTP verification.
     */
    public static boolean requiresOTP(String status) {
        return "otp_verify".equals(status);
    }
}
