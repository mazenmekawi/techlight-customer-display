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
 * TechPro Kitchen V4.
 * Modern KDS UI + fixed historical durations + silent WebSocket watchdog.
 */
public final class KitchenActivityV3 extends Activity implements TechProClient.Listener {
    private enum Filter { ALL, NEW, PREPARING, READY, HISTORY }

    private static final long INFER_SAVE_DELAY_MS = 2400L;
    private static final long PAYMENT_GUARD_MS = 7000L;
    private static final long LATE_AFTER_MS = 60_000L;
    private static final long LATE_REPEAT_MS = 60_000L;
    private static final long SILENT_RECONNECT_MS = 120_000L;
    private static final long RECONNECT_COOLDOWN_MS = 25_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Filter, TextView> tabs = new EnumMap<>(Filter.class);
    private final Map<String, Long> lateAlertAt = new HashMap<>();

    private FrameLayout shell;
    private GridLayout board;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptySub;
    private TextView connectionText;
    private TextView allMetric;
    private TextView newMetric;
    private TextView prepMetric;
    private TextView readyMetric;

    private TechProSession session;
    private KitchenOrderStoreV2 store;
    private ProductCatalog catalog;
    private ProductImageLoader imageLoader;
    private TechProClient client;
    private SharedPreferences pair;
    private SharedPreferences settings;
    private SharedPreferences diagnostics;
    private ToneGenerator tone;

    private Filter filter = Filter.ALL;
    private KitchenOrder liveDraft;
    private Runnable pendingInferredSave;
    private long lastPaymentSignalAt;
    private long lastRawAt;
    private long lastConnectAttemptAt;
    private long lastForcedReconnectAt;
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
    private int blue;
    private int purple;
    private int green;
    private int amber;
    private int red;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            try { renderBoard(false); } catch (Throwable error) { recordError("clock", error); }
            handler.postDelayed(this, 5000L);
        }
    };

    private final Runnable connectionWatchdog = new Runnable() {
        @Override public void run() {
            try {
                long now = System.currentTimeMillis();
                if (pair != null) {
                    String ip = pair.getString("ip", "");
                    int port = pair.getInt("port", 4040);
                    if (!ip.isEmpty()) {
                        boolean silent = connectionOk && lastRawAt > 0L && now - lastRawAt >= SILENT_RECONNECT_MS;
                        boolean stuckDisconnected = !connectionOk && now - lastConnectAttemptAt >= 35_000L;
                        if ((silent || stuckDisconnected) && now - lastForcedReconnectAt >= RECONNECT_COOLDOWN_MS) {
                            lastForcedReconnectAt = now;
                            recordDiagnostic("WATCHDOG_RECONNECT", silent ? "silent socket" : "stuck disconnected");
                            setConnection(t("recovering"), false);
                            connect(ip, port);
                        }
                    }
                }
            } catch (Throwable error) {
                recordError("watchdog", error);
            }
            handler.postDelayed(this, 10_000L);
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
            diagnostics = getSharedPreferences("kitchen_diagnostics_v4", MODE_PRIVATE);
            applyPalette();
            applySystemBars();

            store = new KitchenOrderStoreV2(this);
            try { catalog = new ProductCatalog(this); } catch (Throwable error) { recordError("catalog", error); }
            try { imageLoader = new ProductImageLoader(this, session.token()); } catch (Throwable error) { recordError("images", error); }
            try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 88); } catch (Throwable error) { recordError("tone", error); }

            buildUi();
            renderBoard(true);
            restoreConnectionSafe();
            handler.postDelayed(clockTick, 1000L);
            handler.postDelayed(connectionWatchdog, 10_000L);
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
        purple = 0xFF7432E0;
        blue = dark ? 0xFF6EA8FF : 0xFF1769E0;
        green = dark ? 0xFF4AD6A0 : 0xFF0B8D60;
        amber = dark ? 0xFFFFC863 : 0xFFAA6200;
        red = dark ? 0xFFFF737D : 0xFFC9303B;
        if (dark) {
            bg = 0xFF090B0F;
            surface = 0xFF12161C;
            surface2 = 0xFF1A2028;
            border = 0xFF252D37;
            text = 0xFFF7F9FC;
            muted = 0xFF96A2B1;
        } else {
            bg = 0xFFF4F6F9;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFF7F9FC;
            border = 0xFFE0E5EB;
            text = 0xFF111827;
            muted = 0xFF667587;
        }
    }

    private void applySystemBars() {
        try {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    private String t(String key) {
        boolean a = ar();
        switch (key) {
            case "kitchen": return a ? "مطبخ TechPro" : "TechPro Kitchen";
            case "live": return a ? "لوحة المطبخ المباشرة" : "Live kitchen board";
            case "settings": return a ? "الإعدادات" : "Settings";
            case "all": return a ? "الكل" : "All";
            case "new": return a ? "جديد" : "New";
            case "preparing": return a ? "التحضير" : "Preparing";
            case "ready": return a ? "جاهز" : "Ready";
            case "history": return a ? "السجل" : "History";
            case "waiting": return a ? "لا توجد طلبات الآن" : "No active orders";
            case "waitingSub": return a ? "سيظهر الطلب هنا فور حفظه مؤقتًا من الكاشير." : "Orders appear here as soon as they are temporarily saved at the POS.";
            case "invoice": return a ? "فاتورة" : "Invoice";
            case "temporary": return a ? "طلب مؤقت" : "Temporary order";
            case "table": return a ? "طاولة" : "Table";
            case "cashierOrder": return a ? "طلب كاشير" : "Cashier order";
            case "unpaid": return a ? "غير مدفوع" : "UNPAID";
            case "modified": return a ? "تعديل بعد الحفظ" : "Modified after save";
            case "cancelled": return a ? "تم إلغاء الطلب" : "Order cancelled";
            case "orderNote": return a ? "ملاحظة الطلب" : "Order note";
            case "itemNote": return a ? "ملاحظة" : "Note";
            case "start": return a ? "بدء التحضير" : "Start preparing";
            case "markReady": return a ? "تحديد كجاهز" : "Mark ready";
            case "served": return a ? "إنهاء الطلب" : "Complete order";
            case "details": return a ? "تفاصيل الطلب" : "Order details";
            case "connected": return a ? "متصل" : "Connected";
            case "reconnecting": return a ? "إعادة الاتصال" : "Reconnecting";
            case "recovering": return a ? "استعادة الاتصال" : "Recovering connection";
            case "notPaired": return a ? "غير مرتبط" : "Not paired";
            case "connecting": return a ? "جارٍ الاتصال" : "Connecting";
            case "pair": return a ? "ربط TechPro" : "Pair TechPro";
            case "late": return a ? "متأخر — لم يبدأ التحضير" : "Late — not started";
            case "duration": return a ? "مدة الطلب" : "Order time";
            case "prepTime": return a ? "وقت التحضير" : "Prep time";
            case "received": return a ? "استلام" : "Received";
            case "started": return a ? "بدأ" : "Started";
            case "finished": return a ? "انتهى" : "Finished";
            case "language": return a ? "اللغة" : "Language";
            case "appearance": return a ? "المظهر" : "Appearance";
            case "showImages": return a ? "إظهار صور الأصناف" : "Show product images";
            case "inferSave": return a ? "استنتاج الحفظ المؤقت عند اختفاء السلة بدون دفع" : "Infer temporary save when cart clears without payment";
            case "lateReminder": return a ? "تنبيه صوتي للطلب الجديد بعد دقيقة" : "Sound alert for new orders after one minute";
            case "save": return a ? "حفظ" : "Save";
            case "changePair": return a ? "تغيير الربط" : "Change pairing";
            case "logout": return a ? "تسجيل خروج" : "Sign out";
            case "reconnectNow": return a ? "إعادة الاتصال الآن" : "Reconnect now";
            case "noHistory": return a ? "لا توجد طلبات منتهية" : "No completed orders";
            case "historySub": return a ? "عند إنهاء أي طلب ستظهر مدته النهائية هنا." : "Completed orders will appear here with a fixed final duration.";
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
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setLayoutDirection(direction());
        topBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        topBar.setBackground(cardBg(surface, 24, border));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.techlight_t_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        topBar.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(11), 0, dp(11), 0);
        TextView title = label(t("kitchen"), wide() ? 22 : 19, text, true);
        TextView sub = label(t("live"), 11, muted, false);
        titleBox.addView(title);
        titleBox.addView(sub);
        topBar.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        connectionText = chip(connectionOk ? t("connected") : t("notPaired"), connectionOk ? softGreen() : surface2, connectionOk ? green : muted);
        topBar.addView(connectionText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));

        TextView settingsButton = action(t("settings"), false);
        settingsButton.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(dp(wide() ? 112 : 96), dp(42));
        sb.setMargins(dp(8), 0, 0, 0);
        topBar.addView(settingsButton, sb);
        root.addView(topBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setLayoutDirection(direction());
        metrics.setPadding(0, dp(10), 0, dp(4));
        allMetric = metric(metrics, t("all"), "0", purple);
        newMetric = metric(metrics, t("new"), "0", blue);
        prepMetric = metric(metrics, t("preparing"), "0", amber);
        readyMetric = metric(metrics, t("ready"), "0", green);
        root.addView(metrics, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)));

        HorizontalTabHost tabHost = new HorizontalTabHost(this);
        LinearLayout tabRow = tabHost.row;
        addTab(tabRow, Filter.ALL, t("all"));
        addTab(tabRow, Filter.NEW, t("new"));
        addTab(tabRow, Filter.PREPARING, t("preparing"));
        addTab(tabRow, Filter.READY, t("ready"));
        addTab(tabRow, Filter.HISTORY, t("history"));
        root.addView(tabHost.scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        board = new GridLayout(this);
        board.setUseDefaultMargins(false);
        board.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        board.setPadding(0, dp(2), 0, dp(28));
        scroll.addView(board, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setLayoutDirection(direction());
        ImageView emptyLogo = new ImageView(this);
        emptyLogo.setImageResource(R.drawable.techlight_t_logo);
        emptyLogo.setAlpha(0.55f);
        emptyState.addView(emptyLogo, new LinearLayout.LayoutParams(dp(82), dp(82)));
        emptyTitle = label(t("waiting"), wide() ? 23 : 20, text, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(4));
        emptyState.addView(emptyTitle);
        emptySub = label(t("waitingSub"), 13, muted, false);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(dp(22), 0, dp(22), 0);
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

    private static final class HorizontalTabHost {
        final ScrollView dummy = null;
        final android.widget.HorizontalScrollView scroll;
        final LinearLayout row;
        HorizontalTabHost(Activity context) {
            scroll = new android.widget.HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            scroll.addView(row, new android.widget.HorizontalScrollView.LayoutParams(
                    android.widget.HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                    android.widget.HorizontalScrollView.LayoutParams.MATCH_PARENT));
        }
    }

    private TextView metric(LinearLayout parent, String name, String value, int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(7), dp(8), dp(7));
        box.setBackground(cardBg(surface, 18, border));
        TextView number = label(value, wide() ? 22 : 19, accent, true);
        number.setGravity(Gravity.CENTER);
        TextView caption = label(name, 10, muted, true);
        caption.setGravity(Gravity.CENTER);
        box.addView(number);
        box.addView(caption);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(62), 1);
        bp.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(box, bp);
        return number;
    }

    private void addTab(LinearLayout row, Filter value, String name) {
        TextView tab = label(name, wide() ? 13 : 12, text, true);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setOnClickListener(v -> { filter = value; renderBoard(true); });
        tabs.put(value, tab);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wide() ? 142 : 116), dp(42));
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(tab, lp);
    }

    private void renderBoard(boolean force) {
        if (board == null || store == null) return;
        List<KitchenOrder> active = store.active();
        List<KitchenOrder> history = store.history();
        long now = System.currentTimeMillis();
        checkLateAlerts(active, now);
        updateMetrics(active, history.size());
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

        emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            if (filter == Filter.HISTORY) {
                emptyTitle.setText(t("noHistory"));
                emptySub.setText(t("historySub"));
            } else {
                emptyTitle.setText(t("waiting"));
                emptySub.setText(t("waitingSub"));
            }
        }
    }

    private void updateMetrics(List<KitchenOrder> active, int historyCount) {
        int n = 0, p = 0, r = 0;
        for (KitchenOrder order : active) {
            if (order.kitchenStatus == KitchenOrder.Status.NEW) n++;
            if (order.kitchenStatus == KitchenOrder.Status.PREPARING) p++;
            if (order.kitchenStatus == KitchenOrder.Status.READY) r++;
        }
        if (allMetric != null) allMetric.setText(String.valueOf(active.size()));
        if (newMetric != null) newMetric.setText(String.valueOf(n));
        if (prepMetric != null) prepMetric.setText(String.valueOf(p));
        if (readyMetric != null) readyMetric.setText(String.valueOf(r));
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
        GradientDrawable b = round(selected ? purple : surface, 13);
        b.setStroke(dp(1), selected ? purple : border);
        tab.setBackground(b);
        tab.setTextColor(selected ? Color.WHITE : muted);
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
        card.setPadding(dp(15), dp(13), dp(15), dp(14));
        card.setBackground(cardBg(surface, 22, border));
        card.setElevation(dp(dark ? 3 : 2));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showOrderDetails(order, history));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(direction());

        LinearLayout numberBox = new LinearLayout(this);
        numberBox.setOrientation(LinearLayout.VERTICAL);
        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        String mainLabel = number.isEmpty() ? t("temporary") : t("invoice") + "  #" + number;
        TextView invoice = label(mainLabel, wide() ? 23 : 19, text, true);
        TextView table = label(clean(order.table).isEmpty() ? orderTypeLabel(order) : t("table") + " " + order.table, 12, muted, true);
        table.setPadding(0, dp(2), 0, 0);
        numberBox.addView(invoice);
        numberBox.addView(table);
        top.addView(numberBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        long timerMs = history ? finalDuration(order) : Math.max(0L, now - order.createdAt);
        TextView timer = label(formatAge(timerMs), wide() ? 18 : 15, history ? purple : statusColor(order, now), true);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(dp(11), dp(7), dp(11), dp(7));
        timer.setBackground(round(surface2, 12));
        top.addView(timer);
        card.addView(top);

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateRow.setLayoutDirection(direction());
        stateRow.setPadding(0, dp(9), 0, dp(5));
        stateRow.addView(chip(history ? t("duration") : statusLabel(order.kitchenStatus), statusFill(order.kitchenStatus), history ? purple : statusText(order.kitchenStatus)));
        if (!history && order.temporaryOrder && !order.isPaid()) {
            TextView unpaid = chip(t("unpaid"), surface2, muted);
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
            up.setMargins(dp(7), 0, 0, 0);
            stateRow.addView(unpaid, up);
        }
        if (history) {
            long prep = prepDuration(order);
            if (prep > 0) {
                TextView prepChip = chip(t("prepTime") + "  " + formatAge(prep), surface2, muted);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
                pp.setMargins(dp(7), 0, 0, 0);
                stateRow.addView(prepChip, pp);
            }
        }
        card.addView(stateRow);

        boolean late = !history && order.kitchenStatus == KitchenOrder.Status.NEW && now - order.createdAt >= LATE_AFTER_MS;
        if (late) card.addView(alertBand(t("late"), red));
        else if (!history && order.changedAt > 0 && now - order.changedAt < 45_000L) card.addView(alertBand(t("modified"), amber));
        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) card.addView(alertBand(t("cancelled"), red));

        View divider = new View(this);
        divider.setBackgroundColor(border);
        LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dv.setMargins(0, dp(5), 0, dp(6));
        card.addView(divider, dv);

        int max = wide() ? 8 : 6;
        for (int i = 0; i < order.items.size() && i < max; i++) addItemRow(card, order.items.get(i));
        if (order.items.size() > max) {
            TextView more = label("+" + (order.items.size() - max) + (ar() ? " أصناف أخرى" : " more items"), 12, purple, true);
            more.setPadding(0, dp(5), 0, dp(3));
            card.addView(more);
        }

        if (!clean(order.customerNote).isEmpty()) {
            TextView note = label(t("orderNote") + ": " + order.customerNote, 12, dark ? 0xFFFFD995 : 0xFF795500, true);
            note.setPadding(dp(10), dp(8), dp(10), dp(8));
            note.setBackground(round(dark ? 0xFF2D271A : 0xFFFFF5DD, 11));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.setMargins(0, dp(8), 0, 0);
            card.addView(note, np);
        }

        if (!history && order.kitchenStatus != KitchenOrder.Status.CANCELLED) {
            TextView primary = action(actionLabel(order.kitchenStatus), true);
            primary.setOnClickListener(v -> advanceOrder(order));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            ap.setMargins(0, dp(11), 0, 0);
            card.addView(primary, ap);
        }
        return card;
    }

    private TextView alertBand(String value, int color) {
        TextView band = label(value, 11, color, true);
        band.setGravity(Gravity.CENTER);
        band.setPadding(dp(10), dp(6), dp(10), dp(6));
        band.setBackground(round(dark ? 0xFF211B1E : 0xFFFFF0F1, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        band.setLayoutParams(lp);
        return band;
    }

    private void addItemRow(LinearLayout card, KitchenOrder.Item item) {
        ProductCatalog.Product product = product(item);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setLayoutDirection(direction());
        row.setPadding(0, dp(5), 0, dp(5));

        String imagePath = imagePath(item, product);
        if (settings.getBoolean("show_images", true) && imageLoader != null && !imagePath.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(surface2, 12));
            image.setClipToOutline(true);
            image.setOnClickListener(v -> showProductImage(imagePath, displayName(item, product)));
            imageLoader.load(imagePath, image, null);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(58), dp(58));
            ip.setMargins(ar() ? dp(10) : 0, 0, ar() ? 0 : dp(10), 0);
            row.addView(image, ip);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(direction());
        content.addView(label(formatQty(item.qty) + " × " + displayName(item, product), wide() ? 17 : 15, text, true));
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
        if (!imagePath.isEmpty()) content.setOnClickListener(v -> showProductImage(imagePath, displayName(item, product)));
        row.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row);
    }

    private ProductCatalog.Product product(KitchenOrder.Item item) {
        if (catalog == null || item == null || item.itemId <= 0) return null;
        try { return catalog.find(item.itemId, 0, "", ""); } catch (Throwable ignored) { return null; }
    }

    private String displayName(KitchenOrder.Item item, ProductCatalog.Product product) {
        if (item != null) {
            String fromOrder = item.displayName(ar());
            if (!fromOrder.equals(ar() ? "صنف" : "Item")) return fromOrder;
        }
        if (product != null) {
            String localized = ar() ? clean(product.nameAr) : clean(product.nameEn);
            String alternate = ar() ? clean(product.nameEn) : clean(product.nameAr);
            if (!localized.isEmpty()) return localized;
            if (!alternate.isEmpty()) return alternate;
        }
        return ar() ? "صنف" : "Item";
    }

    private String imagePath(KitchenOrder.Item item, ProductCatalog.Product product) {
        if (item != null && !clean(item.imagePath).isEmpty()) return item.imagePath;
        return product == null ? "" : clean(product.imagePath);
    }

    private void showProductImage(String path, String name) {
        if (shell == null || imageLoader == null || clean(path).isEmpty()) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF4000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> removeOverlay(overlay));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(24), dp(24), dp(24), dp(78));
        overlay.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        imageLoader.load(path, image, null);
        TextView caption = label(name, wide() ? 19 : 16, Color.WHITE, true);
        caption.setGravity(Gravity.CENTER);
        caption.setBackgroundColor(0xB0000000);
        overlay.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM));
        shell.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setAlpha(0f);
        overlay.setScaleX(0.96f);
        overlay.setScaleY(0.96f);
        overlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        handler.postDelayed(() -> removeOverlay(overlay), 4300L);
    }

    private void removeOverlay(View overlay) {
        if (shell != null && overlay != null && overlay.getParent() == shell) {
            overlay.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                try { shell.removeView(overlay); } catch (Throwable ignored) { }
            }).start();
        }
    }

    private void showOrderDetails(KitchenOrder order, boolean history) {
        if (order == null) return;
        StringBuilder out = new StringBuilder();
        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        out.append(number.isEmpty() ? t("temporary") : t("invoice") + " #" + number).append('\n');
        if (!clean(order.table).isEmpty()) out.append(t("table")).append(' ').append(order.table).append('\n');
        if (history) {
            out.append('\n').append(t("duration")).append(": ").append(formatAge(finalDuration(order))).append('\n');
            if (prepDuration(order) > 0) out.append(t("prepTime")).append(": ").append(formatAge(prepDuration(order))).append('\n');
        }
        out.append('\n');
        for (KitchenOrder.Item item : order.items) {
            out.append(formatQty(item.qty)).append(" × ").append(displayName(item, product(item))).append('\n');
            for (String modifier : item.modifiers) out.append("   + ").append(modifier).append('\n');
            for (String removed : item.removed) out.append("   − ").append(removed).append('\n');
            if (!clean(item.note).isEmpty()) out.append("   ").append(t("itemNote")).append(": ").append(item.note).append('\n');
        }
        if (!clean(order.customerNote).isEmpty()) out.append('\n').append(t("orderNote")).append(": ").append(order.customerNote);
        new AlertDialog.Builder(this)
                .setTitle(t("details"))
                .setMessage(out.toString())
                .setPositiveButton(ar() ? "حسنًا" : "OK", null)
                .show();
    }

    private void advanceOrder(KitchenOrder order) {
        if (store == null || order == null) return;
        if (order.kitchenStatus == KitchenOrder.Status.NEW) {
            store.setStatus(order.id, KitchenOrder.Status.PREPARING);
            beep(false);
        } else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) {
            store.setStatus(order.id, KitchenOrder.Status.READY);
            beep(true);
        } else if (order.kitchenStatus == KitchenOrder.Status.READY) {
            store.setStatus(order.id, KitchenOrder.Status.DONE);
        }
        renderBoard(true);
    }

    private void checkLateAlerts(List<KitchenOrder> active, long now) {
        if (settings == null || !settings.getBoolean("late_reminder", true)) return;
        for (KitchenOrder order : active) {
            if (order.kitchenStatus != KitchenOrder.Status.NEW) continue;
            if (now - order.createdAt < LATE_AFTER_MS) continue;
            Long last = lateAlertAt.get(order.id);
            if (last == null || now - last >= LATE_REPEAT_MS) {
                lateAlertAt.put(order.id, now);
                beepLate();
            }
        }
    }

    private long finalDuration(KitchenOrder order) {
        if (order == null) return 0L;
        long end = order.updatedAt > 0L ? order.updatedAt : (order.readyAt > 0L ? order.readyAt : order.createdAt);
        return Math.max(0L, end - order.createdAt);
    }

    private long prepDuration(KitchenOrder order) {
        if (order == null || order.startedAt <= 0L) return 0L;
        long end = order.readyAt > 0L ? order.readyAt : order.updatedAt;
        return Math.max(0L, end - order.startedAt);
    }

    private void restoreConnectionSafe() {
        String ip = pair == null ? "" : pair.getString("ip", "");
        int port = pair == null ? 4040 : pair.getInt("port", 4040);
        if (ip.isEmpty()) setConnection(t("notPaired"), false);
        else connect(ip, port);
    }

    private void showPairDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), 0, dp(22), 0);
        EditText ip = new EditText(this);
        ip.setHint("IP  192.168.1.20");
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
                .setNegativeButton(ar() ? "إلغاء" : "Cancel", null)
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
            if (pair != null) pair.edit().putString("ip", ip).putInt("port", port).apply();
            lastConnectAttemptAt = System.currentTimeMillis();
            connectionOk = false;
            setConnection(t("connecting"), false);
            if (client != null) {
                try { client.stop(); } catch (Throwable ignored) { }
            }
            client = new TechProClient(ip, port, this);
            client.start();
        } catch (Throwable error) {
            recordError("connect", error);
            setConnection(t("reconnecting"), false);
        }
    }

    @Override public void onConnected() {
        connectionOk = true;
        lastRawAt = System.currentTimeMillis();
        runOnUiThread(() -> setConnection(t("connected"), true));
    }

    @Override public void onDisconnected(String reason) {
        connectionOk = false;
        recordDiagnostic("disconnect", reason);
        runOnUiThread(() -> setConnection(t("reconnecting"), false));
    }

    @Override public void onRaw(String raw) {
        if (raw == null) return;
        lastRawAt = System.currentTimeMillis();
        try {
            KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
            lastEvent = signal.parsed.kind.name() + (signal.parsed.eventName.isEmpty() ? "" : " • " + signal.parsed.eventName);
            recordDiagnostic("raw", lastEvent);
            processSignal(signal);
        } catch (Throwable error) {
            recordError("raw", error);
        }
    }

    @Override public void onOrder(OrderState order) { }

    @Override public void onDiagnostic(String stage, String detail) {
        recordDiagnostic(stage, detail);
    }

    private void processSignal(KitchenSignalV2.Signal signal) {
        if (signal == null || signal.parsed == null) return;
        KitchenOrderParser.Kind kind = signal.parsed.kind;
        KitchenOrder incoming = signal.order;
        long now = System.currentTimeMillis();

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

        if (kind == KitchenOrderParser.Kind.SAVED && incoming != null && !incoming.items.isEmpty()) {
            cancelInferredSave();
            incoming.temporaryOrder = true;
            enqueueSaved(incoming.copy(), false);
            liveDraft = null;
            return;
        }

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
            draft.temporaryOrder = true;
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
        order.temporaryOrder = true;
        boolean changed = store.upsert(order);
        if (before == null) beepNew();
        else if (changed) beepModified();
        runOnUiThread(() -> renderBoard(true));
    }

    private void normalizeSavedIdentity(KitchenOrder order) {
        order.displayNumber = KitchenSignalV2.cleanIdentity(order.displayNumber);
        order.id = KitchenSignalV2.cleanIdentity(order.id);
        if (!order.displayNumber.isEmpty() && order.id.isEmpty()) order.id = "invoice-" + order.displayNumber;
        if (order.id.isEmpty()) order.id = "weak-" + System.currentTimeMillis() + "-" + (++weakSequence);
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

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(10), dp(24), dp(12));

        panel.addView(label(t("language"), 15, text, true));
        RadioGroup languages = new RadioGroup(this);
        languages.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton arabic = new RadioButton(this); arabic.setText("العربية");
        RadioButton english = new RadioButton(this); english.setText("English");
        languages.addView(arabic); languages.addView(english);
        (ar() ? arabic : english).setChecked(true);
        panel.addView(languages);

        TextView appearance = label(t("appearance"), 15, text, true);
        appearance.setPadding(0, dp(12), 0, 0);
        panel.addView(appearance);
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

        TextView reconnect = action(t("reconnectNow"), false);
        reconnect.setOnClickListener(v -> {
            String ip = pair.getString("ip", "");
            if (!ip.isEmpty()) connect(ip, pair.getInt("port", 4040));
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rp.setMargins(0, dp(12), 0, 0);
        panel.addView(reconnect, rp);
        scroll.addView(panel);

        new AlertDialog.Builder(this)
                .setTitle("TechPro Kitchen V4")
                .setView(scroll)
                .setPositiveButton(t("save"), (d, w) -> {
                    settings.edit()
                            .putString("language", english.isChecked() ? "en" : "ar")
                            .putString("theme", lightOption.isChecked() ? "light" : "dark")
                            .putBoolean("show_images", images.isChecked())
                            .putBoolean("infer_temp_save", infer.isChecked())
                            .putBoolean("late_reminder", late.isChecked())
                            .apply();
                    applyPalette();
                    applySystemBars();
                    buildUi();
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
            connectionText.setBackground(round(ok ? softGreen() : surface2, 10));
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
        if (status == KitchenOrder.Status.READY) return dark ? 0xFF15372D : 0xFFE4F8F0;
        if (status == KitchenOrder.Status.PREPARING) return dark ? 0xFF3C3018 : 0xFFFFF3D8;
        if (status == KitchenOrder.Status.CANCELLED) return dark ? 0xFF421C21 : 0xFFFFE8EA;
        return dark ? 0xFF192D46 : 0xFFE8F1FE;
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

    private int softGreen() {
        return dark ? 0xFF173228 : 0xFFE7F8F1;
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
        GradientDrawable shape = round(primary ? purple : surface2, 13);
        shape.setStroke(dp(1), primary ? purple : border);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape, null));
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

    private GradientDrawable cardBg(int fill, int radius, int stroke) {
        GradientDrawable g = round(fill, radius);
        g.setStroke(dp(1), stroke);
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

    private void beepNew() {
        try {
            if (tone == null) return;
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
        try {
            FrameLayout frame = new FrameLayout(this);
            frame.setBackgroundColor(0xFF090B0F);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(30), dp(30), dp(30), dp(30));
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.techlight_t_logo);
            box.addView(logo, new LinearLayout.LayoutParams(dp(90), dp(90)));
            TextView title = new TextView(this);
            title.setText("TechPro Kitchen");
            title.setTextColor(Color.WHITE);
            title.setTextSize(26);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            box.addView(title);
            TextView message = new TextView(this);
            message.setText("Kitchen startup error\n" + (error == null ? "Unknown" : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
            message.setTextColor(0xFFFFAAB1);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, dp(12), 0, dp(18));
            box.addView(message);
            TextView retry = new TextView(this);
            retry.setText("Retry / إعادة المحاولة");
            retry.setTextColor(Color.WHITE);
            retry.setGravity(Gravity.CENTER);
            retry.setBackground(round(0xFF7432E0, 14));
            retry.setOnClickListener(v -> recreate());
            box.addView(retry, new LinearLayout.LayoutParams(dp(230), dp(52)));
            frame.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(frame);
        } catch (Throwable ignored) { }
    }

    @Override protected void onDestroy() {
        cancelInferredSave();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(connectionWatchdog);
        try { if (client != null) client.stop(); } catch (Throwable ignored) { }
        try { if (tone != null) tone.release(); } catch (Throwable ignored) { }
        try { if (imageLoader != null) imageLoader.shutdown(); } catch (Throwable ignored) { }
        try { if (catalog != null) catalog.close(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
