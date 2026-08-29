package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Iterator;
import java.util.Locale;

/**
 * Normalizes TechPro kitchen events without ever treating placeholder zero values as order identity.
 * It also searches nested payloads for invoice number, table and service type metadata.
 */
public final class KitchenSignalV2 {
    public static final class Signal {
        public KitchenOrderParser.ParsedEvent parsed;
        public KitchenOrder order;
        public String raw;

        public boolean hasStrongIdentity() {
            return order != null && (valid(order.id) || valid(order.displayNumber));
        }
    }

    private KitchenSignalV2() { }

    public static Signal parse(String raw) {
        Signal out = new Signal();
        out.raw = raw == null ? "" : raw;
        out.parsed = KitchenOrderParser.parse(out.raw);
        out.order = out.parsed.order == null ? null : out.parsed.order.copy();
        if (out.order == null) return out;

        out.order.id = cleanIdentity(out.order.id);
        out.order.displayNumber = cleanIdentity(out.order.displayNumber);
        out.order.table = cleanDisplay(out.order.table);
        out.order.orderType = cleanDisplay(out.order.orderType);

        Object root = structured(out.raw);
        if (!valid(out.order.displayNumber)) {
            out.order.displayNumber = firstCandidate(root, 0, Candidate.NUMBER, "");
        }
        if (!valid(out.order.id)) {
            out.order.id = firstCandidate(root, 0, Candidate.ID, "");
        }
        if (out.order.table.isEmpty()) {
            out.order.table = cleanDisplay(firstCandidate(root, 0, Candidate.TABLE, ""));
        }

        String normalizedType = normalizeOrderType(out.order.orderType);
        if (normalizedType.isEmpty()) normalizedType = firstOrderType(root, 0);
        out.order.orderType = normalizedType;

        // Some TechPro builds expose only one non-zero numeric invoice identifier.
        if (!valid(out.order.displayNumber) && valid(out.order.id) && simpleNumber(out.order.id)) {
            out.order.displayNumber = out.order.id;
        }
        if (!valid(out.order.id) && valid(out.order.displayNumber)) {
            out.order.id = "invoice-" + out.order.displayNumber;
        }
        return out;
    }

    public static boolean valid(String value) {
        return !cleanIdentity(value).isEmpty();
    }

    public static String cleanIdentity(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty()) return "";
        String n = v.toLowerCase(Locale.US);
        if ("0".equals(n) || "0.0".equals(n) || "-1".equals(n)
                || "null".equals(n) || "undefined".equals(n) || "none".equals(n)
                || "false".equals(n) || "00000000-0000-0000-0000-000000000000".equals(n)
                || "invoice-0".equals(n) || "invoice-0.0".equals(n)
                || "order-0".equals(n) || "sale-0".equals(n) || "transaction-0".equals(n)) return "";
        return v;
    }

    private static String cleanDisplay(String value) {
        String v = value == null ? "" : value.trim();
        return "0".equals(v) || "0.0".equals(v) || "-1".equals(v)
                || "null".equalsIgnoreCase(v) || "undefined".equalsIgnoreCase(v) ? "" : v;
    }

    /** Canonicalizes the service type while preserving unknown descriptive strings. */
    public static String normalizeOrderType(String value) {
        String raw = cleanDisplay(value);
        if (raw.isEmpty()) return "";
        String n = raw.toLowerCase(Locale.US)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        if (n.contains("dinein") || n.contains("dining") || n.contains("eat-in")
                || n.contains("local") || n.contains("inside") || n.contains("محلي")) return "DINE_IN";
        if (n.contains("takeaway") || n.contains("takeout") || n.contains("togo")
                || n.contains("pickup") || n.contains("pick-up") || n.contains("سفري")) return "TAKEAWAY";
        if (n.contains("delivery") || n.contains("deliver") || n.contains("توصيل")) return "DELIVERY";
        if (n.matches("[0-9.]+")) return "";
        return raw;
    }

    public static String displayOrderType(String value, boolean arabic) {
        String normalized = normalizeOrderType(value);
        if ("DINE_IN".equals(normalized)) return arabic ? "محلي" : "Dine in";
        if ("TAKEAWAY".equals(normalized)) return arabic ? "سفري" : "Takeaway";
        if ("DELIVERY".equals(normalized)) return arabic ? "توصيل" : "Delivery";
        return normalized;
    }

    private enum Candidate { NUMBER, ID, TABLE }

    private static String firstCandidate(Object raw, int depth, Candidate type, String parentKey) {
        if (raw == null || raw == JSONObject.NULL || depth > 10) return "";
        Object value = structured(raw);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;

            for (String key : candidateKeys(type)) {
                Object cell = valueForKey(object, key);
                if (!scalar(cell)) continue;
                String candidate = type == Candidate.TABLE
                        ? cleanDisplay(String.valueOf(cell))
                        : cleanIdentity(String.valueOf(cell));
                if (!candidate.isEmpty()) return candidate;
            }

            // TechPro versions sometimes wrap metadata as invoice:{number:...} / table:{name:...}.
            if (type == Candidate.NUMBER && invoiceContext(parentKey)) {
                String generic = firstScalar(object, "number", "no", "serial", "sequence", "reference", "refNo", "code");
                generic = cleanIdentity(generic);
                if (!generic.isEmpty()) return generic;
            } else if (type == Candidate.ID && invoiceContext(parentKey)) {
                String generic = cleanIdentity(firstScalar(object, "id", "uuid", "guid"));
                if (!generic.isEmpty()) return generic;
            } else if (type == Candidate.TABLE && tableContext(parentKey)) {
                String generic = cleanDisplay(firstScalar(object, "number", "no", "name", "label", "code", "id"));
                if (!generic.isEmpty()) return generic;
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                String found = firstCandidate(child, depth + 1, type, key);
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = firstCandidate(array.opt(i), depth + 1, type, parentKey);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static String[] candidateKeys(Candidate type) {
        if (type == Candidate.NUMBER) {
            return new String[]{
                    "invoiceNumber", "invoiceNo", "invoiceNum", "invoiceSerial", "invoiceSequence", "invoiceSeq",
                    "invNo", "invNumber", "invNum", "salesInvoiceNo", "salesInvoiceNumber", "posInvoiceNo", "posInvoiceNumber",
                    "receiptNumber", "receiptNo", "billNumber", "billNo", "documentNumber", "documentNo",
                    "voucherNo", "voucherNumber", "referenceNo", "refNo", "orderNumber", "orderNo",
                    "tempOrderNo", "transactionNo", "serialNo", "saleNo", "saleNumber",
                    "invoice_no", "invoice_number", "invoice_num", "order_no", "order_number", "receipt_no", "bill_no"
            };
        }
        if (type == Candidate.ID) {
            return new String[]{
                    "orderId", "invoiceId", "invId", "saleId", "transactionId", "documentId", "receiptId", "tempOrderId",
                    "order_id", "invoice_id", "transaction_id", "uuid", "guid"
            };
        }
        return new String[]{
                "tableNumber", "tableNo", "tableName", "tableLabel", "tableCode", "table",
                "diningTableNumber", "diningTableNo", "diningTableName", "diningTable", "deskNo", "deskNumber",
                "tblNo", "tblNumber", "table_number", "table_no", "table_name", "tableId", "table_id"
        };
    }

    private static String firstOrderType(Object raw, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 10) return "";
        Object value = structured(raw);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] typeKeys = {
                    "orderTypeName", "serviceTypeName", "saleTypeName", "deliveryTypeName", "transactionTypeName",
                    "orderModeName", "serviceModeName", "fulfillmentTypeName", "fulfilmentTypeName",
                    "orderType", "serviceType", "saleType", "deliveryType", "transactionType", "orderMode", "serviceMode",
                    "fulfillmentType", "fulfilmentType", "orderKind", "order_type", "service_type"
            };
            for (String key : typeKeys) {
                String text = textCell(valueForKey(object, key));
                String normalized = normalizeOrderType(text);
                if (!normalized.isEmpty()) return normalized;
            }

            if (truthy(object, "isDineIn", "dineIn", "isDining", "isLocal", "localOrder", "eatIn")) return "DINE_IN";
            if (truthy(object, "isTakeAway", "isTakeaway", "takeAway", "takeaway", "isTakeOut", "takeOut", "isPickup", "pickup")) return "TAKEAWAY";
            if (truthy(object, "isDelivery", "deliveryOrder", "isDelivered")) return "DELIVERY";

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String found = firstOrderType(object.opt(keys.next()), depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = firstOrderType(array.opt(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static boolean truthy(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = valueForKey(object, key);
            if (raw == null || raw == JSONObject.NULL) continue;
            if (raw instanceof Boolean && (Boolean) raw) return true;
            if (raw instanceof Number && ((Number) raw).intValue() != 0) return true;
            String value = String.valueOf(raw).trim().toLowerCase(Locale.US);
            if ("true".equals(value) || "yes".equals(value) || "1".equals(value)) return true;
        }
        return false;
    }

    private static String textCell(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (raw instanceof JSONObject) {
            return firstScalar((JSONObject) raw, "nameAr", "name", "nameEn", "titleAr", "title", "titleEn", "label", "value");
        }
        if (raw instanceof JSONArray) return "";
        return cleanDisplay(String.valueOf(raw));
    }

    private static String firstScalar(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object raw = valueForKey(object, key);
            if (!scalar(raw)) continue;
            String value = cleanDisplay(String.valueOf(raw));
            if (!value.isEmpty()) return value;
        }
        return "";
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

    private static boolean invoiceContext(String key) {
        String k = normalizeKey(key);
        return k.contains("invoice") || k.contains("receipt") || k.contains("bill")
                || k.contains("voucher") || k.equals("order") || k.equals("sale") || k.equals("transaction");
    }

    private static boolean tableContext(String key) {
        String k = normalizeKey(key);
        return k.contains("table") || k.contains("diningtable") || k.contains("desk");
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.US).replace("_", "").replace("-", "");
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number;
    }

    private static boolean simpleNumber(String value) {
        if (!valid(value)) return false;
        String v = value.trim();
        if (v.length() > 18) return false;
        for (int i = 0; i < v.length(); i++) if (!Character.isDigit(v.charAt(i))) return false;
        return true;
    }

    private static Object structured(Object raw) {
        Object value = raw;
        for (int pass = 0; pass < 4; pass++) {
            if (value instanceof JSONObject || value instanceof JSONArray) return value;
            if (!(value instanceof String)) return value;
            String text = ((String) value).trim();
            if (text.isEmpty()) return value;
            try {
                Object parsed = new JSONTokener(text).nextValue();
                if (parsed instanceof String && text.equals(parsed)) return parsed;
                value = parsed;
            } catch (Exception ignored) {
                return value;
            }
        }
        return value;
    }
}
