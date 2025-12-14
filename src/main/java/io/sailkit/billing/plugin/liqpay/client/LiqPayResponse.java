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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LiqPay API response model.
 * Contains all fields returned by LiqPay API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LiqPayResponse {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Result
    private String result;

    // Transaction identifiers
    @JsonProperty("payment_id")
    private Long paymentId;

    @JsonProperty("transaction_id")
    private Long transactionId;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("liqpay_order_id")
    private String liqpayOrderId;

    // Action and status
    private String action;
    private String status;
    private String type;

    // Amounts
    private BigDecimal amount;

    @JsonProperty("amount_debit")
    private BigDecimal amountDebit;

    @JsonProperty("amount_credit")
    private BigDecimal amountCredit;

    @JsonProperty("amount_bonus")
    private BigDecimal amountBonus;

    // Currency
    private String currency;

    @JsonProperty("currency_debit")
    private String currencyDebit;

    @JsonProperty("currency_credit")
    private String currencyCredit;

    // Commission
    @JsonProperty("sender_commission")
    private BigDecimal senderCommission;

    @JsonProperty("receiver_commission")
    private BigDecimal receiverCommission;

    @JsonProperty("agent_commission")
    private BigDecimal agentCommission;

    @JsonProperty("commission_debit")
    private BigDecimal commissionDebit;

    @JsonProperty("commission_credit")
    private BigDecimal commissionCredit;

    // Card info
    @JsonProperty("card_token")
    private String cardToken;

    @JsonProperty("sender_card_mask2")
    private String senderCardMask;

    @JsonProperty("sender_card_type")
    private String senderCardType;

    @JsonProperty("sender_card_bank")
    private String senderCardBank;

    @JsonProperty("sender_card_country")
    private String senderCardCountry;

    // Sender info
    @JsonProperty("sender_first_name")
    private String senderFirstName;

    @JsonProperty("sender_last_name")
    private String senderLastName;

    @JsonProperty("sender_phone")
    private String senderPhone;

    // Error info
    @JsonProperty("err_code")
    private String errCode;

    @JsonProperty("err_description")
    private String errDescription;

    // Dates
    @JsonProperty("create_date")
    private String createDate;

    @JsonProperty("end_date")
    private String endDate;

    @JsonProperty("completion_date")
    private String completionDate;

    @JsonProperty("refund_date_last")
    private String refundDateLast;

    // Authorization codes
    @JsonProperty("authcode_debit")
    private String authcodeDebit;

    @JsonProperty("authcode_credit")
    private String authcodeCredit;

    // Reference numbers
    @JsonProperty("rrn_debit")
    private String rrnDebit;

    @JsonProperty("rrn_credit")
    private String rrnCredit;

    // Acquirer
    @JsonProperty("acq_id")
    private Long acqId;

    // 3DS info
    @JsonProperty("is_3ds")
    private Boolean is3ds;

    @JsonProperty("mpi_eci")
    private Integer mpiEci;

    // Redirect for 3DS
    @JsonProperty("redirect_to")
    private String redirectTo;

    // Payment type
    private String paytype;

    // IP
    private String ip;

    // Description
    private String description;

    // Customer
    private String customer;

    // Additional info
    private String info;

    // Public key
    @JsonProperty("public_key")
    private String publicKey;

    // Version
    private Integer version;

    // Language
    private String language;

    // Bonus
    @JsonProperty("bonus_procent")
    private BigDecimal bonusPercent;

    @JsonProperty("bonus_type")
    private String bonusType;

    @JsonProperty("sender_bonus")
    private BigDecimal senderBonus;

    // Phone confirmation
    @JsonProperty("confirm_phone")
    private String confirmPhone;

    // Verification code (for auth action)
    private String verifycode;

    // Wait amount (for partial refunds)
    @JsonProperty("wait_amount")
    private BigDecimal waitAmount;

    // Raw response map for any unmapped fields
    private Map<String, Object> rawResponse;

    // Static factory method
    public static LiqPayResponse fromJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, LiqPayResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LiqPay response", e);
        }
    }

    public static LiqPayResponse fromMap(Map<String, Object> map) {
        LiqPayResponse response = OBJECT_MAPPER.convertValue(map, LiqPayResponse.class);
        response.rawResponse = map;
        return response;
    }

    // Helper methods

    public boolean isSuccess() {
        return "ok".equals(result) && ("success".equals(status) || "sandbox".equals(status));
    }

    public boolean isHoldWait() {
        return "hold_wait".equals(status);
    }

    public boolean isPending() {
        return "processing".equals(status) ||
               "3ds_verify".equals(status) ||
               "otp_verify".equals(status) ||
               "cvv_verify".equals(status) ||
               "wait_accept".equals(status) ||
               "wait_secure".equals(status) ||
               "wait_card".equals(status) ||
               "prepared".equals(status) ||
               "invoice_wait".equals(status) ||
               "cash_wait".equals(status);
    }

    public boolean isError() {
        return "error".equals(status) || "failure".equals(status);
    }

    public boolean isReversed() {
        return "reversed".equals(status);
    }

    public boolean requires3DS() {
        return "3ds_verify".equals(status);
    }

    public boolean hasCardToken() {
        return cardToken != null && !cardToken.isEmpty();
    }

    // Getters

    public String getResult() {
        return result;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getLiqpayOrderId() {
        return liqpayOrderId;
    }

    public String getAction() {
        return action;
    }

    public String getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAmountDebit() {
        return amountDebit;
    }

    public BigDecimal getAmountCredit() {
        return amountCredit;
    }

    public BigDecimal getAmountBonus() {
        return amountBonus;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCurrencyDebit() {
        return currencyDebit;
    }

    public String getCurrencyCredit() {
        return currencyCredit;
    }

    public BigDecimal getSenderCommission() {
        return senderCommission;
    }

    public BigDecimal getReceiverCommission() {
        return receiverCommission;
    }

    public BigDecimal getAgentCommission() {
        return agentCommission;
    }

    public String getCardToken() {
        return cardToken;
    }

    public String getSenderCardMask() {
        return senderCardMask;
    }

    public String getSenderCardType() {
        return senderCardType;
    }

    public String getSenderCardBank() {
        return senderCardBank;
    }

    public String getSenderCardCountry() {
        return senderCardCountry;
    }

    public String getSenderFirstName() {
        return senderFirstName;
    }

    public String getSenderLastName() {
        return senderLastName;
    }

    public String getSenderPhone() {
        return senderPhone;
    }

    public String getErrCode() {
        return errCode;
    }

    public String getErrDescription() {
        return errDescription;
    }

    public String getCreateDate() {
        return createDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public String getCompletionDate() {
        return completionDate;
    }

    public String getRefundDateLast() {
        return refundDateLast;
    }

    public String getAuthcodeDebit() {
        return authcodeDebit;
    }

    public String getAuthcodeCredit() {
        return authcodeCredit;
    }

    public String getRrnDebit() {
        return rrnDebit;
    }

    public String getRrnCredit() {
        return rrnCredit;
    }

    public Long getAcqId() {
        return acqId;
    }

    public Boolean getIs3ds() {
        return is3ds;
    }

    public Integer getMpiEci() {
        return mpiEci;
    }

    public String getRedirectTo() {
        return redirectTo;
    }

    public String getPaytype() {
        return paytype;
    }

    public String getIp() {
        return ip;
    }

    public String getDescription() {
        return description;
    }

    public String getCustomer() {
        return customer;
    }

    public String getInfo() {
        return info;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public Integer getVersion() {
        return version;
    }

    public String getLanguage() {
        return language;
    }

    public BigDecimal getBonusPercent() {
        return bonusPercent;
    }

    public String getBonusType() {
        return bonusType;
    }

    public BigDecimal getSenderBonus() {
        return senderBonus;
    }

    public String getConfirmPhone() {
        return confirmPhone;
    }

    public String getVerifycode() {
        return verifycode;
    }

    public BigDecimal getWaitAmount() {
        return waitAmount;
    }

    public Map<String, Object> getRawResponse() {
        return rawResponse;
    }

    public BigDecimal getCommissionDebit() {
        return commissionDebit;
    }

    public BigDecimal getCommissionCredit() {
        return commissionCredit;
    }

    // Setters for Jackson

    public void setResult(String result) {
        this.result = result;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public void setLiqpayOrderId(String liqpayOrderId) {
        this.liqpayOrderId = liqpayOrderId;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setAmountDebit(BigDecimal amountDebit) {
        this.amountDebit = amountDebit;
    }

    public void setAmountCredit(BigDecimal amountCredit) {
        this.amountCredit = amountCredit;
    }

    public void setAmountBonus(BigDecimal amountBonus) {
        this.amountBonus = amountBonus;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setCurrencyDebit(String currencyDebit) {
        this.currencyDebit = currencyDebit;
    }

    public void setCurrencyCredit(String currencyCredit) {
        this.currencyCredit = currencyCredit;
    }

    public void setSenderCommission(BigDecimal senderCommission) {
        this.senderCommission = senderCommission;
    }

    public void setReceiverCommission(BigDecimal receiverCommission) {
        this.receiverCommission = receiverCommission;
    }

    public void setAgentCommission(BigDecimal agentCommission) {
        this.agentCommission = agentCommission;
    }

    public void setCardToken(String cardToken) {
        this.cardToken = cardToken;
    }

    public void setSenderCardMask(String senderCardMask) {
        this.senderCardMask = senderCardMask;
    }

    public void setSenderCardType(String senderCardType) {
        this.senderCardType = senderCardType;
    }

    public void setSenderCardBank(String senderCardBank) {
        this.senderCardBank = senderCardBank;
    }

    public void setSenderCardCountry(String senderCardCountry) {
        this.senderCardCountry = senderCardCountry;
    }

    public void setSenderFirstName(String senderFirstName) {
        this.senderFirstName = senderFirstName;
    }

    public void setSenderLastName(String senderLastName) {
        this.senderLastName = senderLastName;
    }

    public void setSenderPhone(String senderPhone) {
        this.senderPhone = senderPhone;
    }

    public void setErrCode(String errCode) {
        this.errCode = errCode;
    }

    public void setErrDescription(String errDescription) {
        this.errDescription = errDescription;
    }

    public void setCreateDate(String createDate) {
        this.createDate = createDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public void setCompletionDate(String completionDate) {
        this.completionDate = completionDate;
    }

    public void setRefundDateLast(String refundDateLast) {
        this.refundDateLast = refundDateLast;
    }

    public void setAuthcodeDebit(String authcodeDebit) {
        this.authcodeDebit = authcodeDebit;
    }

    public void setAuthcodeCredit(String authcodeCredit) {
        this.authcodeCredit = authcodeCredit;
    }

    public void setRrnDebit(String rrnDebit) {
        this.rrnDebit = rrnDebit;
    }

    public void setRrnCredit(String rrnCredit) {
        this.rrnCredit = rrnCredit;
    }

    public void setAcqId(Long acqId) {
        this.acqId = acqId;
    }

    public void setIs3ds(Boolean is3ds) {
        this.is3ds = is3ds;
    }

    public void setMpiEci(Integer mpiEci) {
        this.mpiEci = mpiEci;
    }

    public void setRedirectTo(String redirectTo) {
        this.redirectTo = redirectTo;
    }

    public void setPaytype(String paytype) {
        this.paytype = paytype;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setBonusPercent(BigDecimal bonusPercent) {
        this.bonusPercent = bonusPercent;
    }

    public void setBonusType(String bonusType) {
        this.bonusType = bonusType;
    }

    public void setSenderBonus(BigDecimal senderBonus) {
        this.senderBonus = senderBonus;
    }

    public void setConfirmPhone(String confirmPhone) {
        this.confirmPhone = confirmPhone;
    }

    public void setVerifycode(String verifycode) {
        this.verifycode = verifycode;
    }

    public void setWaitAmount(BigDecimal waitAmount) {
        this.waitAmount = waitAmount;
    }

    public void setRawResponse(Map<String, Object> rawResponse) {
        this.rawResponse = rawResponse;
    }

    public void setCommissionDebit(BigDecimal commissionDebit) {
        this.commissionDebit = commissionDebit;
    }

    public void setCommissionCredit(BigDecimal commissionCredit) {
        this.commissionCredit = commissionCredit;
    }

    @Override
    public String toString() {
        return "LiqPayResponse{" +
                "result='" + result + '\'' +
                ", status='" + status + '\'' +
                ", action='" + action + '\'' +
                ", orderId='" + orderId + '\'' +
                ", paymentId=" + paymentId +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", errCode='" + errCode + '\'' +
                ", errDescription='" + errDescription + '\'' +
                '}';
    }
}
