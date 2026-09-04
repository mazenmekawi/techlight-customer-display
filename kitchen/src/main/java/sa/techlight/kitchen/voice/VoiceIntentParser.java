package sa.techlight.kitchen.voice;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic bilingual voice intent parser for TechLight KDS.
 *
 * Phase 1 intentionally supports read-only intents only. Mutating intents are
 * recognized but flagged as requiring confirmation before execution.
 */
public final class VoiceIntentParser {

    public enum IntentType {
        FIND_INVOICE,
        INVOICE_STATUS,
        LIST_DELAYED,
        COUNT_DELAYED,
        LIST_READY,
        START_PREPARING,
        MARK_READY,
        MARK_COMPLETED,
        UNKNOWN
    }

    public static final class VoiceIntent {
        public final IntentType type;
        public final Integer invoiceNumber;
        public final String language;
        public final boolean mutating;
        public final boolean requiresConfirmation;
        public final String rawText;

        VoiceIntent(IntentType type, Integer invoiceNumber, String language,
                    boolean mutating, boolean requiresConfirmation, String rawText) {
            this.type = type;
            this.invoiceNumber = invoiceNumber;
            this.language = language;
            this.mutating = mutating;
            this.requiresConfirmation = requiresConfirmation;
            this.rawText = rawText;
        }
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?:#|رقم\\s*)?(\\d{1,9})");

    private VoiceIntentParser() {}

    public static VoiceIntent parse(String input) {
        String raw = input == null ? "" : input.trim();
        String normalized = normalize(raw);
        String language = containsArabic(normalized) ? "ar" : "en";
        Integer invoice = extractNumber(normalized);

        if (normalized.isEmpty()) {
            return intent(IntentType.UNKNOWN, null, language, false, raw);
        }

        if (matchesAny(normalized,
                "كم طلب متأخر", "كم الطلبات المتأخرة", "عدد الطلبات المتأخرة",
                "how many delayed", "how many late", "count delayed")) {
            return intent(IntentType.COUNT_DELAYED, null, language, false, raw);
        }

        if (matchesAny(normalized,
                "الطلبات المتأخرة", "ايش الطلبات المتأخرة", "ما هي الطلبات المتأخرة",
                "what orders are late", "which orders are late", "delayed orders", "late orders")) {
            return intent(IntentType.LIST_DELAYED, null, language, false, raw);
        }

        if (matchesAny(normalized,
                "الطلبات الجاهزة", "ايش الطلبات الجاهزة", "ما هي الطلبات الجاهزة",
                "what orders are ready", "ready orders")) {
            return intent(IntentType.LIST_READY, null, language, false, raw);
        }

        if (invoice != null && matchesAny(normalized,
                "وين فاتورة", "اين فاتورة", "أين فاتورة", "مكان فاتورة",
                "where is invoice", "find invoice", "where is order")) {
            return intent(IntentType.FIND_INVOICE, invoice, language, false, raw);
        }

        if (invoice != null && matchesAny(normalized,
                "حالة فاتورة", "حاله فاتورة", "حالة الطلب", "حاله الطلب",
                "status of invoice", "invoice status", "status of order", "order status")) {
            return intent(IntentType.INVOICE_STATUS, invoice, language, false, raw);
        }

        if (invoice != null && matchesAny(normalized,
                "ابدأ تحضير", "ابدء تحضير", "ابدأ تجهيز", "start preparing", "start preparation")) {
            return intent(IntentType.START_PREPARING, invoice, language, true, raw);
        }

        if (invoice != null && matchesAny(normalized,
                "خلي فاتورة جاهزة", "اجعل فاتورة جاهزة", "فاتورة جاهزة",
                "mark invoice ready", "mark order ready", "make invoice ready")) {
            return intent(IntentType.MARK_READY, invoice, language, true, raw);
        }

        if (invoice != null && matchesAny(normalized,
                "اكمل فاتورة", "أكمل فاتورة", "انهاء فاتورة", "إنهاء فاتورة",
                "complete invoice", "complete order", "mark completed")) {
            return intent(IntentType.MARK_COMPLETED, invoice, language, true, raw);
        }

        return intent(IntentType.UNKNOWN, invoice, language, false, raw);
    }

    private static VoiceIntent intent(IntentType type, Integer invoice, String language,
                                      boolean mutating, String raw) {
        return new VoiceIntent(type, invoice, language, mutating, mutating, raw);
    }

    private static Integer extractNumber(String text) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean matchesAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(normalize(phrase))) return true;
        }
        return false;
    }

    private static boolean containsArabic(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0600' && c <= '\u06FF') return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text
                .toLowerCase(Locale.ROOT)
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ة', 'ه')
                .replace('ى', 'ي')
                .replaceAll("[\\p{Punct}&&[^#]]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
