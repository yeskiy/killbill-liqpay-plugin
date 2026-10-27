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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import io.sailkit.billing.plugin.liqpay.crypto.LiqPaySigner;
import io.sailkit.billing.plugin.liqpay.dao.gen.tables.records.LiqpayTransactionsRecord;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.plugin.api.payment.PluginPaymentMethodPlugin;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static io.sailkit.billing.plugin.liqpay.api.Fixtures.PRIVATE_KEY;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.RESULT_URL;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.context;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.data;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.prop;
import static io.sailkit.billing.plugin.liqpay.api.Fixtures.props;
import static io.sailkit.billing.plugin.liqpay.config.LiqPayConfig.PREFIX;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class CheckoutFlowTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private InMemoryStore store;
    private LiqPayPaymentPluginApi api;

    @BeforeMethod
    public void setUp() throws Exception {
        store = new InMemoryStore();
        api = api(Fixtures.properties("https://www.liqpay.ua"));
    }

    private LiqPayPaymentPluginApi api(final Properties p) throws Exception {
        return new LiqPayPaymentPluginApi(Fixtures.handler(tenant, Fixtures.config(p)), store, new Fixtures.MutableClock());
    }

    private PaymentTransactionInfoPlugin purchase(final UUID tx, final String amount, final Currency currency,
                                                  final PluginProperty... properties) throws PaymentPluginApiException {
        return api.purchasePayment(account, UUID.randomUUID(), tx, UUID.randomUUID(), new BigDecimal(amount), currency,
                                   List.of(properties), context(account, tenant));
    }

    private PaymentTransactionInfoPlugin purchase(final UUID tx) throws PaymentPluginApiException {
        return purchase(tx, "100.00", Currency.USD, prop("liqpay_result_url", RESULT_URL));
    }

    @Test
    public void aPurchaseIsPendingWithTheCheckoutForm() throws Exception {
        final UUID tx = UUID.randomUUID();
        final PaymentTransactionInfoPlugin info = purchase(tx);
        assertEquals(info.getStatus(), PaymentPluginStatus.PENDING);
        assertEquals(info.getTransactionType(), TransactionType.PURCHASE);
        assertEquals(info.getKbTransactionPaymentId(), tx);
        final Map<String, Object> p = props(info);
        assertEquals(p.get("liqpay_checkout_url"), "https://www.liqpay.ua/api/3/checkout");
        assertEquals(p.get("liqpay_order_id"), tx.toString());
        assertEquals(p.get("liqpay_expires_at"), "2026-10-18T12:30:00Z");
        assertTrue(LiqPaySigner.verify(PRIVATE_KEY, (String) p.get("liqpay_data"), (String) p.get("liqpay_signature")));
    }

    @Test
    public void theDataHoldsTheKillBillAmountAndTheServerValues() throws Exception {
        final UUID tx = UUID.randomUUID();
        final JsonNode data = data(purchase(tx));
        assertEquals(data.get("version").asInt(), 7);
        assertEquals(data.get("public_key").asText(), Fixtures.PUBLIC_KEY);
        assertEquals(data.get("action").asText(), "pay");
        assertEquals(data.get("amount").asText(), "100.00");
        assertEquals(data.get("currency").asText(), "USD");
        assertEquals(data.get("order_id").asText(), tx.toString());
        assertEquals(data.get("server_url").asText(),
                     "https://pay.example.com/plugins/killbill-liqpay/callback?kbTenantId=" + tenant);
        assertEquals(data.get("result_url").asText(), RESULT_URL);
        assertEquals(data.get("expired_date").asText(), "2026-10-18 12:30:00");
        assertEquals(data.get("language").asText(), "en");
        assertEquals(data.get("description").asText(), "Payment");
    }

    @Test
    public void callerValuesForProtectedFieldsChangeNothing() throws Exception {
        final UUID tx = UUID.randomUUID();
        final JsonNode data = data(purchase(tx, "100.00", Currency.USD,
                                            prop("liqpay_result_url", RESULT_URL),
                                            prop("amount", "0.01"), prop("currency", "UAH"), prop("action", "hold"),
                                            prop("server_url", "https://evil.example.com/cb"), prop("order_id", "x1")));
        assertEquals(data.get("amount").asText(), "100.00");
        assertEquals(data.get("currency").asText(), "USD");
        assertEquals(data.get("action").asText(), "pay");
        assertEquals(data.get("order_id").asText(), tx.toString());
        assertTrue(data.get("server_url").asText().startsWith("https://pay.example.com/"));
    }

    @Test
    public void languageAndDescriptionFollowTheRules() throws Exception {
        final JsonNode uk = data(purchase(UUID.randomUUID(), "5.00", Currency.USD, prop("liqpay_result_url", RESULT_URL),
                                          prop("liqpay_language", "UK"), prop("liqpay_description", "  Sample\u0007 Build\n ")));
        assertEquals(uk.get("language").asText(), "uk");
        assertEquals(uk.get("description").asText(), "Sample Build");
        final JsonNode other = data(purchase(UUID.randomUUID(), "5.00", Currency.USD, prop("liqpay_result_url", RESULT_URL),
                                             prop("liqpay_language", "de"), prop("liqpay_description", "x".repeat(300))));
        assertEquals(other.get("language").asText(), "en");
        assertEquals(other.get("description").asText().length(), 255);
        final JsonNode blank = data(purchase(UUID.randomUUID(), "5.00", Currency.USD, prop("liqpay_result_url", RESULT_URL),
                                             prop("liqpay_description", " \t ")));
        assertEquals(blank.get("description").asText(), "Payment");
    }

    @Test
    public void aResultUrlOutsideTheAllowlistIsCanceled() throws Exception {
        final PaymentTransactionInfoPlugin bad = purchase(UUID.randomUUID(), "100.00", Currency.USD,
                                                          prop("liqpay_result_url", "https://evil.example.com/checkout/return"));
        assertEquals(bad.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(bad.getGatewayErrorCode(), "result_url_not_allowed");
        assertFalse(props(bad).containsKey("liqpay_data"));
        final PaymentTransactionInfoPlugin missing = purchase(UUID.randomUUID(), "100.00", Currency.USD);
        assertEquals(missing.getGatewayErrorCode(), "result_url_not_allowed");
    }

    @Test
    public void aCurrencyOutsideTheListIsCanceled() throws Exception {
        final PaymentTransactionInfoPlugin info = purchase(UUID.randomUUID(), "100.00", Currency.EUR, prop("liqpay_result_url", RESULT_URL));
        assertEquals(info.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(info.getGatewayErrorCode(), "currency_not_allowed");
        assertFalse(props(info).containsKey("liqpay_data"));
    }

    @Test
    public void oneRowIsStoredWithTheExpiry() throws Exception {
        final UUID tx = UUID.randomUUID();
        purchase(tx);
        assertEquals(store.rows.size(), 1);
        final LiqpayTransactionsRecord row = store.rows.get(0);
        assertEquals(row.getPluginStatus(), "PENDING");
        assertEquals(row.getOrderId(), tx.toString());
        assertEquals(row.getKbTransactionId(), tx.toString());
        assertEquals(row.getKbTenantId(), tenant.toString());
        assertEquals(row.getTransactionType(), "PURCHASE");
        assertEquals(row.getLiqpayAction(), "pay");
        assertEquals(row.getAmount().compareTo(new BigDecimal("100")), 0);
        assertEquals(row.getCurrency(), "USD");
        assertEquals(row.getExpiresAt(), LocalDateTime.of(2026, 10, 18, 12, 30));
    }

    @Test
    public void theTtlComesFromConfig() throws Exception {
        final Properties p = Fixtures.properties("https://www.liqpay.ua");
        p.setProperty(PREFIX + "checkoutTtl", "PT2H");
        api = api(p);
        assertEquals(data(purchase(UUID.randomUUID())).get("expired_date").asText(), "2026-10-18 14:00:00");
    }

    @Test
    public void aTenantWithoutConfigIsCanceled() throws Exception {
        final UUID other = UUID.randomUUID();
        final PaymentTransactionInfoPlugin info = api.purchasePayment(account, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                                      BigDecimal.TEN, Currency.USD, List.of(prop("liqpay_result_url", RESULT_URL)),
                                                                      context(account, other));
        assertEquals(info.getStatus(), PaymentPluginStatus.CANCELED);
        assertEquals(info.getGatewayErrorCode(), "not_configured");
        assertTrue(store.rows.isEmpty());
    }

    @Test
    public void aPaymentMethodStoresItsMode() throws Exception {
        final UUID checkout = UUID.randomUUID();
        api.addPaymentMethod(account, checkout, new PluginPaymentMethodPlugin(checkout, null, true, List.of()), true, List.of(),
                             context(account, tenant));
        assertEquals(store.findPaymentMethod(checkout, tenant).orElseThrow().getMode(), "checkout");
        final UUID token = UUID.randomUUID();
        api.addPaymentMethod(account, token, new PluginPaymentMethodPlugin(token, null, false, List.of(prop("liqpay_mode", "token"))),
                             false, List.of(), context(account, tenant));
        assertEquals(store.findPaymentMethod(token, tenant).orElseThrow().getMode(), "token");
        assertFalse(store.findPaymentMethod(token, tenant).orElseThrow().getVerified());
        final UUID bad = UUID.randomUUID();
        assertThrows(PaymentPluginApiException.class,
                     () -> api.addPaymentMethod(account, bad, new PluginPaymentMethodPlugin(bad, null, false, List.of()), false,
                                                List.of(prop("liqpay_mode", "cash")), context(account, tenant)));
        assertEquals(api.getPaymentMethodDetail(account, token, List.of(), context(account, tenant)).getProperties().stream()
                        .filter(pp -> "verified".equals(pp.getKey())).findFirst().orElseThrow().getValue(), "false");
    }

    @Test
    public void aRepeatedPurchaseCallReturnsTheStoredRow() throws Exception {
        final UUID tx = UUID.randomUUID();
        final String first = (String) props(purchase(tx)).get("liqpay_data");
        final PaymentTransactionInfoPlugin again = purchase(tx);
        assertEquals(store.rows.size(), 1);
        assertEquals(again.getStatus(), PaymentPluginStatus.PENDING);
        assertEquals(props(again).get("liqpay_data"), first);
        assertNull(again.getGatewayErrorCode());
    }
}
