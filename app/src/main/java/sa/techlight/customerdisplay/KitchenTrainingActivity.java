package sa.techlight.customerdisplay;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Fully isolated walkthrough; it never reads or writes real orders or API data. */
public final class KitchenTrainingActivity extends Activity {
    private boolean arabic;
    private LinearLayout cards;
    private final List<DemoOrder> orders = new ArrayList<>();

    private static final class DemoOrder {
        int invoice;
        int state;
        int additions;
        String items;
        DemoOrder(int invoice, int state, int additions, String items) {
            this.invoice = invoice; this.state = state; this.additions = additions; this.items = items;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        arabic = getIntent().getBooleanExtra("arabic", true);
        orders.add(new DemoOrder(7, 0, 0, arabic ? "2 × برجر\n1 × بطاطس\n1 × كولا" : "2 × Burger\n1 × Fries\n1 × Cola"));
        orders.add(new DemoOrder(12, 1, 2, arabic ? "1 × آيس لاتيه\n  + كراميل\n1 × تشيز كيك" : "1 × Iced Latte\n  + Caramel\n1 × Cheesecake"));
        orders.add(new DemoOrder(19, 2, 0, arabic ? "1 × قهوة اليوم" : "1 × Today's coffee"));
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF090B0F);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));

        TextView title = label(arabic ? "وضع التدريب — لا يؤثر على الطلبات الحقيقية" : "Training Mode — real orders are untouched", 24, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        TextView help = label(arabic
                ? "جرّب انتقال الطلب من جديد إلى التحضير ثم الجاهزية، وشاهد تنبيه الأصناف الإضافية."
                : "Practice New → Preparing → Ready and review the additional-items alert.", 14, 0xFF9BA7B7, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(dp(20), 0, dp(20), dp(16));
        root.addView(help);

        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        root.addView(cards);
        render();

        TextView close = button(arabic ? "إنهاء التدريب" : "Finish training", false);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        cp.setMargins(0, dp(16), 0, 0);
        root.addView(close, cp);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void render() {
        cards.removeAllViews();
        for (DemoOrder order : orders) cards.addView(card(order));
    }

    private View card(DemoOrder order) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(round(0xFF141920, 20, 0xFF29313B));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(7), 0, dp(7));
        card.setLayoutParams(cp);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView invoice = label("#" + order.invoice, 26, Color.WHITE, true);
        header.addView(invoice, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView status = label(status(order.state), 13, statusColor(order.state), true);
        status.setGravity(Gravity.CENTER);
        status.setBackground(round(0xFF202731, 11, 0xFF323D49));
        header.addView(status, new LinearLayout.LayoutParams(dp(130), dp(40)));
        card.addView(header);

        if (order.additions > 0) {
            TextView warning = label("+" + order.additions + (arabic ? " أصناف جديدة — اضغط للمراجعة" : " NEW ITEMS — tap to review"), 14, 0xFFFFC45E, true);
            warning.setGravity(Gravity.CENTER);
            warning.setPadding(dp(10), dp(9), dp(10), dp(9));
            warning.setBackground(round(0xFF332813, 12, 0xFF765218));
            warning.setOnClickListener(v -> { order.additions = 0; render(); });
            card.addView(warning);
        }

        TextView items = label(order.items, 16, 0xFFF1F5FA, false);
        items.setPadding(0, dp(12), 0, dp(12));
        card.addView(items);

        TextView action = button(actionLabel(order), true);
        action.setOnClickListener(v -> {
            if (order.additions > 0) order.additions = 0;
            else if (order.state < 2) order.state++;
            else order.state = 0;
            render();
        });
        card.addView(action, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        return card;
    }

    private String status(int state) {
        if (state == 1) return arabic ? "قيد التحضير" : "Preparing";
        if (state == 2) return arabic ? "جاهز" : "Ready";
        return arabic ? "طلب جديد" : "New";
    }

    private String actionLabel(DemoOrder order) {
        if (order.additions > 0) return arabic ? "مراجعة الإضافات" : "Review additions";
        if (order.state == 0) return arabic ? "بدء التحضير" : "Start preparing";
        if (order.state == 1) return arabic ? "جاهز للتسليم" : "Mark ready";
        return arabic ? "إعادة المثال" : "Restart example";
    }

    private int statusColor(int state) {
        if (state == 2) return 0xFF4AD6A0;
        if (state == 1) return 0xFFFFC863;
        return 0xFF6EA8FF;
    }

    private TextView button(String text, boolean primary) {
        TextView view = label(text, 15, primary ? Color.WHITE : 0xFFF1F5FA, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(round(primary ? 0xFF7432E0 : 0xFF1C222B, 14,
                primary ? 0xFF7432E0 : 0xFF353E49));
        return view;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity((arabic ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
