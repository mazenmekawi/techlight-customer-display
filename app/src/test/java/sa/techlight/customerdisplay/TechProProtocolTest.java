package sa.techlight.customerdisplay;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class TechProProtocolTest {
    @Test public void loginPayloadMatchesOriginalTechProModel() throws Exception {
        JSONObject payload = TechProAccountClient.buildLoginPayload(" 0042 ", " cashier ", " secret ");

        assertEquals("0042", payload.getString("posCode"));
        assertEquals("cashier", payload.getString("username"));
        assertEquals(" secret ", payload.getString("password"));
        assertEquals(3, payload.length());
    }

    @Test public void parsesExactFullSnapshotEnvelope() {
        String message = "{"
                + "\"type\":\"fullSnapshot\","
                + "\"payload\":{"
                + "\"state\":\"showingOrder\","
                + "\"items\":[{"
                + "\"id\":\"1\",\"nameAr\":\"قهوة عربية\",\"nameEn\":\"Arabic coffee\","
                + "\"qty\":2,\"unitPrice\":7.5,\"lineTotal\":15,\"isAddition\":false"
                + "}],"
                + "\"subtotal\":15,\"discount\":0,\"tax\":2.25,\"total\":17.25,\"currency\":\"SAR\""
                + "},"
                + "\"sentAt\":\"2026-08-23T12:00:00.000Z\",\"version\":1"
                + "}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("قهوة عربية", order.items.get(0).name);
        assertEquals(2.0, order.items.get(0).qty, 0.0001);
        assertEquals(15.0, order.items.get(0).total(), 0.0001);
        assertEquals(17.25, order.total, 0.0001);
        assertFalse(order.completed);
    }

    @Test public void parsesRawSnapshotForCompatibility() {
        String message = "{\"state\":\"showingOrder\",\"items\":[{"
                + "\"nameEn\":\"Water\",\"qty\":3,\"unitPrice\":2,\"lineTotal\":6"
                + "}],\"subtotal\":6,\"tax\":0,\"discount\":0,\"total\":6}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("Water", order.items.get(0).name);
        assertEquals(6.0, order.total, 0.0001);
    }

    @Test public void parsesExactPairingQr() throws Exception {
        PairingParser.PairingInfo pairing = PairingParser.parse(
                "{\"type\":\"pos_pair\",\"ip\":\"192.168.100.23\",\"port\":4040}"
        );
        assertEquals("192.168.100.23", pairing.ip);
        assertEquals(4040, pairing.port);
    }

    @Test public void parsesItemsKeyedByIdAndNestedProductData() {
        String message = "{\"type\":\"orderUpdated\",\"payload\":{"
                + "\"items\":{\"line-7\":{"
                + "\"product\":{\"nameAr\":\"كركديه آيس\",\"salePrice\":12},"
                + "\"quantity\":3,\"lineTotal\":36}},"
                + "\"subtotal\":36,\"total\":41.4}}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("كركديه آيس", order.items.get(0).name);
        assertEquals(3.0, order.items.get(0).qty, 0.0001);
        assertEquals(36.0, order.items.get(0).total(), 0.0001);
        assertEquals(41.4, order.total, 0.0001);
    }

    @Test public void parsesStringPayloadWithSnakeCaseItems() {
        String message = "{\"type\":\"orderUpdated\",\"payload\":"
                + "\"{\\\"order_items\\\":[{\\\"product_name\\\":\\\"Water\\\","
                + "\\\"item_qty\\\":2,\\\"unit_price\\\":2,\\\"line_total\\\":4}],"
                + "\\\"total\\\":4}\"}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("Water", order.items.get(0).name);
        assertEquals(2.0, order.items.get(0).qty, 0.0001);
        assertEquals(4.0, order.total, 0.0001);
    }

    @Test public void parsesLocalizedNameObject() {
        String message = "{\"items\":[{\"name\":{\"ar\":\"قهوة اليوم\",\"en\":\"Coffee\"},"
                + "\"qty\":1,\"unitPrice\":9}],\"total\":9}";

        OrderState order;
        try {
            order = TechProClient.parseOrderMessageOrThrow(message);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertNotNull(order);
        assertEquals("قهوة اليوم", order.items.get(0).name);
    }

    @Test public void parsesTechProWebItemListWithUppercaseFields() {
        String message = "{\"TYPE\":\"orderUpdated\",\"PAYLOAD\":{"
                + "\"Itemlist\":[{\"ID\":45473,\"Name\":\"سلاش صغير\","
                + "\"QTY\":2,\"UNIT_PRICE\":4.35,\"LINE_TOTAL\":8.70}],"
                + "\"NET_AMOUNT\":8.70,\"VAT_AMOUNT\":1.30,\"INV_TOTAL\":10.00}}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertTrue(order.itemsIncluded);
        assertEquals(1, order.items.size());
        assertEquals("سلاش صغير", order.items.get(0).name);
        assertEquals(2.0, order.items.get(0).qty, 0.0001);
        assertEquals(4.35, order.items.get(0).unitPrice, 0.0001);
        assertEquals(8.70, order.items.get(0).total(), 0.0001);
        assertEquals(10.00, order.total, 0.0001);
    }

    @Test public void parsesDoubleEncodedArrayPayload() {
        String message = "{\"type\":\"orderUpdated\",\"payload\":"
                + "\"[{\\\"NAME_AR\\\":\\\"عصير توت\\\",\\\"QTY\\\":3,"
                + "\\\"UNIT_PRICE\\\":2.5,\\\"LINE_TOTAL\\\":7.5}]\"}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("عصير توت", order.items.get(0).name);
        assertEquals(3.0, order.items.get(0).qty, 0.0001);
        assertEquals(7.5, order.total, 0.0001);
    }

    @Test public void keepsOuterTotalsWhenItemsAreInsideSnapshot() {
        String message = "{\"type\":\"fullSnapshot\",\"payload\":{"
                + "\"snapshot\":{\"items\":[{\"nameAr\":\"ماء\",\"qty\":1,\"unitPrice\":2}]},"
                + "\"tax\":0.3,\"total\":2.3}}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals(0.3, order.tax, 0.0001);
        assertEquals(2.3, order.total, 0.0001);
    }

    @Test public void parsesPricePatchEvenWhenEventNameDoesNotContainOrder() {
        OrderState order = TechProClient.parseOrderMessage(
                "{\"type\":\"priceUpdated\",\"payload\":{\"grandTotal\":25.5}}"
        );
        assertNotNull(order);
        assertFalse(order.itemsIncluded);
        assertTrue(order.totalIncluded);
        assertEquals(25.5, order.total, 0.0001);
    }

    @Test public void recognizesExplicitEmptyItemsAsARealClearSnapshot() {
        OrderState order = TechProClient.parseOrderMessage(
                "{\"type\":\"fullSnapshot\",\"payload\":{\"state\":\"idle\",\"items\":[],\"total\":0}}"
        );
        assertNotNull(order);
        assertTrue(order.itemsIncluded);
        assertTrue(order.clearRequested);
        assertEquals(0, order.items.size());
    }

    @Test public void findsCartItemsBehindAnEmptyPublicItemsArray() {
        String message = "{\"type\":\"orderUpdated\",\"payload\":{"
                + "\"items\":[],\"orderModel\":{\"cartItems\":[{"
                + "\"item\":{\"nameAr\":\"سلاش صغير\",\"salePrice\":4.35},"
                + "\"qty\":2,\"lineTotal\":8.70}]},\"total\":10.00}}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertTrue(order.itemsIncluded);
        assertEquals(1, order.items.size());
        assertEquals("سلاش صغير", order.items.get(0).name);
        assertEquals(2.0, order.items.get(0).qty, 0.0001);
        assertEquals(8.70, order.items.get(0).total(), 0.0001);
        assertEquals(10.00, order.total, 0.0001);
    }

    @Test public void keepsIdentityOnlyCustomerDisplayLinesForCatalogResolution() {
        OrderState order = TechProClient.parseOrderMessage(
                "{\"type\":\"fullSnapshot\",\"payload\":{\"items\":[{"
                        + "\"itemId\":91,\"unitId\":4,\"qty\":2,\"lineTotal\":30}],\"total\":30}}"
        );
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals(91, order.items.get(0).itemId);
        assertEquals(4, order.items.get(0).unitId);
        assertEquals("", order.items.get(0).name);
        assertEquals(30, order.items.get(0).total(), 0.0001);
    }

    @Test public void parsesTechProPagedCatalogWithNestedUnits() {
        String response = "{\"data\":{\"items\":[{\"id\":91,"
                + "\"nameAr\":\"آيس لاتيه\",\"nameEn\":\"Iced Latte\","
                + "\"itemCode\":\"LAT-91\",\"units\":[{\"id\":4,\"itemId\":91,"
                + "\"displayNameAr\":\"كوب\",\"unitBarcode\":\"62800091\","
                + "\"salePrice\":15.0}]}]}}";

        List<ProductCatalog.Product> products = TechProAccountClient.parseCatalog(response);
        ProductCatalog.Product unit = null;
        for (ProductCatalog.Product product : products) {
            if (product.itemId == 91 && product.unitId == 4) unit = product;
        }
        assertNotNull(unit);
        assertEquals("آيس لاتيه", unit.nameAr);
        assertEquals("62800091", unit.barcode);
        assertEquals(15.0, unit.price, 0.0001);
    }
}
