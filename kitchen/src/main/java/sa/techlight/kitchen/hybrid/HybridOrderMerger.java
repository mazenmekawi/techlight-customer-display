package sa.techlight.kitchen.hybrid;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field-aware merge helper for Hybrid KDS payloads.
 *
 * API data is authoritative for lifecycle/status fields. Local TechPro data
 * enriches item/group/modifier metadata when the API omits it. Existing API
 * values are never blindly overwritten with local values.
 */
public final class HybridOrderMerger {

    private HybridOrderMerger() {}

    public static Map<String, Object> merge(Map<String, Object> api,
                                            Map<String, Object> local) {
        Map<String, Object> safeApi = api == null ? Collections.emptyMap() : api;
        Map<String, Object> safeLocal = local == null ? Collections.emptyMap() : local;
        Map<String, Object> result = new LinkedHashMap<>();

        // Start with local enrichment, then let API override authoritative values.
        result.putAll(safeLocal);
        result.putAll(safeApi);

        // Explicit enrichment keys: preserve API when populated, otherwise use local.
        enrichIfMissing(result, safeApi, safeLocal, "groupId");
        enrichIfMissing(result, safeApi, safeLocal, "groupName");
        enrichIfMissing(result, safeApi, safeLocal, "categoryId");
        enrichIfMissing(result, safeApi, safeLocal, "categoryName");
        enrichIfMissing(result, safeApi, safeLocal, "station");
        enrichIfMissing(result, safeApi, safeLocal, "kitchenGroup");
        enrichIfMissing(result, safeApi, safeLocal, "modifiers");
        enrichIfMissing(result, safeApi, safeLocal, "addons");
        enrichIfMissing(result, safeApi, safeLocal, "options");
        enrichIfMissing(result, safeApi, safeLocal, "extras");
        enrichIfMissing(result, safeApi, safeLocal, "imagePath");
        enrichIfMissing(result, safeApi, safeLocal, "itemName");
        enrichIfMissing(result, safeApi, safeLocal, "nameAr");
        enrichIfMissing(result, safeApi, safeLocal, "nameEn");

        return result;
    }

    private static void enrichIfMissing(Map<String, Object> result,
                                        Map<String, Object> api,
                                        Map<String, Object> local,
                                        String key) {
        Object apiValue = api.get(key);
        if (isPopulated(apiValue)) {
            result.put(key, apiValue);
            return;
        }
        Object localValue = local.get(key);
        if (isPopulated(localValue)) {
            result.put(key, localValue);
        }
    }

    private static boolean isPopulated(Object value) {
        if (value == null) return false;
        if (value instanceof String) return !((String) value).trim().isEmpty();
        return true;
    }
}
