package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class KitchenDualApiMergeTest {
    @Test public void modernApiIsTheDefaultAndLegacyCanStillBeNormalized() {
        assertEquals(
                "https://posapifornewapp.techlight.sa/api/",
                TechProAccountClient.normalizeApiBase("")
        );
        assertEquals(
                "https://posapi.techlight.sa/api/",
                KitchenCloudOrdersPoller.normalizeApiBase("https://posapi.techlight.sa")
        );
    }

    @Test public void listHeaderAndPagedDetailsBecomeOneCompleteKitchenTicket() {
        String headerPayload = "{\"data\":[{"
                + "\"id\":501,\"number\":1842,\"reservationNumber\":12,\"orderTypeId\":2,"
                + "\"orderDate\":\"2026-08-30T18:10:00\",\"temporaryOrderItems\":[]"
                + "}]}";
        String detailPayload = "{\"data\":[{"
                + "\"id\":501,\"number\":1842,"
                + "\"temporaryOrderItems\":[{\"itemId\":55,\"qty\":2},{\"itemId\":90,\"qty\":1}]"
                + "}]}";

        List<KitchenTemporaryOrdersApiClient.Candidate> merged = new ArrayList<>();
        KitchenCloudOrdersPoller.mergeTemporaryCandidates(
                merged,
                KitchenTemporaryOrdersApiClient.parseCandidates(headerPayload)
        );
        KitchenCloudOrdersPoller.mergeTemporaryCandidates(
                merged,
                KitchenTemporaryOrdersApiClient.parseCandidates(detailPayload)
        );

        assertEquals(1, merged.size());
        assertEquals("1842", merged.get(0).usableNumber());
        assertEquals("12", merged.get(0).table);
        assertEquals(2L, merged.get(0).orderTypeId);
        assertEquals(2, merged.get(0).items.size());

        HashMap<Long, String> orderTypes = new HashMap<>();
        orderTypes.put(2L, "محلي");
        List<KitchenOrder> tickets = KitchenCloudOrdersPoller.convertTemporary(merged, "", orderTypes);
        assertEquals(1, tickets.size());
        assertEquals("1842", tickets.get(0).displayNumber);
        assertEquals("invoice-1842", tickets.get(0).id);
        assertEquals("12", tickets.get(0).table);
        assertEquals("DINE_IN", tickets.get(0).orderType);
        assertEquals(2, tickets.get(0).items.size());
    }

    @Test public void separateInvoiceNumbersAreNeverMergedTogether() {
        String payload = "{\"data\":["
                + "{\"id\":1,\"number\":101,\"temporaryOrderItems\":[{\"itemId\":5,\"qty\":1}]},"
                + "{\"id\":2,\"number\":102,\"temporaryOrderItems\":[{\"itemId\":6,\"qty\":1}]}"
                + "]}";
        List<KitchenTemporaryOrdersApiClient.Candidate> merged = new ArrayList<>();
        KitchenCloudOrdersPoller.mergeTemporaryCandidates(
                merged,
                KitchenTemporaryOrdersApiClient.parseCandidates(payload)
        );
        assertEquals(2, merged.size());
    }
}
