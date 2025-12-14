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
 * Supports both redirect-based checkout and embedded widget.
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
        this.formFields.add(new PluginProperty("data", formData.get("data"), false));
        this.formFields.add(new PluginProperty("signature", formData.get("signature"), false));

        this.properties = new ArrayList<>();
        // Add widget-specific properties
        this.properties.add(new PluginProperty("widget_data", formData.get("data"), false));
        this.properties.add(new PluginProperty("widget_signature", formData.get("signature"), false));
        this.properties.add(new PluginProperty("checkout_url", LiqPayClient.CHECKOUT_URL, false));
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
