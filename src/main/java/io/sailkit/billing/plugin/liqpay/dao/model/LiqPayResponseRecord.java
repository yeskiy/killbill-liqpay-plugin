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
 * Database record for LiqPay API responses.
 */
public class LiqPayResponseRecord {

    private Long recordId;
    private UUID kbAccountId;
    private UUID kbPaymentId;
    private UUID kbTransactionId;
    private UUID kbPaymentMethodId;
    private UUID kbTenantId;
    private String liqpayOrderId;
    private String liqpayPaymentId;
    private String liqpayTransactionId;
    private String transactionType;
    private String action;
    private String status;
    private BigDecimal amount;
    private String currency;
    private String errCode;
    private String errDescription;
    private String rawResponse;
    private Timestamp apiCallDate;
    private Timestamp createdDate;

    public LiqPayResponseRecord() {
    }

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

    public String getLiqpayOrderId() {
        return liqpayOrderId;
    }

    public void setLiqpayOrderId(String liqpayOrderId) {
        this.liqpayOrderId = liqpayOrderId;
    }

    public String getLiqpayPaymentId() {
        return liqpayPaymentId;
    }

    public void setLiqpayPaymentId(String liqpayPaymentId) {
        this.liqpayPaymentId = liqpayPaymentId;
    }

    public String getLiqpayTransactionId() {
        return liqpayTransactionId;
    }

    public void setLiqpayTransactionId(String liqpayTransactionId) {
        this.liqpayTransactionId = liqpayTransactionId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getErrCode() {
        return errCode;
    }

    public void setErrCode(String errCode) {
        this.errCode = errCode;
    }

    public String getErrDescription() {
        return errDescription;
    }

    public void setErrDescription(String errDescription) {
        this.errDescription = errDescription;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public Timestamp getApiCallDate() {
        return apiCallDate;
    }

    public void setApiCallDate(Timestamp apiCallDate) {
        this.apiCallDate = apiCallDate;
    }

    public Timestamp getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Timestamp createdDate) {
        this.createdDate = createdDate;
    }
}
