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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Immutable plugin config. Secret values come from env vars that the config names.
 * Every error message names the key, never the value.
 */
public final class LiqPayConfig {

    public static final String PREFIX = "org.killbill.billing.plugin.liqpay.";

    private static final int MAX_KEY_FILE_BYTES = 1024;

    private final String publicKey;
    private final String privateKey;
    private final byte[] cardTokenKey;
    private final String cardTokenKeyId;
    private final boolean sandbox;
    private final URI serverUrl;
    private final List<String> resultUrlAllowlist;
    private final Set<String> currencies;
    private final Set<String> languages;
    private final String language;
    private final String description;
    private final Duration checkoutTtl;
    private final Duration expiryGrace;
    private final BigDecimal verificationAmount;
    private final String verificationCurrency;
    private final URI apiBaseUrl;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final String killbillUser;
    private final String killbillPassword;

    private LiqPayConfig(final Properties props, final Function<String, String> env) {
        if (props.containsKey(PREFIX + "privateKey") || props.containsKey(PREFIX + "cardTokenKey")
            || props.containsKey(PREFIX + "killbillPassword")) {
            throw new IllegalArgumentException("a literal key value is not allowed, use the Env or File keys");
        }
        this.publicKey = required(props, "publicKey");
        this.privateKey = fromEnv(env, required(props, "privateKeyEnv"), "privateKeyEnv");
        final String cardTokenKeyBase64 = secret(props, env, "cardTokenKeyEnv", "cardTokenKeyFile");
        this.cardTokenKey = cardTokenKeyBase64 == null ? null
                            : decodeAesKey(cardTokenKeyBase64, optional(props, "cardTokenKeyEnv", null) != null ? "cardTokenKeyEnv" : "cardTokenKeyFile");
        this.cardTokenKeyId = optional(props, "cardTokenKeyId", "1");
        this.sandbox = Boolean.parseBoolean(optional(props, "sandbox", "false"));
        this.serverUrl = httpsUrl(required(props, "serverUrl"), "serverUrl", false);
        this.resultUrlAllowlist = Collections.unmodifiableList(
                list(optional(props, "resultUrlAllowlist", "")).stream()
                                                               .map(u -> httpsUrl(u, "resultUrlAllowlist", false).toString())
                                                               .collect(Collectors.toList()));
        this.currencies = set(optional(props, "currencies", "USD"), true);
        this.languages = set(optional(props, "languages", "en,uk"), false);
        this.language = optional(props, "language", "en").toLowerCase(Locale.ROOT);
        if (!languages.contains(language)) {
            throw new IllegalArgumentException("language must be one of languages");
        }
        this.description = optional(props, "description", "Payment");
        this.checkoutTtl = duration(props, "checkoutTtl", "PT30M");
        this.expiryGrace = duration(props, "expiryGrace", "PT15M");
        this.verificationAmount = amount(optional(props, "verificationAmount", "1.00"));
        this.verificationCurrency = optional(props, "verificationCurrency", "UAH").toUpperCase(Locale.ROOT);
        this.apiBaseUrl = httpsUrl(optional(props, "apiBaseUrl", "https://www.liqpay.ua"), "apiBaseUrl", true);
        this.connectTimeout = duration(props, "connectTimeout", "PT10S");
        this.requestTimeout = duration(props, "requestTimeout", "PT30S");
        this.killbillUser = optional(props, "killbillUser", null);
        this.killbillPassword = secret(props, env, "killbillPasswordEnv", "killbillPasswordFile");
        if ((killbillUser == null) != (killbillPassword == null)) {
            throw new IllegalArgumentException("set killbillUser together with killbillPasswordEnv or killbillPasswordFile");
        }
    }

    public static LiqPayConfig parse(final Properties props, final Function<String, String> env) {
        return new LiqPayConfig(props, env);
    }

    /** True when the properties hold at least one key of this plugin. */
    public static boolean hasPluginKeys(final Properties props) {
        return props != null && props.stringPropertyNames().stream().anyMatch(k -> k.startsWith(PREFIX));
    }

    public String publicKey() { return publicKey; }

    public String privateKey() { return privateKey; }

    public boolean hasCardTokenKey() { return cardTokenKey != null; }

    public byte[] cardTokenKey() { return cardTokenKey == null ? null : Arrays.copyOf(cardTokenKey, cardTokenKey.length); }

    public String cardTokenKeyId() { return cardTokenKeyId; }

    public boolean sandbox() { return sandbox; }

    public URI serverUrl() { return serverUrl; }

    public List<String> resultUrlAllowlist() { return List.copyOf(resultUrlAllowlist); }

    public Set<String> currencies() { return Set.copyOf(currencies); }

    public Set<String> languages() { return Set.copyOf(languages); }

    public String language() { return language; }

    public String description() { return description; }

    public Duration checkoutTtl() { return checkoutTtl; }

    public Duration expiryGrace() { return expiryGrace; }

    public BigDecimal verificationAmount() { return verificationAmount; }

    public String verificationCurrency() { return verificationCurrency; }

    public URI apiBaseUrl() { return apiBaseUrl; }

    public Duration connectTimeout() { return connectTimeout; }

    public Duration requestTimeout() { return requestTimeout; }

    /** True when the tenant names a Kill Bill user for the calls that need a permission. */
    public boolean hasKillbillLogin() { return killbillUser != null; }

    public String killbillUser() { return killbillUser; }

    public String killbillPassword() { return killbillPassword; }

    /** True when the URL starts with an allowlist entry at a path boundary. */
    public boolean isResultUrlAllowed(final String url) {
        if (url == null) {
            return false;
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException e) {
            return false;
        }
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null
               && resultUrlAllowlist.stream().anyMatch(p -> url.equals(p) || url.startsWith(p.endsWith("/") ? p : p + "/")
                                                            || url.startsWith(p + "?"));
    }

    @Override
    public String toString() {
        return "LiqPayConfig{publicKey=" + publicKey
               + ", privateKey=<set>"
               + ", cardTokenKey=" + (cardTokenKey == null ? "<none>" : "<set>")
               + ", cardTokenKeyId=" + cardTokenKeyId
               + ", sandbox=" + sandbox
               + ", serverUrl=" + serverUrl
               + ", resultUrlAllowlist=" + resultUrlAllowlist
               + ", currencies=" + currencies
               + ", languages=" + languages
               + ", language=" + language
               + ", checkoutTtl=" + checkoutTtl
               + ", expiryGrace=" + expiryGrace
               + ", verificationAmount=" + verificationAmount
               + ", verificationCurrency=" + verificationCurrency
               + ", apiBaseUrl=" + apiBaseUrl
               + ", connectTimeout=" + connectTimeout
               + ", requestTimeout=" + requestTimeout
               + ", killbillUser=" + (killbillUser == null ? "<none>" : killbillUser)
               + ", killbillPassword=" + (killbillPassword == null ? "<none>" : "<set>") + "}";
    }

    // --- Parsing helpers ---

    private static String required(final Properties props, final String key) {
        final String value = optional(props, key, null);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String optional(final Properties props, final String key, final String fallback) {
        final String value = props.getProperty(PREFIX + key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** A secret from the env var or the file that the config names, or null when it names neither. */
    private static String secret(final Properties props, final Function<String, String> env, final String envKey, final String fileKey) {
        final String envName = optional(props, envKey, null);
        final String file = optional(props, fileKey, null);
        if (envName != null && file != null) {
            throw new IllegalArgumentException("set " + envKey + " or " + fileKey + ", not both");
        }
        return envName != null ? fromEnv(env, envName, envKey) : file != null ? fromFile(file, fileKey) : null;
    }

    private static String fromEnv(final Function<String, String> env, final String name, final String key) {
        final String value = env.apply(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " names an env var that is not set");
        }
        return value.trim();
    }

    /** Reads a small key file, for example a mounted Secret. The path must be absolute. */
    private static String fromFile(final String name, final String key) {
        final Path path;
        try {
            path = Path.of(name);
        } catch (final InvalidPathException e) {
            throw new IllegalArgumentException(key + " is not a valid path");
        }
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(key + " must name a readable file by its absolute path");
        }
        try (InputStream in = Files.newInputStream(path)) {
            final byte[] bytes = in.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (bytes.length > MAX_KEY_FILE_BYTES) {
                throw new IllegalArgumentException(key + " names a file that is too large");
            }
            final String value = new String(bytes, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(key + " names an empty file");
            }
            return value;
        } catch (final IOException e) {
            throw new IllegalArgumentException(key + " names a file that cannot be read");
        }
    }

    private static byte[] decodeAesKey(final String base64, final String key) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must hold base64");
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException(key + " must hold a 32-byte key");
        }
        return bytes;
    }

    private static URI httpsUrl(final String value, final String key, final boolean allowLocalHttp) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException(key + " is not a valid URL");
        }
        final boolean localHttp = allowLocalHttp && "http".equals(uri.getScheme())
                                  && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!("https".equals(uri.getScheme()) || localHttp) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(key + " must be an https:// URL");
        }
        return uri;
    }

    private static Duration duration(final Properties props, final String key, final String fallback) {
        try {
            final Duration d = Duration.parse(optional(props, key, fallback));
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return d;
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 duration");
        }
    }

    private static BigDecimal amount(final String value) {
        try {
            final BigDecimal a = new BigDecimal(value);
            if (a.signum() <= 0) {
                throw new IllegalArgumentException("verificationAmount must be positive");
            }
            return a;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("verificationAmount must be a number");
        }
    }

    private static List<String> list(final String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private static Set<String> set(final String csv, final boolean upper) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(
                list(csv).stream()
                         .map(s -> upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT))
                         .collect(Collectors.toList())));
    }
}
