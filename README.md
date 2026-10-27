# Kill Bill LiqPay Payment Plugin
[![Security Status](https://snyk.io/test/github/yeskiy/killbill-liqpay-plugin/badge.svg?targetFile=pom.xml&org=29bd1a78-0798-42bc-aaca-abbd019ff271)](https://snyk.io/test/github/yeskiy/killbill-liqpay-plugin?targetFile=pom.xml&org=29bd1a78-0798-42bc-aaca-abbd019ff271)

A payment plugin for [Kill Bill](https://killbill.io/) 0.24 that connects the [LiqPay](https://www.liqpay.ua/) payment gateway. The plugin name is `killbill-liqpay`.

| Item | Value |
|---|---|
| Plugin version | 2.0.0 |
| Kill Bill | 0.24.22 (killbill-api 0.54.0, plugin-api 0.27.3) |
| Java | 11 |
| LiqPay API | version 7, request signature SHA3-256 |
| Databases | PostgreSQL and MySQL |

## What the plugin does

A payment method has one of two modes. The property `liqpay_mode` of the payment method sets the mode. A payment method with no mode uses `checkout`.

| Mode | Use | Kill Bill calls |
|---|---|---|
| `checkout` | A one-time purchase on the LiqPay hosted page. No card data is stored | `purchasePayment` (PENDING with a signed form), `refundPayment`, `getPaymentInfo` |
| `token` | Card verification on the hosted page, then server payments with the stored card token | `buildFormDescriptor`, `purchasePayment`, `authorizePayment`, `capturePayment`, `voidPayment`, `refundPayment`, `getPaymentInfo` |

| Kill Bill call | LiqPay action |
|---|---|
| `purchasePayment`, mode `checkout` | `pay` on the hosted page (`/api/3/checkout`). The transaction stays PENDING until a callback or a status poll settles it |
| `buildFormDescriptor` | `hold` of `verificationAmount` with `recurringbytoken=1` on the hosted page |
| Verification callback with `hold_wait` | The plugin stores the encrypted `card_token`, sets `verified=true`, then releases the hold with `refund` |
| `purchasePayment`, mode `token` | `paytoken` |
| `authorizePayment` | `hold` by token |
| `capturePayment` | `hold_completion` of the hold order |
| `voidPayment` | `refund` of the held amount. LiqPay has no `unhold` action |
| `refundPayment` | `refund` of the paid order, full or partial, never above the paid amount minus earlier refunds |
| `creditPayment` | Not supported. The answer is CANCELED with `err_code=not_supported` |
| `getPaymentInfo` | `status` for each PENDING transaction, once for each read |

The plugin takes the amount and the currency from the Kill Bill call. It ignores `amount`, `currency`, `action`, `server_url` and `order_id` from the caller. The `order_id` of a payment is the Kill Bill transaction id.

## Install

1. Build the jar, or download `liqpay-plugin-2.0.0.jar` and `liqpay-plugin-2.0.0.jar.sha256` from the GitHub release.
2. Check the jar against the sha256 file.
3. Copy the jar to `/var/lib/killbill/bundles/plugins/java/killbill-liqpay/2.0.0/`.
4. Apply `src/main/resources/ddl-postgresql.sql` (PostgreSQL) or `src/main/resources/ddl.sql` (MySQL) to the Kill Bill database. Both files are idempotent.
5. Set the env vars that hold the keys (see "Secrets").
6. Upload the tenant config: `POST /1.0/kb/tenants/uploadPluginConfig/killbill-liqpay` with the keys of the next section, one `key=value` line each.

## Configuration

Each key has the prefix `org.killbill.billing.plugin.liqpay.`. A tenant with no config gets no default keys. Each call for that tenant then fails with `err_code=not_configured`. A config that breaks a rule is rejected at load, and the log names the key, never the value.

| Key | Default | Rule |
|---|---|---|
| `publicKey` | none | Required |
| `privateKeyEnv` | none | Required. The name of an env var that holds the private key. A key named `privateKey` with a value is rejected |
| `cardTokenKeyEnv` | none | The name of an env var that holds a base64 AES-256 key (32 bytes). Needed for mode `token` |
| `cardTokenKeyFile` | none | The absolute path of a file that holds the same base64 key, for example a mounted Secret. 1 KiB or less. Set `cardTokenKeyEnv` or `cardTokenKeyFile`, not both. A key named `cardTokenKey` with a value is rejected |
| `cardTokenKeyId` | `1` | Stored next to each encrypted token, and bound into the encryption |
| `sandbox` | `false` | `true` accepts the LiqPay status `sandbox` as success |
| `serverUrl` | none | Required. `https://` only. The plugin adds `?kbTenantId=<uuid>` |
| `resultUrlAllowlist` | none | Comma-separated `https://` prefixes. A `liqpay_result_url` must start with one of them at a path boundary |
| `currencies` | `USD` | The currencies of purchases and authorizations |
| `languages`, `language` | `en,uk`, `en` | The allowed and the default hosted page language |
| `description` | `Payment` | The default description |
| `checkoutTtl` | `PT30M` | Sent as `expired_date` of a hosted page |
| `expiryGrace` | `PT15M` | A PENDING checkout becomes CANCELED when LiqPay knows no such order after `checkoutTtl` plus this time |
| `verificationAmount`, `verificationCurrency` | `1.00`, `UAH` | The verification hold of mode `token` |
| `apiBaseUrl` | `https://www.liqpay.ua` | `https://` only, except `http://localhost` and `http://127.0.0.1` for tests |
| `connectTimeout`, `requestTimeout` | `PT10S`, `PT30S` | HTTP timeouts |
| `killbillUser` | none | The Kill Bill user that the callback logs in as before it settles a payment. See [Kill Bill user](#kill-bill-user) |
| `killbillPasswordEnv`, `killbillPasswordFile` | none | An env var name or an absolute file path that holds the password of `killbillUser`. Set one of them, and only with `killbillUser`. A key named `killbillPassword` with a value is rejected |

### Secrets

The tenant config holds no secret value, only references:

| Secret | Reference | Example |
|---|---|---|
| LiqPay private key | `privateKeyEnv` names an env var of the Kill Bill process | `privateKeyEnv=LIQPAY_PRIVATE_KEY`, with the env var set from a Kubernetes Secret |
| Card token key | `cardTokenKeyEnv` names an env var, or `cardTokenKeyFile` names a file | `cardTokenKeyEnv=LIQPAY_CARD_TOKEN_KEY`, or `cardTokenKeyFile=/run/secrets/liqpay-card-token-key` |
| Kill Bill user password | `killbillPasswordEnv` names an env var, or `killbillPasswordFile` names a file | `killbillPasswordFile=/run/secrets/killbill-liqpay-plugin-password` |

Make a card token key with `head -c 32 /dev/urandom | base64`. Keep it in your secret store. A lost key makes every stored token useless, and each customer must verify the card again. To change the key, give the new key a new `cardTokenKeyId`. A token stored under another key id is not readable, and a payment with it is CANCELED with `err_code=card_token_key_mismatch`.

## Plugin properties

Input on a payment method (`pluginInfo.properties`):

| Key | Values |
|---|---|
| `liqpay_mode` | `checkout` or `token` |

Input on a payment call or the hosted form (`pluginProperty` query parameters):

| Key | Calls | Rule |
|---|---|---|
| `liqpay_result_url` | Checkout purchase, hosted form | Required. Must match `resultUrlAllowlist`. Else a purchase is CANCELED with `err_code=result_url_not_allowed` |
| `liqpay_language` | Checkout purchase, hosted form, token calls | `en` or `uk`. Else the config default |
| `liqpay_description` | All payment calls, hosted form | 255 characters or less after a trim, control characters removed. Else the config default |
| `liqpay_payment_method_id` | Hosted form | Required. A `token` payment method of the account |
| `liqpay_ip` | Token calls | The client IP. LiqPay requires it for `paytoken`. A value that is not an IPv4 or IPv6 address is not sent |
| `liqpay_phone` | Token calls | The payer phone, 7 to 15 digits with an optional `+`. A bad value is not sent |

Output on a transaction (`properties` of the transaction):

| Key | When |
|---|---|
| `liqpay_order_id`, `liqpay_status`, `liqpay_payment_id`, `liqpay_err_code` | Always, when known |
| `liqpay_checkout_url`, `liqpay_data`, `liqpay_signature`, `liqpay_expires_at` | A PENDING checkout purchase. Post `data` and `signature` as a form to `liqpay_checkout_url` |

Output on a payment method (`getPaymentMethodDetail`): `liqpay_mode`, `verified` (`true` or `false`), `cardMask`, `cardType`. The plugin never returns the card token.

## Card verification for mode `token`

1. Add a payment method with `pluginName=killbill-liqpay` and the property `liqpay_mode=token`.
2. Call `POST /1.0/kb/paymentGateways/hosted/form/{accountId}?paymentMethodId=<id>` with the plugin properties `liqpay_payment_method_id=<id>` and `liqpay_result_url=<url>`.
3. Post the `formFields` `data` and `signature` to `formUrl`. The customer enters the card on the LiqPay page.
4. LiqPay sends the callback with `hold_wait` and the token. The plugin encrypts the token with AES-256-GCM, stores it, sets `verified=true` and releases the hold.
5. Read `verified` from `GET /1.0/kb/paymentMethods/{id}?withPluginInfo=true`.

A deleted payment method loses its token.

## Callback

LiqPay posts each status change to `POST /plugins/killbill-liqpay/callback?kbTenantId=<uuid>`, the `serverUrl` of the config. The plugin checks, in this order:

1. The method is POST, the content type is a form, and the body is 16 KiB or less.
2. `kbTenantId` is the UUID of a configured tenant.
3. `data` and `signature` are present, each 12 KiB or less.
4. The signature is correct (constant-time compare).
5. `data` is valid JSON within the parser limits.
6. `public_key` is the key of the tenant.
7. `order_id` is a known payment or verification of the tenant.
8. `action`, `amount` and `currency` are the stored values. A `paytoken` order reports `action=pay`.
9. `version` is 7.

| Case | HTTP |
|---|---|
| Processed, a replay, an older `end_date`, or a status that is not final | 200 |
| A failed check | 400. One warning log line with the tenant id and the reason code |
| A database or Kill Bill error | 503. LiqPay or the Kill Bill Janitor tries again |

A final status never goes back to PENDING. Logs hold ids, actions, statuses and error codes only. They never hold `data`, `signature`, a token or a key.

When a callback is final, the plugin tells Kill Bill with `notifyPendingTransactionOfStateChanged`. It does this only while the Kill Bill transaction is still PENDING. A callback for a row that is already final notifies again. Thus a retry of a callback that got 503 settles the payment.

### Kill Bill user

The notify needs the Kill Bill permission `payment:transition`. LiqPay sends no Kill Bill credentials, so the callback request is anonymous, and Kill Bill answers `UnauthenticatedException`. The callback then answers 503. For this reason, give each tenant a Kill Bill user with only this permission:

1. Create a role, for example `liqpay_plugin`, with the permissions `["payment:transition"]` (`POST /1.0/kb/security/roles`).
2. Create a user, for example `liqpay-plugin`, with that role only (`POST /1.0/kb/security/users`).
3. Put the password in a Secret of the Kill Bill process, as an env var or a file.
4. Set `killbillUser` and `killbillPasswordEnv` or `killbillPasswordFile` in the tenant config.

The plugin logs in as this user for each notify and logs out after it. Without `killbillUser`, the notify runs as the anonymous caller and works only when Kill Bill skips the check, for example with `org.killbill.security.skipAuthForPlugins=true`.

## LiqPay facts that change the integration

| Fact | Effect |
|---|---|
| The server APIs `paytoken` and `hold` are private at LiqPay | A production merchant needs LiqPay access for mode `token`. The sandbox keys accept both |
| LiqPay has no `unhold` action | Void and the verification release send `refund` of the held amount |
| On the sandbox, a partial `hold_completion` of a USD hold failed with `wrong_amount_currency`. A full completion of the same kind of hold worked | Capture the full held amount. The capture is ERROR with that `err_code` otherwise |
| A refund sent seconds after a payment can answer `payment_err_status` | The refund is ERROR. Try a new refund later |
| LiqPay reports no chargeback through the callback or the status API | Staff record a chargeback in Kill Bill |
| LiqPay cannot reach a callback URL on a private network | A PENDING payment settles through `getPaymentInfo`, for example `GET /1.0/kb/payments/{id}?withPluginInfo=true`, or the Kill Bill Janitor |

## Build and test

```bash
mvn -B verify
```

The build needs Temurin 11 and Docker. Docker runs the DAO tests on `postgres:18` and `mysql:8.4` with Testcontainers. `KillBillCallbackIT` starts Kill Bill 0.24.22 on `killbill/postgresql:0.24` with the built bundle. It sends a signed callback and checks that the Kill Bill payment becomes SUCCESS. The output is `target/liqpay-plugin-2.0.0.jar`, an OSGi bundle with its own Jackson 2.22.3. The manifest test fails when the bundle imports a `com.fasterxml.jackson` package from Kill Bill.

With no local Java, run the build in a container:

```bash
docker run --rm -v "$PWD:/src" -w /src -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-11 mvn -B verify
```

### Sandbox signature test

`LiqPaySandboxIT` sends one `status` call to the LiqPay sandbox and checks that LiqPay accepts the signature. It runs in `mvn verify` only when both env vars are set. Otherwise it is skipped.

| Env var | Value |
|---|---|
| `LIQPAY_SANDBOX_PUBLIC_KEY` | The sandbox public key, for example `sandbox_i00000000000` |
| `LIQPAY_SANDBOX_PRIVATE_KEY_FILE` | The path of a file that holds the sandbox private key. Never put the key itself in an env var or a repository |

In a container, mount the key file read-only and pass its path:

```bash
docker run --rm -v "$PWD:/src" -w /src -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v "/path/to/sandbox-private.key:/run/liqpay/sandbox-private:ro" \
  -e LIQPAY_SANDBOX_PUBLIC_KEY=sandbox_i00000000000 -e LIQPAY_SANDBOX_PRIVATE_KEY_FILE=/run/liqpay/sandbox-private \
  maven:3.9-eclipse-temurin-11 mvn -B verify
```

### Sandbox run

`scripts/sandbox-checkout.sh` tests the plugin in a running Kill Bill against the LiqPay sandbox:

1. It makes an account and a `checkout` payment method, then a purchase of 2.00 USD. The purchase is PENDING with a signed form.
2. Playwright opens the LiqPay page and pays with the sandbox test card `4242424242424242`.
3. It reads the payment with `withPluginInfo=true` until the purchase is SUCCESS. No callback is needed.
4. It refunds half of the amount and expects SUCCESS.

The tenant config needs the sandbox keys, `sandbox=true` and the currency `USD`. Settings:

| Env var | Value |
|---|---|
| `LIQPAY_RESULT_URL` | Required. A URL that matches `resultUrlAllowlist` |
| `KILLBILL_URL` | The Kill Bill URL as the container sees it. Default `http://127.0.0.1:8080` |
| `KB_NETWORK` | The Docker network of the container. Default `host` |
| `KB_ENV_FILE` | An env file with `KILLBILL_API_KEY`, `KILLBILL_API_SECRET`, `KILLBILL_USER` and `KILLBILL_PASSWORD`. Without it, the script passes these four env vars by name |
| `PLAYWRIGHT_IMAGE` | The Playwright image. Default `mcr.microsoft.com/playwright:v1.64.1-noble`, pinned by digest |
| `SANDBOX_AMOUNT` | The purchase amount. Default `2.00` |

The script never prints a credential. It passes each one by name or by env file.

## CI and releases

| Workflow | Trigger | Steps |
|---|---|---|
| `ci.yml` | Push, pull request | `mvn -B verify` on Temurin 11, and an OSV scan of `pom.xml` that fails on HIGH or CRITICAL |
| `release.yml` | Tag `v*` | `mvn -B verify`, then the jar and its `.sha256` file go to the GitHub release |
| `commitlint.yml` | Pull request | Conventional Commits check |

The tag must match the pom version, for example tag `v2.0.0` for version `2.0.0`.

## Upgrade from 1.x

Version 2.0.0 is a rewrite. Property names, config keys and tables changed, and the plugin stores no raw LiqPay answer. The 1.x code is on the branch `legacy/v1` and the tag `v1-final`.

| 1.x | 2.0.0 |
|---|---|
| `privateKey` in the config | `privateKeyEnv`, an env var name |
| `resultUrl` in the config | `liqpay_result_url` on each call, checked against `resultUrlAllowlist` |
| Card tokens in clear text | AES-256-GCM with `cardTokenKeyEnv` or `cardTokenKeyFile` |
| `/plugins/killbill-liqpay/hpp/{id}` | `POST /1.0/kb/paymentGateways/hosted/form/{accountId}` |
| Request signature SHA-1 | SHA3-256 |

See [CONTRIBUTING.md](CONTRIBUTING.md) for the commit rules. The license is Apache 2.0, see [LICENSE](LICENSE).
