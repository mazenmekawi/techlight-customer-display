package sa.techlight.customerdisplay;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class OrderDisplayOrderTest {
    @Test public void newlyAppendedProductMovesToFirstRow() {
        assertEquals(
                Arrays.asList("new", "first"),
                OrderDisplayOrder.arrange(
                        Arrays.asList("first", "new"),
                        Collections.singletonList("first"),
                        Collections.singletonList("first")
                )
        );
    }

    @Test public void existingVisualOrderStaysStable() {
        assertEquals(
                Arrays.asList("new", "second", "first"),
                OrderDisplayOrder.arrange(
                        Arrays.asList("first", "second", "new"),
                        Arrays.asList("first", "second"),
                        Arrays.asList("second", "first")
                )
        );
    }
}
