package sa.techlight.kitchen.hybrid;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class HybridOrderMergerTest {

    @Test
    public void apiStatusRemainsAuthoritativeWhileLocalEnrichesGroup() {
        Map<String, Object> api = new HashMap<>();
        api.put("status", "Preparing");
        api.put("invoiceNumber", 7);

        Map<String, Object> local = new HashMap<>();
        local.put("status", "New");
        local.put("groupName", "Coffee");

        Map<String, Object> merged = HybridOrderMerger.merge(api, local);

        assertEquals("Preparing", merged.get("status"));
        assertEquals(7, merged.get("invoiceNumber"));
        assertEquals("Coffee", merged.get("groupName"));
    }

    @Test
    public void apiGroupWinsWhenAlreadyPresent() {
        Map<String, Object> api = new HashMap<>();
        api.put("groupName", "Kitchen");

        Map<String, Object> local = new HashMap<>();
        local.put("groupName", "Coffee");

        assertEquals("Kitchen", HybridOrderMerger.merge(api, local).get("groupName"));
    }
}
