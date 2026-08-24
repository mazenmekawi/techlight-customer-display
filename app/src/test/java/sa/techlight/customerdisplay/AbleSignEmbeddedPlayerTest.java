package sa.techlight.customerdisplay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AbleSignEmbeddedPlayerTest {
    @Test public void usesOfficialPairingWebPlayer() {
        assertEquals("https://player.ablesign.tv", AbleSignEmbeddedPlayer.playerUrl());
    }
}
