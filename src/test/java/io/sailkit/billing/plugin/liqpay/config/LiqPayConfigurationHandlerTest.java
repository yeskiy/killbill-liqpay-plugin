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

import java.io.StringWriter;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.tenant.api.TenantUserApi;
import org.killbill.billing.util.callcontext.TenantContext;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class LiqPayConfigurationHandlerTest {

    private static final String CONFIG_KEY = "PLUGIN_CONFIG_killbill-liqpay";

    private static LiqPayConfigurationHandler handler(final List<String> values) throws Exception {
        final TenantUserApi tenantUserApi = mock(TenantUserApi.class);
        when(tenantUserApi.getTenantValuesForKey(eq(CONFIG_KEY), any(TenantContext.class))).thenReturn(values);
        final OSGIKillbillAPI api = mock(OSGIKillbillAPI.class);
        when(api.getTenantUserApi()).thenReturn(tenantUserApi);
        return new LiqPayConfigurationHandler("killbill-liqpay", api, LiqPayConfigTest.ENV);
    }

    private static String asText(final Properties p) throws Exception {
        final StringWriter w = new StringWriter();
        p.store(w, null);
        return w.toString();
    }

    @Test
    public void readsTheTenantConfig() throws Exception {
        final UUID tenant = UUID.randomUUID();
        assertEquals(handler(List.of(asText(LiqPayConfigTest.base()))).get(tenant).publicKey(), "sandbox_i000");
    }

    @Test
    public void anEmptyTenantConfigThrows() throws Exception {
        final UUID tenant = UUID.randomUUID();
        assertThrows(LiqPayNotConfiguredException.class, () -> handler(List.of()).get(tenant));
        assertThrows(LiqPayNotConfiguredException.class, () -> handler(List.of("")).get(tenant));
    }

    @Test
    public void anInvalidTenantConfigThrows() throws Exception {
        final Properties p = LiqPayConfigTest.base();
        p.setProperty(LiqPayConfig.PREFIX + "privateKey", "literal");
        final UUID tenant = UUID.randomUUID();
        assertThrows(LiqPayNotConfiguredException.class, () -> handler(List.of(asText(p))).get(tenant));
    }

    @Test
    public void aConfigEventReloadsTheTenant() throws Exception {
        final TenantUserApi tenantUserApi = mock(TenantUserApi.class);
        when(tenantUserApi.getTenantValuesForKey(eq(CONFIG_KEY), any(TenantContext.class)))
                .thenReturn(List.of(), List.of(asText(LiqPayConfigTest.base())));
        final OSGIKillbillAPI api = mock(OSGIKillbillAPI.class);
        when(api.getTenantUserApi()).thenReturn(tenantUserApi);
        final LiqPayConfigurationHandler handler = new LiqPayConfigurationHandler("killbill-liqpay", api, LiqPayConfigTest.ENV);
        final UUID tenant = UUID.randomUUID();
        assertThrows(LiqPayNotConfiguredException.class, () -> handler.get(tenant));
        handler.configure(tenant);
        assertEquals(handler.get(tenant).publicKey(), "sandbox_i000");
    }

    @Test
    public void aNullTenantThrows() throws Exception {
        assertThrows(LiqPayNotConfiguredException.class, () -> handler(List.of()).get(null));
    }
}
