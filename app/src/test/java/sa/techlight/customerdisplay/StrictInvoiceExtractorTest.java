package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class StrictInvoiceExtractorTest {
    @Test public void ignoresDatabaseIdWhenNoInvoiceNumberExists() {
        String raw = "{\"type\":\"orderUpdated\",\"payload\":{\"orderId\":5511,\"items\":[{\"itemId\":1,\"name\":\"A\"}]}}";
        assertEquals("", StrictInvoiceExtractor.extract(raw));
    }

    @Test public void findsNestedCashierInvoiceNumber() {
        String raw = "{\"type\":\"orderSaved\",\"payload\":{\"order\":{\"invoice\":{\"number\":\"1842\"},\"items\":[{\"itemId\":1,\"name\":\"A\"}]}}}";
        assertEquals("1842", StrictInvoiceExtractor.extract(raw));
    }

    @Test public void findsCommonTechProInvoiceAliases() {
        assertEquals("907", StrictInvoiceExtractor.extract("{\"payload\":{\"INV_NO\":907}}"));
        assertEquals("908", StrictInvoiceExtractor.extract("{\"sale\":{\"documentNo\":\"908\"}}"));
    }
}
