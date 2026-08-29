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
}
