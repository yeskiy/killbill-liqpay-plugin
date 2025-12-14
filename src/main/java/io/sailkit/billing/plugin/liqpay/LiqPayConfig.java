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

package io.sailkit.billing.plugin.liqpay;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * LiqPay plugin configuration.
 * Parses configuration from Properties.
 */
public class LiqPayConfig {

    private static final String PREFIX = "org.killbill.billing.plugin.liqpay.";

    // Default values
    private static final boolean DEFAULT_SANDBOX = true;
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String DEFAULT_CURRENCIES = "UAH,USD,EUR";
    private static final int DEFAULT_CONNECTION_TIMEOUT = 30000;
    private static final int DEFAULT_READ_TIMEOUT = 60000;

    private final String publicKey;
    private final String privateKey;
    private final boolean sandbox;
    private final String serverUrl;
    private final List<String> currencies;
    private final String language;
    private final int connectionTimeout;
    private final int readTimeout;

    public LiqPayConfig(Properties properties) {
        this.publicKey = getProperty(properties, "publicKey", null);
        this.privateKey = getProperty(properties, "privateKey", null);
        this.sandbox = getBooleanProperty(properties, "sandbox", DEFAULT_SANDBOX);
        this.serverUrl = getProperty(properties, "serverUrl", null);
        this.language = getProperty(properties, "language", DEFAULT_LANGUAGE);
        this.connectionTimeout = getIntProperty(properties, "connectionTimeout", DEFAULT_CONNECTION_TIMEOUT);
        this.readTimeout = getIntProperty(properties, "readTimeout", DEFAULT_READ_TIMEOUT);

        String currenciesStr = getProperty(properties, "currencies", DEFAULT_CURRENCIES);
        this.currencies = currenciesStr != null
                ? Arrays.asList(currenciesStr.split(","))
                : Collections.emptyList();
    }

    private String getProperty(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(PREFIX + key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private boolean getBooleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = getProperty(properties, key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value) || "1".equals(value);
    }

    private int getIntProperty(Properties properties, String key, int defaultValue) {
        String value = getProperty(properties, key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean isConfigured() {
        return publicKey != null && !publicKey.isEmpty() &&
               privateKey != null && !privateKey.isEmpty();
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public List<String> getCurrencies() {
        return currencies;
    }

    public String getLanguage() {
        return language;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    @Override
    public String toString() {
        return "LiqPayConfig{" +
                "publicKey='" + (publicKey != null ? publicKey.substring(0, Math.min(10, publicKey.length())) + "..." : "null") + '\'' +
                ", sandbox=" + sandbox +
                ", serverUrl='" + serverUrl + '\'' +
                ", currencies=" + currencies +
                ", language='" + language + '\'' +
                '}';
    }
}
