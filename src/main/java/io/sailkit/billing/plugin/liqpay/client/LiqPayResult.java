/*
 * Copyright 2025-2026 Yehor Brodskyi
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
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** The allow-listed fields of a LiqPay answer or callback. It never holds the raw body. */
public final class LiqPayResult {

    private final String result;
    private final String status;
    private final String action;
    private final String paymentId;
    private final String orderId;
    private final BigDecimal amount;
    private final String currency;
    private final Long endDate;
    private final Long createDate;
    private final String errCode;
    private final String errDescription;
    private final String paytype;

    private LiqPayResult(final JsonNode n) {
        this.result = text(n, "result");
        this.status = text(n, "status");
        this.action = text(n, "action");
        this.paymentId = text(n, "payment_id");
        this.orderId = text(n, "order_id");
        this.amount = decimal(text(n, "amount"));
        this.currency = text(n, "currency");
        this.endDate = millis(text(n, "end_date"));
        this.createDate = millis(text(n, "create_date"));
        this.errCode = text(n, "err_code");
        this.errDescription = truncate(text(n, "err_description"), 1000);
        this.paytype = text(n, "paytype");
    }

    public static LiqPayResult from(final JsonNode node) {
        return new LiqPayResult(node);
    }

    public String result() { return result; }

    public String status() { return status; }

    public String action() { return action; }

    public String paymentId() { return paymentId; }

    public String orderId() { return orderId; }

    public BigDecimal amount() { return amount; }

    public String currency() { return currency; }

    /** LiqPay `end_date` in Unix milliseconds, or null. */
    public Long endDate() { return endDate; }

    public Long createDate() { return createDate; }

    public String errCode() { return errCode; }

    public String errDescription() { return errDescription; }

    public String paytype() { return paytype; }

    public boolean isError() {
        return "error".equals(result) || "error".equals(status) || errCode != null;
    }

    /** The fields that the DAO may store in `additional_data`. */
    public Map<String, Object> toStoredFields() {
        final Map<String, Object> m = new LinkedHashMap<>();
        put(m, "status", status);
        put(m, "action", action);
        put(m, "payment_id", paymentId);
        put(m, "order_id", orderId);
        put(m, "amount", amount == null ? null : amount.toPlainString());
        put(m, "currency", currency);
        put(m, "end_date", endDate);
        put(m, "create_date", createDate);
        put(m, "err_code", errCode);
        put(m, "err_description", errDescription);
        put(m, "paytype", paytype);
        return m;
    }

    @Override
    public String toString() {
        return "LiqPayResult" + toStoredFields();
    }

    private static void put(final Map<String, Object> m, final String k, final Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static String text(final JsonNode n, final String field) {
        final JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : truncate(v.asText(), 255);
    }

    private static String truncate(final String s, final int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static BigDecimal decimal(final String s) {
        try {
            return s == null ? null : new BigDecimal(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Long millis(final String s) {
        try {
            return s == null ? null : Long.valueOf(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
