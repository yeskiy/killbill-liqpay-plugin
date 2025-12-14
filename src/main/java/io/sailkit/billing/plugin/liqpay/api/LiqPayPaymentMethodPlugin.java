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
import java.util.UUID;

import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;

import io.sailkit.billing.plugin.liqpay.dao.model.LiqPayPaymentMethodRecord;

/**
 * Implementation of PaymentMethodPlugin for LiqPay payment methods.
 */
public class LiqPayPaymentMethodPlugin implements PaymentMethodPlugin {

    private final UUID kbPaymentMethodId;
    private final String externalPaymentMethodId;
    private final boolean isDefaultPaymentMethod;
    private final List<PluginProperty> properties;

    public LiqPayPaymentMethodPlugin(UUID kbPaymentMethodId, String externalPaymentMethodId,
                                      boolean isDefaultPaymentMethod, List<PluginProperty> properties) {
        this.kbPaymentMethodId = kbPaymentMethodId;
        this.externalPaymentMethodId = externalPaymentMethodId;
        this.isDefaultPaymentMethod = isDefaultPaymentMethod;
        this.properties = properties != null ? properties : new ArrayList<>();
    }

    /**
     * Creates a PaymentMethodPlugin from a database record.
     */
    public static LiqPayPaymentMethodPlugin fromRecord(LiqPayPaymentMethodRecord record) {
        List<PluginProperty> props = new ArrayList<>();

        if (record.getCardMask() != null) {
            props.add(new PluginProperty("card_mask", record.getCardMask(), false));
        }
        if (record.getCardType() != null) {
            props.add(new PluginProperty("card_type", record.getCardType(), false));
        }
        if (record.getCardBank() != null) {
            props.add(new PluginProperty("card_bank", record.getCardBank(), false));
        }
        if (record.getCardCountry() != null) {
            props.add(new PluginProperty("card_country", record.getCardCountry(), false));
        }
        if (record.hasToken()) {
            props.add(new PluginProperty("has_token", true, false));
        }

        return new LiqPayPaymentMethodPlugin(
                record.getKbPaymentMethodId(),
                record.getLiqpayCardToken(),
                record.getIsDefault() != null && record.getIsDefault(),
                props
        );
    }

    /**
     * Creates an empty payment method (no token yet).
     */
    public static LiqPayPaymentMethodPlugin empty(UUID kbPaymentMethodId, boolean isDefault) {
        return new LiqPayPaymentMethodPlugin(kbPaymentMethodId, null, isDefault, new ArrayList<>());
    }

    @Override
    public UUID getKbPaymentMethodId() {
        return kbPaymentMethodId;
    }

    @Override
    public String getExternalPaymentMethodId() {
        return externalPaymentMethodId;
    }

    @Override
    public boolean isDefaultPaymentMethod() {
        return isDefaultPaymentMethod;
    }

    @Override
    public List<PluginProperty> getProperties() {
        return properties;
    }

    public boolean hasToken() {
        return externalPaymentMethodId != null && !externalPaymentMethodId.isEmpty();
    }
}
