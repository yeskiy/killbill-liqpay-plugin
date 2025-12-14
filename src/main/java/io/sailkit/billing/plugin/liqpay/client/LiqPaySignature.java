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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LiqPay signature generation and verification utility.
 *
 * LiqPay uses the following signature algorithm:
 * signature = base64_encode(sha1(private_key + data + private_key))
 *
 * Where 'data' is the base64-encoded JSON request parameters.
 */
public class LiqPaySignature {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LiqPaySignature() {
        // Utility class
    }

    /**
     * Creates a signature for LiqPay API requests.
     *
     * @param privateKey LiqPay private key
     * @param data Base64-encoded JSON data
     * @return Base64-encoded SHA1 signature
     */
    public static String createSignature(String privateKey, String data) {
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("Private key cannot be null or empty");
        }
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }

        String signString = privateKey + data + privateKey;

        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(signString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Verifies a signature from LiqPay callback.
     *
     * @param privateKey LiqPay private key
     * @param data Base64-encoded JSON data from callback
     * @param signature Signature from callback
     * @return true if signature is valid
     */
    public static boolean verifySignature(String privateKey, String data, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }

        String expectedSignature = createSignature(privateKey, data);
        return expectedSignature.equals(signature);
    }

    /**
     * Encodes request parameters to base64-encoded JSON.
     *
     * @param params Request parameters map
     * @return Base64-encoded JSON string
     */
    public static String encodeData(Map<String, Object> params) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(params);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to encode parameters to JSON", e);
        }
    }

    /**
     * Decodes base64-encoded JSON data to a map.
     *
     * @param data Base64-encoded JSON string
     * @return Decoded parameters map
     */
    public static Map<String, Object> decodeData(String data) {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode data", e);
        }
    }

    /**
     * Decodes base64-encoded JSON data to a specific type.
     *
     * @param data Base64-encoded JSON string
     * @param clazz Target class type
     * @return Decoded object
     */
    public static <T> T decodeData(String data, Class<T> clazz) {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode data to " + clazz.getName(), e);
        }
    }

    /**
     * Encodes a JSON string to base64.
     *
     * @param json JSON string
     * @return Base64-encoded string
     */
    public static String encodeJson(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes base64 to JSON string.
     *
     * @param base64Data Base64-encoded string
     * @return Decoded JSON string
     */
    public static String decodeToJson(String base64Data) {
        byte[] decoded = Base64.getDecoder().decode(base64Data);
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
