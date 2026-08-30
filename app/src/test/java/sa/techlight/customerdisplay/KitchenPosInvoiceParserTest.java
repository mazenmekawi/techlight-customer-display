package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class KitchenPosInvoiceParserTest {
    @Test public void parsesPaidInvoiceWithLinesAndMetadata() {
        String raw = "{\"data\":[{"
                + "\"id\":901,\"invoiceNumber\":1842,\"invoiceCode\":\"INV-1842\","
                + "\"invoiceDate\":\"2026-08-30T18:44:10\",\"reservationNumber\":7,"
                + "\"orderTypeName\":\"محلي\",\"posCode\":\"0042\","
                + "\"invoiceLines\":[{\"itemId\":55,\"qty\":2,\"itemName\":\"Burger\"},{\"itemId\":90,\"qty\":1,\"itemName\":\"Fries\"}]"
                + "}]}";
        List<KitchenPosInvoiceParser.Candidate> parsed = KitchenPosInvoiceParser.parse(raw);
        assertEquals(1, parsed.size());
        assertEquals("1842", parsed.get(0).usableNumber());
        assertEquals(2, parsed.get(0).items.size());

        List<KitchenOrder> orders = KitchenPosInvoiceParser.convert(parsed, "0042");
        assertEquals(1, orders.size());
        KitchenOrder order = orders.get(0);
        assertEquals("invoice-1842", order.id);
        assertEquals("1842", order.displayNumber);
        assertEquals("7", order.table);
        assertEquals("DINE_IN", order.orderType);
        assertEquals("PAID", order.paymentStatus);
        assertEquals("POS_INVOICE", order.rawStatus);
        assertFalse(order.temporaryOrder);
        assertEquals(2, order.items.size());
    }

    @Test public void invoiceHeaderWithoutLinesIsKeptForPaymentUpgrade() {
        String raw = "[{\"invoiceNumber\":2001,\"invoiceCode\":\"2001\",\"invoiceDate\":\"2026-08-30T18:45:00\"}]";
        List<KitchenOrder> orders = KitchenPosInvoiceParser.convert(KitchenPosInvoiceParser.parse(raw), "");
        assertEquals(1, orders.size());
        assertEquals("2001", orders.get(0).displayNumber);
        assertTrue(orders.get(0).items.isEmpty());
        assertEquals("PAID", orders.get(0).paymentStatus);
    }

    @Test public void itemLineIsNotMistakenForInvoiceHeader() {
        String raw = "[{\"itemId\":55,\"qty\":1,\"invoiceNumber\":0}]";
        assertTrue(KitchenPosInvoiceParser.parse(raw).isEmpty());
    }
}
