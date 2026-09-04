package sa.techlight.kitchen.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VoiceIntentParserTest {

    @Test
    public void parsesArabicInvoiceLookup() {
        VoiceIntentParser.VoiceIntent intent = VoiceIntentParser.parse("وين فاتورة 7؟");
        assertEquals(VoiceIntentParser.IntentType.FIND_INVOICE, intent.type);
        assertEquals(Integer.valueOf(7), intent.invoiceNumber);
        assertEquals("ar", intent.language);
        assertFalse(intent.mutating);
    }

    @Test
    public void parsesEnglishDelayedCount() {
        VoiceIntentParser.VoiceIntent intent = VoiceIntentParser.parse("How many delayed orders?");
        assertEquals(VoiceIntentParser.IntentType.COUNT_DELAYED, intent.type);
        assertEquals("en", intent.language);
        assertFalse(intent.mutating);
    }

    @Test
    public void mutatingCommandRequiresConfirmation() {
        VoiceIntentParser.VoiceIntent intent = VoiceIntentParser.parse("خلي فاتورة 20 جاهزة");
        assertEquals(VoiceIntentParser.IntentType.MARK_READY, intent.type);
        assertEquals(Integer.valueOf(20), intent.invoiceNumber);
        assertTrue(intent.mutating);
        assertTrue(intent.requiresConfirmation);
    }

    @Test
    public void unknownCommandDoesNotMutate() {
        VoiceIntentParser.VoiceIntent intent = VoiceIntentParser.parse("افتح الاعدادات");
        assertEquals(VoiceIntentParser.IntentType.UNKNOWN, intent.type);
        assertFalse(intent.mutating);
    }
}
