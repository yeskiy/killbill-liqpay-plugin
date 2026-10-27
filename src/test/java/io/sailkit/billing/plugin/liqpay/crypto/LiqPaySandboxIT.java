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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;
import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfig;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.testng.Assert.assertNotEquals;

/** Needs LIQPAY_SANDBOX_PUBLIC_KEY and LIQPAY_SANDBOX_PRIVATE_KEY_FILE. Skipped without them. */
@Test(groups = "sandbox")
public class LiqPaySandboxIT {

    @Test
    public void theSandboxAcceptsTheSignature() throws Exception {
        final String publicKey = System.getenv("LIQPAY_SANDBOX_PUBLIC_KEY");
        final String keyFile = System.getenv("LIQPAY_SANDBOX_PRIVATE_KEY_FILE");
        if (publicKey == null || keyFile == null || !Files.isRegularFile(Path.of(keyFile))) {
            throw new SkipException("LIQPAY_SANDBOX_PUBLIC_KEY and LIQPAY_SANDBOX_PRIVATE_KEY_FILE are not set");
        }
        final String privateKey = Files.readString(Path.of(keyFile), StandardCharsets.UTF_8).trim();
        final Properties p = new Properties();
        p.setProperty(PREFIX + "publicKey", publicKey);
        p.setProperty(PREFIX + "privateKeyEnv", "SANDBOX_KEY");
        p.setProperty(PREFIX + "serverUrl", "https://pay.example.com/callback");
        p.setProperty(PREFIX + "sandbox", "true");
        final LiqPayConfig config = LiqPayConfig.parse(p, Map.of("SANDBOX_KEY", privateKey)::get);
        final LiqPayResult r = new LiqPayClient(Duration.ofSeconds(10)).status(config, "sandbox-" + UUID.randomUUID());
        System.out.println("liqpay sandbox status answer: result=" + r.result() + " status=" + r.status() + " err_code=" + r.errCode());
        assertNotEquals(r.errCode(), "invalid_signature");
        assertNotEquals(r.errCode(), "err_signature");
    }
}
