package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Generic parser for TechPro PosInvoice responses from the official POS API. */
public final class KitchenPosInvoiceParser {
    private KitchenPosInvoiceParser() { }

    static final class Candidate {
        long id;
        String number = "";
        String code = "";
        String table = "";
        String orderType = "";
        long orderTypeId;
        String note = "";
        String posCode = "";
        long invoiceDate;
        final ArrayList<ItemKey> items = new ArrayList<>();

        String usableNumber() {
            String numberClean = cleanNumber(number);
            if (!numberClean.isEmpty()) return numberClean;
            return cleanNumber(code);
        }
    }

    static final class ItemKey {
        long itemId;
        long unitId;
        double qty;
        String name = "";
    }

    static List<Candidate> parse(String raw) {
        ArrayList<Candidate> found = new ArrayList<>();
        collect(structured(raw), found, 0);
        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate c : found) {
            String number = c.usableNumber();
            if (number.isEmpty()) continue;
            Candidate old = unique.get(number);
            if (old == null || c.items.size() > old.items.size() || c.invoiceDate > old.invoiceDate) {
                unique.put(number, c);
            }
        }
        return new ArrayList<>(unique.values());
    }

    static List<KitchenOrder> convert(List<Candidate> candidates, String posCode) {
        ArrayList<KitchenOrder> out = new ArrayList<>();
        if (candidates == null) return out;
        for (Candidate c : candidates) {
            if (c == null) continue;
            String number = c.usableNumber();
            if (number.isEmpty()) continue;
            if (!clean(posCode).isEmpty() && !clean(c.posCode).isEmpty()
                    && !clean(posCode).equalsIgnoreCase(clean(c.posCode))) continue;
            KitchenOrder order = new KitchenOrder();
            order.id = "invoice-" + number;
            order.displayNumber = number;
            order.table = normalizeTable(c.table);
            order.orderType = KitchenSignalV2.normalizeOrderType(c.orderType);
            if (order.orderType.isEmpty() && c.orderTypeId > 0L) order.orderType = "TYPE_" + c.orderTypeId;
            order.customerNote = clean(c.note);
            order.paymentStatus = "PAID";
            order.rawStatus = "POS_INVOICE";
            order.temporaryOrder = false;
            if (c.invoiceDate > 0L) {
                order.createdAt = c.invoiceDate;
                order.updatedAt = c.invoiceDate;
            }
            for (ItemKey source : c.items) {
                if (source == null || (source.itemId <= 0L && clean(source.name).isEmpty())) continue;
                KitchenOrder.Item item = new KitchenOrder.Item();
                item.itemId = source.itemId;
                item.qty = source.qty > 0d ? source.qty : 1d;
                item.name = clean(source.name);
                order.items.add(item);
            }
            // Some invoice list endpoints expose headers without lines. Keep the header because it can
            // still mark an existing temporary ticket paid; do not invent item rows.
            out.add(order);
        }
        return out;
    }

    private static void collect(Object raw, List<Candidate> out, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 14) return;
        Object value = structured(raw);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), out, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        if (looksLikeInvoice(object)) {
            Candidate c = parseCandidate(object);
            if (c != null && !c.usableNumber().isEmpty()) out.add(c);
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object child = object.opt(keys.next());
            Object nested = structured(child);
            if (nested instanceof JSONObject || nested instanceof JSONArray) collect(nested, out, depth + 1);
        }
    }

    private static boolean looksLikeInvoice(JSONObject object) {
        if (object == null) return false;
        boolean invoiceMarker = hasAny(object,
                "invoiceNumber", "invoice_number", "invoiceNo", "invoiceCode", "InvoiceCode",
                "invoiceDate", "invoice_date", "InvoiceDate", "posInvoiceId");
        if (!invoiceMarker) return false;
        // Avoid accidentally treating a line item as an invoice header.
        boolean lineLike = hasAny(object, "qty", "quantity", "unitPrice", "lineTotal", "itemId")
                && !hasAny(object, "invoiceDate", "InvoiceDate", "invoiceCode", "InvoiceCode", "invoiceNumber");
        return !lineLike;
    }

    private static Candidate parseCandidate(JSONObject object) {
        Candidate c = new Candidate();
        c.id = longValue(object, 0L, "id", "Id", "invoiceId", "posInvoiceId", "InvoiceId");
        c.number = text(object,
                "invoiceNumber", "invoice_number", "InvoiceNumber", "invoiceNo", "InvoiceNo", "number", "Number");
        c.code = text(object, "invoiceCode", "InvoiceCode", "code", "Code", "referenceCode");
        c.invoiceDate = dateValue(first(object,
                "invoiceDate", "InvoiceDate", "invoice_date", "date", "Date", "createdAt", "creationDate"));
        c.table = text(object,
                "tableNumber", "tableNo", "tableName", "table", "reservationNumber", "reservation", "ReservationNumber");
        c.orderType = text(object,
                "orderTypeName", "serviceTypeName", "orderType", "serviceType", "OrderTypeName");
        c.orderTypeId = longValue(object, 0L, "orderTypeId", "OrderTypeId", "serviceTypeId");
        c.note = text(object, "note", "notes", "invoiceNote", "customerNote", "Note");
        c.posCode = text(object, "posCode", "PosCode", "pointCode", "cashierCode", "pos_code");
        JSONArray rows = itemArray(object);
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = asObject(rows.opt(i));
            if (row == null) continue;
            ItemKey item = new ItemKey();
            item.itemId = longValue(row, 0L, "itemId", "ItemId", "productId", "item_id");
            item.unitId = longValue(row, 0L, "unitId", "UnitId", "unit_id");
            item.qty = doubleValue(row, 0d, "qty", "Qty", "quantity", "Quantity", "itemQty");
            item.name = textDeep(row, "itemName", "name", "nameAr", "nameEn", "productName", "description");
            if (item.itemId > 0L || !clean(item.name).isEmpty()) c.items.add(item);
        }
        return c;
    }

    private static JSONArray itemArray(JSONObject object) {
        String[] keys = {
                "invoiceLines", "InvoiceLines", "lines", "Lines", "items", "Items",
                "invoiceDetails", "InvoiceDetails", "details", "Details", "posInvoiceDetails", "PosInvoiceDetails"
        };
        for (String key : keys) {
            Object raw = value(object, key);
            Object parsed = structured(raw);
            if (parsed instanceof JSONArray) return (JSONArray) parsed;
            if (parsed instanceof JSONObject) {
                JSONArray array = new JSONArray();
                Iterator<String> it = ((JSONObject) parsed).keys();
                while (it.hasNext()) {
                    Object child = ((JSONObject) parsed).opt(it.next());
                    if (child instanceof JSONObject) array.put(child);
                }
                if (array.length() > 0) return array;
            }
        }
        return null;
    }

    private static String textDeep(JSONObject object, String... keys) {
        String direct = text(object, keys);
        if (!direct.isEmpty()) return direct;
        String[] nested = {"item", "product", "itemData", "productData", "data"};
        for (String key : nested) {
            JSONObject child = asObject(value(object, key));
            if (child == null) continue;
            String found = textDeep(child, keys);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static String text(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw == null || raw == JSONObject.NULL || raw instanceof JSONArray) continue;
            if (raw instanceof JSONObject) {
                String localized = text((JSONObject) raw, "ar", "nameAr", "value", "en", "nameEn");
                if (!localized.isEmpty()) return localized;
                continue;
            }
            String result = clean(String.valueOf(raw));
            if (!result.isEmpty() && !"null".equalsIgnoreCase(result)) return result;
        }
        return "";
    }

    private static Object first(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw != null && raw != JSONObject.NULL) return raw;
        }
        return null;
    }

    private static boolean hasAny(JSONObject object, String... keys) {
        for (String key : keys) if (value(object, key) != null) return true;
        return false;
    }

    private static long longValue(JSONObject object, long fallback, String... keys) {
        Object raw = first(object, keys);
        if (raw == null) return fallback;
        try { return raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static double doubleValue(JSONObject object, double fallback, String... keys) {
        Object raw = first(object, keys);
        if (raw == null) return fallback;
        try { return raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static Object value(JSONObject object, String requested) {
        if (object == null) return null;
        if (object.has(requested)) return object.opt(requested);
        String wanted = normalizeKey(requested);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (normalizeKey(key).equals(wanted)) return object.opt(key);
        }
        return null;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replace("_", "").replace("-", "").replace(" ", "");
    }

    private static JSONObject asObject(Object raw) {
        Object parsed = structured(raw);
        return parsed instanceof JSONObject ? (JSONObject) parsed : null;
    }

    private static Object structured(Object raw) {
        Object value = raw;
        for (int i = 0; i < 5; i++) {
            if (value instanceof JSONObject || value instanceof JSONArray) return value;
            if (!(value instanceof String)) return value;
            String text = ((String) value).trim();
            if (text.isEmpty()) return value;
            try {
                Object parsed = new JSONTokener(text).nextValue();
                if (parsed instanceof String && parsed.equals(value)) return parsed;
                value = parsed;
            } catch (Exception ignored) { return value; }
        }
        return value;
    }

    private static long dateValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return 0L;
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            return value < 10_000_000_000L ? value * 1000L : value;
        }
        String text = clean(String.valueOf(raw));
        if (text.isEmpty()) return 0L;
        try {
            long value = Long.parseLong(text);
            return value < 10_000_000_000L ? value * 1000L : value;
        } catch (Exception ignored) { }
        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"
        };
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                if (!format.contains("XXX")) parser.setTimeZone(TimeZone.getDefault());
                Date date = parser.parse(text);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) { }
        }
        return 0L;
    }

    private static String cleanNumber(String value) {
        String cleaned = KitchenSignalV2.cleanIdentity(value);
        if (cleaned.toLowerCase(Locale.US).startsWith("invoice-")) cleaned = cleaned.substring(8);
        return cleaned.trim();
    }

    private static String normalizeTable(String value) {
        String table = clean(value);
        if (table.equals("0") || table.equals("0.0") || table.equalsIgnoreCase("null")) return "";
        return table;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
