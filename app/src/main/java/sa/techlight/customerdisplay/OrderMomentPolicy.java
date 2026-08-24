package sa.techlight.customerdisplay;

/** Pure transition rules for the live-order, receipt and idle customer moments. */
final class OrderMomentPolicy {
    private OrderMomentPolicy() { }

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
