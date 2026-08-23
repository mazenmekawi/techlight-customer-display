package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public final class TechProProtocolTest {
    @Test public void parsesExactFullSnapshotEnvelope() {
        String message = "{"
                + "\"type\":\"fullSnapshot\","
                + "\"payload\":{"
                + "\"state\":\"showingOrder\","
                + "\"items\":[{"
                + "\"id\":\"1\",\"nameAr\":\"قهوة عربية\",\"nameEn\":\"Arabic coffee\","
                + "\"qty\":2,\"unitPrice\":7.5,\"lineTotal\":15,\"isAddition\":false"
                + "}],"
                + "\"subtotal\":15,\"discount\":0,\"tax\":2.25,\"total\":17.25,\"currency\":\"SAR\""
                + "},"
                + "\"sentAt\":\"2026-08-23T12:00:00.000Z\",\"version\":1"
                + "}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("قهوة عربية", order.items.get(0).name);
        assertEquals(2.0, order.items.get(0).qty, 0.0001);
        assertEquals(15.0, order.items.get(0).total(), 0.0001);
        assertEquals(17.25, order.total, 0.0001);
        assertFalse(order.completed);
    }

    @Test public void parsesRawSnapshotForCompatibility() {
        String message = "{\"state\":\"showingOrder\",\"items\":[{"
                + "\"nameEn\":\"Water\",\"qty\":3,\"unitPrice\":2,\"lineTotal\":6"
                + "}],\"subtotal\":6,\"tax\":0,\"discount\":0,\"total\":6}";

        OrderState order = TechProClient.parseOrderMessage(message);
        assertNotNull(order);
        assertEquals(1, order.items.size());
        assertEquals("Water", order.items.get(0).name);
        assertEquals(6.0, order.total, 0.0001);
    }

    @Test public void parsesExactPairingQr() throws Exception {
        PairingParser.PairingInfo pairing = PairingParser.parse(
                "{\"type\":\"pos_pair\",\"ip\":\"192.168.100.23\",\"port\":4040}"
        );
        assertEquals("192.168.100.23", pairing.ip);
        assertEquals(4040, pairing.port);
    }
}
