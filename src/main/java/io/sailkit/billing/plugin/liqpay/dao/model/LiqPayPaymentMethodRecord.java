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

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Database record for LiqPay payment methods (card tokens).
 */
public class LiqPayPaymentMethodRecord {

    private Long recordId;
    private UUID kbAccountId;
    private UUID kbPaymentMethodId;
    private UUID kbTenantId;
    private String liqpayCardToken;
    private String cardMask;
    private String cardType;
    private String cardBank;
    private String cardCountry;
    private Boolean isDefault;
    private Boolean isDeleted;
    private String additionalData;
    private Timestamp createdDate;
    private Timestamp updatedDate;

    public LiqPayPaymentMethodRecord() {
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

    public String getLiqpayCardToken() {
        return liqpayCardToken;
    }

    public void setLiqpayCardToken(String liqpayCardToken) {
        this.liqpayCardToken = liqpayCardToken;
    }

    public String getCardMask() {
        return cardMask;
    }

    public void setCardMask(String cardMask) {
        this.cardMask = cardMask;
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public String getCardBank() {
        return cardBank;
    }

    public void setCardBank(String cardBank) {
        this.cardBank = cardBank;
    }

    public String getCardCountry() {
        return cardCountry;
    }

    public void setCardCountry(String cardCountry) {
        this.cardCountry = cardCountry;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Boolean isDeleted) {
        this.isDeleted = isDeleted;
    }

    public String getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(String additionalData) {
        this.additionalData = additionalData;
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

    public boolean hasToken() {
        return liqpayCardToken != null && !liqpayCardToken.isEmpty();
    }
}
