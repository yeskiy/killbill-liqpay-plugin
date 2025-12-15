# LiqPay API Documentation (API-only)

**API version:** 7  
**Base URL (public checkout/widget):** https://www.liqpay.ua/api/3/  
**Base URL (server-to-server):** https://www.liqpay.ua/api/request

This document is trimmed to API behavior only: request envelopes, required/optional parameters, statuses, and verification flows. SDK references and marketing content are intentionally omitted.

---

## 1. Requests & Signatures

All API calls post two fields:
- `data`: base64-encoded JSON of request parameters
- `signature`: request signature

**Request signature (v7):** `base64_encode(sha1(private_key + data + private_key))`

**Callback signature (v7):** `base64_encode(sha3-256(private_key + data + private_key))`

> Always compute signatures on the exact base64 payload you send/receive.

Minimal envelope:
```json
{
  "data": "BASE64_JSON",
  "signature": "BASE64_SIGNATURE"
}
```

Common parameters (appear across APIs):
- `version` (Number, required): 7
- `public_key` (String, required)
- `order_id` (String, required): unique per merchant (≤255 chars)
- `description` (String, required)
- `amount` (Number, required when charging)
- `currency` (String): `UAH`, `USD`, `EUR`
- `language` (String): `uk`, `en`
- `server_url` (String): callback URL (≤510 chars)
- `result_url` (String): customer redirect URL (≤510 chars)
- `sandbox` (Number): `1` to enable sandbox/test mode

---

## 2. Internet Acquiring APIs

### 2.1 Checkout (Public)
Endpoint: `POST https://www.liqpay.ua/api/3/checkout`

Required: `version`, `public_key`, `action`, `amount`, `currency`, `description`, `order_id`

`action` values: `pay`, `hold`, `subscribe`, `paydonate`, `apay`, `gpay`, `split_rules`

Optional (selected):
- `language`
- `result_url`, `server_url`
- `expired_date` (`yyyy-MM-dd HH:mm:ss` UTC)
- `paytypes`: comma list `apay,gpay,card,privat24,moment_part,paypart,cash,invoice,qr`
- `sandbox`: `1`
- `rro_info` object: `{ items: [{amount, price, cost, id}], delivery_emails: [] }`
- `split_rules`: JSON array of recipients `{ public_key, amount, commission_payer (sender|receiver), server_url, rro_info }`
- `customer`, `recurringbytoken:1`, `customer_user_id`
- Subscription: `subscribe:1`, `subscribe_date_start` (UTC), `subscribe_periodicity` (`day|week|month|year`)
- Sender: `sender_first_name`, `sender_last_name`, `sender_city`, `sender_address`, `sender_country_code` (ISO 3166-1 numeric), `sender_postal_code`
- Other: `info`, `product_*`, `dae`, `verifycode` (for `auth`), `prepare` (`tariffs` for DCC quote)

Response: see Callback fields and payment statuses.

Examples:
- Simple card payment:
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "pay",
  "amount": 10,
  "currency": "USD",
  "description": "Order 1001",
  "order_id": "order_1001",
  "result_url": "https://merchant/success",
  "server_url": "https://merchant/callback"
}
```
- Hold via checkout (customer authorizes on LiqPay page, then you capture server-side):
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "hold",
  "amount": 50,
  "currency": "UAH",
  "description": "Hotel hold",
  "order_id": "hold_2024_001",
  "result_url": "https://merchant/return",
  "server_url": "https://merchant/callback"
}
```
Edge cases: duplicate `order_id` yields `order_id_duplicate`; `expired_date` too soon cancels unpaid invoices; mismatch between `split_rules.amount` total and `amount` returns `err_split_amount`.

### 2.2 Payment Widget (Public)
Uses same `data` payload as Checkout. Embed parameters:
- `data`, `signature` (required)
- `embedTo` (selector), `mode` (`embed|popup`), `language`
Script: `//static.liqpay.ua/libjs/checkout.js`

Modes and redirects:
- Redirect (full page): use Checkout form post; LiqPay hosts the page and redirects to `result_url` when done. Card entry happens on LiqPay.
- Widget popup: `mode: "popup"` opens LiqPay in an overlay iframe; LiqPay handles card entry in its domain. No page navigation; you get events (`liqpay.callback`, `liqpay.close`). `result_url` is still used if the flow leaves to external 3DS/bank page; after completion LiqPay can navigate the top window to `result_url` or fall back to the popup closing with `status` in the callback.
- Widget embed: `mode: "embed"` renders an iframe inside your page (`embedTo`). Card data is entered inside the LiqPay iframe, not your DOM (keeps PCI burden off you). `result_url` behaves like popup: used only if the flow needs a full-page step (e.g., bank redirect); otherwise rely on `liqpay.callback` event for final status.

Iframe/card entry notes:
- You cannot host LiqPay inputs in your own form; card fields live inside the LiqPay iframe delivered by `checkout.js` (PCI segregation). If you need to collect PAN on your page, use the server-to-server Card Payment API and meet PCI requirements.
- Do not wrap the widget iframe in additional iframes that block top-level redirects; 3DS/bank steps may require top navigation.

Example (embed mode collecting card in LiqPay iframe):
```html
<div id="liqpay_checkout"></div>
<script>
window.LiqPayCheckoutCallback = function() {
  LiqPayCheckout.init({
    data: "BASE64_DATA",
    signature: "BASE64_SIGNATURE",
    embedTo: "#liqpay_checkout",
    mode: "embed",
    language: "en"
  }).on("liqpay.callback", function(data){
    console.log("status", data.status);
  }).on("liqpay.close", function(){
    console.log("closed");
  });
};
</script>
<script src="https://static.liqpay.ua/libjs/checkout.js" async></script>
```

### 2.3 Card Payment (Server-to-Server, PCI scope)
Endpoint: `POST https://www.liqpay.ua/api/request`

Required: `version`, `public_key`, `action: pay`, `amount`, `currency`, `description`, `order_id`, `card`, `phone`, `ip`

Card fields: `card` (PAN), `card_exp_month`, `card_exp_year`, `card_cvv` (CVV required when PAN provided)

Token / wallet paytypes:
- `paytype`: `apay`, `gpay` (encrypted tokens), `apay_tavv`, `gpay_tavv`, `tavv`
- `tavv`: cryptogram for unencrypted tokens
- `tid`: previous transaction id (recurring Visa token)

Options:
- `prepare:1` (validation only) or `prepare: tariffs` (DCC quote)
- `recurringbytoken:1` (return `card_token`)
- `server_url`, `result_url`
- `language`
- 3DS/MPI fields: `eci`, `cavv`, `tdsv`, `dsTransID`, `mpi_pares`, `mpi_md`, `cres`
- Sender: `sender_*` (first_name, last_name, country_code, city, address, postal_code, email)
- `is_recurring` (Boolean): token payments without customer

Statuses: `success|error|failure|reversed|3ds_verify|otp_verify|cvv_verify|receiver_verify|sender_verify|wait_accept|wait_secure` plus standard list in section 8.

Examples:
- Direct PAN charge:
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "pay",
  "amount": 15,
  "currency": "EUR",
  "description": "Ticket",
  "order_id": "srv_2001",
  "card": "4242424242424242",
  "card_exp_month": "12",
  "card_exp_year": "30",
  "card_cvv": "123",
  "phone": "+380950000001",
  "ip": "203.0.113.10",
  "server_url": "https://merchant/callback"
}
```
- DCC quote without charging:
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "pay",
  "amount": 100,
  "currency": "USD",
  "description": "DCC check",
  "order_id": "dcc_01",
  "card": "4242424242424242",
  "card_exp_month": "12",
  "card_exp_year": "30",
  "card_cvv": "123",
  "phone": "+380950000001",
  "ip": "203.0.113.10",
  "prepare": "tariffs"
}
```

### 2.4 PrivatPay Button (Public)
Use Checkout or Widget with `paytypes=privat24` (no extra fields). Server-side paytype for card payment: `paytype: privat24`.

### 2.5 Apple Pay (Public/Server)
- Use Checkout/Widget (`paytypes=apay`) for client-side.
- For server API, call Card Payment with `paytype: apay` (encrypted token) or `apay_tavv` (unencrypted) and pass token data in `card` plus `tavv` for unencrypted; still include `amount`, `currency`, `description`, `order_id`, `ip`.

### 2.6 Google Pay (Public/Server)
- Use Checkout/Widget (`paytypes=gpay`) for client-side.
- Server API: Card Payment with `paytype: gpay` (encrypted) or `gpay_tavv` (unencrypted) and token payload; include `tavv` for unencrypted.

### 2.7 QR Payment (Public)
Generate a payment via Checkout/Widget with `paytypes=qr` (or dedicated QR API if enabled). Customer pays by scanning QR in Privat24. Status arrives via Callback.

### 2.8 Token Payment (Server)
Endpoint: `POST https://www.liqpay.ua/api/request`

Required: `version`, `public_key`, `action: paytoken`, `amount`, `currency`, `description`, `order_id`, `card_token`, `ip`

Optional: `phone`, `language`, `server_url`, `prepare`, `split_rules`, `split_tickets_only`, sender fields, `customer`, `info`, `product_*`, `is_recurring`, `sandbox`.

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
  "server_url": "https://merchant/callback",
  "sandbox": 1
}
```

### 2.9 Subscription
- Checkout/Widget: `action: subscribe`, include `subscribe:1`, `subscribe_date_start`, `subscribe_periodicity` (`day|week|month|year`).
- Server flows: same request pattern as Card Payment with `action: subscribe` when enabled.
Statuses: `subscribed`, `unsubscribed`, plus payment statuses for recurring charges.

### 2.10 Refund
Endpoint: `POST https://www.liqpay.ua/api/request`

Required: `version`, `public_key`, `action: refund`, `order_id`, `amount`
Optional: `wait_amount` (defer until amount is available)
Statuses: `reversed`, `wait_accept`, or errors.

Example (partial refund):
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "refund",
  "order_id": "srv_2001",
  "amount": 5.50
}
```
Edge cases: refund amount must be ≤ captured amount; repeated refunds after full reversal return `err_amount_hold` or `payment_err_status`.

### 2.11 Two-step Payment (Hold)
- **Hold:** `action: hold`, standard card params; status `hold_wait` on success.
- **Capture:** `action: hold_completion`, fields: `version`, `public_key`, `order_id`, `amount` (≤ held amount).
- **Release:** `action: unhold`, fields: `version`, `public_key`, `order_id`.

Redirect-first flow (Checkout/Widget):
1) Create checkout with `action: hold` and `server_url`. Customer authorizes on LiqPay page and is redirected to `result_url`.
2) Callback arrives with `status: hold_wait` (funds blocked). No charge yet.
3) Capture server-side:
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "hold_completion",
  "order_id": "hold_2024_001",
  "amount": 45
}
```
4) If you must release instead:
```json
{
  "public_key": "i123",
  "version": 7,
  "action": "unhold",
  "order_id": "hold_2024_001"
}
```

Server-initiated hold (no redirect): same as Card Payment but `action: hold` with PAN/token data; status `hold_wait` returned directly or via callback.

Edge cases:
- Capture amount must be ≤ held amount; exceeding returns `err_amount_hold`.
- After capture, unhold is not allowed; use `refund` for reversals.
- Holds expire per acquirer rules (commonly 7–14 days); expired holds return `payment_not_found` on capture.
- Duplicate capture requests may return `payment_err_status` if already completed.

### 2.12 Splitting
Attach `split_rules` (JSON array) to supported APIs (Checkout, Widget, Card Payment, Token Payment, Two-step, QR). Item: `{ public_key, amount, commission_payer (sender|receiver), server_url, rro_info }`. Up to 5 recipients per official limits.

### 2.13 Invoice (Public)
Issue invoice email without immediate charge.
- Use Checkout/Widget with `action: invoice` (or server API if enabled).
- Required: `version`, `public_key`, `action: invoice`, `amount`, `currency`, `description`, `order_id`.
- Optional: `expired_date`, `language`, `result_url`, `server_url`, product list via `rro_info.items`.
Statuses: `invoice_wait`, `success` once paid, `reversed` if refunded, `wait_accept` possible.

### 2.14 Cash Payment (Public)
Use Checkout/Widget with `paytypes=cash`. Required: `phone` for terminal code, `expired_date` for payment deadline. Status `cash_wait` until customer pays in terminal.

### 2.15 DCC (Dynamic Currency Conversion)
Server Card Payment supports DCC: call with `prepare: tariffs` to fetch conversion offer, then confirm charge with returned rates; ensure customer consent is collected.
Example prepare (step 1): see DCC quote example in 2.3. After receiving rates, repeat `action: pay` without `prepare` and include customer-approved currency choice.

---

## 3. Testing
- Test keys start with `sandbox_`; no real funds move.
- Use `sandbox: 1` in request for sandbox processing.
- Test cards (CVV: any 3 digits; expiry: any future MM/YY):
  - `4242424242424242` → success
  - `4000000000003063` → success with 3DS
  - `4000000000003089` → success with OTP
  - `4000000000003055` → success with CVV prompt
  - `4000000000000002` → failure (`limit`)
  - `4000000000009995` → failure (`9859`)
  - `sandbox_token` → token payment success

---

## 4. Transfers & P2P

### 4.1 Transfer to Card (p2pcredit)
Endpoint: `POST https://www.liqpay.ua/api/request`
- `action: p2pcredit`; include receiver card/token/account fields, `amount`, `currency`, `description`, `order_id`, `public_key`, `version`.

### 4.2 P2P Debit (p2pdebit)
Endpoint: `POST https://www.liqpay.ua/api/request`
- `action: p2pdebit`; payer card fields similar to Card Payment; statuses follow standard payment list.

---

## 5. Tokenization APIs

### 5.1 Issue Token without Charge
Private API to tokenize a card without payment (action varies by enablement; often `token`). Requires card PAN + CVV + expiry, `order_id`, `public_key`, `version`, `description`, `ip`, `phone`. Returns `card_token` or reference. Contact LiqPay to enable.

### 5.2 Using Tokens
- Payments: `action: paytoken` (see 2.8)
- Recurring: pass `is_recurring:true` when appropriate
- Tokens are merchant-bound; error `101` if used by another merchant.

---

## 6. Verification Flows (3DS/OTP/CVV)

Statuses that require extra steps:
- `3ds_verify`: redirect customer to `redirect_to`; for 3DS 1.0 use `mpi_req_*`, for 3DS 2.0 use `cres` flow.
- `otp_verify`: collect OTP sent to `confirm_phone` and submit via follow-up request.
- `cvv_verify`: prompt for CVV and resend with `card_cvv`.
- `sender_verify` / `receiver_verify`: collect required sender/receiver data and resend.

After completing verification, resend the payment request with the additional credential(s) as instructed by the specific flow.

---

## 7. Callback

- Sent to your `server_url` as POST with `data` (base64 JSON) and `signature`.
- Validate with `base64_encode(sha3-256(private_key + data + private_key))`.

Key response fields (not exhaustive): `status`, `action`, `order_id`, `liqpay_order_id`, `payment_id`, `transaction_id`, `amount`, `currency`, `paytype`, `card_token`, `sender_card_mask2`, `sender_card_type`, `sender_card_country`, `receiver_commission`, `sender_commission`, `agent_commission`, `rrn_credit`, `rrn_debit`, `mpi_eci`, `is_3ds`, `redirect_to`, `verifycode`, `err_code`, `err_description`.

Status buckets:
- Final: `success`, `failure`, `error`, `reversed`, `subscribed`, `unsubscribed`
- Verification required: `3ds_verify`, `cvv_verify`, `otp_verify`, `receiver_verify`, `sender_verify`
- Processing/other: `processing`, `prepared`, `wait_accept`, `wait_card`, `wait_compensation`, `wait_lc`, `wait_reserve`, `wait_secure`, `hold_wait`, `cash_wait`, `invoice_wait`, `try_again`, `wait_qr`, `p24_verify`, `mp_verify`, `captcha_verify`, `ivr_verify`, `password_verify`, `phone_verify`, `pin_verify`, `senderapp_verify`

Callback retry: LiqPay retries with backoff until HTTP 200 is received.

---

## 8. Error Codes (selected)

Non-financial examples: `err_auth`, `invalid_signature`, `order_id_empty`, `order_id_duplicate`, `public_key_not_found`, `payment_not_found`, `err_api_action`, `err_api_ip`, `expired_3ds`, `expired_otp`, `expired_cvv`, `expired_phone`, `err_card`, `err_phone`, `err_amount_hold`, `err_split_amount`.

Financial examples: `90`, `101`, `102`, `103`, `104`, `105`, `106`, `107`, `108`, `109`, `110`, `9851`, `9852`, `9854`, `9855`, `9857`, `9859`, `9860`, `9861`, `9863`, `9867`, `9868`, `9872`, `9882`, `9886`, `9961`, `9989`.

Refer to LiqPay Errors API for the full table.

---

## 9. Support
Technical support: liqpay.support@privatbank.ua  
Phone: 3700 (UA mobile, free)
