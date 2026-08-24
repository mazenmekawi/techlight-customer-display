package sa.techlight.customerdisplay;

/** Pure transition rules for the live-order, receipt and idle customer moments. */
final class OrderMomentPolicy {
    static final long COMPLETION_ANIMATION_MS = 4000L;

    private OrderMomentPolicy() { }

    static long completionDisplayMs(long configuredDelayMs) {
        return Math.max(COMPLETION_ANIMATION_MS, configuredDelayMs);
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
