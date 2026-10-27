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

import java.util.ArrayList;
import java.util.List;

import io.sailkit.billing.plugin.liqpay.LogCapture;
import io.sailkit.billing.plugin.liqpay.client.LiqPayAction;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.sailkit.billing.plugin.liqpay.client.LiqPayAction.HOLD;
import static io.sailkit.billing.plugin.liqpay.client.LiqPayAction.HOLD_COMPLETION;
import static io.sailkit.billing.plugin.liqpay.client.LiqPayAction.PAY;
import static io.sailkit.billing.plugin.liqpay.client.LiqPayAction.PAYTOKEN;
import static io.sailkit.billing.plugin.liqpay.client.LiqPayAction.REFUND;
import static io.sailkit.billing.plugin.liqpay.client.LiqPayAction.UNHOLD;
import static org.killbill.billing.payment.plugin.api.PaymentPluginStatus.ERROR;
import static org.killbill.billing.payment.plugin.api.PaymentPluginStatus.PENDING;
import static org.killbill.billing.payment.plugin.api.PaymentPluginStatus.PROCESSED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class LiqPayStatusMapperTest {

    private static final LiqPayAction[] ACTIONS = {PAY, PAYTOKEN, HOLD, HOLD_COMPLETION, REFUND, UNHOLD};

    // Columns: PAY, PAYTOKEN, HOLD, HOLD_COMPLETION, REFUND, UNHOLD.
    private static final Object[][] TABLE = {
            {"success", PROCESSED, PROCESSED, PENDING, PROCESSED, PROCESSED, PROCESSED},
            {"wait_compensation", PROCESSED, PROCESSED, PENDING, PROCESSED, PENDING, PENDING},
            {"hold_wait", PENDING, PENDING, PROCESSED, PENDING, PENDING, PENDING},
            {"reversed", ERROR, ERROR, ERROR, ERROR, PROCESSED, PROCESSED},
            {"failure", ERROR, ERROR, ERROR, ERROR, ERROR, ERROR},
            {"error", ERROR, ERROR, ERROR, ERROR, ERROR, ERROR},
            {"try_again", ERROR, ERROR, ERROR, ERROR, ERROR, ERROR},
            {"unsubscribed", ERROR, ERROR, ERROR, ERROR, ERROR, ERROR},
            {"processing", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"prepared", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_secure", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_accept", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_lc", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_reserve", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_card", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"invoice_wait", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"cash_wait", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_qr", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"wait_sender", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"3ds_verify", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"otp_verify", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"cvv_verify", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"p24_verify", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"mp_verify", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"senderapp_verify", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
            {"unknown_new_status", PENDING, PENDING, PENDING, PENDING, PENDING, PENDING},
    };

    @DataProvider(name = "cells")
    public Object[][] cells() {
        final List<Object[]> rows = new ArrayList<>();
        for (final Object[] line : TABLE) {
            for (int i = 0; i < ACTIONS.length; i++) {
                rows.add(new Object[]{line[0], ACTIONS[i], false, line[i + 1]});
            }
        }
        for (final LiqPayAction action : ACTIONS) {
            rows.add(new Object[]{"sandbox", action, true, PROCESSED});
            rows.add(new Object[]{"sandbox", action, false, ERROR});
        }
        return rows.toArray(new Object[0][]);
    }

    @Test(dataProvider = "cells")
    public void mapsEachCell(final String status, final LiqPayAction action, final boolean sandbox, final PaymentPluginStatus expected) {
        assertEquals(LiqPayStatusMapper.map(action, status, sandbox), expected, status + " / " + action);
    }

    @Test
    public void anUnknownStatusLogsOneWarning() {
        try (LogCapture capture = new LogCapture()) {
            assertEquals(LiqPayStatusMapper.map(PAY, "brand_new", false), PENDING);
            final String log = capture.text();
            assertEquals(log.split("liqpay status not mapped", -1).length - 1, 1);
            assertTrue(log.contains("WARN"));
        }
    }

    @Test
    public void aNullStatusIsPending() {
        assertEquals(LiqPayStatusMapper.map(PAY, null, false), PENDING);
    }

    @Test
    public void theStatusActionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LiqPayStatusMapper.map(LiqPayAction.STATUS, "success", false));
    }

    @Test
    public void finalStatuses() {
        assertTrue(LiqPayStatusMapper.isFinal(PROCESSED));
        assertTrue(LiqPayStatusMapper.isFinal(ERROR));
        assertTrue(LiqPayStatusMapper.isFinal(PaymentPluginStatus.CANCELED));
        assertFalse(LiqPayStatusMapper.isFinal(PENDING));
        assertFalse(LiqPayStatusMapper.isFinal(PaymentPluginStatus.UNDEFINED));
    }
}
