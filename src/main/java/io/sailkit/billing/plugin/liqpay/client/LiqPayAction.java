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

import java.util.Arrays;

/** Actions that the plugin sends to LiqPay, with their wire names. */
public enum LiqPayAction {
    PAY("pay"),
    PAYTOKEN("paytoken"),
    HOLD("hold"),
    HOLD_COMPLETION("hold_completion"),
    // LiqPay has no unhold action. A hold release goes out as REFUND.
    UNHOLD("unhold"),
    REFUND("refund"),
    STATUS("status");

    private final String wireName;

    LiqPayAction(final String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static LiqPayAction fromWireName(final String wireName) {
        return Arrays.stream(values())
                     .filter(a -> a.wireName.equals(wireName))
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException("unknown liqpay action"));
    }
}
