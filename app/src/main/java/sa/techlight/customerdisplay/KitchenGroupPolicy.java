package sa.techlight.customerdisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Group selection driven by real local/API station values. */
public final class KitchenGroupPolicy {
    public static final String ALL = "__ALL__";
    public static final String GENERAL = "General";

    private KitchenGroupPolicy() { }

    public static List<String> groups(KitchenOrder order) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (order != null) {
            for (KitchenOrder.Item item : order.items) {
                String station = clean(item.station);
                if (!station.isEmpty()) result.add(station);
            }
        }
        if (result.isEmpty()) result.add(GENERAL);
        return new ArrayList<>(result);
    }

    public static List<String> discover(List<KitchenOrder> orders) {
        Set<String> result = new LinkedHashSet<>();
        if (orders != null) for (KitchenOrder order : orders) result.addAll(groups(order));
        ArrayList<String> list = new ArrayList<>(result);
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public static boolean matches(KitchenOrder order, String selectedCsv) {
        String selected = clean(selectedCsv);
        if (selected.isEmpty() || ALL.equals(selected)) return true;
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String part : selected.split(",")) {
            String value = normalize(part);
            if (!value.isEmpty()) wanted.add(value);
        }
        if (wanted.isEmpty()) return true;
        for (String group : groups(order)) if (wanted.contains(normalize(group))) return true;
        return false;
    }

    public static String displaySelection(String selectedCsv, boolean arabic) {
        String selected = clean(selectedCsv);
        if (selected.isEmpty() || ALL.equals(selected)) return arabic ? "كل المجموعات" : "All groups";
        return selected.replace(",", " • ");
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
