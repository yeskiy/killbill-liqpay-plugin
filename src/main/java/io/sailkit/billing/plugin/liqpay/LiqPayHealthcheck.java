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

import java.util.Map;

import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.tenant.api.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health check for the LiqPay plugin.
 * Verifies that the plugin is properly configured.
 */
public class LiqPayHealthcheck implements Healthcheck {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayHealthcheck.class);

    private final LiqPayConfigurationHandler configurationHandler;

    public LiqPayHealthcheck(LiqPayConfigurationHandler configurationHandler) {
        this.configurationHandler = configurationHandler;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public HealthStatus getHealthStatus(Tenant tenant, Map properties) {
        if (tenant == null) {
            return check(null);
        }
        return check(tenant.getId());
    }

    private HealthStatus check(java.util.UUID tenantId) {
        try {
            LiqPayConfig config = configurationHandler.getConfigForTenant(tenantId);

            if (config == null) {
                return HealthStatus.unHealthy("LiqPay configuration not found");
            }

            if (!config.isConfigured()) {
                return HealthStatus.unHealthy("LiqPay API keys not configured");
            }

            // Check for sandbox mode warning
            if (config.isSandbox()) {
                return HealthStatus.healthy("LiqPay plugin configured (SANDBOX MODE)");
            }

            return HealthStatus.healthy("LiqPay plugin configured");

        } catch (Exception e) {
            logger.error("Health check failed", e);
            return HealthStatus.unHealthy("Health check failed: " + e.getMessage());
        }
    }
}
