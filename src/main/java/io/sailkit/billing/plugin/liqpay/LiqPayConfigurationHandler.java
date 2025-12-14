/*
 * Copyright 2024 Sailkit.io
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

package io.sailkit.billing.plugin.liqpay;

import java.util.Properties;
import java.util.UUID;

import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.plugin.api.notification.PluginTenantConfigurableConfigurationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sailkit.billing.plugin.liqpay.client.LiqPayClient;

/**
 * Handles per-tenant configuration for the LiqPay plugin.
 * Supports runtime configuration changes via KillBill's tenant API.
 *
 * Example usage:
 * <pre>
 * curl -v \
 *      -X POST \
 *      -u admin:password \
 *      -H "Content-Type: text/plain" \
 *      -H "X-Killbill-ApiKey: bob" \
 *      -H "X-Killbill-ApiSecret: lazar" \
 *      -H "X-Killbill-CreatedBy: demo" \
 *      -d 'org.killbill.billing.plugin.liqpay.publicKey=your_public_key
 * org.killbill.billing.plugin.liqpay.privateKey=your_private_key
 * org.killbill.billing.plugin.liqpay.sandbox=true' \
 *      "http://127.0.0.1:8080/1.0/kb/tenants/uploadPluginConfig/killbill-liqpay"
 * </pre>
 */
public class LiqPayConfigurationHandler extends PluginTenantConfigurableConfigurationHandler<LiqPayConfig> {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayConfigurationHandler.class);

    private final String region;

    public LiqPayConfigurationHandler(final String region,
                                       final String pluginName,
                                       final OSGIKillbillAPI osgiKillbillAPI) {
        super(pluginName, osgiKillbillAPI);
        this.region = region;
    }

    @Override
    protected LiqPayConfig createConfigurable(final Properties properties) {
        LiqPayConfig config = new LiqPayConfig(properties);
        logger.info("LiqPay configuration loaded for region {}: {}", region, config);
        return config;
    }

    /**
     * Gets the LiqPay configuration for a specific tenant.
     *
     * @param tenantId Tenant UUID
     * @return LiqPay configuration
     */
    public LiqPayConfig getConfigForTenant(UUID tenantId) {
        LiqPayConfig config = getConfigurable(tenantId);
        if (config == null) {
            logger.warn("No LiqPay configuration found for tenant {}, using default", tenantId);
            config = getConfigurable(null);
        }
        return config;
    }

    /**
     * Creates a LiqPay client for a specific tenant.
     *
     * @param tenantId Tenant UUID
     * @return LiqPay client configured for the tenant
     */
    public LiqPayClient createClientForTenant(UUID tenantId) {
        LiqPayConfig config = getConfigForTenant(tenantId);
        if (config == null || !config.isConfigured()) {
            throw new IllegalStateException("LiqPay is not configured for tenant " + tenantId);
        }

        return new LiqPayClient(
                config.getPublicKey(),
                config.getPrivateKey(),
                config.isSandbox(),
                config.getServerUrl(),
                config.getLanguage(),
                config.getConnectionTimeout(),
                config.getReadTimeout()
        );
    }
}
