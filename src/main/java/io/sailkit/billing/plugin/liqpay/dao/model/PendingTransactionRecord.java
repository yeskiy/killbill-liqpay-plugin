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

package io.sailkit.billing.plugin.liqpay.dao.model;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Model for pending transaction records stored in liqpay_pending_transactions table.
 * Used to track transactions awaiting callback from LiqPay hosted payment page.
 */
public class PendingTransactionRecord {

    private Long recordId;
    private UUID kbAccountId;
    private UUID kbPaymentId;
    private UUID kbTransactionId;
    private UUID kbPaymentMethodId;
    private UUID kbTenantId;
    private String orderId;
    private String transactionType;
    private BigDecimal amount;
    private String currency;
    private String status;
    private Timestamp createdDate;
    private Timestamp updatedDate;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public UUID getKbAccountId() {
        return kbAccountId;
    }

    public void setKbAccountId(UUID kbAccountId) {
        this.kbAccountId = kbAccountId;
    }

    public UUID getKbPaymentId() {
        return kbPaymentId;
    }

    public void setKbPaymentId(UUID kbPaymentId) {
        this.kbPaymentId = kbPaymentId;
    }

    public UUID getKbTransactionId() {
        return kbTransactionId;
    }

    public void setKbTransactionId(UUID kbTransactionId) {
        this.kbTransactionId = kbTransactionId;
    }

    public UUID getKbPaymentMethodId() {
        return kbPaymentMethodId;
    }

    public void setKbPaymentMethodId(UUID kbPaymentMethodId) {
        this.kbPaymentMethodId = kbPaymentMethodId;
    }

    public UUID getKbTenantId() {
        return kbTenantId;
    }

    public void setKbTenantId(UUID kbTenantId) {
        this.kbTenantId = kbTenantId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Timestamp createdDate) {
        this.createdDate = createdDate;
    }

    public Timestamp getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Timestamp updatedDate) {
        this.updatedDate = updatedDate;
    }

    @Override
    public String toString() {
        return "PendingTransactionRecord{" +
                "kbTransactionId=" + kbTransactionId +
                ", orderId='" + orderId + '\'' +
                ", transactionType='" + transactionType + '\'' +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
