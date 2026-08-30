package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public final class KitchenHybridIpCloudTest {
    @Test public void localSavedInvoiceIsImmediatelyUsableWithoutCloudLookup() {
        String raw = "{\"type\":\"ordersaved\",\"payload\":{"
                + "\"invoiceNo\":\"4821\",\"orderTypeName\":\"محلي\",\"tableNo\":\"9\","
                + "\"items\":[{\"itemId\":15,\"name\":\"Burger\",\"qty\":1}]}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal);
        assertNotNull(signal.parsed);
        assertNotNull(signal.order);
        assertEquals(KitchenOrderParser.Kind.SAVED, signal.parsed.kind);
        assertEquals("4821", signal.order.displayNumber);
        assertEquals("invoice-4821", signal.order.id);
        assertEquals("9", signal.order.table);
        assertEquals("DINE_IN", signal.order.orderType);
        assertEquals(1, signal.order.items.size());
    }

    @Test public void strictExtractorRecoversInvoiceFromNestedLocalEnvelope() {
        String raw = "{\"event\":\"ordersaved\",\"payload\":{\"sale\":{"
                + "\"invoice\":{\"number\":\"9007\"},"
                + "\"items\":[{\"itemId\":2,\"name\":\"Coffee\",\"qty\":2}]}}}";
        assertEquals("9007", StrictInvoiceExtractor.extract(raw));
    }

    @Test public void itemIdIsNeverMistakenForInvoiceNumber() {
        String raw = "{\"type\":\"updated\",\"payload\":{"
                + "\"items\":[{\"itemId\":7777,\"name\":\"Tea\",\"qty\":1}]}}";
        assertEquals("", StrictInvoiceExtractor.extract(raw));
    }

    @Test public void selectedCloudHostIsAlsoUsedByTemporaryResolver() {
        assertEquals(
                "https://posapi.techlight.sa/api/",
                KitchenTemporaryOrdersApiClient.normalizeApiBase("https://posapi.techlight.sa")
        );
        assertEquals(
                "https://posapifornewapp.techlight.sa/api/",
                KitchenTemporaryOrdersApiClient.normalizeApiBase("")
        );
    }

    @Test public void directPaidLocalInvoiceKeepsSameStrongIdentity() {
        String raw = "{\"type\":\"paymentCompleted\",\"payload\":{"
                + "\"invoiceNumber\":\"5100\",\"isPaid\":true,"
                + "\"items\":[{\"itemId\":31,\"name\":\"V60\",\"qty\":1}]}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal.order);
        assertEquals(KitchenOrderParser.Kind.PAYMENT, signal.parsed.kind);
        assertEquals("5100", signal.order.displayNumber);
        assertEquals("invoice-5100", signal.order.id);
        assertFalse(signal.order.items.isEmpty());
    }
}
