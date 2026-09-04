package sa.techlight.customerdisplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Field-aware item merger for the hybrid cloud + cashier-IP feed.
 * The newest payload controls which lines still exist, while useful local
 * metadata is retained when the cloud payload omits it.
 */
public final class KitchenItemMerger {
    private KitchenItemMerger() { }

    public static List<KitchenOrder.Item> merge(List<KitchenOrder.Item> previous,
                                                 List<KitchenOrder.Item> incoming) {
        ArrayList<KitchenOrder.Item> result = new ArrayList<>();
        if (incoming == null || incoming.isEmpty()) {
            if (previous != null) {
                for (KitchenOrder.Item item : previous) result.add(KitchenOrder.copyItem(item));
            }
            return result;
        }

        Map<String, KitchenOrder.Item> old = new LinkedHashMap<>();
        if (previous != null) {
            for (KitchenOrder.Item item : previous) {
                old.put(key(item), item);
                String fallback = fallbackKey(item);
                if (!fallback.isEmpty()) old.put(fallback, item);
            }
        }

        for (KitchenOrder.Item source : incoming) {
            KitchenOrder.Item item = KitchenOrder.copyItem(source);
            KitchenOrder.Item before = old.get(key(source));
            if (before == null) before = old.get(fallbackKey(source));
            if (before != null) enrich(item, before);
            result.add(item);
        }
        return result;
    }

    private static void enrich(KitchenOrder.Item target, KitchenOrder.Item old) {
        if (clean(target.name).isEmpty()) target.name = old.name;
        if (clean(target.nameAr).isEmpty()) target.nameAr = old.nameAr;
        if (clean(target.nameEn).isEmpty()) target.nameEn = old.nameEn;
        if (clean(target.imagePath).isEmpty()) target.imagePath = old.imagePath;
        if (clean(target.note).isEmpty()) target.note = old.note;
        if (clean(target.station).isEmpty()) target.station = old.station;
        if (target.modifiers.isEmpty()) target.modifiers.addAll(old.modifiers);
        else addUnique(target.modifiers, old.modifiers);
        if (target.removed.isEmpty()) target.removed.addAll(old.removed);
        else addUnique(target.removed, old.removed);
    }

    private static void addUnique(List<String> target, List<String> source) {
        for (String value : source) {
            String normalized = normalize(value);
            boolean found = false;
            for (String existing : target) {
                if (normalize(existing).equals(normalized)) { found = true; break; }
            }
            if (!found && !clean(value).isEmpty()) target.add(value);
        }
    }

    static String key(KitchenOrder.Item item) {
        if (item == null) return "";
        String line = clean(item.lineId);
        if (!line.isEmpty()) return "line:" + normalize(line);
        if (item.itemId > 0L) return "item:" + item.itemId;
        return fallbackKey(item);
    }

    private static String fallbackKey(KitchenOrder.Item item) {
        if (item == null) return "";
        String name = clean(item.name);
        if (name.isEmpty()) name = clean(item.nameAr);
        if (name.isEmpty()) name = clean(item.nameEn);
        return name.isEmpty() ? "" : "name:" + normalize(name);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
