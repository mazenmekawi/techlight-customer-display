package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class KitchenCloudOrdersPollerTest {
    @Test public void cloudSnapshotBuildsInvoiceTicketWithoutWebsocket() {
        String raw = "{\"data\":[{"
                + "\"id\":501,\"number\":1842,\"reservationNumber\":12,\"orderTypeId\":2,"
                + "\"orderDate\":\"2026-08-29T18:10:00\","
                + "\"temporaryOrderItems\":[{\"itemId\":55,\"qty\":2},{\"itemId\":90,\"qty\":1}]"
                + "}]}";
        List<KitchenTemporaryOrdersApiClient.Candidate> candidates = KitchenTemporaryOrdersApiClient.parseCandidates(raw);
        HashMap<Long, String> types = new HashMap<>();
        types.put(2L, "سفري");
        List<KitchenOrder> orders = KitchenCloudOrdersPoller.convert(candidates, "", types);
        assertEquals(1, orders.size());
        KitchenOrder order = orders.get(0);
        assertEquals("1842", order.displayNumber);
        assertEquals("invoice-1842", order.id);
        assertEquals("12", order.table);
        assertEquals("TAKEAWAY", order.orderType);
        assertEquals(2, order.items.size());
        assertEquals(55L, order.items.get(0).itemId);
        assertEquals(2d, order.items.get(0).qty, 0.0001);
    }

    @Test public void duplicateWrappersStillProduceOneTicket() {
        String raw = "{\"data\":[{\"id\":1,\"number\":99,\"orderTypeId\":1,\"temporaryOrderItems\":[{\"itemId\":5,\"qty\":1}]},"
                + "{\"wrapper\":{\"id\":1,\"number\":99,\"orderTypeId\":1,\"temporaryOrderItems\":[{\"itemId\":5,\"qty\":1}]}}]}";
        List<KitchenOrder> orders = KitchenCloudOrdersPoller.convert(
                KitchenTemporaryOrdersApiClient.parseCandidates(raw), ""
        );
        assertEquals(1, orders.size());
        assertEquals("99", orders.get(0).displayNumber);
    }

    @Test public void emptyWrapperIsNeverShownAsKitchenTicket() {
        String raw = "{\"data\":[{\"id\":2,\"number\":100,\"orderTypeId\":1,\"temporaryOrderItems\":[]}]}";
        List<KitchenOrder> orders = KitchenCloudOrdersPoller.convert(
                KitchenTemporaryOrdersApiClient.parseCandidates(raw), ""
        );
        assertFalse(!orders.isEmpty());
    }
}
