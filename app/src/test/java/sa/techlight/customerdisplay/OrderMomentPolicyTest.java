package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class OrderMomentPolicyTest {
    @Test public void itemToInvoiceStoryIsExactlyFourSeconds() {
        assertEquals(4000L, OrderMomentPolicy.COMPLETION_ANIMATION_MS);
        assertEquals(4000L, OrderMomentPolicy.completionDisplayMs(3000L));
        assertEquals(10000L, OrderMomentPolicy.completionDisplayMs(10000L));
    }

    @Test public void invoiceEntranceOverlapsItemTransferAndRowsFollowArrivals() {
        long invoiceEntranceEnd = OrderMomentPolicy.INVOICE_ENTRANCE_START_MS
                + OrderMomentPolicy.INVOICE_ENTRANCE_DURATION_MS;
        assertTrue(OrderMomentPolicy.INVOICE_ENTRANCE_START_MS
                <= OrderMomentPolicy.ITEM_TRANSFER_START_MS);
        assertTrue(invoiceEntranceEnd > OrderMomentPolicy.ITEM_TRANSFER_START_MS);
        assertTrue(OrderMomentPolicy.invoiceRowRevealStartMs(0) < invoiceEntranceEnd);
        assertEquals(OrderMomentPolicy.ITEM_TRANSFER_STAGGER_MS,
                OrderMomentPolicy.invoiceRowRevealStartMs(1)
                        - OrderMomentPolicy.invoiceRowRevealStartMs(0));
        assertEquals(OrderMomentPolicy.ITEM_TRANSFER_STAGGER_MS,
                OrderMomentPolicy.invoiceRowRevealStartMs(2)
                        - OrderMomentPolicy.invoiceRowRevealStartMs(1));
    }

    @Test public void quantityMotionUsesGreenForAddsAndRedForRemovals() {
        assertEquals(1, OrderMomentPolicy.quantityDirection(Double.NaN, 1, true));
        assertEquals(1, OrderMomentPolicy.quantityDirection(1, 2, false));
        assertEquals(-1, OrderMomentPolicy.quantityDirection(2, 1, false));
        assertEquals(0, OrderMomentPolicy.quantityDirection(2, 2, false));
    }

    @Test public void clearingAnActiveOrderShowsReceiptAndKeepsFinalTotal() {
        boolean event = OrderMomentPolicy.isCompletionEvent(true, true, false, 0);
        assertTrue(event);
        assertTrue(OrderMomentPolicy.shouldPreserveLastSummary(event, true, 0));
    }

    @Test public void explicitCompletionShowsReceiptOnlyOnce() {
        assertTrue(OrderMomentPolicy.isCompletionEvent(false, true, true, 0));
        assertFalse(OrderMomentPolicy.isCompletionEvent(true, false, true, 5000));
    }

    @Test public void repeatedEmptySnapshotsHoldReceiptUntilAdvertisingStarts() {
        assertTrue(OrderMomentPolicy.shouldHoldCompletion(
                false, true, false, 8000, 5000, false
        ));
        assertFalse(OrderMomentPolicy.shouldHoldCompletion(
                false, true, false, 4000, 5000, false
        ));
        assertTrue(OrderMomentPolicy.shouldHoldCompletion(
                false, true, false, 4000, 5000, true
        ));
    }
}
