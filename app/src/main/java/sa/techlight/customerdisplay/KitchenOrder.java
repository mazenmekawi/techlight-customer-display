package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Kitchen-facing immutable-ish order model persisted locally on the display. */
public final class KitchenOrder {
    public enum Status { NEW, PREPARING, READY, DONE, CANCELLED }

    public String id = "";
    public String displayNumber = "";
    public String table = "";
    public String orderType = "";
    public String customerNote = "";
    public String paymentStatus = "UNPAID";
    public String rawStatus = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = createdAt;
    public long changedAt = 0L;
    public int revision = 1;
    public Status kitchenStatus = Status.NEW;
    public boolean inferredTemporarySave;
    public final List<Item> items = new ArrayList<>();

    public static final class Item {
        public String lineId = "";
        public long itemId;
        public String name = "";
        public double qty = 1d;
        public String note = "";
        public String station = "";
        public final List<String> modifiers = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();

        public String signature() {
            StringBuilder out = new StringBuilder();
            out.append(lineId).append('|').append(itemId).append('|').append(clean(name)).append('|')
                    .append(String.format(Locale.US, "%.3f", qty)).append('|').append(clean(note));
            for (String value : modifiers) out.append("|+").append(clean(value));
            for (String value : removed) out.append("|-").append(clean(value));
            return out.toString();
        }

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("lineId", lineId);
            object.put("itemId", itemId);
            object.put("name", name);
            object.put("qty", qty);
            object.put("note", note);
            object.put("station", station);
            object.put("modifiers", new JSONArray(modifiers));
            object.put("removed", new JSONArray(removed));
            return object;
        }

        static Item fromJson(JSONObject object) {
            Item item = new Item();
            item.lineId = object.optString("lineId", "");
            item.itemId = object.optLong("itemId", 0L);
            item.name = object.optString("name", "");
            item.qty = object.optDouble("qty", 1d);
            item.note = object.optString("note", "");
            item.station = object.optString("station", "");
            JSONArray modifiers = object.optJSONArray("modifiers");
            if (modifiers != null) for (int i = 0; i < modifiers.length(); i++) {
                String value = modifiers.optString(i, "").trim();
                if (!value.isEmpty()) item.modifiers.add(value);
            }
            JSONArray removed = object.optJSONArray("removed");
            if (removed != null) for (int i = 0; i < removed.length(); i++) {
                String value = removed.optString(i, "").trim();
                if (!value.isEmpty()) item.removed.add(value);
            }
            return item;
        }
    }

    public KitchenOrder copy() {
        KitchenOrder copy = new KitchenOrder();
        copy.id = id;
        copy.displayNumber = displayNumber;
        copy.table = table;
        copy.orderType = orderType;
        copy.customerNote = customerNote;
        copy.paymentStatus = paymentStatus;
        copy.rawStatus = rawStatus;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.changedAt = changedAt;
        copy.revision = revision;
        copy.kitchenStatus = kitchenStatus;
        copy.inferredTemporarySave = inferredTemporarySave;
        for (Item source : items) {
            Item item = new Item();
            item.lineId = source.lineId;
            item.itemId = source.itemId;
            item.name = source.name;
            item.qty = source.qty;
            item.note = source.note;
            item.station = source.station;
            item.modifiers.addAll(source.modifiers);
            item.removed.addAll(source.removed);
            copy.items.add(item);
        }
        return copy;
    }

    public String contentSignature() {
        StringBuilder out = new StringBuilder();
        out.append(clean(table)).append('|').append(clean(orderType)).append('|').append(clean(customerNote));
        for (Item item : items) out.append("||").append(item.signature());
        return out.toString();
    }

    public boolean isPaid() {
        String normalized = clean(paymentStatus).toLowerCase(Locale.US);
        if (normalized.contains("unpaid") || normalized.contains("غير مدفوع")) return false;
        return normalized.contains("paid") || normalized.contains("مدفوع") || "1".equals(normalized)
                || "true".equals(normalized);
    }

    public String bestNumber() {
        if (!clean(displayNumber).isEmpty()) return displayNumber.trim();
        if (id.startsWith("invoice-")) return id.substring("invoice-".length());
        return id.length() > 10 ? id.substring(Math.max(0, id.length() - 8)) : id;
    }

    JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("displayNumber", displayNumber);
        object.put("table", table);
        object.put("orderType", orderType);
        object.put("customerNote", customerNote);
        object.put("paymentStatus", paymentStatus);
        object.put("rawStatus", rawStatus);
        object.put("createdAt", createdAt);
        object.put("updatedAt", updatedAt);
        object.put("changedAt", changedAt);
        object.put("revision", revision);
        object.put("kitchenStatus", kitchenStatus.name());
        object.put("inferredTemporarySave", inferredTemporarySave);
        JSONArray rows = new JSONArray();
        for (Item item : items) rows.put(item.toJson());
        object.put("items", rows);
        return object;
    }

    static KitchenOrder fromJson(JSONObject object) {
        KitchenOrder order = new KitchenOrder();
        order.id = object.optString("id", "");
        order.displayNumber = object.optString("displayNumber", "");
        order.table = object.optString("table", "");
        order.orderType = object.optString("orderType", "");
        order.customerNote = object.optString("customerNote", "");
        order.paymentStatus = object.optString("paymentStatus", "UNPAID");
        order.rawStatus = object.optString("rawStatus", "");
        order.createdAt = object.optLong("createdAt", System.currentTimeMillis());
        order.updatedAt = object.optLong("updatedAt", order.createdAt);
        order.changedAt = object.optLong("changedAt", 0L);
        order.revision = Math.max(1, object.optInt("revision", 1));
        try { order.kitchenStatus = Status.valueOf(object.optString("kitchenStatus", "NEW")); }
        catch (Exception ignored) { order.kitchenStatus = Status.NEW; }
        order.inferredTemporarySave = object.optBoolean("inferredTemporarySave", false);
        JSONArray items = object.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row != null) order.items.add(Item.fromJson(row));
        }
        return order;
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
}
