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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** LiqPay API version 7 signature: base64(sha3-256(private_key + data + private_key)). */
public final class LiqPaySigner {

    private LiqPaySigner() {}

    public static String sign(final String privateKey, final String data) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA3-256")
                                 .digest((privateKey + data + privateKey).getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is not available", e);
        }
    }

    /** Compares in constant time. A null value never verifies. */
    public static boolean verify(final String privateKey, final String data, final String signature) {
        return privateKey != null && data != null && signature != null
               && MessageDigest.isEqual(sign(privateKey, data).getBytes(StandardCharsets.US_ASCII),
                                        signature.getBytes(StandardCharsets.US_ASCII));
    }
}
