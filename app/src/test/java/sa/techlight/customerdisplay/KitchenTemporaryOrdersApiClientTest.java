package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class KitchenTemporaryOrdersApiClientTest {
    private KitchenOrder draft(long itemId, double qty) {
        KitchenOrder order = new KitchenOrder();
        KitchenOrder.Item item = new KitchenOrder.Item();
        item.itemId = itemId;
        item.qty = qty;
        item.name = "Item " + itemId;
        order.items.add(item);
        return order;
    }

    @Test public void parsesRealTechProTemporaryOrderShape() {
        String raw = "{\"data\":[{"
                + "\"id\":912,\"orderDate\":\"2026-08-29T18:10:00\",\"reservationNumber\":12,"
                + "\"orderTypeId\":2,\"code\":\"TMP-912\",\"number\":1842,\"isNewOrder\":true,"
                + "\"temporaryOrderItems\":[{\"itemId\":55,\"unitId\":4,\"qty\":2},{\"itemId\":90,\"qty\":1}]"
                + "}]}";
        List<KitchenTemporaryOrdersApiClient.Candidate> values = KitchenTemporaryOrdersApiClient.parseCandidates(raw);
        assertEquals(1, values.size());
        KitchenTemporaryOrdersApiClient.Candidate order = values.get(0);
        assertEquals(912L, order.id);
        assertEquals("1842", order.usableNumber());
        assertEquals("12", order.table);
        assertEquals(2L, order.orderTypeId);
        assertEquals(2, order.items.size());
    }

    @Test public void matchesCorrectCloudOrderByItemsAndQuantities() {
        KitchenOrder draft = draft(55, 2);
        KitchenOrder.Item second = new KitchenOrder.Item();
        second.itemId = 90;
        second.qty = 1;
        second.name = "Fries";
        draft.items.add(second);

        String raw = "{\"items\":["
                + "{\"id\":100,\"number\":1800,\"orderTypeId\":1,\"temporaryOrderItems\":[{\"itemId\":77,\"qty\":1}]},"
                + "{\"id\":101,\"number\":1842,\"reservationNumber\":7,\"orderTypeName\":\"محلي\","
                + "\"temporaryOrderItems\":[{\"itemId\":55,\"qty\":2},{\"itemId\":90,\"qty\":1}]}]}";

        KitchenOrder resolved = KitchenTemporaryOrdersApiClient.resolveFromPayloads(
                draft, "0042", Collections.emptyList(), raw
        );
        assertNotNull(resolved);
        assertEquals("1842", resolved.displayNumber);
        assertEquals("invoice-1842", resolved.id);
        assertEquals("7", resolved.table);
        assertEquals("DINE_IN", resolved.orderType);
        assertEquals(2, resolved.items.size());
    }

    @Test public void sameSavedNumberRemainsSameTicketAfterItemEdit() {
        KitchenOrder edited = draft(55, 2);
        KitchenOrder.Item added = new KitchenOrder.Item();
        added.itemId = 91;
        added.qty = 1;
        added.name = "Added item";
        edited.items.add(added);

        String raw = "{\"data\":[{\"id\":501,\"number\":2225,\"isNewOrder\":false,"
                + "\"temporaryOrderItems\":[{\"itemId\":55,\"qty\":2},{\"itemId\":91,\"qty\":1}]}]}";

        KitchenOrder resolved = KitchenTemporaryOrdersApiClient.resolveFromPayloads(
                edited, "", Arrays.asList("2225"), raw
        );
        assertNotNull(resolved);
        assertEquals("2225", resolved.displayNumber);
        assertEquals("invoice-2225", resolved.id);
        assertEquals(2, resolved.items.size());
    }

    @Test public void doesNotMistakeItemNumberForOrderNumber() {
        KitchenOrder draft = draft(55, 1);
        String raw = "{\"data\":[{\"itemId\":55,\"number\":999,\"qty\":1}]}";
        KitchenOrder resolved = KitchenTemporaryOrdersApiClient.resolveFromPayloads(
                draft, "", Collections.emptyList(), raw
        );
        assertNull(resolved);
    }

    @Test public void weakDifferentCartIsRejected() {
        KitchenOrder draft = draft(55, 3);
        String raw = "{\"data\":[{\"id\":1,\"number\":44,\"orderDate\":\"2026-08-29T18:10:00\","
                + "\"temporaryOrderItems\":[{\"itemId\":999,\"qty\":3}]}]}";
        KitchenOrder resolved = KitchenTemporaryOrdersApiClient.resolveFromPayloads(
                draft, "", Collections.emptyList(), raw
        );
        assertNull(resolved);
    }
}
