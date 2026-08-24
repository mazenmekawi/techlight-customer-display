package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OrderMomentPolicyTest {
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
