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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM for card tokens at rest. Blob: 12-byte nonce, then ciphertext with the 16-byte tag. */
public final class CardTokenCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public CardTokenCipher(final byte[] key, final String keyId) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("card token key must be 32 bytes");
        }
        if (keyId == null || keyId.isEmpty()) {
            throw new IllegalArgumentException("card token key id is missing");
        }
        this.key = new SecretKeySpec(Arrays.copyOf(key, KEY_BYTES), "AES");
        this.keyId = keyId;
    }

    public String keyId() {
        return keyId;
    }

    public byte[] encrypt(final String token) throws GeneralSecurityException {
        final byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        final byte[] sealed = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(token.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    }

    /** Throws AEADBadTagException when the blob, the key or the key id is wrong. */
    public String decrypt(final byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length <= NONCE_BYTES) {
            throw new GeneralSecurityException("card token blob is too short");
        }
        return new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(blob, NONCE_BYTES))
                                  .doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES),
                          StandardCharsets.UTF_8);
    }

    private Cipher cipher(final int mode, final byte[] nonce) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
