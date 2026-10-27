# LiqPay API Documentation (API-only)

**API version:** 7 for every request, checkout included
**Request signature:** `base64(sha3-256(private_key + data + private_key))`
**Base URL (client to server, checkout):** `https://www.liqpay.ua/api/3/checkout`
**Base URL (server to server):** `https://www.liqpay.ua/api/request`

This file holds API behavior only: request envelopes, parameters, statuses and verification flows. It leaves out SDK references and marketing text.

Scan date: 2026-10. Each section names the live page that it comes from. A section marked "not re-read" keeps the text of the 2025-12 scan.

---

## Changes since the 2025-12 scan

| Item | 2025-12 text | Live text (2026-10) |
|---|---|---|
| Request signature | `base64_encode(sha1(private_key + data + private_key))` for a v7 request | `base64_encode(sha3-256(private_key + data + private_key))` for every request. Source: the "self-integration" block of the checkout page |
| Version in checkout | "use 3 in checkout calls" | `version` is 7 in every parameter table. The doc test vector sends version 7 to `api/3/checkout` |
| Checkout endpoint | `https://www.liqpay.ua/api/3/` | `POST https://www.liqpay.ua/api/3/checkout` (client to server). Server to server: `https://www.liqpay.ua/api/request` |
| `expired_date` | `yyyy-MM-dd HH:mm:ss` UTC | Same format, UTC, example `2016-04-24 00:00:00` |
| SDK samples | - | The BASH, PHP and other SDK samples on the live pages still show `version: 3` and SHA-1. The parameter tables and the self-integration block show version 7 and SHA3-256. This file follows the tables |
| `sandbox` request parameter | `sandbox: 1` turns on test mode | Not in any live parameter table. The testing page says that test mode comes from keys that start with `sandbox_` |
| `sandbox` status | - | Not in the live status lists of the callback and status pages |
| `unhold` action | "There is no `action: unhold` API" | Still no `unhold`. The two-step page says: "Unblock funds by using the Returns API" (`action: refund`) |
| Callback retries | "LiqPay retries with backoff until HTTP 200 is received" | The callback page says nothing about retries. It says: use the Payment Status API to get the current status before the final callback |
| Refund response | Statuses `reversed`, `wait_accept` | Fields `result` (`ok`, `error`), `wait_amount` (boolean), `payment_id`, `status`. Final statuses: `error`, `failure`, `reversed`, `success` |
| `try_again` | Not listed | Listed on the status page: "Payment unsuccessful. Client can try again" |
| Chargeback | Not covered | No chargeback or dispute status, action or field on the callback, status, refund or error pages. The two-step page says only that a bank "may dispute the transaction" after the hold period |
| Date fields in answers | Not covered | `create_date` and `end_date` are Unix time in milliseconds, sent as strings (status page example) |
| Number fields in answers | Not covered | The status page example sends `amount` and `payment_id` as JSON strings |
| `paytoken` | `ip` required | `ip` is still required ("Client IP") |

---

## 1. Requests and signatures

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/checkout?tab=1 (self-integration block), scan 2026-10.

Every API call posts two form fields:
- `data`: base64 of the JSON string of the request parameters.
- `signature`: `base64_encode(sha3-256(private_key + data + private_key))`. The SHA3-256 hash is the 32-byte binary value.

The callback uses the same signature (section 7).

Compute the signature on the exact base64 text that you send or receive.

### Doc test vector

| Item | Value |
|---|---|
| `private_key` | `a4825234f4bae72a0be04eafe9e8e2bada209255` |
| `json_string` | `{"public_key":"i00000000","version":7,"action":"pay","amount":"3","currency":"UAH","description":"test","order_id":"000001"}` |
| `data` | `eyJwdWJsaWNfa2V5IjoiaTAwMDAwMDAwIiwidmVyc2lvbiI6NywiYWN0aW9uIjoicGF5IiwiYW1vdW50IjoiMyIsImN1cnJlbmN5IjoiVUFIIiwiZGVzY3JpcHRpb24iOiJ0ZXN0Iiwib3JkZXJfaWQiOiIwMDAwMDEifQ==` |
| `sign_string` | `private_key + data + private_key` |
| `signature` | `0adgJ8F2Ds5HCVkcz4AlmdLMRoIJf7IxsL3QmeFRz/s=` |

The private key above is the public example key of the LiqPay docs, not a merchant key.

Common parameters:
- `version` (Number, required): 7
- `public_key` (String, required)
- `action` (String, required)
- `order_id` (String, required): unique for each merchant, 255 characters or less
- `description` (String, required for payments)
- `amount` (Number, required for payments): for example `5`, `7.34`
- `currency` (String): `USD`, `EUR`, `UAH`. LiqPay adds other currencies on request of the company
- `language` (String): `uk`, `en`
- `server_url` (String): callback URL, 510 characters or less
- `result_url` (String): the URL that the buyer returns to, 510 characters or less

---

## 2. Internet acquiring APIs

### 2.1 Checkout (public)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/checkout?tab=1, scan 2026-10.

Endpoint: `POST https://www.liqpay.ua/api/3/checkout`, as an auto-submit HTML form with the hidden fields `data` and `signature`.

Required: `version`, `public_key`, `action`, `amount`, `currency`, `description`, `order_id`.

`action` values: `pay` (payment), `hold` (hold on the account of the sender), `subscribe` (regular payment), `paydonate` (donation).

Optional:
- `expired_date`: the date and time until which the customer can pay, UTC, format `2016-04-24 00:00:00`
- `language`: `uk`, `en`
- `paytypes`: `apay`, `gpay`, `card`, `privat24`, `moment_part`, `paypart`, `cash`, `invoice`, `qr`. Without it, the shop settings apply
- `result_url`, `server_url`
- `verifycode`: `Y`. Works for `action = auth`
- `rro_info` (fiscal data): `items` (`amount`, `cost`, `id`, `price`), `delivery_emails`
- `split_rules`: JSON array of `{public_key, amount, commission_payer, server_url, rro_info}`
- Sender: `sender_address`, `sender_city`, `sender_country_code` (ISO 3166-1 numeric), `sender_first_name`, `sender_last_name`, `sender_postal_code`
- Regular payment: `subscribe` (`1`), `subscribe_date_start` (UTC), `subscribe_periodicity` (`day`, `week`, `month`, `year`)
- 1-click: `customer` (100 characters or less), `recurringbytoken` (`1` returns `card_token` in the callback), `customer_user_id`
- Other: `dae` (MCC 4511), `info`, `product_category` (25), `product_description` (500), `product_name` (100), `product_url` (2000)

Example (simple card payment):
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "pay",
  "amount": 10,
  "currency": "USD",
  "description": "Order 1001",
  "order_id": "order_1001",
  "expired_date": "2026-10-18 12:30:00",
  "result_url": "https://merchant/success",
  "server_url": "https://merchant/callback"
}
```

Edge cases: a repeated `order_id` gives `order_id_duplicate`. A `split_rules` total that differs from `amount` gives `err_split_amount`.

### 2.2 Payment widget (public)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/widget, not re-read in 2026-10.

The widget uses the same `data` as checkout. Embed parameters: `data`, `signature`, `embedTo` (selector), `mode` (`embed` or `popup`), `language`. Script: `https://static.liqpay.ua/libjs/checkout.js`. Card data stays inside the LiqPay iframe. Events: `liqpay.callback`, `liqpay.close`.

### 2.3 Card payment (server to server, PCI scope)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/card_payment, not re-read in 2026-10.

Endpoint: `POST https://www.liqpay.ua/api/request`. Required: `version`, `public_key`, `action: pay`, `amount`, `currency`, `description`, `order_id`, `card`, `phone`, `ip`. This API needs PCI DSS certification.

### 2.4 PrivatPay button (public)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/privat_pay, not re-read in 2026-10.

Use checkout or the widget with `paytypes=privat24`.

### 2.5 Apple Pay (public and server)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/apay, not re-read in 2026-10.

Checkout or widget with `paytypes=apay`. Server API: `paytype` `apay` (encrypted token) or `apay_tavv` (with `tavv`).

### 2.6 Google Pay (public and server)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/gpay, not re-read in 2026-10.

Checkout or widget with `paytypes=gpay`. Server API: `paytype` `gpay` or `gpay_tavv`.

### 2.7 QR payment (public)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/qr, not re-read in 2026-10.

Checkout or widget with `paytypes=qr`. The status comes through the callback.

### 2.8 Token payment (server, private)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/token?tab=1, scan 2026-10.

The page marks this API as private. LiqPay gives access on request.

Endpoint: `POST https://www.liqpay.ua/api/request`

Required: `version`, `public_key`, `action: paytoken`, `amount`, `card_token`, `currency`, `description`, `ip` (client IP), `order_id`.

Optional: `phone`, `language`, `prepare`, `server_url`, `split_rules`, sender fields, `customer`, `info`, `product_*`.

Example:
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "paytoken",
  "amount": 35,
  "currency": "USD",
  "description": "Renewal",
  "order_id": "renew_22",
  "card_token": "B5B0D00B88B00ED00A00D0D0",
  "ip": "203.0.113.20",
  "server_url": "https://merchant/callback"
}
```

### 2.9 Subscription

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/subscription, not re-read in 2026-10.

Checkout or widget with `action: subscribe`, `subscribe: 1`, `subscribe_date_start`, `subscribe_periodicity`. Statuses: `subscribed`, `unsubscribed`, and the payment statuses.

### 2.10 Refund

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/refund?tab=1, scan 2026-10.

Endpoint: `POST https://www.liqpay.ua/api/request`

Required: `version`, `public_key`, `action: refund`, `amount`, `order_id`. The `amount` can be less than the payment amount (partial refund).

Answer:

| Field | Type | Meaning |
|---|---|---|
| `result` | String | `ok` or `error` |
| `wait_amount` | Boolean | `true`: the refund comes from future payments. `false`: from the account of the merchant |
| `payment_id` | Number | Payment id in LiqPay |
| `status` | String | Final: `error`, `failure`, `reversed` (refunded), `success` |

Example answer:
```json
{
  "result": "ok",
  "wait_amount": "true",
  "payment_id": "2417662437",
  "status": "reversed"
}
```

Edge cases (2025-12 scan, not shown on the live page): a refund over the captured amount and a refund after a full reversal give `err_amount_hold` or `payment_err_status`.

### 2.11 Two-step payment (hold)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/two_step and https://www.liqpay.ua/en/doc/api/internet_acquiring/two_step?tab=1, scan 2026-10.

The page marks the server API as private. Without PCI DSS, use checkout or the widget with `action: hold`.

- **Hold:** `action: hold`. Required: `version`, `public_key`, `amount`, `currency`, `description`, `order_id`, `phone`, and a card or a token. Optional: `recurringbytoken`, `is_recurring`, `server_url`, `language`, `prepare`, `split_rules`. A successful hold has status `hold_wait`.
- **Completion:** `action: hold_completion`. Required: `version`, `public_key`, `amount`, `order_id`. Optional: `rro_info`, `split_tickets_only`. A partial completion cancels the hold on the remaining part.
- **Release:** there is no `unhold` action. The page says: "Unblock funds by using the Returns API". Send `action: refund` with the held amount.

Hold periods: the bank of the payer guarantees the hold for 7 days (Mastercard) or 10 days (Visa). After that period, the bank releases the funds. A completion is possible for 30 days, but after the guaranteed period the bank of the payer can dispute the transaction.

Edge cases (2025-12 scan): a completion over the held amount gives `err_amount_hold`. A second completion can give `payment_err_status`.

### 2.11.1 Use case: token capture through a hold and a refund

Source: 2025-12 scan, checked against the two-step page in 2026-10.

1. Make a hold for a small amount (for example 1 UAH) with `recurringbytoken: 1`.
2. The callback has status `hold_wait`, `card_token`, `sender_card_mask2` and `sender_card_type`.
3. Release the hold with `action: refund` and the held amount.
4. Keep the token for later payments with `action: paytoken`.

### 2.12 Splitting

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/splitting, not re-read in 2026-10.

Add `split_rules` to checkout, widget, card payment, token payment, two-step or QR. Each item: `{public_key, amount, commission_payer, server_url, rro_info}`.

### 2.13 Invoice (public)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/invoice, not re-read in 2026-10.

Checkout or widget with `action: invoice`. Statuses: `invoice_wait`, then `success`.

### 2.14 Cash payment (public)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/cash, not re-read in 2026-10.

Checkout or widget with `paytypes=cash`. Status `cash_wait` until the customer pays at a terminal.

### 2.15 DCC (dynamic currency conversion)

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/dcc, not re-read in 2026-10.

Server card payment with `prepare: tariffs` gets a conversion offer. Then send `action: pay` without `prepare`.

### 2.16 Card verification (`action: auth` and `action: cardverification`)

Source: https://www.liqpay.ua/en/doc/api/confirmation/cardverification?tab=1, scan 2026-10.

- Client to server: checkout or widget with `action: auth` and `verifycode: Y`. LiqPay returns the dynamic code in the callback.
- Server to server (private, PCI DSS): `action: cardverification`. Required: `version`, `public_key`, `card`, `card_cvv`, `card_exp_month`, `card_exp_year`, `description`, `ip`, `order_id`. Optional: `language`, `verifycode`.

The answer has `status` and, when requested, `verifycode`. The docs do not say that `auth` or `cardverification` returns a `card_token`. To get a token, use a payment or a hold with `recurringbytoken: 1`.

---

## 3. Testing

Source: https://www.liqpay.ua/en/doc/api/testing, scan 2026-10.

- LiqPay makes two key pairs for each merchant: a test pair and a production pair. Test keys start with `sandbox_`. No real money moves with them.
- The live page does not name a `sandbox` request parameter.
- Test cards. CVV2: any three digits. Expiry: any `MM/YY` date that is not in the past.

| Card | Result |
|---|---|
| `4242424242424242` | Successful payment |
| `4000000000003063` | Successful payment with 3DS |
| `4000000000003089` | Successful payment with OTP |
| `4000000000003055` | Successful payment with CVV |
| `4000000000000002` | Failed payment, error code `limit` |
| `4000000000009995` | Failed payment, error code `9859` |
| `sandbox_token` | Successful payment with a token |

---

## 4. Transfers and P2P

### 4.1 Transfer to card (`p2pcredit`)

Source: https://www.liqpay.ua/en/doc/api/transferring_to_card, not re-read in 2026-10.

`POST https://www.liqpay.ua/api/request` with `action: p2pcredit`, the receiver fields, `amount`, `currency`, `description`, `order_id`.

### 4.2 P2P debit (`p2pdebit`)

Source: https://www.liqpay.ua/en/doc/api/p2pdebit, not re-read in 2026-10.

`POST https://www.liqpay.ua/api/request` with `action: p2pdebit` and the card fields of the payer.

---

## 5. Tokenization APIs

### 5.1 Token without a charge

Source: https://www.liqpay.ua/en/doc/api/tokens, scan 2026-10.

A private API gets a card token without a payment. The merchant sends the card number, the expiry and the CVV from its server, so PCI DSS certification is required. LiqPay returns a token or a token reference. The page also has a "Status change" tab for token control.

### 5.2 Use of tokens

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/token, scan 2026-10.

- The first payment goes through checkout, the widget, a two-step payment, a card payment or a QR payment. After a successful payment, LiqPay returns the card token.
- Later payments send only the token, with `action: paytoken` (section 2.8).
- A token belongs to one merchant. Error `101` means that the token does not belong to this merchant.

---

## 6. Verification flows (3DS, OTP, CVV)

Source: https://www.liqpay.ua/en/doc/api/confirmation/3ds, not re-read in 2026-10.

Statuses that need one more step:
- `3ds_verify`: send the customer to `redirect_to`.
- `otp_verify`: get the OTP that LiqPay sent to `confirm_phone`, and send it in a new request.
- `cvv_verify`: ask for the CVV and send it again in `card_cvv`.

---

## 7. Callback

Source: https://www.liqpay.ua/en/doc/api/callback, scan 2026-10.

- After LiqPay gets the final status, it sends a POST to `server_url` with `data` (base64 JSON) and `signature`.
- Check the signature: `base64_encode(sha3-256(private_key + data + private_key))`, then compare it with the received value.
- To get the current status before the final callback, use the Payment Status API (section 7.1) at any time.
- The page says nothing about retries of a callback.

Response fields (selected): `acq_id`, `action`, `amount`, `card_token`, `completion_date`, `create_date`, `currency`, `customer`, `description`, `end_date`, `err_code`, `err_description`, `info`, `ip`, `is_3ds`, `liqpay_order_id`, `mpi_eci`, `order_id`, `payment_id`, `paytype`, `public_key`, `redirect_to`, `refund_date_last`, `refund_amount`, `sender_card_mask2`, `sender_card_type`, `sender_card_country`, `status`, `token`, `type`, `version` (7), `verifycode`, `wait_reserve_status`.

`action` values in the callback: `pay`, `hold`, `subscribe`, `regular`. The status page adds `paysplit`, `paydonate` and `auth`.

Status list (callback page and status page):

| Group | Statuses |
|---|---|
| Final | `error` (data is incorrect), `failure`, `reversed` (refunded), `subscribed`, `success`, `unsubscribed`. The status page also lists `try_again` (the client can try again) |
| Confirmation required | `3ds_verify`, `captcha_verify`, `cvv_verify`, `ivr_verify`, `otp_verify`, `password_verify`, `phone_verify`, `pin_verify`, `senderapp_verify`, `wait_qr`, `wait_sender`. The status page also lists `p24_verify` and `mp_verify` |
| Other | `cash_wait`, `hold_wait` (amount is held), `invoice_wait`, `prepared`, `processing`, `wait_accept` (money taken, shop not verified, cancelled after 60 days), `wait_card`, `wait_compensation` (successful, paid in the daily settlement), `wait_lc`, `wait_reserve`, `wait_secure` |

`wait_reserve_status`: `true` means that the payment is reserved for a refund.

Chargebacks: the callback, status, refund and error pages show no chargeback or dispute status, action or field. A chargeback does not reach the plugin through the callback or the status API. Staff record it in Kill Bill.

### 7.1 Payment status

Source: https://www.liqpay.ua/en/doc/api/information/status_payment?tab=1, scan 2026-10.

`POST https://www.liqpay.ua/api/request` with `version` 7, `public_key`, `action: status`, `order_id`.

The answer has `result` (`ok`, `error`) and the callback fields, plus `transaction_id`, `bonus_*`, `confirm_phone`, `language`, `moment_part`. In the page example, `amount`, `payment_id`, `create_date` and `end_date` are JSON strings. `create_date` and `end_date` are Unix time in milliseconds.

Example answer (selected fields):
```json
{
  "action": "pay",
  "amount": "0.1",
  "create_date": "1715597977414",
  "currency": "UAH",
  "end_date": "1715598023416",
  "order_id": "idByCard34v5D30esm7x",
  "payment_id": "13291813",
  "public_key": "i00000000000",
  "result": "ok",
  "status": "success",
  "transaction_id": "13291813",
  "type": "buy",
  "version": "7"
}
```

For an unknown `order_id`, the error list has `payment_not_found` ("Payment is not found").

---

## 8. Error codes

Source: https://www.liqpay.ua/en/doc/api/errors, scan 2026-10.

An error answer has `status` (`error` or `failure`), `err_code` and `err_description`.

| Group | Codes |
|---|---|
| Anti-fraud | `limit`, `frod`, `decline` |
| Signature | `invalid_signature`, `err_signature` |
| Order and payment | `order_id_empty`, `order_id_duplicate`, `payment_not_found`, `payment_err_status`, `payment_processing`, `payment_not_subscribed`, `err_amount_hold`, `wrong_amount_currency`, `err_payment_amount_limit`, `err_limit_amount`, `amount_limit` |
| Request | `err_auth`, `err_access`, `err_blocked`, `err_empty`, `err_missing`, `err_wrong`, `err_path`, `err_api_action`, `err_api_callback`, `err_api_ip`, `public_key_not_found`, `shop_blocked`, `shop_not_active`, `err_wrong_currency`, `err_currency_is_not_allowed` |
| Token | `err_no_card_token`, `101` (not owned by this merchant), `102` (not active), `108` (no such token) |
| Expiry | `err_cache`, `expired_phone`, `expired_3ds`, `expired_otp`, `expired_cvv`, `expired_p24`, `expired_sender`, `expired_pin`, `expired_ivr`, `expired_captcha`, `expired_password`, `expired_senderapp`, `expired_prepared`, `expired_mp`, `expired_qr` |
| Financial | `90`, `103` to `107`, `109` to `115`, `2903`, `2915`, `3914`, `9851`, `9852`, `9854`, `9855`, `9857`, `9859`, `9860`, `9861`, `9863`, `9867`, `9868`, `9872`, `9882`, `9886`, `9961`, `9989` |

---

## 9. Support

Source: https://www.liqpay.ua/en/doc/api/internet_acquiring/two_step, scan 2026-10.

Technical support: liqpay.support@privatbank.ua. Phone: 3700 (free from mobile phones in Ukraine).
