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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for LiqPay API.
 * Handles all communication with LiqPay payment gateway.
 */
public class LiqPayClient {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayClient.class);

    public static final String API_URL = "https://www.liqpay.ua/api/request";
    public static final String CHECKOUT_URL = "https://www.liqpay.ua/api/3/checkout";

    private final String publicKey;
    private final String privateKey;
    private final boolean sandbox;
    private final String serverUrl;
    private final String language;
    private final int connectionTimeout;
    private final int readTimeout;

    private final CloseableHttpClient httpClient;

    public LiqPayClient(String publicKey, String privateKey, boolean sandbox,
                        String serverUrl, String language,
                        int connectionTimeout, int readTimeout) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.sandbox = sandbox;
        this.serverUrl = serverUrl;
        this.language = language;
        this.connectionTimeout = connectionTimeout;
        this.readTimeout = readTimeout;

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectionTimeout)
                .setSocketTimeout(readTimeout)
                .setConnectionRequestTimeout(connectionTimeout)
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    /**
     * Sends a request to LiqPay API.
     *
     * @param request LiqPay request
     * @return LiqPay response
     * @throws LiqPayException if the request fails
     */
    public LiqPayResponse request(LiqPayRequest request) throws LiqPayException {
        Map<String, Object> params = request.getParams();

        // Add default parameters
        params.putIfAbsent("public_key", publicKey);
        if (sandbox) {
            params.put("sandbox", 1);
        }
        if (serverUrl != null && !serverUrl.isEmpty()) {
            params.putIfAbsent("server_url", serverUrl);
        }
        // NOTE: result_url is passed per request, not from config
        if (language != null && !language.isEmpty()) {
            params.putIfAbsent("language", language);
        }

        String data = LiqPaySignature.encodeData(params);
        String signature = LiqPaySignature.createSignature(privateKey, data);

        return executeRequest(data, signature);
    }

    /**
     * Executes the HTTP request to LiqPay API.
     */
    private LiqPayResponse executeRequest(String data, String signature) throws LiqPayException {
        HttpPost httpPost = new HttpPost(API_URL);

        List<NameValuePair> formParams = new ArrayList<>();
        formParams.add(new BasicNameValuePair("data", data));
        formParams.add(new BasicNameValuePair("signature", signature));

        try {
            httpPost.setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8));

            logger.debug("Sending request to LiqPay API");

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                HttpEntity entity = response.getEntity();
                String responseBody = EntityUtils.toString(entity, StandardCharsets.UTF_8);

                logger.debug("LiqPay API response: {}", responseBody);

                LiqPayResponse liqPayResponse = LiqPayResponse.fromJson(responseBody);

                if (liqPayResponse.isError()) {
                    throw new LiqPayException(liqPayResponse);
                }

                return liqPayResponse;
            }
        } catch (LiqPayException e) {
            throw e;
        } catch (IOException e) {
            logger.error("Failed to communicate with LiqPay API", e);
            throw new LiqPayException("Failed to communicate with LiqPay API", e);
        }
    }

    // Convenience methods for common operations

    /**
     * Performs a direct payment (purchase).
     */
    public LiqPayResponse pay(String orderId, BigDecimal amount, String currency, String description) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.pay(publicKey, orderId, amount, currency, description)
                .recurringByToken(true)
                .build();
        return request(request);
    }

    /**
     * Performs a token-based payment.
     */
    public LiqPayResponse payToken(String orderId, String cardToken, BigDecimal amount, String currency, String description) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.payToken(publicKey, orderId, cardToken, amount, currency, description)
                .isRecurring(true)
                .build();
        return request(request);
    }

    /**
     * Performs a hold (pre-authorization).
     */
    public LiqPayResponse hold(String orderId, BigDecimal amount, String currency, String description) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.hold(publicKey, orderId, amount, currency, description)
                .recurringByToken(true)
                .build();
        return request(request);
    }

    /**
     * Performs a token-based hold (pre-authorization).
     */
    public LiqPayResponse holdWithToken(String orderId, String cardToken, BigDecimal amount, String currency, String description) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.builder()
                .publicKey(publicKey)
                .action("hold")
                .orderId(orderId)
                .cardToken(cardToken)
                .amount(amount)
                .currency(currency)
                .description(description)
                .recurringByToken(true)
                .build();
        return request(request);
    }

    /**
     * Captures (completes) a held payment.
     */
    public LiqPayResponse holdCompletion(String orderId, BigDecimal amount) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.holdCompletion(publicKey, orderId, amount)
                .build();
        return request(request);
    }

    /**
     * Voids (releases) a held payment.
     */
    public LiqPayResponse unhold(String orderId) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.unhold(publicKey, orderId)
                .build();
        return request(request);
    }

    /**
     * Performs a refund.
     */
    public LiqPayResponse refund(String orderId, BigDecimal amount) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.refund(publicKey, orderId, amount)
                .build();
        return request(request);
    }

    /**
     * Gets the status of a payment.
     */
    public LiqPayResponse status(String orderId) throws LiqPayException {
        LiqPayRequest request = LiqPayRequest.status(publicKey, orderId)
                .build();
        return request(request);
    }

    /**
     * Builds checkout form data for hosted payment page.
     *
     * @param orderId Order ID
     * @param amount Payment amount
     * @param currency Currency code
     * @param description Payment description
     * @param action Action type (pay, hold, subscribe, etc.)
     * @return Map containing 'data' and 'signature' for the checkout form
     */
    public Map<String, String> buildCheckoutFormData(String orderId, BigDecimal amount, String currency,
                                                      String description, String action) {
        LiqPayRequest.Builder builder = LiqPayRequest.builder()
                .publicKey(publicKey)
                .action(action)
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .description(description)
                .recurringByToken(true);

        if (sandbox) {
            builder.sandbox(true);
        }
        if (serverUrl != null && !serverUrl.isEmpty()) {
            builder.serverUrl(serverUrl);
        }
        // NOTE: result_url is passed per request, not from config
        if (language != null && !language.isEmpty()) {
            builder.language(language);
        }

        LiqPayRequest request = builder.build();
        String data = LiqPaySignature.encodeData(request.getParams());
        String signature = LiqPaySignature.createSignature(privateKey, data);

        return Map.of(
                "data", data,
                "signature", signature
        );
    }

    /**
     * Builds checkout form data with custom parameters.
     */
    public Map<String, String> buildCheckoutFormData(Map<String, Object> params) {
        params.putIfAbsent("public_key", publicKey);
        params.putIfAbsent("version", LiqPayRequest.API_VERSION);

        if (sandbox) {
            params.put("sandbox", 1);
        }
        if (serverUrl != null && !serverUrl.isEmpty()) {
            params.putIfAbsent("server_url", serverUrl);
        }
        // NOTE: result_url is passed per request, not from config
        if (language != null && !language.isEmpty()) {
            params.putIfAbsent("language", language);
        }

        // Always request token for recurring capability
        params.putIfAbsent("recurringbytoken", "1");

        String data = LiqPaySignature.encodeData(params);
        String signature = LiqPaySignature.createSignature(privateKey, data);

        return Map.of(
                "data", data,
                "signature", signature
        );
    }

    /**
     * Verifies a callback signature.
     */
    public boolean verifyCallbackSignature(String data, String signature) {
        return LiqPaySignature.verifySignature(privateKey, data, signature);
    }

    /**
     * Parses callback data.
     */
    public LiqPayResponse parseCallback(String data) {
        Map<String, Object> decodedData = LiqPaySignature.decodeData(data);
        return LiqPayResponse.fromMap(decodedData);
    }

    /**
     * Closes the HTTP client.
     */
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            logger.warn("Failed to close HTTP client", e);
        }
    }

    // Getters

    public String getPublicKey() {
        return publicKey;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getLanguage() {
        return language;
    }

    public static String getCheckoutUrl() {
        return CHECKOUT_URL;
    }
}
