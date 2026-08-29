package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Iterator;
import java.util.Locale;

/**
 * Normalizes TechPro kitchen events without ever treating placeholder zero values as order identity.
 * It also searches nested payloads for a real invoice number when older POS builds hide it deeper.
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

        Object root = structured(out.raw);
        if (!valid(out.order.displayNumber)) {
            out.order.displayNumber = firstCandidate(root, 0, Candidate.NUMBER);
        }
        if (!valid(out.order.id)) {
            out.order.id = firstCandidate(root, 0, Candidate.ID);
        }

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
        return "0".equals(v) || "0.0".equals(v) || "null".equalsIgnoreCase(v) ? "" : v;
    }

    private enum Candidate { NUMBER, ID }

    private static String firstCandidate(Object raw, int depth, Candidate type) {
        if (raw == null || raw == JSONObject.NULL || depth > 9) return "";
        Object value = structured(raw);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object cell = object.opt(key);
                if (matches(key, type) && scalar(cell)) {
                    String candidate = cleanIdentity(String.valueOf(cell));
                    if (valid(candidate)) return candidate;
                }
            }
            keys = object.keys();
            while (keys.hasNext()) {
                Object child = object.opt(keys.next());
                String found = firstCandidate(child, depth + 1, type);
                if (valid(found)) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = firstCandidate(array.opt(i), depth + 1, type);
                if (valid(found)) return found;
            }
        }
        return "";
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number;
    }

    private static boolean matches(String key, Candidate type) {
        String k = key == null ? "" : key.toLowerCase(Locale.US).replace("_", "").replace("-", "");
        if (type == Candidate.NUMBER) {
            return k.equals("invoicenumber") || k.equals("invoiceno") || k.equals("invno")
                    || k.equals("invoiceserial") || k.equals("invoicecode") || k.equals("salesinvoiceno")
                    || k.equals("salesinvoicenumber") || k.equals("receiptnumber") || k.equals("receiptno")
                    || k.equals("billnumber") || k.equals("billno") || k.equals("documentnumber")
                    || k.equals("documentno") || k.equals("ordernumber") || k.equals("orderno")
                    || k.equals("temporderno") || k.equals("transactionno") || k.equals("voucherno")
                    || k.equals("serialno") || k.equals("posinvoiceno") || k.equals("invoiceseq");
        }
        return k.equals("orderid") || k.equals("invoiceid") || k.equals("invid") || k.equals("saleid")
                || k.equals("transactionid") || k.equals("documentid") || k.equals("receiptid")
                || k.equals("temporderid") || k.equals("uuid") || k.equals("guid");
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
