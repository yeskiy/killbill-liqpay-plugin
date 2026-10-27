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

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LiqPaySignerTest {

    // The public example of the LiqPay checkout page, not a merchant key.
    static final String DOC_PRIVATE_KEY = "a4825234f4bae72a0be04eafe9e8e2bada209255";
    static final String DOC_DATA = "eyJwdWJsaWNfa2V5IjoiaTAwMDAwMDAwIiwidmVyc2lvbiI6NywiYWN0aW9uIjoicGF5IiwiYW1vdW50IjoiMyIsImN1cnJlbmN5IjoiVUFIIiwiZGVzY3JpcHRpb24iOiJ0ZXN0Iiwib3JkZXJfaWQiOiIwMDAwMDEifQ==";
    static final String DOC_SIGNATURE = "0adgJ8F2Ds5HCVkcz4AlmdLMRoIJf7IxsL3QmeFRz/s=";

    @Test
    public void signsTheDocVector() {
        assertEquals(LiqPaySigner.sign(DOC_PRIVATE_KEY, DOC_DATA), DOC_SIGNATURE);
    }

    @Test
    public void verifiesTheDocVector() {
        assertTrue(LiqPaySigner.verify(DOC_PRIVATE_KEY, DOC_DATA, DOC_SIGNATURE));
    }

    @Test
    public void rejectsOneChangedCharacterInData() {
        assertFalse(LiqPaySigner.verify(DOC_PRIVATE_KEY, "f" + DOC_DATA.substring(1), DOC_SIGNATURE));
    }

    @Test
    public void rejectsOneChangedCharacterInSignature() {
        assertFalse(LiqPaySigner.verify(DOC_PRIVATE_KEY, DOC_DATA, "1" + DOC_SIGNATURE.substring(1)));
    }

    @Test
    public void rejectsASignatureOfAnotherLength() {
        assertFalse(LiqPaySigner.verify(DOC_PRIVATE_KEY, DOC_DATA, DOC_SIGNATURE.substring(0, 20)));
        assertFalse(LiqPaySigner.verify(DOC_PRIVATE_KEY, DOC_DATA, DOC_SIGNATURE + "A"));
    }

    @Test
    public void rejectsNullInput() {
        assertFalse(LiqPaySigner.verify(DOC_PRIVATE_KEY, DOC_DATA, null));
        assertFalse(LiqPaySigner.verify(DOC_PRIVATE_KEY, null, DOC_SIGNATURE));
    }
}
