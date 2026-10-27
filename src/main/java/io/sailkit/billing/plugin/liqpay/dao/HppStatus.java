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


package io.sailkit.billing.plugin.liqpay.dao;

import java.util.List;

/** States of a card verification request in `liqpay_hpp_requests`. RELEASED and FAILED are final. */
public final class HppStatus {

    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";
    public static final String RELEASED = "RELEASED";
    public static final String FAILED = "FAILED";
    public static final List<String> FINAL = List.of(RELEASED, FAILED);

    private HppStatus() {}
}
