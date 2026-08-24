package sa.techlight.customerdisplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure ordering rule used by the UI: genuinely new lines always appear first. */
final class OrderDisplayOrder {
    private OrderDisplayOrder() { }

    static List<String> arrange(
            Collection<String> incomingKeys,
            Collection<String> alreadyRenderedKeys,
            Collection<String> previousVisualOrder
    ) {
        Set<String> incoming = new HashSet<>(incomingKeys);
        Set<String> existing = new HashSet<>(alreadyRenderedKeys);
        List<String> result = new ArrayList<>();

        for (String key : incomingKeys) {
            if (!existing.contains(key) && !result.contains(key)) result.add(key);
        }
        for (String key : previousVisualOrder) {
            if (incoming.contains(key) && !result.contains(key)) result.add(key);
        }
        for (String key : incomingKeys) {
            if (!result.contains(key)) result.add(key);
        }
        return result;
    }
}
