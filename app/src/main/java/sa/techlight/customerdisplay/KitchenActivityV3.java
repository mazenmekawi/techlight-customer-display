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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
 * TechPro Kitchen V3.
 * Crash-safe KDS with strict order lifecycle, Arabic/English and Dark/Light themes.
 */
public final class KitchenActivityV3 extends Activity implements TechProClient.Listener {
    private enum Filter { ALL, NEW, PREPARING, READY, HISTORY }

    private static final String BRAND_LOGO = "https://images.leadconnectorhq.com/image/f_webp/q_80/r_1200/u_https%3A//assets.cdn.filesafe.space/RrpygctF85S4KPExuRDV/media/678e63b989e1f5731ca4114c.png";
    private static final long INFER_SAVE_DELAY_MS = 2400L;
    private static final long PAYMENT_GUARD_MS = 7000L;
    private static final long LATE_AFTER_MS = 60_000L;
    private static final long LATE_REPEAT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Filter, TextView> tabs = new EnumMap<>(Filter.class);
    private final Map<String, Long> lateAlertAt = new HashMap<>();

    private FrameLayout shell;
    private GridLayout board;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptySub;
    private TextView connectionText;
    private TextView diagnosticsText;

    private TechProSession session;
    private KitchenOrderStoreV2 store;
    private ProductCatalog catalog;
    private ProductImageLoader imageLoader;
    private ProductImageLoader brandLoader;
    private TechProClient client;
    private SharedPreferences pair;
    private SharedPreferences settings;
    private SharedPreferences diagnostics;
    private ToneGenerator tone;

    private Filter filter = Filter.ALL;
    private KitchenOrder liveDraft;
    private Runnable pendingInferredSave;
    private long lastPaymentSignalAt;
    private int weakSequence;
    private String lastEvent = "";
    private boolean connectionOk;

    private boolean dark;
    private int bg;
    private int surface;
    private int surface2;
    private int border;
    private int text;
    private int muted;
    private int input;
    private int blue;
    private int amber;
    private int green;
    private int red;
    private int purple;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            try {
                renderBoard(false);
            } catch (Throwable error) {
                recordError("clock", error);
            }
            handler.postDelayed(this, 5000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            session = new TechProSession(this);
            if (!session.isSignedIn()) {
                openLogin();
                return;
            }
            settings = getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE);
            pair = getSharedPreferences("kitchen_pair", MODE_PRIVATE);
            diagnostics = getSharedPreferences("kitchen_diagnostics_v3", MODE_PRIVATE);
            applyPalette();
            applySystemBars();

            // Build the UI before optional services. If any optional service fails, the app stays open.
            buildUi();

            store = new KitchenOrderStoreV2(this);
            try { catalog = new ProductCatalog(this); } catch (Throwable error) { recordError("catalog", error); }
            try { imageLoader = new ProductImageLoader(this, session.token()); } catch (Throwable error) { recordError("images", error); }
            try { brandLoader = new ProductImageLoader(this, ""); } catch (Throwable error) { recordError("brand", error); }
            try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 88); } catch (Throwable error) { recordError("tone", error); }

            refreshBrandLogo();
            renderBoard(true);
            restoreConnectionSafe();
            handler.postDelayed(clockTick, 1200L);
        } catch (Throwable error) {
            showFatalStartup(error);
        }
    }

    private void openLogin() {
        try { startActivity(new Intent(this, KitchenLoginActivityV3.class)); } catch (Throwable ignored) { }
        finish();
    }

    private boolean ar() {
        return settings == null || !"en".equalsIgnoreCase(settings.getString("language", "ar"));
    }

    private int direction() {
        return ar() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    private void applyPalette() {
        dark = settings == null || !"light".equalsIgnoreCase(settings.getString("theme", "dark"));
        blue = 0xFF1769E0;
        green = dark ? 0xFF37D39A : 0xFF0D8F62;
        amber = dark ? 0xFFFFC55C : 0xFFB36A00;
        red = dark ? 0xFFFF6670 : 0xFFC9313B;
        purple = dark ? 0xFF9D7BFF : 0xFF6D43D4;
        if (dark) {
            bg = 0xFF0B0D10;
            surface = 0xFF15181D;
            surface2 = 0xFF1B2027;
            border = 0xFF2B323B;
            text = 0xFFF7F9FC;
            muted = 0xFFA2ACB8;
            input = 0xFF0F1216;
        } else {
            bg = 0xFFF3F6F9;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFF7F9FC;
            border = 0xFFD9E0E7;
            text = 0xFF15202B;
            muted = 0xFF667587;
            input = 0xFFF8FAFC;
        }
    }

    private void applySystemBars() {
        try {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    private String t(String key) {
        boolean a = ar();
        switch (key) {
            case "kitchen": return a ? "شاشة المطبخ" : "Kitchen Display";
            case "settings": return a ? "الإعدادات" : "Settings";
            case "all": return a ? "الكل" : "All";
            case "new": return a ? "جديد" : "New";
            case "preparing": return a ? "التحضير" : "Preparing";
            case "ready": return a ? "جاهز" : "Ready";
            case "history": return a ? "السجل" : "History";
            case "waiting": return a ? "بانتظار الطلبات المحفوظة" : "Waiting for saved orders";
            case "waitingSub": return a ? "الطلب يظهر بعد الحفظ المؤقت فقط. السداد لا يعيد إرساله." : "Orders appear after temporary save only. Payment never sends them again.";
            case "invoice": return a ? "فاتورة" : "Invoice";
            case "temporary": return a ? "طلب مؤقت" : "Temporary order";
            case "table": return a ? "طاولة" : "Table";
            case "cashierOrder": return a ? "طلب كاشير" : "Cashier order";
            case "unpaid": return a ? "غير مدفوع" : "UNPAID";
            case "modified": return a ? "تم تعديل الطلب بعد الحفظ" : "Modified after save";
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
            case "appearance": return a ? "المظهر" : "Appearance";
            case "dark": return a ? "داكن" : "Dark";
            case "light": return a ? "فاتح" : "Light";
            case "showImages": return a ? "إظهار صور الأصناف" : "Show product images";
            case "inferSave": return a ? "استنتاج الحفظ المؤقت عند اختفاء السلة بدون دفع" : "Infer temporary save when cart clears without payment";
            case "lateReminder": return a ? "تنبيه صوتي كل دقيقة للطلب الجديد غير المبدوء" : "Sound reminder every minute for unstarted orders";
            case "save": return a ? "حفظ" : "Save";
            case "changePair": return a ? "تغيير الربط" : "Change pairing";
            case "pinTitle": return a ? "إعدادات المطبخ" : "Kitchen settings";
            case "wrongPin": return a ? "الرمز غير صحيح" : "Wrong PIN";
            case "more": return a ? "أصناف أخرى" : "more items";
            case "imageHint": return a ? "تغلق الصورة تلقائيًا" : "Image closes automatically";
            case "recall": return a ? "استرجاع آخر طلب" : "Recall last order";
            case "logout": return a ? "تسجيل الخروج" : "Sign out";
            case "noOrders": return a ? "لا توجد طلبات في هذه الحالة" : "No orders in this status";
            case "chooseOther": return a ? "اختر حالة أخرى من الأعلى." : "Choose another status above.";
            case "late": return a ? "متأخر — ابدأ التحضير" : "LATE — start preparing";
            default: return key;
        }
    }

    private void buildUi() {
        tabs.clear();
        shell = new FrameLayout(this);
        shell.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(direction());
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(direction());
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable headerBg = round(surface, 20);
        headerBg.setStroke(dp(1), border);
        header.setBackground(headerBg);
        header.setElevation(dp(2));

        FrameLayout logoTile = new FrameLayout(this);
        logoTile.setBackground(round(Color.WHITE, 14));
        ImageView logo = new ImageView(this);
        logo.setTag("brand_logo");
        logo.setImageResource(R.drawable.techlight_mark);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(4), dp(4), dp(4), dp(4));
        logoTile.addView(logo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        header.addView(logoTile, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, dp(10), 0);
        TextView title = label("TechPro Kitchen", wide() ? 22 : 18, text, true);
        TextView branch = label(session == null || session.accountName().isEmpty() ? t("kitchen") : session.accountName(), 12, muted, false);
        titleBox.addView(title);
        titleBox.addView(branch);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout connectionBox = new LinearLayout(this);
        connectionBox.setOrientation(LinearLayout.VERTICAL);
        connectionBox.setGravity(Gravity.CENTER);
        connectionBox.setPadding(dp(6), 0, dp(9), 0);
        connectionText = label(connectionOk ? t("connected") : t("notPaired"), 12, connectionOk ? green : muted, true);
        connectionText.setGravity(Gravity.CENTER);
        diagnosticsText = label(lastEvent, 8, muted, false);
        diagnosticsText.setGravity(Gravity.CENTER);
        connectionBox.addView(connectionText);
        connectionBox.addView(diagnosticsText);
        header.addView(connectionBox);

        TextView settingsButton = action(t("settings"), false);
        settingsButton.setOnClickListener(v -> requestSettingsPin());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(wide() ? 116 : 94), dp(44)));
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
        board.setPadding(0, 0, 0, dp(26));
        scroll.addView(board, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setLayoutDirection(direction());
        FrameLayout emptyLogoTile = new FrameLayout(this);
        emptyLogoTile.setBackground(round(Color.WHITE, 18));
        ImageView emptyLogo = new ImageView(this);
        emptyLogo.setTag("brand_logo_empty");
        emptyLogo.setImageResource(R.drawable.techlight_mark);
        emptyLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        emptyLogo.setPadding(dp(6), dp(6), dp(6), dp(6));
        emptyLogoTile.addView(emptyLogo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        emptyState.addView(emptyLogoTile, new LinearLayout.LayoutParams(dp(98), dp(98)));
        emptyTitle = label(t("waiting"), wide() ? 24 : 20, text, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(4));
        emptyState.addView(emptyTitle);
        emptySub = label(t("waitingSub"), 13, muted, false);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(dp(24), 0, dp(24), 0);
        emptyState.addView(emptySub);
        TextView pairNow = action(t("pair"), true);
        pairNow.setOnClickListener(v -> showPairDialog());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(190), dp(50));
        pp.setMargins(0, dp(18), 0, 0);
        emptyState.addView(pairNow, pp);
        content.addView(emptyState, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(shell);
    }

    private void refreshBrandLogo() {
        if (shell == null || brandLoader == null) return;
        View one = shell.findViewWithTag("brand_logo");
        View two = shell.findViewWithTag("brand_logo_empty");
        if (one instanceof ImageView) brandLoader.load(BRAND_LOGO, (ImageView) one, null);
        if (two instanceof ImageView) brandLoader.load(BRAND_LOGO, (ImageView) two, null);
    }

    private void addTab(LinearLayout row, Filter value, String name) {
        TextView tab = label(name, wide() ? 14 : 11, text, true);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setOnClickListener(v -> {
            filter = value;
            renderBoard(true);
        });
        tabs.put(value, tab);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tab, lp);
    }

    private void updateTabs(List<KitchenOrder> active, int historyCount) {
        int n = 0, p = 0, r = 0;
        for (KitchenOrder order : active) {
            if (order.kitchenStatus == KitchenOrder.Status.NEW) n++;
            if (order.kitchenStatus == KitchenOrder.Status.PREPARING) p++;
            if (order.kitchenStatus == KitchenOrder.Status.READY) r++;
        }
        setTab(Filter.ALL, t("all"), active.size());
        setTab(Filter.NEW, t("new"), n);
        setTab(Filter.PREPARING, t("preparing"), p);
        setTab(Filter.READY, t("ready"), r);
        setTab(Filter.HISTORY, t("history"), historyCount);
    }

    private void setTab(Filter value, String name, int count) {
        TextView tab = tabs.get(value);
        if (tab == null) return;
        tab.setText(name + "  " + count);
        boolean selected = filter == value;
        GradientDrawable b = round(selected ? (dark ? 0xFF263245 : 0xFFE9F1FD) : surface, 14);
        b.setStroke(dp(1), selected ? blue : border);
        tab.setBackground(b);
        tab.setTextColor(selected ? (dark ? Color.WHITE : 0xFF174A92) : muted);
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
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            board.addView(card, lp);
        }

        if (emptyState != null) emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty() && emptyTitle != null && emptySub != null) {
            if (filter == Filter.HISTORY) {
                emptyTitle.setText(ar() ? "لا توجد طلبات منتهية" : "No completed orders");
                emptySub.setText(ar() ? "ستظهر الطلبات المكتملة هنا للمراجعة." : "Completed orders will appear here for review.");
            } else if (filter == Filter.ALL) {
                emptyTitle.setText(t("waiting"));
                emptySub.setText(t("waitingSub"));
            } else {
                emptyTitle.setText(t("noOrders"));
                emptySub.setText(t("chooseOther"));
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
        int stateColor = statusColor(order, now);
        GradientDrawable b = round(surface, 18);
        b.setStroke(dp(order.kitchenStatus == KitchenOrder.Status.NEW && now - order.createdAt >= LATE_AFTER_MS ? 2 : 1), stateColor);
        card.setBackground(b);
        card.setElevation(dp(dark ? 3 : 2));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showOrderDetails(order));
        if (!history) card.setOnLongClickListener(v -> { showOrderActions(order); return true; });

        View strip = new View(this);
        strip.setBackground(round(stateColor, 4));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
        sp.setMargins(-dp(14), 0, -dp(14), dp(10));
        card.addView(strip, sp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(direction());
        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        String numberLabel = number.isEmpty() ? t("temporary") : t("invoice") + " #" + number;
        TextView invoice = label(numberLabel, wide() ? 23 : 18, text, true);
        invoice.setOnClickListener(v -> showOrderDetails(order));
        top.addView(invoice, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView age = chip(formatAge(now - order.createdAt), dark ? 0xFF0D1014 : 0xFFF0F3F7, stateColor);
        top.addView(age);
        card.addView(top);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setLayoutDirection(direction());
        String tableValue = clean(order.table).isEmpty() ? orderTypeLabel(order) : t("table") + " " + order.table;
        TextView table = label(tableValue, wide() ? 20 : 16, dark ? 0xFFDCE6F5 : 0xFF33465A, true);
        table.setPadding(0, dp(5), 0, dp(5));
        meta.addView(table, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!clean(order.orderType).isEmpty() && !clean(order.table).isEmpty()) meta.addView(chip(order.orderType, surface2, muted));
        card.addView(meta);

        boolean late = !history && order.kitchenStatus == KitchenOrder.Status.NEW && now - order.createdAt >= LATE_AFTER_MS;
        if (late) {
            TextView lateChip = chip(t("late"), dark ? 0xFF4A2021 : 0xFFFFE9E9, red);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
            cp.setMargins(0, 0, 0, dp(6));
            card.addView(lateChip, cp);
        } else if (order.changedAt > 0 && now - order.changedAt < 45_000L) {
            TextView changed = chip(t("modified"), dark ? 0xFF3B2E15 : 0xFFFFF3D5, amber);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
            cp.setMargins(0, 0, 0, dp(6));
            card.addView(changed, cp);
        }

        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) {
            TextView cancelled = chip(t("cancelled"), dark ? 0xFF461B20 : 0xFFFFE4E6, red);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
            cp.setMargins(0, 0, 0, dp(6));
            card.addView(cancelled, cp);
        }

        View divider = new View(this);
        divider.setBackgroundColor(border);
        LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dv.setMargins(0, dp(3), 0, dp(6));
        card.addView(divider, dv);

        int max = wide() ? 9 : 7;
        for (int i = 0; i < order.items.size() && i < max; i++) addItemRow(card, order.items.get(i));
        if (order.items.size() > max) {
            TextView more = label("+ " + (order.items.size() - max) + " " + t("more"), 12, muted, true);
            more.setPadding(0, dp(4), 0, dp(4));
            card.addView(more);
        }

        if (!clean(order.customerNote).isEmpty()) {
            TextView note = label(t("orderNote") + ": " + order.customerNote, 12, dark ? 0xFFFFE0A3 : 0xFF6E4B00, true);
            note.setPadding(dp(10), dp(8), dp(10), dp(8));
            note.setBackground(round(dark ? 0xFF342A18 : 0xFFFFF6DF, 11));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.setMargins(0, dp(7), 0, dp(5));
            card.addView(note, np);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setLayoutDirection(direction());
        footer.addView(chip(statusLabel(order.kitchenStatus), statusFill(order.kitchenStatus), statusText(order.kitchenStatus)));
        if (!order.isPaid() && !history) {
            TextView unpaid = chip(t("unpaid"), dark ? 0xFF272C34 : 0xFFF0F2F5, muted);
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
            up.setMargins(dp(6), 0, 0, 0);
            footer.addView(unpaid, up);
        }
        card.addView(footer);

        if (!history && order.kitchenStatus != KitchenOrder.Status.CANCELLED) {
            TextView primary = action(actionLabel(order.kitchenStatus), true);
            primary.setOnClickListener(v -> advanceOrder(order));
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
        if (settings.getBoolean("show_images", true) && imageLoader != null && !imagePath.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(surface2, 10));
            image.setClipToOutline(true);
            image.setClickable(true);
            image.setFocusable(true);
            image.setOnClickListener(v -> showProductImage(product, item));
            imageLoader.load(imagePath, image, null);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(56), dp(56));
            ip.setMargins(ar() ? dp(9) : 0, 0, ar() ? 0 : dp(9), 0);
            row.addView(image, ip);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(direction());
        String name = displayName(item, product);
        TextView title = label(formatQty(item.qty) + " × " + name, wide() ? 17 : 15, text, true);
        content.addView(title);
        for (String modifier : item.modifiers) {
            TextView option = label("+ " + modifier, 12, green, true);
            option.setPadding(dp(12), dp(1), 0, dp(1));
            content.addView(option);
        }
        for (String removed : item.removed) {
            TextView option = label("− " + removed, 12, red, true);
            option.setPadding(dp(12), dp(1), 0, dp(1));
            content.addView(option);
        }
        if (!clean(item.note).isEmpty()) {
            TextView note = label(t("itemNote") + ": " + item.note, 12, amber, false);
            note.setPadding(dp(12), dp(2), 0, dp(1));
            content.addView(note);
        }
        if (!imagePath.isEmpty()) content.setOnClickListener(v -> showProductImage(product, item));
        row.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row);
    }

    private ProductCatalog.Product product(KitchenOrder.Item item) {
        if (catalog == null || item == null || item.itemId <= 0) return null;
        try { return catalog.find(item.itemId, 0, "", ""); } catch (Throwable error) { return null; }
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
        if (product == null || clean(product.imagePath).isEmpty() || imageLoader == null || shell == null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF5000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(v -> removeOverlay(overlay));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(24), dp(24), dp(24), dp(84));
        overlay.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        imageLoader.load(product.imagePath, image, null);
        TextView caption = label(displayName(item, product) + "  •  " + t("imageHint"), wide() ? 19 : 16, Color.WHITE, true);
        caption.setGravity(Gravity.CENTER);
        caption.setBackgroundColor(0xAA000000);
        overlay.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM));
        shell.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setAlpha(0f);
        overlay.setScaleX(0.96f);
        overlay.setScaleY(0.96f);
        overlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        handler.postDelayed(() -> removeOverlay(overlay), 4200L);
    }

    private void removeOverlay(View overlay) {
        if (shell != null && overlay != null && overlay.getParent() == shell) {
            overlay.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                try { if (overlay.getParent() == shell) shell.removeView(overlay); } catch (Throwable ignored) { }
            }).start();
        }
    }

    private void showOrderDetails(KitchenOrder order) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(direction());
        body.setPadding(dp(20), dp(12), dp(20), dp(18));
        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        body.addView(label(number.isEmpty() ? t("temporary") : t("invoice") + " #" + number, 24, dark ? Color.WHITE : 0xFF111827, true));
        if (!clean(order.table).isEmpty()) body.addView(label(t("table") + " " + order.table, 18, dark ? 0xFFDCE6F5 : 0xFF33465A, true));
        for (KitchenOrder.Item item : order.items) {
            ProductCatalog.Product p = product(item);
            TextView row = label(formatQty(item.qty) + " × " + displayName(item, p), 17, dark ? Color.WHITE : 0xFF111827, true);
            row.setPadding(0, dp(8), 0, dp(3));
            body.addView(row);
            for (String mod : item.modifiers) body.addView(label("+ " + mod, 13, 0xFF19865D, true));
            for (String rem : item.removed) body.addView(label("− " + rem, 13, 0xFFD1434D, true));
            if (!clean(item.note).isEmpty()) body.addView(label(t("itemNote") + ": " + item.note, 13, 0xFF9A6500, false));
        }
        if (!clean(order.customerNote).isEmpty()) {
            TextView note = label(t("orderNote") + ": " + order.customerNote, 14, 0xFF9A6500, true);
            note.setPadding(0, dp(12), 0, 0);
            body.addView(note);
        }
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(t("details"))
                .setView(scroll)
                .setPositiveButton(t("close"), null)
                .show();
    }

    private void advanceOrder(KitchenOrder order) {
        if (store == null) return;
        if (order.kitchenStatus == KitchenOrder.Status.NEW) {
            store.setStatus(order.id, KitchenOrder.Status.PREPARING);
            lateAlertAt.remove(order.id);
            beep(false);
        } else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) {
            store.setStatus(order.id, KitchenOrder.Status.READY);
            beep(true);
        } else if (order.kitchenStatus == KitchenOrder.Status.READY) {
            store.setStatus(order.id, KitchenOrder.Status.DONE);
        }
        renderBoard(true);
    }

    private void showOrderActions(KitchenOrder order) {
        String[] values = ar()
                ? new String[]{"جديد", "بدء التحضير", "جاهز", "إنهاء/إخفاء", "إلغاء محلي"}
                : new String[]{"New", "Start preparing", "Ready", "Complete / hide", "Cancel locally"};
        new AlertDialog.Builder(this)
                .setTitle(t("details"))
                .setItems(values, (dialog, which) -> {
                    if (which == 0) store.setStatus(order.id, KitchenOrder.Status.NEW);
                    if (which == 1) store.setStatus(order.id, KitchenOrder.Status.PREPARING);
                    if (which == 2) store.setStatus(order.id, KitchenOrder.Status.READY);
                    if (which == 3) store.setStatus(order.id, KitchenOrder.Status.DONE);
                    if (which == 4) store.cancel(order.id);
                    renderBoard(true);
                }).show();
    }

    private void checkLateAlerts(List<KitchenOrder> active, long now) {
        if (settings == null || !settings.getBoolean("late_reminder", true)) return;
        for (KitchenOrder order : active) {
            if (order.kitchenStatus != KitchenOrder.Status.NEW) continue;
            if (now - order.createdAt < LATE_AFTER_MS) continue;
            long last = lateAlertAt.containsKey(order.id) ? lateAlertAt.get(order.id) : 0L;
            if (now - last < LATE_REPEAT_MS) continue;
            lateAlertAt.put(order.id, now);
            beepLate();
        }
    }

    private void restoreConnectionSafe() {
        try {
            String ip = pair.getString("ip", "");
            int port = pair.getInt("port", 4040);
            if (ip.isEmpty()) {
                setConnection(t("notPaired"), false);
                handler.postDelayed(this::showPairDialog, 300L);
            } else connect(ip, port);
        } catch (Throwable error) {
            recordError("pair", error);
            setConnection(t("notPaired"), false);
        }
    }

    private void showPairDialog() {
        if (isFinishing()) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), 0, dp(22), 0);
        EditText ip = new EditText(this);
        ip.setHint(ar() ? "IP الكاشير مثال 192.168.1.20" : "Cashier IP e.g. 192.168.1.20");
        ip.setSingleLine(true);
        ip.setText(pair == null ? "" : pair.getString("ip", ""));
        form.addView(ip);
        EditText port = new EditText(this);
        port.setHint("Port");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setSingleLine(true);
        port.setText(String.valueOf(pair == null ? 4040 : pair.getInt("port", 4040)));
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("pair"))
                .setView(form)
                .setPositiveButton(ar() ? "ربط" : "Connect", null)
                .setNegativeButton(t("close"), null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            if (host.isEmpty()) { ip.setError("IP"); return; }
            try {
                int p = Integer.parseInt(port.getText().toString().trim());
                if (p < 1 || p > 65535) throw new NumberFormatException();
                dialog.dismiss();
                connect(host, p);
            } catch (Exception error) { port.setError("Port"); }
        }));
        dialog.show();
    }

    private void connect(String ip, int port) {
        try {
            pair.edit().putString("ip", ip).putInt("port", port).apply();
            setConnection(t("connecting"), false);
            if (client != null) client.stop();
            client = new TechProClient(ip, port, this);
            client.start();
        } catch (Throwable error) {
            recordError("connect", error);
            setConnection(t("reconnecting"), false);
        }
    }

    @Override public void onConnected() {
        connectionOk = true;
        runOnUiThread(() -> setConnection(t("connected"), true));
    }

    @Override public void onDisconnected(String reason) {
        connectionOk = false;
        recordDiagnostic("disconnect", reason);
        runOnUiThread(() -> setConnection(t("reconnecting"), false));
    }

    @Override public void onRaw(String raw) {
        if (raw == null) return;
        try {
            String sample = raw.length() > 12000 ? raw.substring(0, 12000) : raw;
            diagnostics.edit().putString("last_raw", sample).putLong("last_raw_at", System.currentTimeMillis()).apply();
            KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
            lastEvent = signal.parsed.kind.name() + (signal.parsed.eventName.isEmpty() ? "" : " • " + signal.parsed.eventName);
            runOnUiThread(() -> { if (diagnosticsText != null) diagnosticsText.setText(shortText(lastEvent, 26)); });
            processSignal(signal);
        } catch (Throwable error) {
            recordError("raw", error);
        }
    }

    @Override public void onOrder(OrderState order) {
        // Intentionally ignored. Kitchen lifecycle is driven by the richer raw signal parser.
    }

    @Override public void onDiagnostic(String stage, String detail) {
        recordDiagnostic(stage, detail);
    }

    private void processSignal(KitchenSignalV2.Signal signal) {
        if (signal == null || signal.parsed == null) return;
        KitchenOrderParser.Kind kind = signal.parsed.kind;
        KitchenOrder incoming = signal.order;
        long now = System.currentTimeMillis();

        // Payment is never a create event.
        if (kind == KitchenOrderParser.Kind.PAYMENT) {
            lastPaymentSignalAt = now;
            cancelInferredSave();
            KitchenOrder existing = strongExisting(incoming);
            if (existing != null) {
                store.updatePayment(existing.id, incoming == null || clean(incoming.paymentStatus).isEmpty() ? "PAID" : incoming.paymentStatus);
                runOnUiThread(() -> renderBoard(true));
            }
            liveDraft = null;
            return;
        }

        if (kind == KitchenOrderParser.Kind.CANCELLED) {
            cancelInferredSave();
            KitchenOrder existing = strongExisting(incoming);
            if (existing != null) {
                store.cancel(existing.id);
                beepCancel();
                runOnUiThread(() -> renderBoard(true));
            }
            liveDraft = null;
            return;
        }

        // Only SAVE creates/updates a kitchen ticket.
        if (kind == KitchenOrderParser.Kind.SAVED && incoming != null && !incoming.items.isEmpty()) {
            cancelInferredSave();
            enqueueSaved(incoming.copy(), false);
            liveDraft = null;
            return;
        }

        // These are cashier-cart edits only. Never touch an existing kitchen ticket here.
        if ((kind == KitchenOrderParser.Kind.SNAPSHOT || kind == KitchenOrderParser.Kind.UPDATED)
                && incoming != null && !incoming.items.isEmpty()) {
            liveDraft = incoming.copy();
            return;
        }

        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            if (!settings.getBoolean("infer_temp_save", true)) { liveDraft = null; return; }
            scheduleInferredSave(liveDraft.copy());
        }
    }

    private void scheduleInferredSave(KitchenOrder draft) {
        cancelInferredSave();
        pendingInferredSave = () -> {
            pendingInferredSave = null;
            long sincePayment = System.currentTimeMillis() - lastPaymentSignalAt;
            if (sincePayment >= 0 && sincePayment < PAYMENT_GUARD_MS) {
                liveDraft = null;
                return;
            }
            draft.inferredTemporarySave = true;
            enqueueSaved(draft, true);
            liveDraft = null;
        };
        handler.postDelayed(pendingInferredSave, INFER_SAVE_DELAY_MS);
    }

    private void cancelInferredSave() {
        if (pendingInferredSave != null) handler.removeCallbacks(pendingInferredSave);
        pendingInferredSave = null;
    }

    private void enqueueSaved(KitchenOrder order, boolean inferred) {
        if (store == null || order == null || order.items.isEmpty()) return;
        normalizeSavedIdentity(order);
        KitchenOrder before = strongExisting(order);
        order.inferredTemporarySave = inferred;
        boolean changed = store.upsert(order);
        if (before == null) beepNew();
        else if (changed) beepModified();
        runOnUiThread(() -> renderBoard(true));
    }

    private void normalizeSavedIdentity(KitchenOrder order) {
        order.displayNumber = KitchenSignalV2.cleanIdentity(order.displayNumber);
        order.id = KitchenSignalV2.cleanIdentity(order.id);
        if (!order.displayNumber.isEmpty() && order.id.isEmpty()) order.id = "invoice-" + order.displayNumber;
        if (order.id.isEmpty()) {
            // Missing invoice identity must NEVER match an older ticket. Prefer a harmless duplicate over a wrong merge.
            order.id = "weak-" + System.currentTimeMillis() + "-" + (++weakSequence);
        }
    }

    private KitchenOrder strongExisting(KitchenOrder incoming) {
        if (incoming == null || store == null) return null;
        String id = KitchenSignalV2.cleanIdentity(incoming.id);
        String number = KitchenSignalV2.cleanIdentity(incoming.displayNumber);
        if (!id.isEmpty() && !id.startsWith("weak-")) {
            KitchenOrder found = store.find(id);
            if (found != null) return found;
        }
        if (!number.isEmpty()) return store.findByNumber(number);
        return null;
    }

    private void requestSettingsPin() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setGravity(Gravity.CENTER);
        pin.setHint("PIN");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("pinTitle"))
                .setView(pin)
                .setPositiveButton(ar() ? "فتح" : "Open", null)
                .setNegativeButton(t("close"), null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String saved = settings.getString("pin", "0000");
            if (!saved.equals(pin.getText().toString())) { pin.setError(t("wrongPin")); return; }
            dialog.dismiss();
            showSettings();
        }));
        dialog.show();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(10), dp(24), dp(12));

        TextView languageLabel = label(t("language"), 15, dark ? Color.WHITE : 0xFF111827, true);
        panel.addView(languageLabel);
        RadioGroup languages = new RadioGroup(this);
        languages.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton arabic = new RadioButton(this); arabic.setText("العربية");
        RadioButton english = new RadioButton(this); english.setText("English");
        languages.addView(arabic); languages.addView(english);
        (ar() ? arabic : english).setChecked(true);
        panel.addView(languages);

        TextView appearanceLabel = label(t("appearance"), 15, dark ? Color.WHITE : 0xFF111827, true);
        appearanceLabel.setPadding(0, dp(12), 0, 0);
        panel.addView(appearanceLabel);
        RadioGroup themes = new RadioGroup(this);
        themes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton darkOption = new RadioButton(this); darkOption.setText(ar() ? "داكن" : "Dark");
        RadioButton lightOption = new RadioButton(this); lightOption.setText(ar() ? "فاتح" : "Light");
        themes.addView(darkOption); themes.addView(lightOption);
        (dark ? darkOption : lightOption).setChecked(true);
        panel.addView(themes);

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

        TextView info = label((ar() ? "آخر حدث: " : "Last event: ") + shortText(lastEvent, 42), 11, dark ? 0xFFB8C1CB : 0xFF586879, false);
        info.setPadding(0, dp(10), 0, 0);
        panel.addView(info);
        scroll.addView(panel);

        new AlertDialog.Builder(this)
                .setTitle("TechPro Kitchen")
                .setView(scroll)
                .setPositiveButton(t("save"), (d, w) -> {
                    String language = english.isChecked() ? "en" : "ar";
                    String theme = lightOption.isChecked() ? "light" : "dark";
                    settings.edit()
                            .putString("language", language)
                            .putString("theme", theme)
                            .putBoolean("show_images", images.isChecked())
                            .putBoolean("infer_temp_save", infer.isChecked())
                            .putBoolean("late_reminder", late.isChecked())
                            .apply();
                    applyPalette();
                    applySystemBars();
                    buildUi();
                    refreshBrandLogo();
                    renderBoard(true);
                    setConnection(connectionOk ? t("connected") : t("reconnecting"), connectionOk);
                })
                .setNeutralButton(t("changePair"), (d, w) -> handler.post(this::showPairDialog))
                .setNegativeButton(t("logout"), (d, w) -> {
                    if (session != null) session.clear();
                    openLogin();
                })
                .show();
    }

    private void setConnection(String value, boolean ok) {
        connectionOk = ok;
        if (connectionText != null) {
            connectionText.setText(value);
            connectionText.setTextColor(ok ? green : muted);
        }
    }

    private int statusColor(KitchenOrder order, long now) {
        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) return red;
        if (order.kitchenStatus == KitchenOrder.Status.NEW && now - order.createdAt >= LATE_AFTER_MS) return red;
        if (order.kitchenStatus == KitchenOrder.Status.READY) return green;
        if (order.kitchenStatus == KitchenOrder.Status.PREPARING) return amber;
        return blue;
    }

    private int statusFill(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.READY) return dark ? 0xFF15392E : 0xFFE1F7EE;
        if (status == KitchenOrder.Status.PREPARING) return dark ? 0xFF3D3017 : 0xFFFFF3D6;
        if (status == KitchenOrder.Status.CANCELLED) return dark ? 0xFF431A1F : 0xFFFFE7E9;
        return dark ? 0xFF1B2E4A : 0xFFE5F0FF;
    }

    private int statusText(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.READY) return green;
        if (status == KitchenOrder.Status.PREPARING) return amber;
        if (status == KitchenOrder.Status.CANCELLED) return red;
        return blue;
    }

    private String statusLabel(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.PREPARING) return t("preparing");
        if (status == KitchenOrder.Status.READY) return t("ready");
        if (status == KitchenOrder.Status.CANCELLED) return ar() ? "ملغي" : "Cancelled";
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

    private TextView chip(String value, int fill, int color) {
        TextView v = label(value, 11, color, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(4), dp(9), dp(4));
        v.setBackground(round(fill, 10));
        return v;
    }

    private TextView action(String value, boolean primary) {
        TextView v = label(value, 13, primary ? Color.WHITE : text, true);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        GradientDrawable shape = round(primary ? blue : surface2, 13);
        shape.setStroke(primary ? 0 : dp(1), primary ? blue : border);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), shape, null));
        v.setPadding(dp(10), 0, dp(10), 0);
        return v;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity((ar() ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable round(int fill, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private int columns() {
        int width = getResources().getConfiguration().screenWidthDp;
        if (width >= 1600) return 5;
        if (width >= 1180) return 4;
        if (width >= 800) return 3;
        if (width >= 520) return 2;
        return 1;
    }

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 900;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String shortText(String value, int max) {
        String v = clean(value);
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private void beepNew() {
        if (tone == null) return;
        try {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180);
            handler.postDelayed(() -> { try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 180); } catch (Throwable ignored) { } }, 240L);
        } catch (Throwable ignored) { }
    }

    private void beepModified() {
        try { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250); } catch (Throwable ignored) { }
    }

    private void beepCancel() {
        try { if (tone != null) tone.startTone(ToneGenerator.TONE_SUP_ERROR, 500); } catch (Throwable ignored) { }
    }

    private void beepLate() {
        try {
            if (tone == null) return;
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 430);
            handler.postDelayed(() -> { try { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 430); } catch (Throwable ignored) { } }, 520L);
        } catch (Throwable ignored) { }
    }

    private void beep(boolean positive) {
        try { if (tone != null) tone.startTone(positive ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, 160); } catch (Throwable ignored) { }
    }

    private void recordDiagnostic(String stage, String detail) {
        if (diagnostics == null) return;
        diagnostics.edit()
                .putString("stage", clean(stage))
                .putString("detail", clean(detail))
                .putLong("at", System.currentTimeMillis())
                .apply();
    }

    private void recordError(String stage, Throwable error) {
        String message = error == null ? "unknown" : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        recordDiagnostic(stage, message);
    }

    private void showFatalStartup(Throwable error) {
        recordError("startup", error);
        try {
            dark = true;
            bg = 0xFF0B0D10;
            text = Color.WHITE;
            muted = 0xFFABB4C0;
            FrameLayout frame = new FrameLayout(this);
            frame.setBackgroundColor(bg);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(32), dp(32), dp(32), dp(32));
            TextView title = new TextView(this);
            title.setText("TechPro Kitchen");
            title.setTextColor(Color.WHITE);
            title.setTextSize(28);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            box.addView(title);
            TextView message = new TextView(this);
            message.setText("تعذر تهيئة شاشة المطبخ / Kitchen startup error\n\n" + (error == null ? "Unknown" : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
            message.setTextColor(0xFFFFB0B6);
            message.setTextSize(14);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, dp(16), 0, dp(20));
            box.addView(message);
            TextView retry = new TextView(this);
            retry.setText("إعادة المحاولة / Retry");
            retry.setTextColor(Color.WHITE);
            retry.setTextSize(15);
            retry.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            retry.setGravity(Gravity.CENTER);
            retry.setBackground(round(0xFF1769E0, 14));
            retry.setOnClickListener(v -> recreate());
            box.addView(retry, new LinearLayout.LayoutParams(dp(240), dp(54)));
            TextView login = new TextView(this);
            login.setText("تسجيل الدخول من جديد / Sign in again");
            login.setTextColor(Color.WHITE);
            login.setGravity(Gravity.CENTER);
            login.setPadding(0, dp(16), 0, 0);
            login.setOnClickListener(v -> {
                try { if (session != null) session.clear(); } catch (Throwable ignored) { }
                openLogin();
            });
            box.addView(login);
            frame.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(frame);
        } catch (Throwable ignored) { }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        cancelInferredSave();
        handler.removeCallbacks(clockTick);
        try { if (client != null) client.stop(); } catch (Throwable ignored) { }
        try { if (tone != null) tone.release(); } catch (Throwable ignored) { }
        try { if (imageLoader != null) imageLoader.shutdown(); } catch (Throwable ignored) { }
        try { if (brandLoader != null) brandLoader.shutdown(); } catch (Throwable ignored) { }
        try { if (catalog != null) catalog.close(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
