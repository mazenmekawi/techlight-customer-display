package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.*;

public class KitchenSignalV2Test {
    @Test public void zeroInvoiceNumberIsNeverIdentity() {
        String raw = "{\"type\":\"updated\",\"invoice\":{\"invoiceNumber\":0,\"items\":[{\"itemId\":1,\"itemName\":\"Burger\",\"qty\":1}]}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal.order);
        assertEquals("", signal.order.displayNumber);
        assertEquals("", signal.order.id);
    }

    @Test public void nestedRealInvoiceNumberBeatsPlaceholderZero() {
        String raw = "{\"type\":\"ordersaved\",\"invoiceNumber\":0,\"payload\":{\"sale\":{\"invoiceNo\":\"1842\",\"items\":[{\"itemId\":9,\"itemName\":\"Coffee\",\"quantity\":1}]}}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal.order);
        assertEquals("1842", signal.order.displayNumber);
        assertEquals("invoice-1842", signal.order.id);
    }

    @Test public void updatedCartRemainsUpdatedSignalNotSave() {
        String raw = "{\"type\":\"updated\",\"payload\":{\"orderId\":\"55\",\"items\":[{\"itemId\":1,\"name\":\"A\",\"qty\":1},{\"itemId\":2,\"name\":\"B\",\"qty\":1}]}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertEquals(KitchenOrderParser.Kind.UPDATED, signal.parsed.kind);
        assertEquals("55", signal.order.id);
        assertEquals(2, signal.order.items.size());
    }

    @Test public void nestedInvoiceWrapperCanExposeGenericNumber() {
        String raw = "{\"type\":\"ordersaved\",\"payload\":{\"invoice\":{\"number\":\"9207\",\"items\":[{\"itemId\":4,\"name\":\"Tea\",\"qty\":1}]}}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal.order);
        assertEquals("9207", signal.order.displayNumber);
        assertEquals("invoice-9207", signal.order.id);
    }

    @Test public void nestedTableAndTakeawayAreEnriched() {
        String raw = "{\"type\":\"ordersaved\",\"payload\":{\"invoiceNo\":\"77\",\"service\":{\"isTakeAway\":true},\"dining\":{\"table\":{\"number\":\"12\"}},\"items\":[{\"itemId\":1,\"name\":\"Burger\",\"qty\":1}]}}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal.order);
        assertEquals("77", signal.order.displayNumber);
        assertEquals("12", signal.order.table);
        assertEquals("TAKEAWAY", signal.order.orderType);
        assertEquals("سفري", KitchenSignalV2.displayOrderType(signal.order.orderType, true));
        assertEquals("Takeaway", KitchenSignalV2.displayOrderType(signal.order.orderType, false));
    }

    @Test public void localArabicOrderTypeIsCanonicalized() {
        String raw = "{\"type\":\"ordersaved\",\"invoiceNo\":\"88\",\"orderTypeName\":\"محلي\",\"tableNo\":\"3\",\"items\":[{\"itemId\":2,\"name\":\"Coffee\",\"qty\":1}]}";
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        assertNotNull(signal.order);
        assertEquals("DINE_IN", signal.order.orderType);
        assertEquals("3", signal.order.table);
        assertEquals("محلي", KitchenSignalV2.displayOrderType(signal.order.orderType, true));
    }
}
