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

/**
 * Builder for LiqPay API requests.
 * Supports all main LiqPay actions: pay, hold, hold_completion, unhold, refund, paytoken, status.
 */
public class LiqPayRequest {

    public static final int API_VERSION = 3;

    private final Map<String, Object> params = new HashMap<>();

    private LiqPayRequest() {
        params.put("version", API_VERSION);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> getParams() {
        return new HashMap<>(params);
    }

    public static class Builder {
        private final LiqPayRequest request = new LiqPayRequest();

        // Required parameters

        public Builder publicKey(String publicKey) {
            request.params.put("public_key", publicKey);
            return this;
        }

        public Builder action(String action) {
            request.params.put("action", action);
            return this;
        }

        public Builder amount(BigDecimal amount) {
            request.params.put("amount", amount);
            return this;
        }

        public Builder currency(String currency) {
            request.params.put("currency", currency);
            return this;
        }

        public Builder description(String description) {
            request.params.put("description", description);
            return this;
        }

        public Builder orderId(String orderId) {
            request.params.put("order_id", orderId);
            return this;
        }

        // Token payment

        public Builder cardToken(String cardToken) {
            request.params.put("card_token", cardToken);
            return this;
        }

        // Request token generation
        public Builder recurringByToken(boolean enable) {
            if (enable) {
                request.params.put("recurringbytoken", "1");
            }
            return this;
        }

        // URLs

        public Builder serverUrl(String serverUrl) {
            request.params.put("server_url", serverUrl);
            return this;
        }

        public Builder resultUrl(String resultUrl) {
            request.params.put("result_url", resultUrl);
            return this;
        }

        // Sender info

        public Builder senderFirstName(String firstName) {
            request.params.put("sender_first_name", firstName);
            return this;
        }

        public Builder senderLastName(String lastName) {
            request.params.put("sender_last_name", lastName);
            return this;
        }

        public Builder senderEmail(String email) {
            request.params.put("sender_email", email);
            return this;
        }

        public Builder phone(String phone) {
            request.params.put("phone", phone);
            return this;
        }

        public Builder senderCountryCode(String countryCode) {
            request.params.put("sender_country_code", countryCode);
            return this;
        }

        public Builder senderCity(String city) {
            request.params.put("sender_city", city);
            return this;
        }

        public Builder senderAddress(String address) {
            request.params.put("sender_address", address);
            return this;
        }

        public Builder senderPostalCode(String postalCode) {
            request.params.put("sender_postal_code", postalCode);
            return this;
        }

        // IP address (required for server-to-server)

        public Builder ip(String ip) {
            request.params.put("ip", ip);
            return this;
        }

        // Language

        public Builder language(String language) {
            request.params.put("language", language);
            return this;
        }

        // Sandbox mode

        public Builder sandbox(boolean sandbox) {
            if (sandbox) {
                request.params.put("sandbox", 1);
            }
            return this;
        }

        // Customer identifier for one-click payments

        public Builder customer(String customer) {
            request.params.put("customer", customer);
            return this;
        }

        // Recurring flag for token payments

        public Builder isRecurring(boolean isRecurring) {
            request.params.put("is_recurring", isRecurring);
            return this;
        }

        // Product info

        public Builder productName(String productName) {
            request.params.put("product_name", productName);
            return this;
        }

        public Builder productDescription(String productDescription) {
            request.params.put("product_description", productDescription);
            return this;
        }

        public Builder productCategory(String productCategory) {
            request.params.put("product_category", productCategory);
            return this;
        }

        public Builder productUrl(String productUrl) {
            request.params.put("product_url", productUrl);
            return this;
        }

        // Additional info

        public Builder info(String info) {
            request.params.put("info", info);
            return this;
        }

        // Payment types for checkout

        public Builder payTypes(String payTypes) {
            request.params.put("paytypes", payTypes);
            return this;
        }

        // Expiration

        public Builder expiredDate(String expiredDate) {
            request.params.put("expired_date", expiredDate);
            return this;
        }

        // Generic parameter setter

        public Builder param(String key, Object value) {
            request.params.put(key, value);
            return this;
        }

        public LiqPayRequest build() {
            return request;
        }
    }

    // Convenience factory methods for common actions

    /**
     * Creates a pay request (direct charge).
     */
    public static Builder pay(String publicKey, String orderId, BigDecimal amount, String currency, String description) {
        return builder()
                .publicKey(publicKey)
                .action("pay")
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .description(description);
    }

    /**
     * Creates a token payment request.
     */
    public static Builder payToken(String publicKey, String orderId, String cardToken, BigDecimal amount, String currency, String description) {
        return builder()
                .publicKey(publicKey)
                .action("paytoken")
                .orderId(orderId)
                .cardToken(cardToken)
                .amount(amount)
                .currency(currency)
                .description(description);
    }

    /**
     * Creates a hold (pre-authorization) request.
     */
    public static Builder hold(String publicKey, String orderId, BigDecimal amount, String currency, String description) {
        return builder()
                .publicKey(publicKey)
                .action("hold")
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .description(description);
    }

    /**
     * Creates a hold completion (capture) request.
     */
    public static Builder holdCompletion(String publicKey, String orderId, BigDecimal amount) {
        return builder()
                .publicKey(publicKey)
                .action("hold_completion")
                .orderId(orderId)
                .amount(amount);
    }

    /**
     * Creates an unhold (void) request.
     */
    public static Builder unhold(String publicKey, String orderId) {
        return builder()
                .publicKey(publicKey)
                .action("unhold")
                .orderId(orderId);
    }

    /**
     * Creates a refund request.
     */
    public static Builder refund(String publicKey, String orderId, BigDecimal amount) {
        return builder()
                .publicKey(publicKey)
                .action("refund")
                .orderId(orderId)
                .amount(amount);
    }

    /**
     * Creates a status query request.
     */
    public static Builder status(String publicKey, String orderId) {
        return builder()
                .publicKey(publicKey)
                .action("status")
                .orderId(orderId);
    }
}
