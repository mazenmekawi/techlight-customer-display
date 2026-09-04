package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KitchenItemMergerProTest {
    @Test public void incomingListControlsRowsButLocalMetadataSurvives() {
        KitchenOrder.Item local = new KitchenOrder.Item();
        local.itemId = 44L;
        local.name = "Latte";
        local.station = "Coffee";
        local.modifiers.add("Extra shot");
        List<KitchenOrder.Item> previous = new ArrayList<>();
        previous.add(local);

        KitchenOrder.Item cloud = new KitchenOrder.Item();
        cloud.itemId = 44L;
        cloud.name = "Latte";
        cloud.qty = 2d;
        List<KitchenOrder.Item> incoming = new ArrayList<>();
        incoming.add(cloud);

        List<KitchenOrder.Item> merged = KitchenItemMerger.merge(previous, incoming);
        assertEquals(1, merged.size());
        assertEquals(2d, merged.get(0).qty, 0.001d);
        assertEquals("Coffee", merged.get(0).station);
        assertTrue(merged.get(0).modifiers.contains("Extra shot"));
    }
}
