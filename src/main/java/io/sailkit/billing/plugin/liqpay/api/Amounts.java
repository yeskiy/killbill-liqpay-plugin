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

package io.sailkit.billing.plugin.liqpay.api;

import io.sailkit.billing.plugin.liqpay.client.LiqPayResult;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;

public final class Amounts {

    private Amounts() {}

    /** True when the answer names no amount and no currency, or the same ones as the row. */
    public static boolean match(final LiqpayTransactionsRecord row, final LiqPayResult r) {
        return (r.amount() == null || r.amount().compareTo(row.getAmount()) == 0)
               && (r.currency() == null || r.currency().equals(row.getCurrency()));
    }

    /** True when the answer names the same amount and currency as the row. */
    public static boolean equal(final LiqpayTransactionsRecord row, final LiqPayResult r) {
        return r.amount() != null && r.currency() != null && match(row, r);
    }
}
