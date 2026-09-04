package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KitchenVoiceParserProTest {
    @Test public void parsesArabicInvoiceWithArabicDigits() {
        KitchenVoiceParser.Intent intent = KitchenVoiceParser.parse("هاي تيك برو وين فاتورة ٧؟");
        assertEquals(KitchenVoiceParser.Type.FIND_INVOICE, intent.type);
        assertEquals(Integer.valueOf(7), intent.invoiceNumber);
        assertTrue(intent.arabic);
        assertTrue(intent.wakePhrase);
        assertFalse(intent.mutating);
    }

    @Test public void parsesEnglishLateCount() {
        KitchenVoiceParser.Intent intent = KitchenVoiceParser.parse("Hi TechPro, how many delayed orders?");
        assertEquals(KitchenVoiceParser.Type.COUNT_DELAYED, intent.type);
        assertTrue(intent.wakePhrase);
        assertFalse(intent.mutating);
    }

    @Test public void statusChangesAreMarkedMutating() {
        KitchenVoiceParser.Intent intent = KitchenVoiceParser.parse("Mark invoice 20 ready");
        assertEquals(KitchenVoiceParser.Type.MARK_READY, intent.type);
        assertEquals(Integer.valueOf(20), intent.invoiceNumber);
        assertTrue(intent.mutating);
    }
}
