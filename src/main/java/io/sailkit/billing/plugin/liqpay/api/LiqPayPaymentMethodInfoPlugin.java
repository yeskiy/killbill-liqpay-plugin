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

import java.util.UUID;

import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin;

import io.sailkit.billing.plugin.liqpay.dao.model.LiqPayPaymentMethodRecord;

/**
 * Implementation of PaymentMethodInfoPlugin for LiqPay payment methods.
 */
public class LiqPayPaymentMethodInfoPlugin implements PaymentMethodInfoPlugin {

    private final UUID accountId;
    private final UUID paymentMethodId;
    private final boolean isDefault;
    private final String externalPaymentMethodId;

    public LiqPayPaymentMethodInfoPlugin(UUID accountId, UUID paymentMethodId,
                                          boolean isDefault, String externalPaymentMethodId) {
        this.accountId = accountId;
        this.paymentMethodId = paymentMethodId;
        this.isDefault = isDefault;
        this.externalPaymentMethodId = externalPaymentMethodId;
    }

    /**
     * Creates from a database record.
     */
    public static LiqPayPaymentMethodInfoPlugin fromRecord(LiqPayPaymentMethodRecord record) {
        return new LiqPayPaymentMethodInfoPlugin(
                record.getKbAccountId(),
                record.getKbPaymentMethodId(),
                record.getIsDefault() != null && record.getIsDefault(),
                record.getLiqpayCardToken()
        );
    }

    @Override
    public UUID getAccountId() {
        return accountId;
    }

    @Override
    public UUID getPaymentMethodId() {
        return paymentMethodId;
    }

    @Override
    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public String getExternalPaymentMethodId() {
        return externalPaymentMethodId;
    }
}
