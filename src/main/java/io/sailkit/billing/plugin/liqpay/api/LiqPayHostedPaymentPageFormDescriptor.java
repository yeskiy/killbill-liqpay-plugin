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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;

import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;

/**
 * Implementation of HostedPaymentPageFormDescriptor for LiqPay checkout.
 * Supports redirect-based checkout, embedded widget, and popup modes.
 *
 * Form fields returned:
 * - data: Base64-encoded JSON payload
 * - signature: HMAC-SHA1 signature
 * - session_id: Internal session ID for tracking
 * - order_id: LiqPay order ID (hpp-{uuid})
 *
 * Properties returned:
 * - mode: Display mode (redirect/embed/popup)
 * - checkout_url: Full checkout URL (for redirect mode)
 * - widget_data: Data for widget initialization
 * - widget_signature: Signature for widget
 * - widget_host: Widget script URL
 * - public_key: LiqPay public key
 */
public class LiqPayHostedPaymentPageFormDescriptor implements HostedPaymentPageFormDescriptor {

    private final UUID kbAccountId;
    private final String formMethod;
    private final String formUrl;
    private final List<PluginProperty> formFields;
    private final List<PluginProperty> properties;

    public LiqPayHostedPaymentPageFormDescriptor(UUID kbAccountId, Map<String, String> formData) {
        this.kbAccountId = kbAccountId;
        this.formMethod = "POST";
        this.formUrl = LiqPayClient.CHECKOUT_URL;

        this.formFields = new ArrayList<>();
        this.properties = new ArrayList<>();

        // Core form fields (for HTML form submission)
        addFormField("data", formData.get("data"));
        addFormField("signature", formData.get("signature"));

        // Session tracking fields
        addFormField("session_id", formData.get("session_id"));
        addFormField("order_id", formData.get("order_id"));

        // Properties for client usage
        addProperty("mode", formData.get("mode"));
        addProperty("widget_data", formData.get("data"));
        addProperty("widget_signature", formData.get("signature"));

        // Checkout URL for redirect mode
        if (formData.containsKey("checkout_url") && formData.get("checkout_url") != null) {
            addProperty("checkout_url", formData.get("checkout_url"));
        }

        // Widget configuration
        addProperty("widget_host", formData.getOrDefault("widget_host", "https://static.liqpay.ua/libjs/checkout.js"));
        addProperty("public_key", formData.get("public_key"));
    }

    private void addFormField(String key, String value) {
        if (value != null) {
            this.formFields.add(new PluginProperty(key, value, false));
        }
    }

    private void addProperty(String key, String value) {
        if (value != null) {
            this.properties.add(new PluginProperty(key, value, false));
        }
    }

    @Override
    public UUID getKbAccountId() {
        return kbAccountId;
    }

    @Override
    public String getFormMethod() {
        return formMethod;
    }

    @Override
    public String getFormUrl() {
        return formUrl;
    }

    @Override
    public List<PluginProperty> getFormFields() {
        return formFields;
    }

    @Override
    public List<PluginProperty> getProperties() {
        return properties;
    }

    /**
     * Gets the data parameter for form submission.
     */
    public String getData() {
        for (PluginProperty prop : formFields) {
            if ("data".equals(prop.getKey())) {
                return prop.getValue() != null ? prop.getValue().toString() : null;
            }
        }
        return null;
    }

    /**
     * Gets the signature parameter for form submission.
     */
    public String getSignature() {
        for (PluginProperty prop : formFields) {
            if ("signature".equals(prop.getKey())) {
                return prop.getValue() != null ? prop.getValue().toString() : null;
            }
        }
        return null;
    }
}
