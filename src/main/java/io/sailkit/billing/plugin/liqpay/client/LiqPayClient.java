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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPayEnvelope;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server to server calls to `/api/request`. Logs hold only the action, the order id and the status. */
public class LiqPayClient {

    public static final int API_VERSION = 7;

    private static final Logger logger = LoggerFactory.getLogger(LiqPayClient.class);
    private static final int MAX_ANSWER_BYTES = 64 * 1024;

    private final HttpClient http;

    public LiqPayClient(final Duration connectTimeout) {
        this.http = HttpClient.newBuilder()
                              .connectTimeout(connectTimeout)
                              .followRedirects(HttpClient.Redirect.NEVER)
                              .build();
    }

    public LiqPayResult status(final LiqPayConfig c, final String orderId) throws LiqPayTransientException {
        return call(c, LiqPayAction.STATUS, orderId, new LinkedHashMap<>());
    }

    public LiqPayResult refund(final LiqPayConfig c, final String orderId, final BigDecimal amount) throws LiqPayTransientException {
        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount", money(amount));
        return call(c, LiqPayAction.REFUND, orderId, p);
    }

    /** `extra` holds the optional fields, for example `description`, `ip` and `server_url`. A null value is not sent. */
    public LiqPayResult payToken(final LiqPayConfig c, final String orderId, final BigDecimal amount, final String currency,
                                 final String token, final Map<String, Object> extra) throws LiqPayTransientException {
        return call(c, LiqPayAction.PAYTOKEN, orderId, tokenParams(amount, currency, token, extra));
    }

    public LiqPayResult holdToken(final LiqPayConfig c, final String orderId, final BigDecimal amount, final String currency,
                                  final String token, final Map<String, Object> extra) throws LiqPayTransientException {
        return call(c, LiqPayAction.HOLD, orderId, tokenParams(amount, currency, token, extra));
    }

    public LiqPayResult holdCompletion(final LiqPayConfig c, final String orderId, final BigDecimal amount) throws LiqPayTransientException {
        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount", money(amount));
        return call(c, LiqPayAction.HOLD_COMPLETION, orderId, p);
    }

    /** LiqPay has no unhold action, so a hold release is a refund of the held amount. */
    public LiqPayResult releaseHold(final LiqPayConfig c, final String orderId, final BigDecimal amount) throws LiqPayTransientException {
        return refund(c, orderId, amount);
    }

    // --- Transport ---

    private LiqPayResult call(final LiqPayConfig c, final LiqPayAction action, final String orderId,
                              final Map<String, Object> extra) throws LiqPayTransientException {
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("version", API_VERSION);
        params.put("public_key", c.publicKey());
        params.put("action", action.wireName());
        params.put("order_id", orderId);
        params.putAll(extra);
        final String data = LiqPayEnvelope.encode(params);
        final String body = "data=" + URLEncoder.encode(data, StandardCharsets.UTF_8)
                            + "&signature=" + URLEncoder.encode(LiqPaySigner.sign(c.privateKey(), data), StandardCharsets.UTF_8);
        final HttpRequest request = HttpRequest.newBuilder(URI.create(c.apiBaseUrl().toString().replaceAll("/+$", "") + "/api/request"))
                                               .timeout(c.requestTimeout())
                                               .header("Content-Type", "application/x-www-form-urlencoded")
                                               .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                               .build();
        final HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiqPayTransientException("liqpay call interrupted", e);
        } catch (final IOException e) {
            logger.warn("liqpay call failed action={} order_id={} error={}", action.wireName(), orderId, e.getClass().getSimpleName());
            throw new LiqPayTransientException("liqpay call failed", e);
        }
        if (response.statusCode() / 100 != 2 || response.body() == null || response.body().length > MAX_ANSWER_BYTES) {
            logger.warn("liqpay call failed action={} order_id={} http_status={}", action.wireName(), orderId, response.statusCode());
            throw new LiqPayTransientException("liqpay answered http " + response.statusCode());
        }
        final LiqPayResult result;
        try {
            result = LiqPayResult.from(LiqPayEnvelope.parseJson(response.body()));
        } catch (final IllegalArgumentException e) {
            logger.warn("liqpay answer not readable action={} order_id={}", action.wireName(), orderId);
            throw new LiqPayTransientException("liqpay answer is not valid JSON", e);
        }
        logger.info("liqpay call action={} order_id={} status={} err_code={}",
                    action.wireName(), orderId, result.status(), result.errCode());
        return result;
    }

    private static Map<String, Object> tokenParams(final BigDecimal amount, final String currency, final String token,
                                                   final Map<String, Object> extra) {
        final Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount", money(amount));
        p.put("currency", currency);
        p.put("card_token", token);
        extra.forEach((k, v) -> {
            if (v != null) {
                p.putIfAbsent(k, v);
            }
        });
        return p;
    }

    /** Scale 2 as a plain decimal string, so JSON never shows an exponent. */
    public static String money(final BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
