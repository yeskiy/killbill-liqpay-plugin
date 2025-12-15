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
 * Model for HPP (Hosted Payment Page) request records stored in liqpay_hpp_requests table.
 * Used to track buildFormDescriptor sessions for checkout redirect/embed flows.
 */
public class HppRequestRecord {

    private Long recordId;
    private UUID kbAccountId;
    private UUID kbPaymentMethodId;
    private UUID kbTenantId;
    private String sessionId;
    private String orderId;
    private String mode;
    private boolean isVerification;
    private BigDecimal amount;
    private String currency;
    private String additionalData;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isVerification() {
        return isVerification;
    }

    public void setVerification(boolean verification) {
        isVerification = verification;
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

    public String getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(String additionalData) {
        this.additionalData = additionalData;
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
        return "HppRequestRecord{" +
                "sessionId='" + sessionId + '\'' +
                ", orderId='" + orderId + '\'' +
                ", mode='" + mode + '\'' +
                ", isVerification=" + isVerification +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
