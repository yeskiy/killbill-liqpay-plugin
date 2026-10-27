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

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

public class CardTokenCipherTest {

    private static byte[] key(final int length) {
        final byte[] k = new byte[length];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    public void roundTrip() throws Exception {
        final CardTokenCipher c = new CardTokenCipher(key(32), "1");
        assertEquals(c.decrypt(c.encrypt("B5B0D00B88B00ED00A00D0D0")), "B5B0D00B88B00ED00A00D0D0");
        assertEquals(c.keyId(), "1");
    }

    @Test
    public void twoEncryptionsDiffer() throws Exception {
        final CardTokenCipher c = new CardTokenCipher(key(32), "1");
        assertFalse(Arrays.equals(c.encrypt("token"), c.encrypt("token")));
    }

    @Test
    public void aChangedByteFails() throws Exception {
        final CardTokenCipher c = new CardTokenCipher(key(32), "1");
        final byte[] blob = c.encrypt("token");
        blob[blob.length - 1] ^= 0x01;
        assertThrows(AEADBadTagException.class, () -> c.decrypt(blob));
    }

    @Test
    public void aSixteenByteKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CardTokenCipher(key(16), "1"));
    }

    @Test
    public void aWrongKeyIdFails() throws Exception {
        final byte[] k = key(32);
        final byte[] blob = new CardTokenCipher(k, "1").encrypt("token");
        assertThrows(AEADBadTagException.class, () -> new CardTokenCipher(k, "2").decrypt(blob));
    }

    @Test
    public void aWrongKeyFails() throws Exception {
        final byte[] blob = new CardTokenCipher(key(32), "1").encrypt("token");
        assertThrows(AEADBadTagException.class, () -> new CardTokenCipher(key(32), "1").decrypt(blob));
    }
}
