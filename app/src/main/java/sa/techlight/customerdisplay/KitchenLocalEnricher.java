package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Extracts groups/stations and modifiers that exist only in the local POS feed. */
public final class KitchenLocalEnricher {
    private static final String[] ID_KEYS = {
            "itemId", "itemID", "productId", "productID", "erpItemId", "id"
    };
    private static final String[] LINE_KEYS = {
            "lineId", "lineID", "detailId", "rowId", "cartItemId"
    };
    private static final String[] GROUP_KEYS = {
            "station", "stationName", "kitchenStation", "kitchenGroup",
            "kitchenGroupName", "groupName", "departmentName", "categoryName",
            "printGroup", "printerGroup", "preparationArea", "productionSection"
    };
    private static final String[] MODIFIER_KEYS = {
            "modifiers", "modifier", "addons", "addOns", "options", "extras",
            "selectedOptions", "itemModifiers", "choices"
    };
    private static final String[] NAME_KEYS = {
            "name", "nameAr", "nameEn", "itemName", "productName", "description"
    };

    private KitchenLocalEnricher() { }

    public static void enrich(String raw, KitchenOrder order) {
        if (raw == null || order == null || order.items.isEmpty()) return;
        Object root;
        try {
            String text = raw.trim();
            root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        } catch (Throwable ignored) {
            return;
        }

        ArrayList<NodeData> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        if (nodes.isEmpty()) return;

        Map<Long, NodeData> byId = new LinkedHashMap<>();
        Map<String, NodeData> byLine = new LinkedHashMap<>();
        Map<String, NodeData> byName = new LinkedHashMap<>();
        Set<String> globalGroups = new LinkedHashSet<>();
        for (NodeData node : nodes) {
            if (node.itemId > 0L) byId.put(node.itemId, node);
            if (!node.lineId.isEmpty()) byLine.put(normalize(node.lineId), node);
            if (!node.name.isEmpty()) byName.put(normalize(node.name), node);
            if (!node.group.isEmpty()) globalGroups.add(node.group);
        }

        for (KitchenOrder.Item item : order.items) {
            NodeData node = item.itemId > 0L ? byId.get(item.itemId) : null;
            if (node == null && !clean(item.lineId).isEmpty()) {
                node = byLine.get(normalize(item.lineId));
            }
            if (node == null) {
                String name = clean(item.name);
                if (name.isEmpty()) name = clean(item.nameAr);
                if (name.isEmpty()) name = clean(item.nameEn);
                if (!name.isEmpty()) node = byName.get(normalize(name));
            }
            if (node != null) {
                if (clean(item.station).isEmpty() && !node.group.isEmpty()) item.station = node.group;
                addUnique(item.modifiers, node.modifiers);
            } else if (clean(item.station).isEmpty() && globalGroups.size() == 1) {
                item.station = globalGroups.iterator().next();
            }
        }
    }

    private static final class NodeData {
        long itemId;
        String lineId = "";
        String name = "";
        String group = "";
        final ArrayList<String> modifiers = new ArrayList<>();
    }

    private static void collect(Object value, List<NodeData> output, int depth) {
        if (value == null || depth > 16) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), output, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        NodeData node = parseNode(object);
        if (node.itemId > 0L || !node.lineId.isEmpty() || !node.group.isEmpty() || !node.modifiers.isEmpty()) {
            output.add(node);
        }
        JSONArray names = object.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            Object child = object.opt(names.optString(i));
            if (child instanceof JSONObject || child instanceof JSONArray) collect(child, output, depth + 1);
            else if (child instanceof String) {
                String nested = clean(String.valueOf(child));
                if ((nested.startsWith("{") && nested.endsWith("}"))
                        || (nested.startsWith("[") && nested.endsWith("]"))) {
                    try { collect(nested.startsWith("[") ? new JSONArray(nested) : new JSONObject(nested), output, depth + 1); }
                    catch (Throwable ignored) { }
                }
            }
        }
    }

    private static NodeData parseNode(JSONObject object) {
        NodeData node = new NodeData();
        for (String key : ID_KEYS) {
            long value = asLong(object.opt(key));
            if (value > 0L) { node.itemId = value; break; }
        }
        for (String key : LINE_KEYS) {
            String value = scalar(object.opt(key));
            if (!value.isEmpty()) { node.lineId = value; break; }
        }
        for (String key : NAME_KEYS) {
            String value = scalar(object.opt(key));
            if (!value.isEmpty()) { node.name = value; break; }
        }
        for (String key : GROUP_KEYS) {
            String value = scalar(object.opt(key));
            if (!value.isEmpty() && !looksNumeric(value)) { node.group = value; break; }
        }
        for (String key : MODIFIER_KEYS) appendModifiers(object.opt(key), node.modifiers, 0);
        return node;
    }

    private static void appendModifiers(Object value, List<String> output, int depth) {
        if (value == null || depth > 5) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) appendModifiers(array.opt(i), output, depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String name = "";
            for (String key : NAME_KEYS) {
                name = scalar(object.opt(key));
                if (!name.isEmpty()) break;
            }
            if (!name.isEmpty()) addUnique(output, name);
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                Object child = object.opt(names.optString(i));
                if (child instanceof JSONObject || child instanceof JSONArray) appendModifiers(child, output, depth + 1);
            }
        } else {
            String text = scalar(value);
            if (!text.isEmpty() && !looksNumeric(text)) addUnique(output, text);
        }
    }

    private static void addUnique(List<String> target, List<String> values) {
        for (String value : values) addUnique(target, value);
    }

    private static void addUnique(List<String> target, String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return;
        String normalized = normalize(clean);
        for (String old : target) if (normalize(old).equals(normalized)) return;
        target.add(clean);
    }

    private static long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(clean(String.valueOf(value)).replaceAll("\\.0+$", "")); }
        catch (Throwable ignored) { return 0L; }
    }

    private static String scalar(Object value) {
        if (value == null || value == JSONObject.NULL || value instanceof JSONObject || value instanceof JSONArray) return "";
        return clean(String.valueOf(value));
    }

    private static boolean looksNumeric(String value) { return value.matches("[-+]?\\d+(?:\\.\\d+)?"); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
