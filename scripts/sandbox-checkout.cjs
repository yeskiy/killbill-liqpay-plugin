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

// Sandbox run: a checkout purchase in Kill Bill, paid with the LiqPay test card, settled by status polling, then a partial refund.
// scripts/sandbox-checkout.sh starts it in a Playwright container. Credentials come from env vars and are never printed.
'use strict';

const playwright = (() => {
  try {
    return require('playwright');
  } catch (e) {
    return require('@playwright/test');
  }
})();

const env = (name, fallback) => {
  const v = process.env[name] || (fallback && process.env[fallback]);
  if (!v) {
    throw new Error(`${name} is not set`);
  }
  return v;
};

const kb = (process.env.KILLBILL_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '') + '/1.0/kb';
const auth = 'Basic ' + Buffer.from(`${env('KILLBILL_USER', 'KILLBILL_ROOT_USER')}:${env('KILLBILL_PASSWORD', 'KILLBILL_ROOT_PASSWORD')}`).toString('base64');
const headers = {
  Authorization: auth,
  'X-Killbill-ApiKey': env('KILLBILL_API_KEY'),
  'X-Killbill-ApiSecret': env('KILLBILL_API_SECRET'),
  'X-Killbill-CreatedBy': 'sandbox-checkout',
  'Content-Type': 'application/json',
};
const resultUrl = env('LIQPAY_RESULT_URL');
const amount = Number(process.env.SANDBOX_AMOUNT || '2.00');
const say = (state, text) => console.log(`${state.padEnd(5)} ${text}`);

const call = async (method, path, body) => {
  const res = await fetch(kb + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'manual' });
  if (res.status >= 300 && res.status !== 302) {
    throw new Error(`${method} ${path.split('?')[0]} answered HTTP ${res.status}`);
  }
  return res;
};
const idOf = (res) => (res.headers.get('location') || '').replace(/\/+$/, '').split('/').pop();
const prop = (tx, key) => ((tx.properties || []).find((p) => p.key === key) || {}).value;
const payment = async (id) => (await call('GET', `/payments/${id}?withPluginInfo=true`)).json();

const pay = async (form) => {
  const browser = await playwright.chromium.launch();
  try {
    const page = await browser.newPage();
    await page.setContent('<form method="POST" accept-charset="utf-8"><input type="hidden" name="data"><input type="hidden" name="signature"></form>');
    await page.evaluate((f) => {
      const el = document.forms[0];
      el.action = f.url;
      el.elements.data.value = f.data;
      el.elements.signature.value = f.signature;
      el.submit();
    }, form);
    await page.getByTestId('card_input').pressSequentially('4242424242424242', { timeout: 60000 });
    await page.getByTestId('expiration_input').pressSequentially('1230');
    await page.getByTestId('cvv_input').pressSequentially('123');
    await page.getByTestId('first_name_input').fill('Sandbox');
    await page.getByTestId('last_name_input').fill('Test');
    await page.getByTestId('card_pay_btn').click();
    await page.waitForURL(/\/checkout\/success\//, { timeout: 60000 });
  } finally {
    await browser.close();
  }
};

(async () => {
  const account = idOf(await call('POST', '/accounts', { name: 'LiqPay sandbox', currency: 'USD', externalKey: `liqpay-sandbox-${Date.now()}` }));
  await call('POST', `/accounts/${account}/paymentMethods?isDefault=true`,
             { pluginName: 'killbill-liqpay', pluginInfo: { properties: [{ key: 'liqpay_mode', value: 'checkout' }] } });
  const query = `pluginProperty=${encodeURIComponent(`liqpay_result_url=${resultUrl}`)}`;
  const paymentId = idOf(await call('POST', `/accounts/${account}/payments?${query}`, { transactionType: 'PURCHASE', amount, currency: 'USD' }));
  const pending = (await payment(paymentId)).transactions[0];
  if (pending.status !== 'PENDING' || !prop(pending, 'liqpay_data')) {
    throw new Error(`purchase is ${pending.status}, expected PENDING with a checkout form`);
  }
  say('ok', `purchase ${paymentId} PENDING with a signed checkout form`);

  await pay({ url: prop(pending, 'liqpay_checkout_url'), data: prop(pending, 'liqpay_data'), signature: prop(pending, 'liqpay_signature') });
  say('ok', 'paid with the test card on the LiqPay sandbox page');

  const poll = async (n) => {
    const tx = (await payment(paymentId)).transactions[0];
    return tx.status !== 'PENDING' || n >= 20 ? { tx, polls: n } : (await new Promise((r) => setTimeout(r, 3000)), poll(n + 1));
  };
  const settled = await poll(1);
  if (settled.tx.status !== 'SUCCESS') {
    throw new Error(`purchase is ${settled.tx.status} (liqpay_status ${prop(settled.tx, 'liqpay_status')}), expected SUCCESS`);
  }
  say('ok', `purchase SUCCESS through status polling after ${settled.polls} read(s), liqpay_status ${prop(settled.tx, 'liqpay_status')}`);

  // LiqPay answers payment_err_status to a refund that comes seconds after the payment. Each try is a new Kill Bill refund.
  const half = Math.round(amount * 50) / 100;
  const refund = async (n) => {
    const res = await fetch(`${kb}/payments/${paymentId}/refunds`,
                            { method: 'POST', headers, body: JSON.stringify({ amount: half, currency: 'USD' }), redirect: 'manual' });
    const last = (await payment(paymentId)).transactions.filter((t) => t.transactionType === 'REFUND').pop();
    const retry = res.status === 402 && last && last.gatewayErrorCode === 'payment_err_status' && n < 12;
    return retry ? (await new Promise((r) => setTimeout(r, 10000)), refund(n + 1)) : { res, last, tries: n };
  };
  const done = await refund(1);
  if (!done.last || done.last.status !== 'SUCCESS') {
    throw new Error(`refund is ${done.last ? `${done.last.status} (${done.last.gatewayErrorCode})` : 'missing'}, expected SUCCESS`);
  }
  say('ok', `refund of ${half.toFixed(2)} USD SUCCESS after ${done.tries} try(s), liqpay_status ${prop(done.last, 'liqpay_status')}`);
})().catch((e) => {
  say('FAIL', e.message);
  process.exit(1);
});
