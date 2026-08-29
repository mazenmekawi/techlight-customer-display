package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Defensive parser for TechPro local WebSocket messages.
 * The POS has shipped more than one JSON shape, so this deliberately accepts aliases.
 */
public final class KitchenOrderParser {
    public enum Kind { SNAPSHOT, SAVED, UPDATED, PAYMENT, CANCELLED, CLEARED, IGNORE }

    public static final class ParsedEvent {
        public Kind kind = Kind.IGNORE;
        public String eventName = "";
        public KitchenOrder order;
        public boolean hasItems;
        public boolean explicitTemporarySave;
        public boolean explicitPayment;
        public boolean explicitClear;
    }

    private KitchenOrderParser() { }

    public static ParsedEvent parse(String raw) {
        ParsedEvent result = new ParsedEvent();
        JSONObject root = objectFrom(raw);
        if (root == null) return result;

        String event = firstText(root, "type", "messageType", "event", "action", "eventType", "command");
        result.eventName = event == null ? "" : event;

        Object payloadValue = firstValue(root, "payload", "body", "message", "data");
        JSONObject data = objectFrom(payloadValue);
        if (data == null) data = root;
        JSONObject orderObject = unwrap(data, "snapshot", "order", "invoice", "cart", "sale", "transaction", "data", "payload");
        if (orderObject == null) orderObject = data;

        JSONArray itemArray = findItems(orderObject);
        if (itemArray == null && orderObject != data) itemArray = findItems(data);
        if (itemArray == null && data != root) itemArray = findItems(root);
        result.hasItems = itemArray != null && itemArray.length() > 0;

        JSONObject[] sources = sources(orderObject, data, root);
        String status = firstTextSources(sources,
                "status", "orderStatus", "invoiceStatus", "saleStatus", "state", "viewState", "paymentStatus");
        String signal = (safe(event) + " " + safe(status)).toLowerCase(Locale.US);

        boolean cancel = containsAny(signal, "cancel", "void", "deleted", "deleteorder", "ملغي", "إلغاء", "الغاء")
                || firstBooleanSources(sources, false, "isCancelled", "cancelled", "isCanceled", "canceled", "isVoid");
        boolean explicitlyUnpaid = containsAny(signal, "unpaid", "notpaid", "غير مدفوع");
        boolean payment = (!explicitlyUnpaid && containsAny(signal,
                "paymentcompleted", "payment_success", "paymentsuccess", "thankyou", "checkout", "finalize", "finalised", "finalized",
                "salecompleted", "invoicecompleted", "receiptcompleted", "status paid", "=paid", "تم الدفع", "مدفوع"))
                || firstBooleanSources(sources, false, "isPaid", "paid", "paymentCompleted", "isPaymentCompleted");
        boolean saved = containsAny(signal,
                "temporary", "temporder", "temp_order", "tempsave", "savedorder", "ordersaved",
                "saveorder", "parked", "parkorder", "suspended", "suspendorder", "heldorder",
                "holdorder", "pendingorder", "حفظ مؤقت", "محفوظ")
                || firstBooleanSources(sources, false,
                "isTemp", "isTemporary", "temporary", "isSaved", "saved", "isParked", "parked", "isHeld");
        boolean clear = containsAny(signal, "clearcustomerdisplay", "clearorder", "clearcart", "cartcleared", "resetdisplay")
                || (itemArray != null && itemArray.length() == 0 && containsAny(signal, "idle", "clear"));
        boolean update = containsAny(signal, "update", "updated", "edit", "edited", "modify", "modified", "change", "changed");

        result.explicitTemporarySave = saved;
        result.explicitPayment = payment;
        result.explicitClear = clear;

        KitchenOrder order = parseOrder(sources, itemArray);
        result.order = order;

        if (cancel) result.kind = Kind.CANCELLED;
        else if (payment) result.kind = Kind.PAYMENT;
        else if (saved) result.kind = Kind.SAVED;
        else if (clear) result.kind = Kind.CLEARED;
        else if (update) result.kind = Kind.UPDATED;
        else if (order != null && (result.hasItems || hasOrderIdentity(order))) result.kind = Kind.SNAPSHOT;
        else result.kind = Kind.IGNORE;
        return result;
    }

    private static KitchenOrder parseOrder(JSONObject[] sources, JSONArray itemArray) {
        KitchenOrder order = new KitchenOrder();
        String id = firstTextSources(sources,
                "orderId", "invoiceId", "invId", "saleId", "transactionId", "documentId",
                "order_id", "invoice_id", "transaction_id", "uuid", "guid");
        String number = firstTextSources(sources,
                "invoiceNumber", "invoiceNo", "invNo", "invoice_no", "invoice_number",
                "orderNumber", "orderNo", "order_no", "receiptNo", "receiptNumber",
                "billNo", "documentNo", "tempOrderNo", "transactionNo", "serialNo");
        if (clean(id).isEmpty() && !clean(number).isEmpty()) id = "invoice-" + clean(number);
        order.id = clean(id);
        order.displayNumber = clean(number);
        order.table = clean(firstTextSources(sources,
                "tableNumber", "tableNo", "tableName", "table", "table_number", "table_no",
                "diningTable", "diningTableName", "diningTableNo", "tableCode", "tableLabel"));
        order.orderType = clean(firstTextSources(sources,
                "orderType", "orderTypeName", "saleType", "serviceType", "deliveryType", "order_type",
                "typeName", "transactionType"));
        order.customerNote = clean(firstTextSources(sources,
                "customerNote", "orderNote", "kitchenNote", "note", "notes", "remark", "remarks", "comment"));
        order.rawStatus = clean(firstTextSources(sources,
                "status", "orderStatus", "invoiceStatus", "saleStatus", "state", "viewState"));
        String payment = clean(firstTextSources(sources,
                "paymentStatus", "payStatus", "payment_state", "paidStatus"));
        boolean paid = firstBooleanSources(sources, false, "isPaid", "paid", "paymentCompleted", "isPaymentCompleted");
        order.paymentStatus = paid ? "PAID" : (payment.isEmpty() ? "UNPAID" : payment);
        order.revision = (int) Math.max(1L, firstLongSources(sources, 1L,
                "revision", "version", "orderRevision", "changeNumber", "rowVersion"));
        if (itemArray != null) {
            for (int i = 0; i < itemArray.length(); i++) {
                JSONObject source = objectFrom(itemArray.opt(i));
                if (source == null) continue;
                KitchenOrder.Item item = parseItem(source, i);
                if (item != null) order.items.add(item);
            }
        }
        if (!hasOrderIdentity(order) && order.items.isEmpty() && order.customerNote.isEmpty()) return null;
        return order;
    }

    private static KitchenOrder.Item parseItem(JSONObject source, int index) {
        KitchenOrder.Item item = new KitchenOrder.Item();
        item.lineId = clean(firstTextDeep(source,
                "lineId", "orderLineId", "invoiceLineId", "rowId", "detailId", "line_id", "id"));
        item.itemId = firstLongDeep(source, 0L,
                "itemId", "productId", "item_id", "product_id", "menuItemId");
        item.name = clean(firstTextDeep(source,
                "itemName", "name", "productName", "displayNameAr", "itemNameAr", "nameAr",
                "displayNameEn", "itemNameEn", "nameEn", "titleAr", "titleEn", "title",
                "item_name", "product_name", "description"));
        item.qty = firstDoubleDeep(source, 1d,
                "quantity", "qty", "count", "itemQuantity", "amountQty", "item_qty", "item_quantity");
        item.note = clean(firstTextDeep(source,
                "kitchenNote", "customerNote", "itemNote", "note", "notes", "remark", "remarks", "comment"));
        item.station = clean(firstTextDeep(source,
                "station", "stationName", "kitchenStation", "kitchenStationName", "departmentName"));
        collectOptions(source, item);
        if (item.name.isEmpty() && item.itemId <= 0L) return null;
        if (item.lineId.isEmpty()) item.lineId = "line-" + index + "-" + item.itemId + "-" + item.name;
        return item;
    }

    private static void collectOptions(JSONObject source, KitchenOrder.Item item) {
        String[] modifierKeys = {
                "modifiers", "modifier", "additions", "extras", "addons", "addOns", "options",
                "selectedOptions", "itemModifiers", "itemAdditions", "ingredients", "choices", "variants"
        };
        for (String key : modifierKeys) {
            Object value = valueForKey(source, key);
            collectOptionValue(value, item, false, 0);
        }
        String[] removedKeys = {
                "removedOptions", "removedIngredients", "excluded", "exclusions", "without", "deletions",
                "removed", "noIngredients"
        };
        for (String key : removedKeys) {
            Object value = valueForKey(source, key);
            collectOptionValue(value, item, true, 0);
        }
    }

    private static void collectOptionValue(Object raw, KitchenOrder.Item item, boolean forceRemoved, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 5) return;
        Object structured = structuredFrom(raw);
        if (structured instanceof JSONArray) {
            JSONArray array = (JSONArray) structured;
            for (int i = 0; i < array.length(); i++) collectOptionValue(array.opt(i), item, forceRemoved, depth + 1);
            return;
        }
        if (structured instanceof JSONObject) {
            JSONObject object = (JSONObject) structured;
            String name = clean(firstText(object,
                    "name", "modifierName", "additionName", "optionName", "ingredientName", "displayName",
                    "nameAr", "nameEn", "title", "label", "value"));
            boolean removed = forceRemoved || firstBoolean(object, false,
                    "removed", "isRemoved", "excluded", "isExcluded", "without", "deleted");
            double qty = firstDouble(object, 1d, "qty", "quantity", "count");
            if (!name.isEmpty()) {
                String rendered = qty > 1.001 ? trimNumber(qty) + " × " + name : name;
                addUnique(removed ? item.removed : item.modifiers, rendered);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectOptionValue(child, item, forceRemoved, depth + 1);
                }
            }
            return;
        }
        String value = clean(String.valueOf(raw));
        if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) addUnique(forceRemoved ? item.removed : item.modifiers, value);
    }

    private static void addUnique(List<String> target, String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return;
        for (String existing : target) if (existing.equalsIgnoreCase(clean)) return;
        target.add(clean);
    }

    private static String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static boolean hasOrderIdentity(KitchenOrder order) {
        return order != null && (!clean(order.id).isEmpty() || !clean(order.displayNumber).isEmpty());
    }

    private static JSONObject[] sources(JSONObject first, JSONObject second, JSONObject third) {
        ArrayList<JSONObject> values = new ArrayList<>();
        if (first != null) values.add(first);
        if (second != null && second != first) values.add(second);
        if (third != null && third != first && third != second) values.add(third);
        return values.toArray(new JSONObject[0]);
    }

    private static JSONArray findItems(JSONObject object) {
        if (object == null) return null;
        String[] keys = {
                "items", "lines", "orderLines", "cartItems", "products", "orderItems", "invoiceItems",
                "rows", "order_items", "invoice_items", "cart_items", "itemList", "Itemlist",
                "pos_dt_Collection", "invoiceDetails", "orderDetails", "details"
        };
        JSONArray empty = null;
        for (String key : keys) {
            if (!hasKey(object, key)) continue;
            JSONArray candidate = arrayFrom(valueForKey(object, key));
            if (candidate == null) continue;
            if (candidate.length() > 0) return candidate;
            if (empty == null) empty = candidate;
        }
        JSONArray recursive = findItemsRecursive(object, 0);
        return recursive == null ? empty : recursive;
    }

    private static JSONArray findItemsRecursive(Object raw, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 6) return null;
        Object structured = structuredFrom(raw);
        if (structured instanceof JSONObject) {
            JSONObject object = (JSONObject) structured;
            String[] keys = {"items", "lines", "orderLines", "cartItems", "products", "orderItems", "invoiceItems",
                    "rows", "order_items", "invoice_items", "cart_items", "itemList", "Itemlist",
                    "pos_dt_Collection", "invoiceDetails", "orderDetails", "details"};
            for (String key : keys) {
                JSONArray array = arrayFrom(valueForKey(object, key));
                if (array != null && array.length() > 0) return array;
            }
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (looksFinancialOnlyKey(key)) continue;
                JSONArray nested = findItemsRecursive(object.opt(key), depth + 1);
                if (nested != null && nested.length() > 0) return nested;
            }
        } else if (structured instanceof JSONArray) {
            JSONArray array = (JSONArray) structured;
            if (looksLikeItems(array)) return array;
            for (int i = 0; i < array.length(); i++) {
                JSONArray nested = findItemsRecursive(array.opt(i), depth + 1);
                if (nested != null && nested.length() > 0) return nested;
            }
        }
        return null;
    }

    private static boolean looksFinancialOnlyKey(String key) {
        String value = safe(key).toLowerCase(Locale.US);
        return value.contains("payment") || value.contains("tax") || value.contains("summary");
    }

    private static boolean looksLikeItems(JSONArray array) {
        if (array == null || array.length() == 0) return false;
        int max = Math.min(4, array.length());
        for (int i = 0; i < max; i++) {
            JSONObject object = objectFrom(array.opt(i));
            if (object != null && (firstTextDeep(object, "itemName", "productName", "nameAr", "nameEn", "item_name") != null
                    || firstLongDeep(object, 0, "itemId", "productId", "item_id") > 0)) return true;
        }
        return false;
    }

    private static JSONArray arrayFrom(Object raw) {
        Object structured = structuredFrom(raw);
        if (structured instanceof JSONArray) return (JSONArray) structured;
        return null;
    }

    private static JSONObject unwrap(JSONObject object, String... keys) {
        JSONObject current = object;
        for (int pass = 0; pass < 5 && current != null; pass++) {
            JSONObject nested = null;
            for (String key : keys) {
                nested = objectFrom(valueForKey(current, key));
                if (nested != null) break;
            }
            if (nested == null || nested == current) break;
            current = nested;
        }
        return current;
    }

    private static JSONObject objectFrom(Object raw) {
        Object structured = structuredFrom(raw);
        return structured instanceof JSONObject ? (JSONObject) structured : null;
    }

    private static Object structuredFrom(Object raw) {
        Object current = raw;
        for (int pass = 0; pass < 4; pass++) {
            if (current instanceof JSONObject || current instanceof JSONArray) return current;
            if (!(current instanceof String)) return current;
            String text = ((String) current).trim();
            if (text.isEmpty()) return null;
            try {
                Object parsed = new JSONTokener(text).nextValue();
                if (parsed instanceof String && text.equals(parsed)) return null;
                current = parsed;
            } catch (Exception ignored) {
                return null;
            }
        }
        return current;
    }

    private static Object firstValue(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Object value = valueForKey(object, key);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return null;
    }

    private static String firstTextSources(JSONObject[] sources, String... keys) {
        for (JSONObject source : sources) {
            String value = firstText(source, keys);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Object raw = valueForKey(object, key);
            if (raw instanceof JSONObject) {
                String localized = firstText((JSONObject) raw, "ar", "ar-SA", "arabic", "en", "en-US", "english", "value");
                if (localized != null) return localized;
                continue;
            }
            if (raw == null || raw == JSONObject.NULL || raw instanceof JSONArray) continue;
            String value = clean(String.valueOf(raw));
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static String firstTextDeep(JSONObject object, String... keys) {
        return firstTextDeep(object, 0, keys);
    }

    private static String firstTextDeep(JSONObject object, int depth, String... keys) {
        if (object == null || depth > 4) return null;
        String direct = firstText(object, keys);
        if (direct != null) return direct;
        String[] nested = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String key : nested) {
            String value = firstTextDeep(objectFrom(valueForKey(object, key)), depth + 1, keys);
            if (value != null) return value;
        }
        return null;
    }

    private static long firstLongSources(JSONObject[] sources, long fallback, String... keys) {
        for (JSONObject source : sources) {
            long value = firstLong(source, Long.MIN_VALUE, keys);
            if (value != Long.MIN_VALUE) return value;
        }
        return fallback;
    }

    private static long firstLong(JSONObject object, long fallback, String... keys) {
        if (object == null) return fallback;
        for (String key : keys) {
            Object value = valueForKey(object, key);
            if (value == null || value == JSONObject.NULL) continue;
            try { return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value).trim()); }
            catch (Exception ignored) { }
        }
        return fallback;
    }

    private static long firstLongDeep(JSONObject object, long fallback, String... keys) {
        if (object == null) return fallback;
        long direct = firstLong(object, Long.MIN_VALUE, keys);
        if (direct != Long.MIN_VALUE) return direct;
        String[] nested = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String key : nested) {
            long value = firstLongDeep(objectFrom(valueForKey(object, key)), Long.MIN_VALUE, keys);
            if (value != Long.MIN_VALUE) return value;
        }
        return fallback;
    }

    private static double firstDouble(JSONObject object, double fallback, String... keys) {
        if (object == null) return fallback;
        for (String key : keys) {
            Object value = valueForKey(object, key);
            if (value == null || value == JSONObject.NULL) continue;
            try { return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value).trim()); }
            catch (Exception ignored) { }
        }
        return fallback;
    }

    private static double firstDoubleDeep(JSONObject object, double fallback, String... keys) {
        if (object == null) return fallback;
        double direct = firstDouble(object, Double.NaN, keys);
        if (!Double.isNaN(direct)) return direct;
        String[] nested = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String key : nested) {
            double value = firstDoubleDeep(objectFrom(valueForKey(object, key)), Double.NaN, keys);
            if (!Double.isNaN(value)) return value;
        }
        return fallback;
    }

    private static boolean firstBooleanSources(JSONObject[] sources, boolean fallback, String... keys) {
        for (JSONObject source : sources) {
            Boolean value = firstBooleanNullable(source, keys);
            if (value != null) return value;
        }
        return fallback;
    }

    private static boolean firstBoolean(JSONObject object, boolean fallback, String... keys) {
        Boolean value = firstBooleanNullable(object, keys);
        return value == null ? fallback : value;
    }

    private static Boolean firstBooleanNullable(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Object raw = valueForKey(object, key);
            if (raw == null || raw == JSONObject.NULL) continue;
            if (raw instanceof Boolean) return (Boolean) raw;
            if (raw instanceof Number) return ((Number) raw).intValue() != 0;
            String value = clean(String.valueOf(raw)).toLowerCase(Locale.US);
            if ("true".equals(value) || "yes".equals(value) || "1".equals(value)) return true;
            if ("false".equals(value) || "no".equals(value) || "0".equals(value)) return false;
        }
        return null;
    }

    private static boolean hasKey(JSONObject object, String key) {
        return valueForKey(object, key) != null;
    }

    private static Object valueForKey(JSONObject object, String requested) {
        if (object == null) return null;
        if (object.has(requested)) return object.opt(requested);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.equalsIgnoreCase(requested)) return object.opt(key);
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        String text = safe(value).toLowerCase(Locale.US);
        for (String needle : needles) if (!safe(needle).isEmpty() && text.contains(needle.toLowerCase(Locale.US))) return true;
        return false;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
