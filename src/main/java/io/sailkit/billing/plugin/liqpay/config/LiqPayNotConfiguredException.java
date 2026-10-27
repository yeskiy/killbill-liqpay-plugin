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

package io.sailkit.billing.plugin.liqpay.config;

import java.util.UUID;

public class LiqPayNotConfiguredException extends Exception {

    public static final String ERR_CODE = "not_configured";

    private static final long serialVersionUID = 1L;

    public LiqPayNotConfiguredException(final UUID kbTenantId) {
        super("liqpay is not configured for tenant " + kbTenantId);
    }
}
