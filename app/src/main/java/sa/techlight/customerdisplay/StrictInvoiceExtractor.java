package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Iterator;
import java.util.Locale;

/** Extracts only cashier-facing invoice/order numbers from TechPro raw payloads; IDs are never treated as invoice numbers. */
public final class StrictInvoiceExtractor {
    private StrictInvoiceExtractor() { }

    public static String extract(String raw) {
        Object root = structured(raw);
        String value = find(root, 0, "");
        return KitchenSignalV2.cleanIdentity(value);
    }

    private static String find(Object raw, int depth, String parentKey) {
        if (raw == null || raw == JSONObject.NULL || depth > 12) return "";
        Object value = structured(raw);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;

            String[] direct = {
                    "invoiceNumber", "invoiceNo", "invoiceNum", "invoiceCode", "invoiceSerial",
                    "invoiceSequence", "invoiceSequenceNo", "invoiceSeq", "invoiceRef", "invoiceReference",
                    "invNo", "invNumber", "invNum", "invSerial", "invCode",
                    "invoice_no", "invoice_number", "invoice_num", "invoice_serial", "invoice_code",
                    "salesInvoiceNo", "salesInvoiceNumber", "saleInvoiceNo", "saleInvoiceNumber",
                    "posInvoiceNo", "posInvoiceNumber", "receiptNo", "receiptNumber",
                    "billNo", "billNumber", "voucherNo", "voucherNumber", "documentNo", "documentNumber",
                    "orderNo", "orderNumber", "orderSerial", "orderCode",
                    "tempOrderNo", "tempOrderNumber", "transactionNo", "transactionNumber",
                    "serialNo", "serialNumber", "referenceNo", "referenceNumber", "refNo"
            };
            for (String key : direct) {
                String candidate = scalar(object, key);
                if (valid(candidate)) return candidate;
            }

            if (numberContext(parentKey)) {
                String[] generic = {"number", "no", "serial", "sequence", "code", "reference", "ref", "displayNumber"};
                for (String key : generic) {
                    String candidate = scalar(object, key);
                    if (valid(candidate)) return candidate;
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String found = find(object.opt(key), depth + 1, key);
                if (valid(found)) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = find(array.opt(i), depth + 1, parentKey);
                if (valid(found)) return found;
            }
        }
        return "";
    }

    private static boolean numberContext(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.US).replace("_", "").replace("-", "");
        return k.contains("invoice") || k.contains("receipt") || k.contains("bill")
                || k.contains("voucher") || k.contains("document") || k.contains("temporder")
                || k.equals("order") || k.equals("sale") || k.equals("transaction");
    }

    private static String scalar(JSONObject object, String requested) {
        if (object == null) return "";
        Object raw = null;
        if (object.has(requested)) raw = object.opt(requested);
        else {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.equalsIgnoreCase(requested)) {
                    raw = object.opt(key);
                    break;
                }
            }
        }
        if (!(raw instanceof String) && !(raw instanceof Number)) return "";
        return String.valueOf(raw).trim();
    }

    private static boolean valid(String value) {
        return !KitchenSignalV2.cleanIdentity(value).isEmpty();
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
                if (parsed instanceof String && text.equals(parsed)) return parsed;
                value = parsed;
            } catch (Exception ignored) {
                return value;
            }
        }
        return value;
    }
}
