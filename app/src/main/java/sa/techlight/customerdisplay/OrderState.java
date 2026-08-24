package sa.techlight.customerdisplay;

import java.util.ArrayList;
import java.util.List;

public final class OrderState {
    public static final class Item {
        public long itemId;
        public long unitId;
        public String barcode="";
        public String itemCode="";
        public String imagePath="";
        public String name=""; public double qty=1; public double unitPrice=0;
        public double lineTotal=Double.NaN;
        public double total(){ return Double.isNaN(lineTotal) ? qty * unitPrice : lineTotal; }
    }
    public final List<Item> items = new ArrayList<>();
    public double subtotal, tax, discount, total;
    public boolean completed;
    public boolean itemsIncluded;
    public boolean subtotalIncluded;
    public boolean taxIncluded;
    public boolean discountIncluded;
    public boolean totalIncluded;
    public boolean clearRequested;
}
