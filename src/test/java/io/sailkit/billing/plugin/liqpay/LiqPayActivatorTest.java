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

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** Runs in the integration test phase, after the bundle jar exists. */
public class LiqPayActivatorTest {

    private static File bundle() {
        final File jar = new File(System.getProperty("bundle.jar", "target/liqpay-plugin.jar"));
        assertTrue(jar.isFile(), "the bundle jar exists: " + jar);
        return jar;
    }

    @Test
    public void theBundleImportsNoJackson() throws Exception {
        try (JarFile jar = new JarFile(bundle())) {
            final Attributes main = jar.getManifest().getMainAttributes();
            final String imports = main.getValue("Import-Package");
            assertNotNull(imports);
            assertFalse(imports.contains("com.fasterxml.jackson"), "the plugin must use its own Jackson");
            assertEquals(main.getValue("Bundle-Activator"), LiqPayActivator.class.getName());
        }
    }

    @Test
    public void theBundleHoldsJackson2223() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{bundle().toURI().toURL()}, null)) {
            final Object core = loader.loadClass("com.fasterxml.jackson.core.json.PackageVersion").getField("VERSION").get(null);
            final Object databind = loader.loadClass("com.fasterxml.jackson.databind.cfg.PackageVersion").getField("VERSION").get(null);
            assertEquals(core.toString(), "2.22.3");
            assertEquals(databind.toString(), "2.22.3");
        }
    }
}
