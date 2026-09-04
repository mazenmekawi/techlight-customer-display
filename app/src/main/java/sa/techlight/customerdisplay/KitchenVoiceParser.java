package sa.techlight.customerdisplay;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Arabic/English command parser. No network or AI guesswork. */
public final class KitchenVoiceParser {
    public enum Type {
        FIND_INVOICE,
        INVOICE_STATUS,
        LIST_DELAYED,
        COUNT_DELAYED,
        LIST_READY,
        START_PREPARING,
        MARK_READY,
        MARK_COMPLETED,
        ACK_ADDITIONS,
        UNKNOWN
    }

    public static final class Intent {
        public final Type type;
        public final Integer invoiceNumber;
        public final boolean arabic;
        public final boolean mutating;
        public final boolean wakePhrase;
        public final String raw;

        Intent(Type type, Integer invoiceNumber, boolean arabic, boolean mutating,
               boolean wakePhrase, String raw) {
            this.type = type;
            this.invoiceNumber = invoiceNumber;
            this.arabic = arabic;
            this.mutating = mutating;
            this.wakePhrase = wakePhrase;
            this.raw = raw == null ? "" : raw;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("(?:#|رقم\\s*)?([0-9٠-٩]{1,9})");

    private KitchenVoiceParser() { }

    public static Intent parse(String input) {
        String raw = input == null ? "" : input.trim();
        String normalized = normalize(raw);
        boolean arabic = containsArabic(normalized);
        boolean wake = normalized.contains("hi techpro")
                || normalized.contains("hey techpro")
                || normalized.contains("hi tech pro")
                || normalized.contains("هاي تيك برو")
                || normalized.contains("هلا تيك برو")
                || normalized.contains("يا تيك برو");
        normalized = normalized
                .replace("hi techpro", "")
                .replace("hey techpro", "")
                .replace("hi tech pro", "")
                .replace("هاي تيك برو", "")
                .replace("هلا تيك برو", "")
                .replace("يا تيك برو", "")
                .trim();
        Integer number = extractNumber(normalized);

        if (containsAny(normalized,
                "كم طلب متاخر", "كم الطلبات المتاخره", "عدد الطلبات المتاخره",
                "how many delayed", "how many late", "count delayed")) {
            return result(Type.COUNT_DELAYED, null, arabic, false, wake, raw);
        }
        if (containsAny(normalized,
                "ما هي الطلبات المتاخره", "ايش الطلبات المتاخره", "الطلبات المتاخره",
                "what orders are late", "which orders are late", "delayed orders", "late orders")) {
            return result(Type.LIST_DELAYED, null, arabic, false, wake, raw);
        }
        if (containsAny(normalized,
                "ما هي الطلبات الجاهزه", "ايش الطلبات الجاهزه", "الطلبات الجاهزه",
                "what orders are ready", "which orders are ready", "ready orders")) {
            return result(Type.LIST_READY, null, arabic, false, wake, raw);
        }
        if (number != null && containsAny(normalized,
                "وين فاتوره", "اين فاتوره", "مكان فاتوره", "ابحث عن فاتوره",
                "where is invoice", "find invoice", "where is order", "find order")) {
            return result(Type.FIND_INVOICE, number, arabic, false, wake, raw);
        }
        if (number != null && containsAny(normalized,
                "حاله فاتوره", "حاله الطلب", "وضع فاتوره", "وضع الطلب",
                "invoice status", "status of invoice", "order status", "status of order")) {
            return result(Type.INVOICE_STATUS, number, arabic, false, wake, raw);
        }
        if (number != null && containsAny(normalized,
                "راجع اضافات", "تمت مراجعه اضافات", "تاكيد اضافات", "اقر اضافات",
                "acknowledge additions", "review additions", "confirm additions")) {
            return result(Type.ACK_ADDITIONS, number, arabic, true, wake, raw);
        }
        if (number != null && containsAny(normalized,
                "ابدأ تحضير", "ابدء تحضير", "ابدأ تجهيز", "ابدء تجهيز",
                "start preparing", "start preparation", "prepare invoice", "prepare order")) {
            return result(Type.START_PREPARING, number, arabic, true, wake, raw);
        }
        if (number != null && (containsAny(normalized,
                "خلي فاتوره جاهزه", "اجعل فاتوره جاهزه", "حول فاتوره جاهزه",
                "mark invoice ready", "mark order ready", "make invoice ready", "make order ready")
                || (containsAny(normalized, "خلي", "اجعل", "حول", "mark", "make")
                && containsAny(normalized, "فاتوره", "طلب", "invoice", "order")
                && containsAny(normalized, "جاهزه", "جاهز", "ready")))) {
            return result(Type.MARK_READY, number, arabic, true, wake, raw);
        }
        if (number != null && (containsAny(normalized,
                "اكمل فاتوره", "انهي فاتوره", "تم تسليم فاتوره", "اقفل فاتوره",
                "complete invoice", "complete order", "mark completed", "mark delivered")
                || (containsAny(normalized, "اكمل", "انهي", "اقفل", "complete", "finish", "mark")
                && containsAny(normalized, "فاتوره", "طلب", "invoice", "order")
                && containsAny(normalized, "مكتمله", "مكتمل", "تم", "completed", "complete", "delivered")))) {
            return result(Type.MARK_COMPLETED, number, arabic, true, wake, raw);
        }
        return result(Type.UNKNOWN, number, arabic, false, wake, raw);
    }

    private static Intent result(Type type, Integer number, boolean arabic,
                                 boolean mutating, boolean wake, String raw) {
        return new Intent(type, number, arabic, mutating, wake, raw);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(normalize(candidate))) return true;
        }
        return false;
    }

    private static Integer extractNumber(String value) {
        Matcher matcher = NUMBER.matcher(value);
        if (!matcher.find()) return null;
        try {
            return Integer.valueOf(toWesternDigits(matcher.group(1)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String toWesternDigits(String value) {
        return value
                .replace('٠', '0').replace('١', '1').replace('٢', '2')
                .replace('٣', '3').replace('٤', '4').replace('٥', '5')
                .replace('٦', '6').replace('٧', '7').replace('٨', '8')
                .replace('٩', '9');
    }

    private static boolean containsArabic(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0600' && c <= '\u06ff') return true;
        }
        return false;
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
                .replaceAll("[\\u064B-\\u065F\\u0670]", "")
                .replaceAll("[\\p{Punct}&&[^#]]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
