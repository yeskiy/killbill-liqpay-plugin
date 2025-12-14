# Kill Bill LiqPay Payment Plugin
[![Security Status](https://snyk.io/test/github/yeskiy/killbill-liqpay-plugin/badge.svg?targetFile=pom.xml&org=29bd1a78-0798-42bc-aaca-abbd019ff271)](https://snyk.io/test/github/yeskiy/killbill-liqpay-plugin?targetFile=pom.xml&org=29bd1a78-0798-42bc-aaca-abbd019ff271)

Payment plugin for [Kill Bill](https://killbill.io/) that integrates the [LiqPay](https://www.liqpay.ua/) payment gateway.

## Repository Scope
- Maven project targeting Java 11 with a shaded OSGi bundle output.

## Features
- Redirect-based single payments on the LiqPay hosted page
- Card pre-authorization (hold) with capture/void
- Token-based recurring payments via Kill Bill subscriptions
- Full and partial refunds
- Multi-currency support: UAH, USD, EUR

## Requirements
- Java 11+
- Maven 3.8+

## Build and Test
```bash
mvn clean verify
```
Outputs: `target/liqpay-plugin-1.0.0-SNAPSHOT.jar`


### Installation
```bash
mkdir -p /var/lib/killbill/bundles/plugins/java/killbill-liqpay
cp target/liqpay-plugin-1.0.0-SNAPSHOT.jar /var/lib/killbill/bundles/plugins/java/killbill-liqpay/
```

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `org.killbill.billing.plugin.liqpay.publicKey` | LiqPay public key | - |
| `org.killbill.billing.plugin.liqpay.privateKey` | LiqPay private key | - |
| `org.killbill.billing.plugin.liqpay.sandbox` | Use sandbox mode | `true` |
| `org.killbill.billing.plugin.liqpay.serverUrl` | Callback URL for payment notifications | - |
| `org.killbill.billing.plugin.liqpay.resultUrl` | Customer redirect URL after payment | - |
| `org.killbill.billing.plugin.liqpay.currencies` | Supported currencies | `UAH,USD,EUR` |
| `org.killbill.billing.plugin.liqpay.language` | Checkout page language | `en` |

## Usage Notes
- Redirect users to the LiqPay checkout page for single payments.
- For recurring charges, store and reuse LiqPay tokens returned after the initial charge.
- Pre-authorization holds can be captured or voided within LiqPay's allowed window.

## Testing
- Run tests: `mvn verify`
- Sandbox cards:
  - Success: `4242424242424242`
  - Decline: `4000000000000002`
- Generate sandbox keys via the [LiqPay Business Cabinet](https://www.liqpay.ua/en/adminbusiness).

## CI/CD and Releases
- CI runs on every push/PR: Maven build and tests.
- Commit messages are linted for Conventional Commits.
- Tagging `vX.Y.Z` triggers a release build and uploads the shaded JAR to the GitHub Release.
- Create and push a tag:
  ```bash
  git tag vX.Y.Z
  git push origin vX.Y.Z
  ```

## Contributing
- Follow Conventional Commits (`type(scope): summary`).
- See CONTRIBUTING.md for workflow details.

## License
Apache License 2.0
