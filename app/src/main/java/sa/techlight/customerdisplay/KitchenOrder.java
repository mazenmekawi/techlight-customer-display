package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Durable kitchen ticket used by TechPro Kitchen. */
public final class KitchenOrder {
    public enum Status { NEW, PREPARING, READY, DONE, CANCELLED }

    public String id = "";
    public String displayNumber = "";
    public String table = "";
    public String orderType = "";
    public String customerNote = "";
    public String paymentStatus = "";
    public String rawStatus = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = createdAt;
    public long changedAt;
    public long startedAt;
    public long readyAt;
    public int revision = 1;
    public Status kitchenStatus = Status.NEW;
    /** True only when this ticket came from TechPro temporary/parked save. */
    public boolean temporaryOrder;
    /** True when temporary save had to be inferred from cart-clear without payment. */
    public boolean inferredTemporarySave;
    public final List<Item> items = new ArrayList<>();

    public static final class Item {
        public String lineId = "";
        public long itemId;
        public String name = "";
        public String nameAr = "";
        public String nameEn = "";
        public String imagePath = "";
        public double qty = 1d;
        public String note = "";
        public String station = "";
        public final List<String> modifiers = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();

        public String displayName(boolean arabic) {
            String preferred = arabic ? clean(nameAr) : clean(nameEn);
            if (!preferred.isEmpty()) return preferred;
            String alternate = arabic ? clean(nameEn) : clean(nameAr);
            if (!alternate.isEmpty()) return alternate;
            return clean(name).isEmpty() ? (arabic ? "صنف" : "Item") : clean(name);
        }

        public String signature() {
            StringBuilder out = new StringBuilder();
            out.append(clean(lineId)).append('|').append(itemId).append('|').append(clean(name))
                    .append('|').append(clean(nameAr)).append('|').append(clean(nameEn)).append('|')
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
            object.put("nameAr", nameAr);
            object.put("nameEn", nameEn);
            object.put("imagePath", imagePath);
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
            item.nameAr = object.optString("nameAr", "");
            item.nameEn = object.optString("nameEn", "");
            item.imagePath = object.optString("imagePath", "");
            item.qty = object.optDouble("qty", 1d);
            item.note = object.optString("note", "");
            item.station = object.optString("station", "");
            JSONArray modifiers = object.optJSONArray("modifiers");
            if (modifiers != null) for (int i = 0; i < modifiers.length(); i++) {
                String value = clean(modifiers.optString(i, ""));
                if (!value.isEmpty()) item.modifiers.add(value);
            }
            JSONArray removed = object.optJSONArray("removed");
            if (removed != null) for (int i = 0; i < removed.length(); i++) {
                String value = clean(removed.optString(i, ""));
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
        copy.startedAt = startedAt;
        copy.readyAt = readyAt;
        copy.revision = revision;
        copy.kitchenStatus = kitchenStatus;
        copy.temporaryOrder = temporaryOrder;
        copy.inferredTemporarySave = inferredTemporarySave;
        for (Item source : items) copy.items.add(copyItem(source));
        return copy;
    }

    static Item copyItem(Item source) {
        Item item = new Item();
        item.lineId = source.lineId;
        item.itemId = source.itemId;
        item.name = source.name;
        item.nameAr = source.nameAr;
        item.nameEn = source.nameEn;
        item.imagePath = source.imagePath;
        item.qty = source.qty;
        item.note = source.note;
        item.station = source.station;
        item.modifiers.addAll(source.modifiers);
        item.removed.addAll(source.removed);
        return item;
    }

    public String contentSignature() {
        StringBuilder out = new StringBuilder();
        out.append(clean(table)).append('|').append(clean(orderType)).append('|').append(clean(customerNote));
        for (Item item : items) out.append("||").append(item.signature());
        return out.toString();
    }

    public boolean isPaid() {
        String normalized = clean(paymentStatus).toLowerCase(Locale.US);
        if (normalized.isEmpty() || normalized.contains("unpaid") || normalized.contains("notpaid")
                || normalized.contains("غير مدفوع")) return false;
        return normalized.contains("paid") || normalized.contains("مدفوع") || "1".equals(normalized)
                || "true".equals(normalized) || "completed".equals(normalized);
    }

    public String bestNumber() {
        String number = meaningfulNumber(displayNumber);
        if (!number.isEmpty()) return number;
        if (id.startsWith("invoice-")) {
            String derived = meaningfulNumber(id.substring("invoice-".length()));
            if (!derived.isEmpty()) return derived;
        }
        return "";
    }

    static String meaningfulNumber(String value) {
        String clean = clean(value);
        if (clean.isEmpty() || "0".equals(clean) || "0.0".equals(clean) || "null".equalsIgnoreCase(clean)) return "";
        return clean;
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
        object.put("startedAt", startedAt);
        object.put("readyAt", readyAt);
        object.put("revision", revision);
        object.put("kitchenStatus", kitchenStatus.name());
        object.put("temporaryOrder", temporaryOrder);
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
        order.paymentStatus = object.optString("paymentStatus", "");
        order.rawStatus = object.optString("rawStatus", "");
        order.createdAt = object.optLong("createdAt", System.currentTimeMillis());
        order.updatedAt = object.optLong("updatedAt", order.createdAt);
        order.changedAt = object.optLong("changedAt", 0L);
        order.startedAt = object.optLong("startedAt", 0L);
        order.readyAt = object.optLong("readyAt", 0L);
        order.revision = Math.max(1, object.optInt("revision", 1));
        try { order.kitchenStatus = Status.valueOf(object.optString("kitchenStatus", "NEW")); }
        catch (Exception ignored) { order.kitchenStatus = Status.NEW; }
        order.temporaryOrder = object.optBoolean("temporaryOrder", object.optBoolean("inferredTemporarySave", false));
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
