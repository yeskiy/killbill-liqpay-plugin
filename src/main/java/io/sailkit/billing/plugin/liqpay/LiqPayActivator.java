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

package io.sailkit.billing.plugin.liqpay;

import java.time.Clock;
import java.util.Hashtable;

import javax.servlet.Servlet;

import io.sailkit.billing.plugin.liqpay.api.LiqPayPaymentPluginApi;
import io.sailkit.billing.plugin.liqpay.config.LiqPayConfigurationHandler;
import io.sailkit.billing.plugin.liqpay.dao.LiqPayDao;
import io.sailkit.billing.plugin.liqpay.servlet.LiqPayCallbackServlet;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LiqPayActivator extends KillbillActivatorBase {

    public static final String PLUGIN_NAME = "killbill-liqpay";

    private static final Logger logger = LoggerFactory.getLogger(LiqPayActivator.class);

    @Override
    public void start(final BundleContext context) throws Exception {
        super.start(context);
        logger.info("killbill-liqpay starting");
        final Clock utc = Clock.systemUTC();
        final LiqPayConfigurationHandler configs = new LiqPayConfigurationHandler(PLUGIN_NAME, killbillAPI, System::getenv);
        dispatcher.registerEventHandlers(new PluginConfigurationEventHandler(configs));
        final LiqPayDao dao = new LiqPayDao(dataSource.getDataSource(), utc);
        register(context, PaymentPluginApi.class, new LiqPayPaymentPluginApi(configs, dao, utc));
        register(context, Servlet.class, new LiqPayCallbackServlet(configs, dao, killbillAPI, utc));
    }

    private <T> void register(final BundleContext context, final Class<T> type, final T service) {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, type, service, props);
    }
}
