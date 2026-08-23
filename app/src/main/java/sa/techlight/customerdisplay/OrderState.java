package sa.techlight.customerdisplay;

import java.util.ArrayList;
import java.util.List;

public final class OrderState {
    public static final class Item {
        public String name=""; public int qty=1; public double unitPrice=0;
        public double total(){ return qty * unitPrice; }
    }
    public final List<Item> items = new ArrayList<>();
    public double subtotal, tax, discount, total;
    public boolean completed;
}
