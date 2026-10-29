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

package io.sailkit.billing.plugin.liqpay.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.plugin.api.payment.PluginPaymentTransactionInfoPlugin;

/** Builds the Kill Bill view of a stored row, with the output properties of the plugin. */
public final class TransactionInfos {

    public static final String ORDER_ID = "liqpay_order_id";
    public static final String STATUS = "liqpay_status";
    public static final String PAYMENT_ID = "liqpay_payment_id";
    public static final String ERR_CODE = "liqpay_err_code";
    public static final String ERR_DESCRIPTION = "liqpay_err_description";
    public static final String REQUEST = "liqpay_request";
    public static final String CHECKOUT_URL = "liqpay_checkout_url";
    public static final String DATA = "liqpay_data";
    public static final String SIGNATURE = "liqpay_signature";
    public static final String EXPIRES_AT = "liqpay_expires_at";

    /** Keys in `additional_data` that hold the checkout form of a pending purchase. */
    static final String STORED_URL = "checkout_url";
    static final String STORED_DATA = "checkout_data";
    static final String STORED_SIGNATURE = "checkout_signature";
    /** Key in `additional_data` that holds the request fields of a refund, with no secret. */
    static final String STORED_REQUEST = "request";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransactionInfos() {}

    public static PaymentTransactionInfoPlugin of(final LiqpayTransactionsRecord row) {
        final PaymentPluginStatus status = PaymentPluginStatus.valueOf(row.getPluginStatus());
        final List<PluginProperty> props = new ArrayList<>();
        add(props, ORDER_ID, row.getOriginalOrderId() == null ? row.getOrderId() : row.getOriginalOrderId());
        add(props, STATUS, row.getLiqpayStatus());
        add(props, PAYMENT_ID, row.getLiqpayPaymentId());
        add(props, ERR_CODE, row.getErrCode());
        add(props, ERR_DESCRIPTION, row.getErrDescription());
        final Object request = stored(row).get(STORED_REQUEST);
        add(props, REQUEST, request == null ? null : json(MAPPER.convertValue(request, new TypeReference<Map<String, Object>>() {})));
        if (status == PaymentPluginStatus.PENDING) {
            final Map<String, Object> form = checkoutForm(row);
            if (!form.isEmpty()) {
                add(props, CHECKOUT_URL, form.get(STORED_URL));
                add(props, DATA, form.get(STORED_DATA));
                add(props, SIGNATURE, form.get(STORED_SIGNATURE));
                add(props, EXPIRES_AT, row.getExpiresAt() == null ? null : row.getExpiresAt().toInstant(ZoneOffset.UTC).toString());
            }
        }
        return new PluginPaymentTransactionInfoPlugin(UUID.fromString(row.getKbPaymentId()),
                                                      UUID.fromString(row.getKbTransactionId()),
                                                      TransactionType.valueOf(row.getTransactionType()),
                                                      row.getAmount(),
                                                      Currency.valueOf(row.getCurrency()),
                                                      status,
                                                      row.getErrDescription(),
                                                      row.getErrCode(),
                                                      row.getLiqpayPaymentId(),
                                                      row.getOrderId(),
                                                      date(row.getCreatedDate()),
                                                      date(row.getUpdatedDate()),
                                                      props);
    }

    /** A result for a call that stored no row, for example a tenant with no config. */
    public static PaymentTransactionInfoPlugin rejected(final UUID kbPaymentId, final UUID kbTransactionId, final TransactionType type,
                                                        final BigDecimal amount, final Currency currency,
                                                        final PaymentPluginStatus status, final String errCode) {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        return new PluginPaymentTransactionInfoPlugin(kbPaymentId, kbTransactionId, type, amount, currency, status, errCode, errCode,
                                                      null, null, now, now, List.of(new PluginProperty(ERR_CODE, errCode, false)));
    }

    /** The stored checkout form fields of a row, or an empty map. */
    static Map<String, Object> checkoutForm(final LiqpayTransactionsRecord row) {
        return pick(row, STORED_URL, STORED_DATA, STORED_SIGNATURE);
    }

    /** The stored fields that a later LiqPay answer must keep: the checkout form and the refund request. */
    static Map<String, Object> kept(final LiqpayTransactionsRecord row) {
        return pick(row, STORED_URL, STORED_DATA, STORED_SIGNATURE, STORED_REQUEST);
    }

    private static Map<String, Object> pick(final LiqpayTransactionsRecord row, final String... keys) {
        final Map<String, Object> stored = stored(row);
        final Map<String, Object> picked = new LinkedHashMap<>();
        Stream.of(keys).filter(stored::containsKey).forEach(k -> picked.put(k, stored.get(k)));
        return picked;
    }

    private static Map<String, Object> stored(final LiqpayTransactionsRecord row) {
        if (row.getAdditionalData() == null) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(row.getAdditionalData(), new TypeReference<Map<String, Object>>() {});
        } catch (final IOException e) {
            return Map.of();
        }
    }

    static String json(final Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("stored fields cannot be serialized", e);
        }
    }

    private static void add(final List<PluginProperty> props, final String key, final Object value) {
        if (value != null) {
            props.add(new PluginProperty(key, value, false));
        }
    }

    private static DateTime date(final LocalDateTime d) {
        return d == null ? DateTime.now(DateTimeZone.UTC) : new DateTime(d.toInstant(ZoneOffset.UTC).toEpochMilli(), DateTimeZone.UTC);
    }
}
