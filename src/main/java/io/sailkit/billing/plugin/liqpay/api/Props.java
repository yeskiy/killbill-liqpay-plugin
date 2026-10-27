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

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.StreamSupport;

import org.killbill.billing.payment.api.PluginProperty;

final class Props {

    private Props() {}

    /** The first value of the key as text, from the first list that has it, or null. */
    @SafeVarargs
    static String find(final String key, final Iterable<PluginProperty>... lists) {
        return Arrays.stream(lists)
                     .filter(Objects::nonNull)
                     .flatMap(l -> StreamSupport.stream(l.spliterator(), false))
                     .filter(p -> p != null && key.equals(p.getKey()) && p.getValue() != null)
                     .map(p -> p.getValue().toString())
                     .findFirst()
                     .orElse(null);
    }
}
