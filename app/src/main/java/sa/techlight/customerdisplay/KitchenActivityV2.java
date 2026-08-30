package sa.techlight.customerdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TechPro Kitchen V2.
 *
 * Lifecycle is intentionally strict:
 * CASHIER CART = draft only.
 * TEMP SAVE     = create kitchen order.
 * TEMP SAVE again with the same real invoice/order identity = modification.
 * PAYMENT       = payment status update only, never creates a kitchen order.
 */
public final class KitchenActivityV2 extends Activity implements TechProClient.Listener {
    private enum Filter { ALL, NEW, PREPARING, READY, HISTORY }

    private static final int BG = 0xFF0B0D10;
    private static final int SURFACE = 0xFF15181D;
    private static final int SURFACE_2 = 0xFF1B1F25;
    private static final int BORDER = 0xFF2A3038;
    private static final int TEXT = 0xFFF5F7FA;
    private static final int MUTED = 0xFF9DA6B2;
    private static final int BLUE = 0xFF4C8DFF;
    private static final int AMBER = 0xFFF6B94A;
    private static final int GREEN = 0xFF35C58B;
    private static final int RED = 0xFFFF5D68;
    private static final int PURPLE = 0xFF8A5CF5;
    private static final long INFER_SAVE_DELAY_MS = 2400L;
    private static final long PAYMENT_GUARD_MS = 7000L;
    private static final long LATE_AFTER_MS = 60_000L;
    private static final long LATE_REPEAT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Filter, TextView> tabs = new EnumMap<>(Filter.class);
    private final Map<String, Long> lateAlertAt = new HashMap<>();
    private int weakSequence;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            renderBoard(false);
            handler.postDelayed(this, 5000L);
        }
    };

    private FrameLayout shell;
    private GridLayout board;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptySub;
    private TextView connectionText;
    private TextView branchText;
    private TechProSession session;
    private KitchenOrderStoreV2 store;
    private ProductCatalog catalog;
    private ProductImageLoader imageLoader;
    private TechProClient client;
    private SharedPreferences pair;
    private SharedPreferences settings;
    private ToneGenerator tone;
    private Filter filter = Filter.ALL;
    private KitchenOrder liveDraft;
    private Runnable pendingInferredSave;
    private long lastPaymentSignalAt;
    private String lastSavedId = "";
    private long lastSavedAt;
    private String lastEvent = "";
    private boolean connectionOk;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(5894);

        session = new TechProSession(this);
        if (!session.isSignedIn()) {
            startActivity(new Intent(this, KitchenLoginActivity.class));
            finish();
            return;
        }
        settings = getSharedPreferences("kitchen_settings_v2", MODE_PRIVATE);
        pair = getSharedPreferences("kitchen_pair", MODE_PRIVATE);
        store = new KitchenOrderStoreV2(this);
        catalog = new ProductCatalog(this);
        imageLoader = new ProductImageLoader(this, session.token());
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90); }
        catch (Throwable ignored) { tone = null; }

        buildUi();
        renderBoard(true);
        restoreConnection();
        handler.postDelayed(clockTick, 1000L);
    }

    private boolean ar() {
        return !"en".equalsIgnoreCase(settings.getString("language", "ar"));
    }

    private int direction() {
        return ar() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    private String t(String key) {
        boolean a = ar();
        switch (key) {
            case "kitchen": return a ? "شاشة المطبخ" : "Kitchen display";
            case "settings": return a ? "الإعدادات" : "Settings";
            case "all": return a ? "الكل" : "All";
            case "new": return a ? "جديد" : "New";
            case "preparing": return a ? "التحضير" : "Preparing";
            case "ready": return a ? "جاهز" : "Ready";
            case "history": return a ? "السجل" : "History";
            case "waiting": return a ? "بانتظار الطلبات المحفوظة" : "Waiting for saved orders";
            case "waitingSub": return a ? "سلة الكاشير لا تظهر هنا. الطلب يدخل فقط بعد الحفظ المؤقت." : "The live cashier cart stays hidden. Orders appear after temporary save only.";
            case "invoice": return a ? "فاتورة" : "Invoice";
            case "temporary": return a ? "طلب مؤقت" : "Temporary order";
            case "table": return a ? "طاولة" : "Table";
            case "cashierOrder": return a ? "طلب كاشير" : "Cashier order";
            case "unpaid": return a ? "غير مدفوع" : "UNPAID";
            case "modified": return a ? "تم تعديل الطلب بعد الحفظ" : "Order modified after save";
            case "cancelled": return a ? "تم إلغاء الطلب" : "Order cancelled";
            case "orderNote": return a ? "ملاحظة الطلب" : "Order note";
            case "itemNote": return a ? "ملاحظة" : "Note";
            case "start": return a ? "بدء التحضير" : "Start preparing";
            case "markReady": return a ? "جاهز ✓" : "Mark ready ✓";
            case "served": return a ? "تم التسليم" : "Complete";
            case "details": return a ? "تفاصيل الطلب" : "Order details";
            case "close": return a ? "إغلاق" : "Close";
            case "connected": return a ? "متصل" : "Connected";
            case "reconnecting": return a ? "إعادة الاتصال" : "Reconnecting";
            case "notPaired": return a ? "غير مرتبط" : "Not paired";
            case "connecting": return a ? "جارٍ الاتصال" : "Connecting";
            case "pair": return a ? "ربط TechPro" : "Pair TechPro";
            case "language": return a ? "اللغة" : "Language";
            case "showImages": return a ? "إظهار صور الأصناف" : "Show product images";
            case "inferSave": return a ? "استنتاج الحفظ المؤقت عند اختفاء السلة بدون دفع" : "Infer temporary save when cart clears without payment";
            case "lateReminder": return a ? "تنبيه صوتي كل دقيقة للطلب الجديد غير المبدوء" : "Sound reminder every minute for unstarted new orders";
            case "save": return a ? "حفظ" : "Save";
            case "changePair": return a ? "تغيير الربط" : "Change pairing";
            case "pinTitle": return a ? "إعدادات المطبخ" : "Kitchen settings";
            case "wrongPin": return a ? "الرمز غير صحيح" : "Wrong PIN";
            case "more": return a ? "أصناف أخرى" : "more items";
            case "imageHint": return a ? "تغلق الصورة تلقائيًا" : "Image closes automatically";
            case "recall": return a ? "استرجاع آخر طلب" : "Recall last order";
            default: return key;
        }
    }

    private void buildUi() {
        tabs.clear();
        shell = new FrameLayout(this);
        shell.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(direction());
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(direction());
        header.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable headerBg = round(SURFACE, 20);
        headerBg.setStroke(dp(1), BORDER);
        header.setBackground(headerBg);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.techlight_mark);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(mark, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, dp(10), 0);
        TextView title = text("TechPro Kitchen", wide() ? 22 : 19, TEXT, true);
        branchText = text(session.accountName().isEmpty() ? t("kitchen") : session.accountName(), 12, MUTED, false);
        titleBox.addView(title);
        titleBox.addView(branchText);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connection.setGravity(Gravity.CENTER);
        connection.setPadding(dp(8), 0, dp(12), 0);
        connectionText = text(connectionOk ? t("connected") : t("notPaired"), 12, connectionOk ? GREEN : MUTED, true);
        connectionText.setGravity(Gravity.CENTER);
        connection.addView(connectionText);
        header.addView(connection);

        TextView settingsButton = action(t("settings"), false);
        settingsButton.setOnClickListener(v -> requestSettingsPin());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(wide() ? 116 : 98), dp(44)));
        root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setLayoutDirection(direction());
        tabRow.setPadding(0, dp(9), 0, dp(7));
        addTab(tabRow, Filter.ALL, t("all"));
        addTab(tabRow, Filter.NEW, t("new"));
        addTab(tabRow, Filter.PREPARING, t("preparing"));
        addTab(tabRow, Filter.READY, t("ready"));
        addTab(tabRow, Filter.HISTORY, t("history"));
        root.addView(tabRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        board = new GridLayout(this);
        board.setUseDefaultMargins(false);
        board.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        board.setPadding(0, 0, 0, dp(30));
        scroll.addView(board, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setLayoutDirection(direction());
        ImageView emptyLogo = new ImageView(this);
        emptyLogo.setImageResource(R.drawable.techlight_mark);
        emptyLogo.setAlpha(0.25f);
        emptyLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        emptyState.addView(emptyLogo, new LinearLayout.LayoutParams(dp(90), dp(90)));
        emptyTitle = text(t("waiting"), wide() ? 24 : 20, TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(5));
        emptyState.addView(emptyTitle);
        emptySub = text(t("waitingSub"), 13, MUTED, false);
        emptySub.setGravity(Gravity.CENTER);
        emptyState.addView(emptySub);
        TextView pairButton = action(t("pair"), true);
        pairButton.setOnClickListener(v -> showPairDialog());
        LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(dp(190), dp(50));
        pairParams.setMargins(0, dp(18), 0, 0);
        emptyState.addView(pairButton, pairParams);
        content.addView(emptyState, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(shell);
    }

    private void addTab(LinearLayout row, Filter value, String label) {
        TextView tab = text(label, wide() ? 14 : 12, TEXT, true);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setOnClickListener(v -> {
            filter = value;
            renderBoard(true);
        });
        tabs.put(value, tab);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tab, params);
    }

    private void updateTabs(List<KitchenOrder> active, int historyCount) {
        int all = active.size();
        int n = 0, p = 0, r = 0;
        for (KitchenOrder order : active) {
            if (order.kitchenStatus == KitchenOrder.Status.NEW) n++;
            if (order.kitchenStatus == KitchenOrder.Status.PREPARING) p++;
            if (order.kitchenStatus == KitchenOrder.Status.READY) r++;
        }
        setTab(Filter.ALL, t("all"), all);
        setTab(Filter.NEW, t("new"), n);
        setTab(Filter.PREPARING, t("preparing"), p);
        setTab(Filter.READY, t("ready"), r);
        setTab(Filter.HISTORY, t("history"), historyCount);
    }

    private void setTab(Filter value, String label, int count) {
        TextView tab = tabs.get(value);
        if (tab == null) return;
        tab.setText(label + "  " + count);
        boolean selected = filter == value;
        GradientDrawable bg = round(selected ? 0xFF293243 : SURFACE, 14);
        bg.setStroke(dp(1), selected ? BLUE : BORDER);
        tab.setBackground(bg);
        tab.setTextColor(selected ? Color.WHITE : MUTED);
    }

    private void renderBoard(boolean force) {
        if (board == null || store == null) return;
        List<KitchenOrder> active = store.active();
        List<KitchenOrder> history = store.history();
        long now = System.currentTimeMillis();
        checkLateAlerts(active, now);
        updateTabs(active, history.size());

        List<KitchenOrder> source = filter == Filter.HISTORY ? history : active;
        ArrayList<KitchenOrder> visible = new ArrayList<>();
        for (KitchenOrder order : source) if (matchesFilter(order)) visible.add(order);

        board.removeAllViews();
        board.setColumnCount(columns());
        for (KitchenOrder order : visible) {
            View card = orderCard(order, now, filter == Filter.HISTORY);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            board.addView(card, params);
        }
        emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            if (filter == Filter.HISTORY) {
                emptyTitle.setText(ar() ? "لا توجد طلبات منتهية" : "No completed orders");
                emptySub.setText(ar() ? "الطلبات المكتملة ستظهر هنا ويمكن مراجعتها لاحقًا." : "Completed orders will appear here for review.");
            } else if (filter == Filter.ALL) {
                emptyTitle.setText(t("waiting"));
                emptySub.setText(t("waitingSub"));
            } else {
                emptyTitle.setText(ar() ? "لا توجد طلبات في هذه الحالة" : "No orders in this status");
                emptySub.setText(ar() ? "اختر حالة أخرى من الشريط بالأعلى." : "Choose another status from the tabs above.");
            }
        }
    }

    private boolean matchesFilter(KitchenOrder order) {
        if (filter == Filter.ALL) return order.kitchenStatus != KitchenOrder.Status.DONE;
        if (filter == Filter.NEW) return order.kitchenStatus == KitchenOrder.Status.NEW;
        if (filter == Filter.PREPARING) return order.kitchenStatus == KitchenOrder.Status.PREPARING;
        if (filter == Filter.READY) return order.kitchenStatus == KitchenOrder.Status.READY;
        return true;
    }

    private View orderCard(KitchenOrder order, long now, boolean history) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(direction());
        card.setPadding(dp(14), 0, dp(14), dp(13));
        int status = statusColor(order, now);
        GradientDrawable bg = round(SURFACE, 18);
        bg.setStroke(dp(1), status);
        card.setBackground(bg);
        card.setElevation(dp(3));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showOrderDetails(order));
        if (!history) {
            card.setOnLongClickListener(v -> {
                showOrderActions(order);
                return true;
            });
        }

        View strip = new View(this);
        strip.setBackground(round(status, 4));
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
        stripParams.setMargins(-dp(14), 0, -dp(14), dp(10));
        card.addView(strip, stripParams);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(direction());
        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        String numberLabel = number.isEmpty() ? t("temporary") : t("invoice") + " #" + number;
        TextView invoice = text(numberLabel, wide() ? 22 : 18, TEXT, true);
        invoice.setOnClickListener(v -> showOrderDetails(order));
        top.addView(invoice, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView age = chip(formatAge(now - order.createdAt), 0xFF0D1014, status);
        top.addView(age);
        card.addView(top);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setLayoutDirection(direction());
        String table = clean(order.table).isEmpty() ? orderTypeLabel(order) : t("table") + " " + order.table;
        TextView tableText = text(table, wide() ? 19 : 16, 0xFFDCE6F5, true);
        tableText.setPadding(0, dp(5), 0, dp(5));
        meta.addView(tableText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!clean(order.orderType).isEmpty() && !clean(order.table).isEmpty()) meta.addView(chip(order.orderType, 0xFF222832, 0xFFC8D2DF));
        card.addView(meta);

        if (order.changedAt > 0 && now - order.changedAt < 45_000L) {
            TextView changed = chip(t("modified"), 0xFF3B2E15, 0xFFFFD47F);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
            cp.setMargins(0, 0, 0, dp(6));
            card.addView(changed, cp);
        }
        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) {
            TextView cancelled = chip(t("cancelled"), 0xFF461B20, 0xFFFFA4AB);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
            cp.setMargins(0, 0, 0, dp(6));
            card.addView(cancelled, cp);
        }

        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dpv.setMargins(0, dp(3), 0, dp(6));
        card.addView(divider, dpv);

        int max = wide() ? 9 : 7;
        for (int i = 0; i < order.items.size() && i < max; i++) addItemRow(card, order.items.get(i));
        if (order.items.size() > max) {
            TextView more = text("+ " + (order.items.size() - max) + " " + t("more"), 12, MUTED, true);
            more.setPadding(0, dp(4), 0, dp(4));
            card.addView(more);
        }

        if (!clean(order.customerNote).isEmpty()) {
            TextView note = text(t("orderNote") + ": " + order.customerNote, 12, 0xFFFFE0A3, true);
            note.setPadding(dp(10), dp(8), dp(10), dp(8));
            note.setBackground(round(0xFF342A18, 11));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.setMargins(0, dp(7), 0, dp(5));
            card.addView(note, np);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setLayoutDirection(direction());
        footer.addView(chip(statusLabel(order.kitchenStatus), statusFill(order.kitchenStatus), statusText(order.kitchenStatus)));
        // Per requested logic: show only UNPAID on temporary/open kitchen orders. Paid gets no chip.
        if (!order.isPaid() && !history) {
            TextView unpaid = chip(t("unpaid"), 0xFF272C34, 0xFFC5CDD7);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
            pp.setMargins(dp(6), 0, 0, 0);
            footer.addView(unpaid, pp);
        }
        card.addView(footer);

        if (!history && order.kitchenStatus != KitchenOrder.Status.CANCELLED) {
            TextView primary = action(actionLabel(order.kitchenStatus), true);
            primary.setOnClickListener(v -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                advanceOrder(order);
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(47));
            ap.setMargins(0, dp(9), 0, 0);
            card.addView(primary, ap);
        }
        return card;
    }

    private void addItemRow(LinearLayout card, KitchenOrder.Item item) {
        ProductCatalog.Product product = product(item);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setLayoutDirection(direction());
        row.setPadding(0, dp(5), 0, dp(5));

        String imagePath = product == null ? "" : clean(product.imagePath);
        if (settings.getBoolean("show_images", true) && !imagePath.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFF232830, 10));
            image.setClipToOutline(true);
            image.setClickable(true);
            image.setFocusable(true);
            image.setOnClickListener(v -> showProductImage(product, item));
            imageLoader.load(imagePath, image, null);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(54), dp(54));
            ip.setMargins(ar() ? dp(9) : 0, 0, ar() ? 0 : dp(9), 0);
            row.addView(image, ip);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(direction());
        String name = displayName(item, product);
        TextView title = text(formatQty(item.qty) + " × " + name, wide() ? 17 : 15, TEXT, true);
        content.addView(title);
        for (String modifier : item.modifiers) {
            TextView option = text("+ " + modifier, 12, 0xFF8DE1B8, true);
            option.setPadding(dp(12), dp(1), 0, dp(1));
            content.addView(option);
        }
        for (String removed : item.removed) {
            TextView option = text("− " + removed, 12, 0xFFFF9EA6, true);
            option.setPadding(dp(12), dp(1), 0, dp(1));
            content.addView(option);
        }
        if (!clean(item.note).isEmpty()) {
            TextView note = text(t("itemNote") + ": " + item.note, 12, 0xFFFFD98F, false);
            note.setPadding(dp(12), dp(2), 0, dp(1));
            content.addView(note);
        }
        if (!imagePath.isEmpty()) content.setOnClickListener(v -> showProductImage(product, item));
        row.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row);
    }

    private ProductCatalog.Product product(KitchenOrder.Item item) {
        if (catalog == null || item == null || item.itemId <= 0) return null;
        try { return catalog.find(item.itemId, 0, "", ""); }
        catch (Throwable ignored) { return null; }
    }

    private String displayName(KitchenOrder.Item item, ProductCatalog.Product product) {
        if (product != null) {
            String localized = ar() ? clean(product.nameAr) : clean(product.nameEn);
            String alternate = ar() ? clean(product.nameEn) : clean(product.nameAr);
            if (!localized.isEmpty()) return localized;
            if (!alternate.isEmpty()) return alternate;
        }
        return clean(item.name).isEmpty() ? (ar() ? "صنف" : "Item") : item.name;
    }

    private void showProductImage(ProductCatalog.Product product, KitchenOrder.Item item) {
        if (product == null || clean(product.imagePath).isEmpty() || shell == null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF5000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(v -> removeOverlay(overlay));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(24), dp(24), dp(24), dp(90));
        overlay.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        imageLoader.load(product.imagePath, image, null);

        TextView caption = text(displayName(item, product) + "  •  " + t("imageHint"), wide() ? 19 : 16, Color.WHITE, true);
        caption.setGravity(Gravity.CENTER);
        caption.setBackgroundColor(0xAA000000);
        caption.setPadding(dp(16), dp(12), dp(16), dp(12));
        overlay.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM));
        shell.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setAlpha(0f);
        overlay.setScaleX(0.97f);
        overlay.setScaleY(0.97f);
        overlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        handler.postDelayed(() -> removeOverlay(overlay), 4200L);
    }

    private void removeOverlay(View overlay) {
        if (shell != null && overlay != null && overlay.getParent() == shell) {
            overlay.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (overlay.getParent() == shell) shell.removeView(overlay);
            }).start();
        }
    }

    private void showOrderDetails(KitchenOrder order) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(direction());
        panel.setPadding(dp(20), dp(14), dp(20), dp(18));
        scroll.addView(panel);

        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        TextView title = text(number.isEmpty() ? t("temporary") : t("invoice") + " #" + number, 25, 0xFF111318, true);
        panel.addView(title);
        String meta = (clean(order.table).isEmpty() ? orderTypeLabel(order) : t("table") + " " + order.table)
                + "   •   " + statusLabel(order.kitchenStatus)
                + "   •   " + formatAge(System.currentTimeMillis() - order.createdAt);
        TextView metaView = text(meta, 13, 0xFF596270, true);
        metaView.setPadding(0, dp(4), 0, dp(12));
        panel.addView(metaView);

        for (KitchenOrder.Item item : order.items) {
            ProductCatalog.Product product = product(item);
            LinearLayout itemBox = new LinearLayout(this);
            itemBox.setOrientation(LinearLayout.VERTICAL);
            itemBox.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable itemBg = round(0xFFF4F6F8, 12);
            itemBg.setStroke(dp(1), 0xFFE1E5EA);
            itemBox.setBackground(itemBg);
            TextView name = text(formatQty(item.qty) + " × " + displayName(item, product), 17, 0xFF171A1F, true);
            itemBox.addView(name);
            for (String modifier : item.modifiers) itemBox.addView(text("+ " + modifier, 13, 0xFF137A53, true));
            for (String removed : item.removed) itemBox.addView(text("− " + removed, 13, 0xFFC23A45, true));
            if (!clean(item.note).isEmpty()) itemBox.addView(text(t("itemNote") + ": " + item.note, 13, 0xFF8A5A00, false));
            if (product != null && !clean(product.imagePath).isEmpty()) {
                TextView imageAction = text(ar() ? "عرض صورة الصنف" : "View product image", 13, 0xFF315FC4, true);
                imageAction.setPadding(0, dp(7), 0, 0);
                imageAction.setClickable(true);
                imageAction.setOnClickListener(v -> showProductImage(product, item));
                itemBox.addView(imageAction);
            }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.setMargins(0, 0, 0, dp(8));
            panel.addView(itemBox, ip);
        }
        if (!clean(order.customerNote).isEmpty()) {
            TextView note = text(t("orderNote") + ": " + order.customerNote, 14, 0xFF744D00, true);
            note.setPadding(dp(12), dp(10), dp(12), dp(10));
            note.setBackground(round(0xFFFFF1CF, 12));
            panel.addView(note);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("details"))
                .setView(scroll)
                .setNegativeButton(t("close"), null)
                .create();
        if (order.kitchenStatus == KitchenOrder.Status.NEW || order.kitchenStatus == KitchenOrder.Status.PREPARING || order.kitchenStatus == KitchenOrder.Status.READY) {
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, actionLabel(order.kitchenStatus), (d, which) -> advanceOrder(order));
        }
        dialog.show();
    }

    private void advanceOrder(KitchenOrder order) {
        if (order.kitchenStatus == KitchenOrder.Status.NEW) {
            store.setStatus(order.id, KitchenOrder.Status.PREPARING);
            lateAlertAt.remove(order.id);
            beepStep();
            if (filter == Filter.NEW) filter = Filter.PREPARING;
        } else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) {
            store.setStatus(order.id, KitchenOrder.Status.READY);
            beepReady();
            if (filter == Filter.PREPARING) filter = Filter.READY;
        } else if (order.kitchenStatus == KitchenOrder.Status.READY) {
            store.setStatus(order.id, KitchenOrder.Status.DONE);
        }
        renderBoard(true);
    }

    private void showOrderActions(KitchenOrder order) {
        String[] labels = ar()
                ? new String[]{"جديد", "بدء التحضير", "جاهز", "إنهاء/إخفاء", "إلغاء محلي"}
                : new String[]{"New", "Start preparing", "Ready", "Complete/Hide", "Cancel locally"};
        new AlertDialog.Builder(this)
                .setTitle(t("details") + " " + invoiceShort(order))
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) store.setStatus(order.id, KitchenOrder.Status.NEW);
                    if (which == 1) store.setStatus(order.id, KitchenOrder.Status.PREPARING);
                    if (which == 2) store.setStatus(order.id, KitchenOrder.Status.READY);
                    if (which == 3) store.setStatus(order.id, KitchenOrder.Status.DONE);
                    if (which == 4) store.cancel(order.id);
                    renderBoard(true);
                }).show();
    }

    private void checkLateAlerts(List<KitchenOrder> orders, long now) {
        if (!settings.getBoolean("late_reminder", true)) return;
        boolean sound = false;
        for (KitchenOrder order : orders) {
            if (order.kitchenStatus != KitchenOrder.Status.NEW) {
                lateAlertAt.remove(order.id);
                continue;
            }
            if (now - order.createdAt < LATE_AFTER_MS) continue;
            Long last = lateAlertAt.get(order.id);
            if (last == null || now - last >= LATE_REPEAT_MS) {
                lateAlertAt.put(order.id, now);
                sound = true;
            }
        }
        if (sound) beepLate();
    }

    @Override public void onRaw(String raw) {
        KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
        lastEvent = signal.parsed.kind.name() + (clean(signal.parsed.eventName).isEmpty() ? "" : " • " + signal.parsed.eventName);
        processSignal(signal);
    }

    @Override public void onOrder(OrderState order) {
        // Never use customer-display snapshots as kitchen commits. onRaw() owns the strict state machine.
    }

    @Override public void onDiagnostic(String stage, String detail) { }

    private void processSignal(KitchenSignalV2.Signal signal) {
        KitchenOrderParser.Kind kind = signal.parsed.kind;
        KitchenOrder incoming = signal.order;
        long now = System.currentTimeMillis();

        if (kind == KitchenOrderParser.Kind.PAYMENT) {
            lastPaymentSignalAt = now;
            cancelPendingInferredSave();
            KitchenOrder existing = findStrict(incoming);
            if (existing == null && !lastSavedId.isEmpty() && now - lastSavedAt < 30_000L) existing = store.find(lastSavedId);
            if (existing != null) {
                store.updatePayment(existing.id, incoming == null || clean(incoming.paymentStatus).isEmpty() ? "PAID" : incoming.paymentStatus);
                renderBoard(true);
            }
            liveDraft = null;
            return;
        }

        if (kind == KitchenOrderParser.Kind.CANCELLED) {
            cancelPendingInferredSave();
            KitchenOrder existing = findStrict(incoming);
            if (existing != null) {
                store.cancel(existing.id);
                beepCancel();
                renderBoard(true);
            }
            liveDraft = null;
            return;
        }

        if (kind == KitchenOrderParser.Kind.SAVED) {
            cancelPendingInferredSave();
            KitchenOrder saved = mergeForSave(incoming, liveDraft);
            if (saved != null && !saved.items.isEmpty()) enqueueSaved(saved, false);
            liveDraft = null;
            return;
        }

        if (kind == KitchenOrderParser.Kind.SNAPSHOT || kind == KitchenOrderParser.Kind.UPDATED) {
            // Critical V2 rule: cart edits NEVER touch an already visible kitchen order.
            // They only become a modification after another explicit/inferred TEMP SAVE.
            if (incoming != null && !incoming.items.isEmpty()) {
                cancelPendingInferredSave();
                liveDraft = incoming.copy();
            }
            return;
        }

        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (!settings.getBoolean("infer_temp_save", true)) {
                liveDraft = null;
                return;
            }
            if (liveDraft != null && !liveDraft.items.isEmpty()) scheduleInferredSave(liveDraft.copy());
        }
    }

    private KitchenOrder mergeForSave(KitchenOrder incoming, KitchenOrder draft) {
        if (incoming == null && draft == null) return null;
        KitchenOrder out = incoming != null ? incoming.copy() : draft.copy();
        if (draft != null) {
            if (!KitchenSignalV2.valid(out.id) && KitchenSignalV2.valid(draft.id)) out.id = draft.id;
            if (!KitchenSignalV2.valid(out.displayNumber) && KitchenSignalV2.valid(draft.displayNumber)) out.displayNumber = draft.displayNumber;
            if (clean(out.table).isEmpty()) out.table = draft.table;
            if (clean(out.orderType).isEmpty()) out.orderType = draft.orderType;
            if (clean(out.customerNote).isEmpty()) out.customerNote = draft.customerNote;
            if (out.items.isEmpty() && !draft.items.isEmpty()) copyItems(out, draft);
        }
        out.id = KitchenSignalV2.cleanIdentity(out.id);
        out.displayNumber = KitchenSignalV2.cleanIdentity(out.displayNumber);
        if (!KitchenSignalV2.valid(out.id) && KitchenSignalV2.valid(out.displayNumber)) out.id = "invoice-" + out.displayNumber;
        return out;
    }

    private void scheduleInferredSave(KitchenOrder draft) {
        cancelPendingInferredSave();
        pendingInferredSave = () -> {
            pendingInferredSave = null;
            long sincePay = System.currentTimeMillis() - lastPaymentSignalAt;
            if (sincePay >= 0 && sincePay < PAYMENT_GUARD_MS) {
                liveDraft = null;
                return;
            }
            KitchenOrder saved = mergeForSave(draft, null);
            if (saved != null && !saved.items.isEmpty()) {
                saved.inferredTemporarySave = true;
                enqueueSaved(saved, true);
            }
            liveDraft = null;
        };
        handler.postDelayed(pendingInferredSave, INFER_SAVE_DELAY_MS);
    }

    private void cancelPendingInferredSave() {
        if (pendingInferredSave != null) handler.removeCallbacks(pendingInferredSave);
        pendingInferredSave = null;
    }

    private void enqueueSaved(KitchenOrder order, boolean inferred) {
        order.id = KitchenSignalV2.cleanIdentity(order.id);
        order.displayNumber = KitchenSignalV2.cleanIdentity(order.displayNumber);
        KitchenOrder existing = findStrict(order);

        // A paid save/checkout event must never create a new kitchen card.
        if (existing == null && order.isPaid()) return;

        if (existing != null) {
            order.id = existing.id;
            if (!KitchenSignalV2.valid(order.displayNumber)) order.displayNumber = existing.displayNumber;
            boolean changed = store.upsert(order);
            lastSavedId = existing.id;
            lastSavedAt = System.currentTimeMillis();
            if (changed) beepModification();
        } else {
            ensureNewIdentity(order);
            order.paymentStatus = clean(order.paymentStatus).isEmpty() ? "UNPAID" : order.paymentStatus;
            order.inferredTemporarySave = inferred;
            order.createdAt = System.currentTimeMillis();
            store.upsert(order);
            lastSavedId = order.id;
            lastSavedAt = System.currentTimeMillis();
            beepNewOrder();
        }
        renderBoard(true);
    }

    private KitchenOrder findStrict(KitchenOrder incoming) {
        if (incoming == null) return null;
        String id = KitchenSignalV2.cleanIdentity(incoming.id);
        String number = KitchenSignalV2.cleanIdentity(incoming.displayNumber);
        if (!id.isEmpty()) {
            KitchenOrder found = store.find(id);
            if (found != null) return found;
        }
        if (!number.isEmpty()) return store.findByNumber(number);
        return null;
    }

    private void ensureNewIdentity(KitchenOrder order) {
        if (KitchenSignalV2.valid(order.id)) return;
        if (KitchenSignalV2.valid(order.displayNumber)) {
            order.id = "invoice-" + order.displayNumber;
            return;
        }
        // Weak/no identity is NEVER matched to an older order. Each save becomes a new kitchen order.
        order.id = "temp-" + System.currentTimeMillis() + "-" + (++weakSequence);
    }

    private void copyItems(KitchenOrder destination, KitchenOrder source) {
        destination.items.clear();
        for (KitchenOrder.Item src : source.items) {
            KitchenOrder.Item item = new KitchenOrder.Item();
            item.lineId = src.lineId;
            item.itemId = src.itemId;
            item.name = src.name;
            item.qty = src.qty;
            item.note = src.note;
            item.station = src.station;
            item.modifiers.addAll(src.modifiers);
            item.removed.addAll(src.removed);
            destination.items.add(item);
        }
    }

    private void restoreConnection() {
        String ip = pair.getString("ip", "");
        int port = pair.getInt("port", 4040);
        if (ip.isEmpty()) {
            setConnection(t("notPaired"), false);
            showPairDialog();
        } else connect(ip, port);
    }

    private void connect(String ip, int port) {
        pair.edit().putString("ip", ip).putInt("port", port).apply();
        setConnection(t("connecting"), false);
        if (client != null) client.stop();
        client = new TechProClient(ip, port, this);
        client.start();
    }

    @Override public void onConnected() {
        setConnection(t("connected"), true);
    }

    @Override public void onDisconnected(String reason) {
        setConnection(t("reconnecting"), false);
    }

    private void setConnection(String label, boolean ok) {
        connectionOk = ok;
        runOnUiThread(() -> {
            if (connectionText != null) {
                connectionText.setText(label);
                connectionText.setTextColor(ok ? GREEN : MUTED);
            }
        });
    }

    private void showPairDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), 0, dp(22), 0);
        EditText ip = new EditText(this);
        ip.setHint("192.168.1.20");
        ip.setSingleLine(true);
        ip.setText(pair.getString("ip", ""));
        form.addView(ip);
        EditText port = new EditText(this);
        port.setHint("4040");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(pair.getInt("port", 4040)));
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("pair"))
                .setView(form)
                .setPositiveButton(t("pair"), null)
                .setNegativeButton(t("close"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            if (host.isEmpty()) { ip.setError("IP"); return; }
            try {
                int p = Integer.parseInt(port.getText().toString().trim());
                if (p < 1 || p > 65535) throw new NumberFormatException();
                dialog.dismiss();
                connect(host, p);
            } catch (Exception error) {
                port.setError("Port");
            }
        }));
        dialog.show();
    }

    private void requestSettingsPin() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setGravity(Gravity.CENTER);
        pin.setHint("PIN");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("pinTitle"))
                .setView(pin)
                .setPositiveButton(t("settings"), null)
                .setNegativeButton(t("close"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!settings.getString("pin", "0000").equals(pin.getText().toString())) {
                pin.setError(t("wrongPin"));
                return;
            }
            dialog.dismiss();
            showSettings();
        }));
        dialog.show();
    }

    private void showSettings() {
        final boolean[] english = { !ar() };
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(8), dp(22), dp(8));

        TextView lang = text((ar() ? "اللغة / Language: العربية" : "Language / اللغة: English"), 15, 0xFF22252A, true);
        lang.setPadding(0, dp(10), 0, dp(10));
        lang.setClickable(true);
        lang.setOnClickListener(v -> {
            english[0] = !english[0];
            lang.setText(english[0] ? "Language / اللغة: English" : "اللغة / Language: العربية");
        });
        panel.addView(lang);

        CheckBox images = new CheckBox(this);
        images.setText(t("showImages"));
        images.setChecked(settings.getBoolean("show_images", true));
        panel.addView(images);

        CheckBox infer = new CheckBox(this);
        infer.setText(t("inferSave"));
        infer.setChecked(settings.getBoolean("infer_temp_save", true));
        panel.addView(infer);

        CheckBox late = new CheckBox(this);
        late.setText(t("lateReminder"));
        late.setChecked(settings.getBoolean("late_reminder", true));
        panel.addView(late);

        TextView info = text((ar() ? "آخر حدث: " : "Last event: ") + (lastEvent.isEmpty() ? "—" : lastEvent)
                + "\n" + (ar() ? "الحساب: " : "Account: ") + session.userName()
                + "\n" + (ar() ? "الربط: " : "Pairing: ") + pair.getString("ip", "—") + ":" + pair.getInt("port", 4040), 12, 0xFF596270, false);
        info.setPadding(0, dp(10), 0, dp(10));
        panel.addView(info);

        new AlertDialog.Builder(this)
                .setTitle("TechPro Kitchen")
                .setView(panel)
                .setPositiveButton(t("save"), (dialog, which) -> {
                    settings.edit()
                            .putString("language", english[0] ? "en" : "ar")
                            .putBoolean("show_images", images.isChecked())
                            .putBoolean("infer_temp_save", infer.isChecked())
                            .putBoolean("late_reminder", late.isChecked())
                            .apply();
                    buildUi();
                    renderBoard(true);
                    setConnection(connectionOk ? t("connected") : t("reconnecting"), connectionOk);
                })
                .setNeutralButton(t("changePair"), (dialog, which) -> handler.post(this::showPairDialog))
                .setNegativeButton(t("close"), null)
                .show();
    }

    private int statusColor(KitchenOrder order, long now) {
        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) return RED;
        if (order.kitchenStatus == KitchenOrder.Status.READY) return GREEN;
        if (order.kitchenStatus == KitchenOrder.Status.PREPARING) return AMBER;
        if (order.kitchenStatus == KitchenOrder.Status.NEW && now - order.createdAt >= LATE_AFTER_MS) return RED;
        if (order.kitchenStatus == KitchenOrder.Status.DONE) return MUTED;
        return BLUE;
    }

    private int statusFill(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.READY) return 0xFF173B31;
        if (status == KitchenOrder.Status.PREPARING) return 0xFF44361C;
        if (status == KitchenOrder.Status.CANCELLED) return 0xFF441B20;
        if (status == KitchenOrder.Status.DONE) return 0xFF2B3037;
        return 0xFF1D3150;
    }

    private int statusText(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.READY) return 0xFF99EAC9;
        if (status == KitchenOrder.Status.PREPARING) return 0xFFFFD27A;
        if (status == KitchenOrder.Status.CANCELLED) return 0xFFFFA4AD;
        if (status == KitchenOrder.Status.DONE) return 0xFFC3CAD3;
        return 0xFFBFD5FF;
    }

    private String statusLabel(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.PREPARING) return t("preparing");
        if (status == KitchenOrder.Status.READY) return t("ready");
        if (status == KitchenOrder.Status.CANCELLED) return ar() ? "ملغي" : "Cancelled";
        if (status == KitchenOrder.Status.DONE) return ar() ? "منتهي" : "Completed";
        return t("new");
    }

    private String actionLabel(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.PREPARING) return t("markReady");
        if (status == KitchenOrder.Status.READY) return t("served");
        return t("start");
    }

    private String orderTypeLabel(KitchenOrder order) {
        return clean(order.orderType).isEmpty() ? t("cashierOrder") : order.orderType;
    }

    private String invoiceShort(KitchenOrder order) {
        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        return number.isEmpty() ? t("temporary") : "#" + number;
    }

    private TextView chip(String label, int fill, int color) {
        TextView view = text(label, 11, color, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(9), dp(4), dp(9), dp(4));
        view.setBackground(round(fill, 10));
        return view;
    }

    private TextView action(String label, boolean primary) {
        TextView view = text(label, 13, primary ? Color.WHITE : 0xFFDDE4ED, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        GradientDrawable shape = round(primary ? BLUE : 0xFF252B33, 13);
        shape.setStroke(dp(1), primary ? 0xFF6BA4FF : BORDER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity((ar() ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 900;
    }

    private int columns() {
        int width = getResources().getConfiguration().screenWidthDp;
        if (width >= 1600) return 5;
        if (width >= 1180) return 4;
        if (width >= 780) return 3;
        if (width >= 520) return 2;
        return 1;
    }

    private String formatAge(long ageMs) {
        long seconds = Math.max(0, ageMs / 1000L);
        long minutes = seconds / 60L;
        long remain = seconds % 60L;
        if (minutes >= 60) return (minutes / 60) + (ar() ? "س " : "h ") + (minutes % 60) + (ar() ? "د" : "m");
        return String.format(Locale.US, "%02d:%02d", minutes, remain);
    }

    private String formatQty(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void beepNewOrder() {
        if (tone == null) return;
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 190);
        handler.postDelayed(() -> {
            try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 190); } catch (Throwable ignored) { }
        }, 250L);
    }

    private void beepModification() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
    }

    private void beepCancel() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_SUP_ERROR, 550);
    }

    private void beepLate() {
        if (tone == null) return;
        tone.startTone(ToneGenerator.TONE_SUP_ERROR, 450);
        handler.postDelayed(() -> {
            try { tone.startTone(ToneGenerator.TONE_SUP_ERROR, 450); } catch (Throwable ignored) { }
        }, 540L);
    }

    private void beepStep() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
    }

    private void beepReady() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_ACK, 220);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        cancelPendingInferredSave();
        handler.removeCallbacks(clockTick);
        if (client != null) client.stop();
        if (imageLoader != null) imageLoader.shutdown();
        if (catalog != null) catalog.close();
        if (tone != null) {
            try { tone.release(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }
}
