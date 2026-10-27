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

package io.sailkit.billing.plugin.liqpay.crypto;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The base64 JSON `data` field of LiqPay, decoded with tight Jackson limits. */
public final class LiqPayEnvelope {

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder()
                       .streamReadConstraints(StreamReadConstraints.builder()
                                                                   .maxNestingDepth(10)
                                                                   .maxStringLength(4096)
                                                                   .maxNumberLength(32)
                                                                   .maxNameLength(256)
                                                                   .maxDocumentLength(16_384L)
                                                                   .build())
                       .build());

    private LiqPayEnvelope() {}

    public static String encode(final Map<String, Object> params) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(params));
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("LiqPay parameters cannot be serialized", e);
        }
    }

    /** Throws IllegalArgumentException for bad input. The message never holds the input. */
    public static JsonNode decode(final String data) {
        if (data == null) {
            throw new IllegalArgumentException("data is missing");
        }
        final byte[] json;
        try {
            json = Base64.getDecoder().decode(data);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("data is not base64");
        }
        return parseJson(json);
    }

    /** Parses a plain JSON object, for example a server to server answer, with the same limits. */
    public static JsonNode parseJson(final byte[] json) {
        final JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (final IOException e) {
            throw new IllegalArgumentException("data is not valid JSON within the limits");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("data is not a JSON object");
        }
        return node;
    }
}
