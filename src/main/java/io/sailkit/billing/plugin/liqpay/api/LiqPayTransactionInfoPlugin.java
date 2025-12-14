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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;

import io.sailkit.billing.plugin.liqpay.client.LiqPayResponse;

/**
 * Implementation of PaymentTransactionInfoPlugin for LiqPay transactions.
 */
public class LiqPayTransactionInfoPlugin implements PaymentTransactionInfoPlugin {

    private final UUID kbPaymentId;
    private final UUID kbTransactionPaymentId;
    private final TransactionType transactionType;
    private final BigDecimal amount;
    private final Currency currency;
    private final DateTime effectiveDate;
    private final DateTime createdDate;
    private final PaymentPluginStatus status;
    private final String gatewayErrorCode;
    private final String gatewayError;
    private final String firstPaymentReferenceId;
    private final String secondPaymentReferenceId;
    private final List<PluginProperty> properties;

    private LiqPayTransactionInfoPlugin(Builder builder) {
        this.kbPaymentId = builder.kbPaymentId;
        this.kbTransactionPaymentId = builder.kbTransactionPaymentId;
        this.transactionType = builder.transactionType;
        this.amount = builder.amount;
        this.currency = builder.currency;
        this.effectiveDate = builder.effectiveDate;
        this.createdDate = builder.createdDate;
        this.status = builder.status;
        this.gatewayErrorCode = builder.gatewayErrorCode;
        this.gatewayError = builder.gatewayError;
        this.firstPaymentReferenceId = builder.firstPaymentReferenceId;
        this.secondPaymentReferenceId = builder.secondPaymentReferenceId;
        this.properties = builder.properties;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a transaction info from a LiqPay response.
     */
    public static LiqPayTransactionInfoPlugin fromResponse(UUID kbPaymentId, UUID kbTransactionId,
                                                            TransactionType transactionType,
                                                            BigDecimal amount, Currency currency,
                                                            LiqPayResponse response, DateTime now) {
        PaymentPluginStatus pluginStatus = LiqPayStatusMapper.mapStatus(response.getStatus(), response.getAction());

        Builder builder = builder()
                .withKbPaymentId(kbPaymentId)
                .withKbTransactionPaymentId(kbTransactionId)
                .withTransactionType(transactionType)
                .withAmount(response.getAmount() != null ? response.getAmount() : amount)
                .withCurrency(currency)
                .withEffectiveDate(now)
                .withCreatedDate(now)
                .withStatus(pluginStatus)
                .withFirstPaymentReferenceId(response.getLiqpayOrderId())
                .withSecondPaymentReferenceId(response.getPaymentId() != null ? response.getPaymentId().toString() : null);

        if (response.isError()) {
            builder.withGatewayErrorCode(response.getErrCode())
                   .withGatewayError(response.getErrDescription());
        }

        // Add card token as property if available
        if (response.hasCardToken()) {
            builder.addProperty("card_token", response.getCardToken());
        }
        if (response.getSenderCardMask() != null) {
            builder.addProperty("card_mask", response.getSenderCardMask());
        }
        if (response.getSenderCardType() != null) {
            builder.addProperty("card_type", response.getSenderCardType());
        }
        if (response.getStatus() != null) {
            builder.addProperty("liqpay_status", response.getStatus());
        }
        if (response.getRedirectTo() != null) {
            builder.addProperty("redirect_to", response.getRedirectTo());
        }

        return builder.build();
    }

    /**
     * Creates a pending transaction info (for hosted page flow).
     */
    public static LiqPayTransactionInfoPlugin pending(UUID kbPaymentId, UUID kbTransactionId,
                                                       TransactionType transactionType,
                                                       BigDecimal amount, Currency currency,
                                                       DateTime now) {
        return builder()
                .withKbPaymentId(kbPaymentId)
                .withKbTransactionPaymentId(kbTransactionId)
                .withTransactionType(transactionType)
                .withAmount(amount)
                .withCurrency(currency)
                .withEffectiveDate(now)
                .withCreatedDate(now)
                .withStatus(PaymentPluginStatus.PENDING)
                .build();
    }

    /**
     * Creates a canceled transaction info (for errors before reaching gateway).
     */
    public static LiqPayTransactionInfoPlugin canceled(UUID kbPaymentId, UUID kbTransactionId,
                                                        TransactionType transactionType,
                                                        BigDecimal amount, Currency currency,
                                                        String errorMessage, DateTime now) {
        return builder()
                .withKbPaymentId(kbPaymentId)
                .withKbTransactionPaymentId(kbTransactionId)
                .withTransactionType(transactionType)
                .withAmount(amount)
                .withCurrency(currency)
                .withEffectiveDate(now)
                .withCreatedDate(now)
                .withStatus(PaymentPluginStatus.CANCELED)
                .withGatewayError(errorMessage)
                .build();
    }

    @Override
    public UUID getKbPaymentId() {
        return kbPaymentId;
    }

    @Override
    public UUID getKbTransactionPaymentId() {
        return kbTransactionPaymentId;
    }

    @Override
    public TransactionType getTransactionType() {
        return transactionType;
    }

    @Override
    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public Currency getCurrency() {
        return currency;
    }

    @Override
    public DateTime getEffectiveDate() {
        return effectiveDate;
    }

    @Override
    public DateTime getCreatedDate() {
        return createdDate;
    }

    @Override
    public PaymentPluginStatus getStatus() {
        return status;
    }

    @Override
    public String getGatewayErrorCode() {
        return gatewayErrorCode;
    }

    @Override
    public String getGatewayError() {
        return gatewayError;
    }

    @Override
    public String getFirstPaymentReferenceId() {
        return firstPaymentReferenceId;
    }

    @Override
    public String getSecondPaymentReferenceId() {
        return secondPaymentReferenceId;
    }

    @Override
    public List<PluginProperty> getProperties() {
        return properties;
    }

    public static class Builder {
        private UUID kbPaymentId;
        private UUID kbTransactionPaymentId;
        private TransactionType transactionType;
        private BigDecimal amount;
        private Currency currency;
        private DateTime effectiveDate;
        private DateTime createdDate;
        private PaymentPluginStatus status;
        private String gatewayErrorCode;
        private String gatewayError;
        private String firstPaymentReferenceId;
        private String secondPaymentReferenceId;
        private final List<PluginProperty> properties = new ArrayList<>();

        public Builder withKbPaymentId(UUID kbPaymentId) {
            this.kbPaymentId = kbPaymentId;
            return this;
        }

        public Builder withKbTransactionPaymentId(UUID kbTransactionPaymentId) {
            this.kbTransactionPaymentId = kbTransactionPaymentId;
            return this;
        }

        public Builder withTransactionType(TransactionType transactionType) {
            this.transactionType = transactionType;
            return this;
        }

        public Builder withAmount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder withCurrency(Currency currency) {
            this.currency = currency;
            return this;
        }

        public Builder withEffectiveDate(DateTime effectiveDate) {
            this.effectiveDate = effectiveDate;
            return this;
        }

        public Builder withCreatedDate(DateTime createdDate) {
            this.createdDate = createdDate;
            return this;
        }

        public Builder withStatus(PaymentPluginStatus status) {
            this.status = status;
            return this;
        }

        public Builder withGatewayErrorCode(String gatewayErrorCode) {
            this.gatewayErrorCode = gatewayErrorCode;
            return this;
        }

        public Builder withGatewayError(String gatewayError) {
            this.gatewayError = gatewayError;
            return this;
        }

        public Builder withFirstPaymentReferenceId(String firstPaymentReferenceId) {
            this.firstPaymentReferenceId = firstPaymentReferenceId;
            return this;
        }

        public Builder withSecondPaymentReferenceId(String secondPaymentReferenceId) {
            this.secondPaymentReferenceId = secondPaymentReferenceId;
            return this;
        }

        public Builder addProperty(String key, Object value) {
            if (value != null) {
                properties.add(new PluginProperty(key, value, false));
            }
            return this;
        }

        public LiqPayTransactionInfoPlugin build() {
            return new LiqPayTransactionInfoPlugin(this);
        }
    }
}
