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

import java.util.Hashtable;
import java.util.Properties;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sailkit.billing.plugin.liqpay.dao.LiqPayDao;
import io.sailkit.billing.plugin.liqpay.servlet.LiqPayCallbackServlet;

/**
 * OSGI Bundle Activator for the LiqPay Payment Plugin.
 * Registers PaymentPluginApi, Healthcheck, and Callback Servlet.
 */
public class LiqPayActivator extends KillbillActivatorBase {

    private static final Logger logger = LoggerFactory.getLogger(LiqPayActivator.class);

    public static final String PLUGIN_NAME = "killbill-liqpay";

    private LiqPayConfigurationHandler liqPayConfigurationHandler;

    @Override
    public void start(final BundleContext context) throws Exception {
        super.start(context);

        logger.info("Starting LiqPay Payment Plugin: {}", PLUGIN_NAME);

        // Get region from properties or default to empty
        final Properties props = configProperties.getProperties();
        final String region = props.getProperty("org.killbill.billing.plugin.liqpay.region", "");

        // Initialize DAO
        final LiqPayDao liqPayDao = new LiqPayDao(dataSource.getDataSource());

        // Initialize configuration handler
        liqPayConfigurationHandler = new LiqPayConfigurationHandler(region, PLUGIN_NAME, killbillAPI);
        final LiqPayConfig globalConfiguration = liqPayConfigurationHandler.createConfigurable(props);
        liqPayConfigurationHandler.setDefaultConfigurable(globalConfiguration);

        // Create and register healthcheck
        final LiqPayHealthcheck liqPayHealthcheck = new LiqPayHealthcheck(liqPayConfigurationHandler);
        registerHealthcheck(context, liqPayHealthcheck);

        // Create and register PaymentPluginApi
        final LiqPayPaymentPluginApi paymentPluginApi = new LiqPayPaymentPluginApi(
                killbillAPI,
                liqPayConfigurationHandler,
                clock.getClock(),
                dataSource.getDataSource()
        );
        registerPaymentPluginApi(context, paymentPluginApi);

        // Register the callback servlet using PluginAppBuilder (Jooby framework)
        // This creates endpoint at /plugins/killbill-liqpay/callback
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillAPI,
                                                         dataSource,
                                                         super.clock,
                                                         configProperties)
                .withRouteClass(LiqPayCallbackServlet.class)
                .withService(liqPayHealthcheck)
                .withService(paymentPluginApi)
                .withService(liqPayConfigurationHandler)
                .withService(liqPayDao)
                .withService(killbillAPI)  // Needed for callback servlet to notify KillBill
                .withService(clock)
                .build();

        final HttpServlet liqpayServlet = PluginApp.createServlet(pluginApp);
        registerServlet(context, liqpayServlet);

        // Register configuration event handler for tenant config updates
        registerHandlers();

        logger.info("LiqPay Payment Plugin started successfully. Callback URL: /plugins/{}/callback", PLUGIN_NAME);
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        logger.info("Stopping LiqPay Payment Plugin");
        super.stop(context);
    }

    private void registerHandlers() {
        final PluginConfigurationEventHandler handler = new PluginConfigurationEventHandler(liqPayConfigurationHandler);
        dispatcher.registerEventHandlers(handler);
    }

    private void registerServlet(final BundleContext context, final HttpServlet servlet) {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, Servlet.class, servlet, props);
    }

    private void registerPaymentPluginApi(final BundleContext context, final PaymentPluginApi api) {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, PaymentPluginApi.class, api, props);
    }

    private void registerHealthcheck(final BundleContext context, final Healthcheck healthcheck) {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, Healthcheck.class, healthcheck, props);
    }
}
