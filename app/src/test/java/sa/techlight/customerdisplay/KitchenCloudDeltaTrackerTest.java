package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class KitchenCloudDeltaTrackerTest {
    private KitchenOrder order(String number, long createdAt, long itemId, double qty) {
        KitchenOrder order = new KitchenOrder();
        order.id = "invoice-" + number;
        order.displayNumber = number;
        order.createdAt = createdAt;
        order.updatedAt = createdAt;
        order.temporaryOrder = true;
        order.paymentStatus = "UNPAID";
        order.rawStatus = "TEMPORARY";
        KitchenOrder.Item item = new KitchenOrder.Item();
        item.itemId = itemId;
        item.qty = qty;
        item.name = "Item " + itemId;
        order.items.add(item);
        return order;
    }

    @Test public void firstSnapshotHidesOrdersOlderThanCurrentLogin() {
        long loginAt = 1_000_000L;
        KitchenCloudOrdersPoller.TemporaryDeltaTracker tracker =
                new KitchenCloudOrdersPoller.TemporaryDeltaTracker(loginAt - 5_000L);
        List<KitchenOrder> delta = tracker.select(Collections.singletonList(
                order("101", loginAt - 60_000L, 10L, 1d)
        ));
        assertTrue(delta.isEmpty());
    }

    @Test public void firstSnapshotStillEmitsOrderCreatedAfterLogin() {
        long loginAt = 1_000_000L;
        KitchenCloudOrdersPoller.TemporaryDeltaTracker tracker =
                new KitchenCloudOrdersPoller.TemporaryDeltaTracker(loginAt - 5_000L);
        List<KitchenOrder> delta = tracker.select(Collections.singletonList(
                order("102", loginAt + 1_000L, 10L, 1d)
        ));
        assertEquals(1, delta.size());
        assertEquals("102", delta.get(0).displayNumber);
    }

    @Test public void identicalPollProducesNoDeltaAndNoUiRedrawTrigger() {
        long now = 1_000_000L;
        KitchenCloudOrdersPoller.TemporaryDeltaTracker tracker =
                new KitchenCloudOrdersPoller.TemporaryDeltaTracker(now - 5_000L);
        KitchenOrder value = order("103", now, 10L, 1d);
        assertEquals(1, tracker.select(Collections.singletonList(value)).size());
        assertTrue(tracker.select(Collections.singletonList(value.copy())).isEmpty());
    }

    @Test public void editedSavedInvoiceEmitsSameTicketOnce() {
        long now = 1_000_000L;
        KitchenCloudOrdersPoller.TemporaryDeltaTracker tracker =
                new KitchenCloudOrdersPoller.TemporaryDeltaTracker(now - 5_000L);
        KitchenOrder original = order("104", now, 10L, 1d);
        tracker.select(Collections.singletonList(original));

        KitchenOrder edited = original.copy();
        edited.items.get(0).qty = 2d;
        KitchenOrder.Item extra = new KitchenOrder.Item();
        extra.itemId = 11L;
        extra.qty = 1d;
        extra.name = "Extra";
        edited.items.add(extra);

        List<KitchenOrder> delta = tracker.select(Collections.singletonList(edited));
        assertEquals(1, delta.size());
        assertEquals("invoice-104", delta.get(0).id);
        assertEquals(2, delta.get(0).items.size());
        assertTrue(tracker.select(Collections.singletonList(edited.copy())).isEmpty());
    }

    @Test public void backendRowReorderingDoesNotLookLikeAnEdit() {
        long now = 1_000_000L;
        KitchenOrder first = order("105", now, 10L, 1d);
        KitchenOrder.Item second = new KitchenOrder.Item();
        second.itemId = 20L;
        second.qty = 1d;
        second.name = "Second";
        first.items.add(second);

        KitchenOrder reordered = first.copy();
        Collections.reverse(reordered.items);
        assertEquals(
                KitchenCloudOrdersPoller.stableTicketFingerprint(first),
                KitchenCloudOrdersPoller.stableTicketFingerprint(reordered)
        );
        assertEquals(first.contentSignature(), reordered.contentSignature());
    }

    @Test public void oldBaselineOrderCanReturnOnlyAfterCashierActuallyEditsIt() {
        long loginAt = 1_000_000L;
        KitchenCloudOrdersPoller.TemporaryDeltaTracker tracker =
                new KitchenCloudOrdersPoller.TemporaryDeltaTracker(loginAt - 5_000L);
        KitchenOrder old = order("106", loginAt - 60_000L, 10L, 1d);
        assertTrue(tracker.select(Collections.singletonList(old)).isEmpty());

        KitchenOrder edited = old.copy();
        edited.items.get(0).qty = 3d;
        List<KitchenOrder> delta = tracker.select(Collections.singletonList(edited));
        assertEquals(1, delta.size());
        assertEquals("106", delta.get(0).displayNumber);
    }
}
