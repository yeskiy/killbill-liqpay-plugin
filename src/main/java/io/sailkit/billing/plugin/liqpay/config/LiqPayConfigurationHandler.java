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

import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.plugin.api.notification.PluginConfigurationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tenant config, reloaded on each tenant config event. A tenant without a valid config gets no default keys.
 * It keeps its own map, because the tenant map of base plugin 5.1.12 throws when no default exists.
 */
public class LiqPayConfigurationHandler extends PluginConfigurationHandler {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayConfigurationHandler.class);

    private final Function<String, String> env;
    private final Map<UUID, Optional<LiqPayConfig>> configs = new ConcurrentHashMap<>();

    public LiqPayConfigurationHandler(final String pluginName,
                                      final OSGIKillbillAPI osgiKillbillAPI,
                                      final Function<String, String> env) {
        super(pluginName, osgiKillbillAPI);
        this.env = env;
    }

    @Override
    protected void configure(final UUID kbTenantId) {
        if (kbTenantId != null) {
            configs.put(kbTenantId, load(kbTenantId));
        }
    }

    public LiqPayConfig get(final UUID kbTenantId) throws LiqPayNotConfiguredException {
        if (kbTenantId == null) {
            throw new LiqPayNotConfiguredException(null);
        }
        return configs.computeIfAbsent(kbTenantId, this::load)
                      .orElseThrow(() -> new LiqPayNotConfiguredException(kbTenantId));
    }

    LiqPayConfig createConfigurable(final Properties properties) {
        if (!LiqPayConfig.hasPluginKeys(properties)) {
            return null;
        }
        try {
            return LiqPayConfig.parse(properties, env);
        } catch (final IllegalArgumentException e) {
            logger.warn("liqpay config rejected: {}", e.getMessage());
            return null;
        }
    }

    private Optional<LiqPayConfig> load(final UUID kbTenantId) {
        return Optional.ofNullable(getTenantConfigurationAsProperties(kbTenantId)).map(this::createConfigurable);
    }
}
