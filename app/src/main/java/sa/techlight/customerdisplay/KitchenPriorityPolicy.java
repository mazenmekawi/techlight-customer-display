package sa.techlight.customerdisplay;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Deterministic priority policy with an optional complexity assist. */
public final class KitchenPriorityPolicy {
    private KitchenPriorityPolicy() { }

    public static void sort(List<KitchenOrder> orders, KitchenProState state,
                            long now, long warningMs, long lateMs, boolean smart) {
        if (orders == null || orders.size() < 2) return;
        Collections.sort(orders, comparator(state, now, warningMs, lateMs, smart));
    }

    static Comparator<KitchenOrder> comparator(KitchenProState state,
                                                long now, long warningMs,
                                                long lateMs, boolean smart) {
        return (left, right) -> {
            int lp = priority(left, state, now, warningMs, lateMs, smart);
            int rp = priority(right, state, now, warningMs, lateMs, smart);
            if (lp != rp) return Integer.compare(rp, lp);
            long lc = left == null ? Long.MAX_VALUE : left.createdAt;
            long rc = right == null ? Long.MAX_VALUE : right.createdAt;
            if (lc != rc) return Long.compare(lc, rc);
            String li = left == null || left.id == null ? "" : left.id;
            String ri = right == null || right.id == null ? "" : right.id;
            return li.compareToIgnoreCase(ri);
        };
    }

    public static int priority(KitchenOrder order, KitchenProState state,
                               long now, long warningMs, long lateMs, boolean smart) {
        if (order == null) return Integer.MIN_VALUE;
        long age = Math.max(0L, now - order.createdAt);
        int score = 0;
        if (age >= Math.max(1L, lateMs)) score += 100000;
        else if (age >= Math.max(1L, warningMs)) score += 50000;

        int additions = state == null ? 0 : state.pendingAdditions(order.id);
        if (additions > 0) score += 30000 + Math.min(9999, additions * 200);

        if (order.kitchenStatus == KitchenOrder.Status.NEW) score += 6000;
        else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) score += 4000;
        else if (order.kitchenStatus == KitchenOrder.Status.READY) score += 2000;

        // Age contributes in whole seconds, so polling cannot randomly reshuffle ties.
        score += (int) Math.min(20000L, age / 1000L);
        if (smart && state != null) score += Math.min(5000, state.complexity(order) * 20);
        return score;
    }
}
