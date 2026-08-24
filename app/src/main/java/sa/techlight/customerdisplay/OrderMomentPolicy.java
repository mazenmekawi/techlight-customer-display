package sa.techlight.customerdisplay;

/** Pure transition rules for the live-order, receipt and idle customer moments. */
final class OrderMomentPolicy {
    static final long COMPLETION_ANIMATION_MS = 4000L;
    static final long INVOICE_ENTRANCE_START_MS = 720L;
    static final long INVOICE_ENTRANCE_DURATION_MS = 1100L;
    static final long ITEM_TRANSFER_START_MS = 780L;
    static final long ITEM_TRANSFER_STAGGER_MS = 140L;
    static final long ITEM_TRANSFER_DURATION_MS = 1220L;

    private OrderMomentPolicy() { }

    static long completionDisplayMs(long configuredDelayMs) {
        return Math.max(COMPLETION_ANIMATION_MS, configuredDelayMs);
    }

    static long invoiceRowRevealStartMs(int rowIndex) {
        int safeIndex = Math.max(0, Math.min(2, rowIndex));
        return ITEM_TRANSFER_START_MS
                + safeIndex * ITEM_TRANSFER_STAGGER_MS
                + Math.round(ITEM_TRANSFER_DURATION_MS * 0.68d);
    }

    static int quantityDirection(double previousQuantity, double currentQuantity, boolean newlyAdded) {
        if (newlyAdded) return 1;
        if (Double.isNaN(previousQuantity)
                || Math.abs(previousQuantity - currentQuantity) <= 0.00001) return 0;
        return currentQuantity > previousQuantity ? 1 : -1;
    }

    static boolean isCompletionEvent(
            boolean empty,
            boolean wasOrderVisible,
            boolean explicitCompleted,
            long completionMomentUntil
    ) {
        return (empty && wasOrderVisible)
                || (explicitCompleted && (wasOrderVisible || completionMomentUntil == 0));
    }

    static boolean shouldHoldCompletion(
            boolean completionEvent,
            boolean empty,
            boolean explicitCompleted,
            long completionMomentUntil,
            long now,
            boolean advertisingDisabled
    ) {
        return !completionEvent
                && (empty || explicitCompleted)
                && completionMomentUntil > 0
                && (completionMomentUntil > now || advertisingDisabled);
    }

    static boolean shouldPreserveLastSummary(
            boolean completionEvent,
            boolean empty,
            double incomingTotal
    ) {
        return completionEvent && empty && incomingTotal <= 0.0001;
    }
}
