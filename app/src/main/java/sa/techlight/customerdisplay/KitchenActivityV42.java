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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TechPro Kitchen V4.2
 * Strict invoice-first lifecycle and 1-second clock updates.
 */
public final class KitchenActivityV42 extends Activity implements TechProClient.Listener {
    private enum Filter { ALL, NEW, PREPARING, READY, HISTORY }

    private static final long LATE_AFTER_MS = 60_000L;
    private static final long LATE_REPEAT_MS = 60_000L;
    private static final long PENDING_SAVE_WINDOW_MS = 30_000L;
    private static final long SILENT_RECONNECT_MS = 120_000L;
    private static final long RECONNECT_COOLDOWN_MS = 25_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Filter, TextView> tabs = new EnumMap<>(Filter.class);
    private final Map<String, TimerRef> timerRefs = new HashMap<>();
    private final Map<String, Long> lateAlertAt = new HashMap<>();

    private FrameLayout shell;
    private GridLayout board;
    private LinearLayout emptyState;
    private TextView emptyTitle;
    private TextView emptySub;
    private TextView connectionText;

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
    private KitchenOrder pendingSaved;
    private long pendingSavedAt;
    private long lastPaymentAt;
    private long lastRawAt;
    private long lastConnectAttemptAt;
    private long lastForcedReconnectAt;
    private boolean connectionOk;

    private boolean dark;
    private int bg;
    private int surface;
    private int surface2;
    private int border;
    private int text;
    private int muted;
    private int purple;
    private int blue;
    private int green;
    private int amber;
    private int red;

    private static final class TimerRef {
        final TextView view;
        final long startedAt;
        final String orderId;
        final KitchenOrder.Status status;
        TimerRef(TextView view, long startedAt, String orderId, KitchenOrder.Status status) {
            this.view = view;
            this.startedAt = startedAt;
            this.orderId = orderId;
            this.status = status;
        }
    }

    private final Runnable secondTick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            try {
                for (TimerRef ref : new ArrayList<>(timerRefs.values())) {
                    if (ref.view == null) continue;
                    ref.view.setText(formatAge(now - ref.startedAt));
                    if (ref.status == KitchenOrder.Status.NEW && now - ref.startedAt >= LATE_AFTER_MS) {
                        Long last = lateAlertAt.get(ref.orderId);
                        if (last == null || now - last >= LATE_REPEAT_MS) {
                            lateAlertAt.put(ref.orderId, now);
                            beepLate();
                        }
                    }
                }
            } catch (Throwable error) { recordError("timer", error); }
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                long now = System.currentTimeMillis();
                if (pair != null) {
                    String ip = pair.getString("ip", "");
                    int port = pair.getInt("port", 4040);
                    if (!ip.isEmpty()) {
                        boolean silent = connectionOk && lastRawAt > 0L && now - lastRawAt >= SILENT_RECONNECT_MS;
                        boolean stuck = !connectionOk && now - lastConnectAttemptAt >= 35_000L;
                        if ((silent || stuck) && now - lastForcedReconnectAt >= RECONNECT_COOLDOWN_MS) {
                            lastForcedReconnectAt = now;
                            setConnection(t("recovering"), false);
                            connect(ip, port);
                        }
                    }
                }
            } catch (Throwable error) { recordError("watchdog", error); }
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            session = new TechProSession(this);
            if (!session.isSignedIn()) { openLogin(); return; }
            settings = getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE);
            pair = getSharedPreferences("kitchen_pair", MODE_PRIVATE);
            diagnostics = getSharedPreferences("kitchen_diagnostics_v42", MODE_PRIVATE);
            store = new KitchenOrderStoreV2(this);
            try { catalog = new ProductCatalog(this); } catch (Throwable error) { recordError("catalog", error); }
            try { imageLoader = new ProductImageLoader(this, session.token()); } catch (Throwable error) { recordError("images", error); }
            try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 88); } catch (Throwable ignored) { }
            applyPalette();
            applySystemBars();
            buildUi();
            renderBoard();
            restoreConnection();
            handler.postDelayed(secondTick, 1000L);
            handler.postDelayed(watchdog, 10_000L);
        } catch (Throwable error) { showStartupError(error); }
    }

    private void openLogin() {
        try { startActivity(new Intent(this, KitchenLoginActivityV3.class)); } catch (Throwable ignored) { }
        finish();
    }

    private boolean ar() { return settings == null || !"en".equalsIgnoreCase(settings.getString("language", "ar")); }
    private int direction() { return ar() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR; }

    private void applyPalette() {
        dark = settings == null || !"light".equalsIgnoreCase(settings.getString("theme", "dark"));
        purple = 0xFF7432E0;
        blue = dark ? 0xFF6EA8FF : 0xFF1769E0;
        green = dark ? 0xFF4AD6A0 : 0xFF0B8D60;
        amber = dark ? 0xFFFFC863 : 0xFFAA6200;
        red = dark ? 0xFFFF737D : 0xFFC9303B;
        if (dark) {
            bg = 0xFF090B0F; surface = 0xFF12161C; surface2 = 0xFF1A2028; border = 0xFF252D37; text = 0xFFF7F9FC; muted = 0xFF96A2B1;
        } else {
            bg = 0xFFF4F6F9; surface = 0xFFFFFFFF; surface2 = 0xFFF7F9FC; border = 0xFFE0E5EB; text = 0xFF111827; muted = 0xFF667587;
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
            case "title": return a ? "مطبخ TechPro" : "TechPro Kitchen";
            case "all": return a ? "الطلبات" : "Orders";
            case "new": return a ? "جديد" : "New";
            case "preparing": return a ? "تحضير" : "Preparing";
            case "ready": return a ? "جاهز للتسليم" : "Ready for delivery";
            case "history": return a ? "السجل" : "History";
            case "settings": return a ? "الإعدادات" : "Settings";
            case "connected": return a ? "متصل" : "Connected";
            case "connecting": return a ? "جارٍ الاتصال" : "Connecting";
            case "reconnecting": return a ? "إعادة الاتصال" : "Reconnecting";
            case "recovering": return a ? "استعادة الاتصال" : "Recovering";
            case "notPaired": return a ? "غير مرتبط" : "Not paired";
            case "waiting": return a ? "بانتظار فاتورة محفوظة" : "Waiting for a saved invoice";
            case "waitingSub": return a ? "لن يظهر أي طلب قبل وصول رقم الفاتورة الحقيقي من TechPro." : "No ticket appears until TechPro sends the real invoice number.";
            case "table": return a ? "طاولة" : "Table";
            case "unpaid": return a ? "غير مدفوع" : "UNPAID";
            case "modified": return a ? "تم تحديث نفس الفاتورة" : "Same invoice updated";
            case "late": return a ? "تأخر بدء التحضير" : "Preparation not started";
            case "prepare": return a ? "تحضير" : "Prepare";
            case "readyAction": return a ? "جاهز للتسليم" : "Ready for delivery";
            case "complete": return a ? "تم التسليم — نقل للسجل" : "Delivered — move to history";
            case "duration": return a ? "المدة" : "Duration";
            case "prepTime": return a ? "التحضير" : "Prep";
            case "noHistory": return a ? "لا يوجد سجل" : "No history";
            case "language": return a ? "اللغة" : "Language";
            case "appearance": return a ? "المظهر" : "Appearance";
            case "images": return a ? "إظهار صور الأصناف" : "Show product images";
            case "clear": return a ? "مسح الطلبات" : "Clear orders";
            case "pair": return a ? "ربط TechPro" : "Pair TechPro";
            case "logout": return a ? "تسجيل خروج" : "Sign out";
            default: return key;
        }
    }

    private void buildUi() {
        tabs.clear(); timerRefs.clear();
        shell = new FrameLayout(this); shell.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setLayoutDirection(direction()); root.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setLayoutDirection(direction());
        top.setPadding(dp(14), dp(10), dp(14), dp(10)); top.setBackground(cardBg(surface, 22, border));
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.techlight_t_logo); logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        top.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titleBox = new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL); titleBox.setPadding(dp(10), 0, dp(10), 0);
        titleBox.addView(label(t("title"), wide() ? 22 : 19, text, true));
        titleBox.addView(label(ar() ? "رقم الطلب = رقم الفاتورة" : "Ticket number = invoice number", 11, muted, false));
        top.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        connectionText = chip(connectionOk ? t("connected") : t("notPaired"), connectionOk ? softGreen() : surface2, connectionOk ? green : muted);
        top.addView(connectionText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        TextView settingsButton = action(t("settings"), false); settingsButton.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(dp(wide() ? 112 : 96), dp(42)); sb.setMargins(dp(8), 0, 0, 0); top.addView(settingsButton, sb);
        root.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this); tabRow.setOrientation(LinearLayout.HORIZONTAL); tabRow.setLayoutDirection(direction()); tabRow.setGravity(Gravity.CENTER_VERTICAL);
        addTab(tabRow, Filter.ALL, t("all")); addTab(tabRow, Filter.NEW, t("new")); addTab(tabRow, Filter.PREPARING, t("preparing")); addTab(tabRow, Filter.READY, t("ready")); addTab(tabRow, Filter.HISTORY, t("history"));
        tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(48)));
        LinearLayout.LayoutParams tsp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)); tsp.setMargins(0, dp(8), 0, 0); root.addView(tabScroll, tsp);

        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        board = new GridLayout(this); board.setAlignmentMode(GridLayout.ALIGN_BOUNDS); board.setUseDefaultMargins(false); board.setPadding(0, dp(4), 0, dp(28));
        scroll.addView(board, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        emptyState = new LinearLayout(this); emptyState.setOrientation(LinearLayout.VERTICAL); emptyState.setGravity(Gravity.CENTER); emptyState.setLayoutDirection(direction());
        ImageView emptyLogo = new ImageView(this); emptyLogo.setImageResource(R.drawable.techlight_t_logo); emptyLogo.setAlpha(0.48f); emptyState.addView(emptyLogo, new LinearLayout.LayoutParams(dp(80), dp(80)));
        emptyTitle = label(t("waiting"), 22, text, true); emptyTitle.setGravity(Gravity.CENTER); emptyTitle.setPadding(0, dp(12), 0, dp(4)); emptyState.addView(emptyTitle);
        emptySub = label(t("waitingSub"), 13, muted, false); emptySub.setGravity(Gravity.CENTER); emptySub.setPadding(dp(24), 0, dp(24), 0); emptyState.addView(emptySub);
        content.addView(emptyState, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); setContentView(shell);
    }

    private void addTab(LinearLayout row, Filter value, String name) {
        TextView tab = label(name, wide() ? 13 : 12, muted, true); tab.setGravity(Gravity.CENTER); tab.setClickable(true); tab.setFocusable(true);
        tab.setOnClickListener(v -> { filter = value; renderBoard(); }); tabs.put(value, tab);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wide() ? 150 : 124), dp(42)); lp.setMargins(dp(3), 0, dp(3), 0); row.addView(tab, lp);
    }

    private void updateTabs(List<KitchenOrder> active, int historyCount) {
        int n = 0, p = 0, r = 0;
        for (KitchenOrder order : active) {
            if (!hasInvoice(order)) continue;
            if (order.kitchenStatus == KitchenOrder.Status.NEW) n++; else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) p++; else if (order.kitchenStatus == KitchenOrder.Status.READY) r++;
        }
        setTab(Filter.ALL, t("all"), n + p + r); setTab(Filter.NEW, t("new"), n); setTab(Filter.PREPARING, t("preparing"), p); setTab(Filter.READY, t("ready"), r); setTab(Filter.HISTORY, t("history"), historyCount);
    }

    private void setTab(Filter value, String label, int count) {
        TextView tab = tabs.get(value); if (tab == null) return; tab.setText(label + "  " + count); boolean selected = filter == value;
        GradientDrawable g = round(selected ? purple : surface, 13); g.setStroke(dp(1), selected ? purple : border); tab.setBackground(g); tab.setTextColor(selected ? Color.WHITE : muted);
    }

    private void renderBoard() {
        if (board == null || store == null) return;
        List<KitchenOrder> active = store.active(); List<KitchenOrder> history = store.history(); updateTabs(active, history.size());
        List<KitchenOrder> source = filter == Filter.HISTORY ? history : active; ArrayList<KitchenOrder> visible = new ArrayList<>();
        for (KitchenOrder order : source) { if (filter != Filter.HISTORY && !hasInvoice(order)) continue; if (matchesFilter(order)) visible.add(order); }
        board.removeAllViews(); timerRefs.clear(); int cardWidth = cardWidthDp(); int usable = Math.max(cardWidth, getResources().getConfiguration().screenWidthDp - 36);
        board.setColumnCount(Math.max(1, usable / (cardWidth + 12)));
        for (KitchenOrder order : visible) {
            View card = orderCard(order, filter == Filter.HISTORY); GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = dp(cardWidth); lp.height = GridLayout.LayoutParams.WRAP_CONTENT; lp.setMargins(dp(6), dp(6), dp(6), dp(6)); board.addView(card, lp);
        }
        emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (visible.isEmpty()) {
            if (filter == Filter.HISTORY) { emptyTitle.setText(t("noHistory")); emptySub.setText(ar() ? "الفواتير المكتملة تظهر هنا بالمدة والتاريخ." : "Completed invoices appear here with date and duration."); }
            else { emptyTitle.setText(t("waiting")); emptySub.setText(t("waitingSub")); }
        }
    }

    private boolean matchesFilter(KitchenOrder order) {
        if (filter == Filter.HISTORY) return true;
        if (order == null || order.kitchenStatus == KitchenOrder.Status.CANCELLED || order.kitchenStatus == KitchenOrder.Status.DONE) return false;
        if (filter == Filter.ALL) return true; if (filter == Filter.NEW) return order.kitchenStatus == KitchenOrder.Status.NEW; if (filter == Filter.PREPARING) return order.kitchenStatus == KitchenOrder.Status.PREPARING; if (filter == Filter.READY) return order.kitchenStatus == KitchenOrder.Status.READY; return false;
    }

    private View orderCard(KitchenOrder order, boolean history) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setLayoutDirection(direction()); card.setPadding(dp(15), dp(13), dp(15), dp(14)); card.setBackground(cardBg(surface, 22, border)); card.setElevation(dp(dark ? 3 : 2)); card.setMinimumHeight(dp(300)); card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v -> showOrderDetails(order, history));
        if (!history) card.setOnLongClickListener(v -> { showTicketMenu(order); return true; });
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL); header.setLayoutDirection(direction());
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); String number = invoiceNumber(order); TextView numberView = label("#" + number, wide() ? 28 : 23, text, true); info.addView(numberView);
        String type = KitchenSignalV2.displayOrderType(order.orderType, ar()); String table = clean(order.table); String meta;
        if (!type.isEmpty() && !table.isEmpty()) meta = type + "  •  " + t("table") + " " + table; else if (!type.isEmpty()) meta = type; else if (!table.isEmpty()) meta = t("table") + " " + table; else meta = ar() ? "طلب كاشير" : "POS order";
        TextView metaView = label(meta, 13, muted, true); metaView.setPadding(0, dp(3), 0, 0); info.addView(metaView); header.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        long duration = history ? finalDuration(order) : Math.max(0L, System.currentTimeMillis() - order.createdAt); TextView timer = label(formatAge(duration), 17, history ? purple : statusColor(order.kitchenStatus), true); timer.setGravity(Gravity.CENTER); timer.setPadding(dp(10), dp(7), dp(10), dp(7)); timer.setBackground(round(surface2, 12)); header.addView(timer);
        if (!history) timerRefs.put(order.id, new TimerRef(timer, order.createdAt, order.id, order.kitchenStatus)); card.addView(header);
        LinearLayout chips = new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL); chips.setLayoutDirection(direction()); chips.setPadding(0, dp(9), 0, dp(6));
        if (history) {
            chips.addView(chip(t("duration") + "  " + formatAge(finalDuration(order)), surface2, purple)); long prep = prepDuration(order);
            if (prep > 0L) { TextView prepChip = chip(t("prepTime") + "  " + formatAge(prep), surface2, muted); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29)); pp.setMargins(dp(7), 0, 0, 0); chips.addView(prepChip, pp); }
        } else {
            chips.addView(chip(statusLabel(order.kitchenStatus), statusFill(order.kitchenStatus), statusColor(order.kitchenStatus)));
            if (order.temporaryOrder && !order.isPaid()) { TextView unpaid = chip(t("unpaid"), surface2, muted); LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29)); up.setMargins(dp(7), 0, 0, 0); chips.addView(unpaid, up); }
        }
        card.addView(chips);
        if (!history && order.changedAt > 0L && System.currentTimeMillis() - order.changedAt < 45_000L) card.addView(alertBand(t("modified"), amber));
        if (!history && order.kitchenStatus == KitchenOrder.Status.NEW && System.currentTimeMillis() - order.createdAt >= LATE_AFTER_MS) card.addView(alertBand(t("late"), red));
        View divider = new View(this); divider.setBackgroundColor(border); LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)); dv.setMargins(0, dp(5), 0, dp(6)); card.addView(divider, dv);
        int max = wide() ? 8 : 6; for (int i = 0; i < order.items.size() && i < max; i++) addItemRow(card, order.items.get(i));
        if (order.items.size() > max) { TextView more = label("+" + (order.items.size() - max) + (ar() ? " أصناف" : " items"), 12, purple, true); more.setPadding(0, dp(5), 0, dp(3)); card.addView(more); }
        if (!clean(order.customerNote).isEmpty()) { TextView note = label(order.customerNote, 12, dark ? 0xFFFFD995 : 0xFF795500, true); note.setPadding(dp(10), dp(8), dp(10), dp(8)); note.setBackground(round(dark ? 0xFF2D271A : 0xFFFFF5DD, 11)); LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); np.setMargins(0, dp(8), 0, 0); card.addView(note, np); }
        if (!history && order.kitchenStatus != KitchenOrder.Status.READY) {
            TextView primary = action(order.kitchenStatus == KitchenOrder.Status.NEW ? t("prepare") : t("readyAction"), true); primary.setOnClickListener(v -> advance(order)); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)); ap.setMargins(0, dp(11), 0, 0); card.addView(primary, ap);
        } else if (!history && order.kitchenStatus == KitchenOrder.Status.READY) {
            TextView hint = label(ar() ? "جاهز للتسليم • ضغطة مطولة بعد التسليم لنقله للسجل" : "Ready for delivery • Long-press after delivery to archive", 11, green, true); hint.setGravity(Gravity.CENTER); hint.setPadding(dp(8), dp(9), dp(8), 0); card.addView(hint);
        }
        if (history) { TextView stamp = label(historyStamp(order), 11, muted, true); stamp.setPadding(0, dp(9), 0, 0); card.addView(stamp); }
        return card;
    }

    private void addItemRow(LinearLayout card, KitchenOrder.Item item) {
        ProductCatalog.Product product = product(item); LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.TOP); row.setLayoutDirection(direction()); row.setPadding(0, dp(5), 0, dp(5));
        String imagePath = item != null && !clean(item.imagePath).isEmpty() ? item.imagePath : (product == null ? "" : clean(product.imagePath));
        if (settings.getBoolean("show_images", true) && imageLoader != null && !imagePath.isEmpty()) {
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(round(surface2, 12)); image.setClipToOutline(true); image.setOnClickListener(v -> showProductImage(imagePath, displayName(item, product))); imageLoader.load(imagePath, image, null); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(58), dp(58)); ip.setMargins(ar() ? dp(10) : 0, 0, ar() ? 0 : dp(10), 0); row.addView(image, ip);
        }
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setLayoutDirection(direction()); content.addView(label(formatQty(item.qty) + " × " + displayName(item, product), wide() ? 17 : 15, text, true));
        for (String modifier : item.modifiers) { TextView v = label("+ " + modifier, 12, green, true); v.setPadding(dp(12), dp(1), 0, dp(1)); content.addView(v); }
        for (String removed : item.removed) { TextView v = label("− " + removed, 12, red, true); v.setPadding(dp(12), dp(1), 0, dp(1)); content.addView(v); }
        if (!clean(item.note).isEmpty()) { TextView v = label((ar() ? "ملاحظة: " : "Note: ") + item.note, 12, amber, false); v.setPadding(dp(12), dp(2), 0, dp(1)); content.addView(v); }
        if (!imagePath.isEmpty()) content.setOnClickListener(v -> showProductImage(imagePath, displayName(item, product))); row.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)); card.addView(row);
    }

    private ProductCatalog.Product product(KitchenOrder.Item item) { if (catalog == null || item == null || item.itemId <= 0) return null; try { return catalog.find(item.itemId, 0, "", ""); } catch (Throwable ignored) { return null; } }
    private String displayName(KitchenOrder.Item item, ProductCatalog.Product product) {
        if (item != null) { String name = item.displayName(ar()); if (!name.equals(ar() ? "صنف" : "Item")) return name; }
        if (product != null) { String local = ar() ? clean(product.nameAr) : clean(product.nameEn); String alt = ar() ? clean(product.nameEn) : clean(product.nameAr); if (!local.isEmpty()) return local; if (!alt.isEmpty()) return alt; }
        return ar() ? "صنف" : "Item";
    }

    private void showProductImage(String path, String name) {
        if (shell == null || imageLoader == null || clean(path).isEmpty()) return; FrameLayout overlay = new FrameLayout(this); overlay.setBackgroundColor(0xF4000000); overlay.setClickable(true); overlay.setOnClickListener(v -> removeOverlay(overlay));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setPadding(dp(24), dp(24), dp(24), dp(78)); overlay.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); imageLoader.load(path, image, null);
        TextView caption = label(name, 17, Color.WHITE, true); caption.setGravity(Gravity.CENTER); caption.setBackgroundColor(0xB0000000); overlay.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM)); shell.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); overlay.setAlpha(0f); overlay.animate().alpha(1f).setDuration(160).start(); handler.postDelayed(() -> removeOverlay(overlay), 4300L);
    }
    private void removeOverlay(View overlay) { if (shell != null && overlay != null && overlay.getParent() == shell) overlay.animate().alpha(0f).setDuration(150).withEndAction(() -> { try { shell.removeView(overlay); } catch (Throwable ignored) { } }).start(); }

    private void advance(KitchenOrder order) {
        if (store == null || order == null) return;
        if (order.kitchenStatus == KitchenOrder.Status.NEW) { store.setStatus(order.id, KitchenOrder.Status.PREPARING); beep(false); }
        else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) { store.setStatus(order.id, KitchenOrder.Status.READY); beep(true); }
        renderBoard();
    }

    private void showTicketMenu(KitchenOrder order) {
        if (order == null) return; ArrayList<String> choices = new ArrayList<>(); if (order.kitchenStatus == KitchenOrder.Status.READY) choices.add(t("complete")); choices.add(ar() ? "إلغاء" : "Cancel"); String[] array = choices.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("#" + invoiceNumber(order)).setItems(array, (d, which) -> { if (order.kitchenStatus == KitchenOrder.Status.READY && which == 0) { store.setStatus(order.id, KitchenOrder.Status.DONE); renderBoard(); } }).show();
    }

    private void showOrderDetails(KitchenOrder order, boolean history) {
        StringBuilder out = new StringBuilder(); out.append("#").append(invoiceNumber(order)).append('\n'); String type = KitchenSignalV2.displayOrderType(order.orderType, ar()); if (!type.isEmpty()) out.append(type).append('\n'); if (!clean(order.table).isEmpty()) out.append(t("table")).append(" ").append(order.table).append('\n');
        if (history) { out.append(historyStamp(order)).append('\n'); out.append(t("duration")).append(": ").append(formatAge(finalDuration(order))).append('\n'); }
        out.append('\n'); for (KitchenOrder.Item item : order.items) { out.append(formatQty(item.qty)).append(" × ").append(displayName(item, product(item))).append('\n'); for (String modifier : item.modifiers) out.append("   + ").append(modifier).append('\n'); for (String removed : item.removed) out.append("   − ").append(removed).append('\n'); if (!clean(item.note).isEmpty()) out.append("   ").append(item.note).append('\n'); }
        if (!clean(order.customerNote).isEmpty()) out.append('\n').append(order.customerNote);
        new AlertDialog.Builder(this).setTitle("#" + invoiceNumber(order)).setMessage(out.toString()).setPositiveButton(ar() ? "إغلاق" : "Close", null).show();
    }

    @Override public void onConnected() { connectionOk = true; lastRawAt = System.currentTimeMillis(); runOnUiThread(() -> setConnection(t("connected"), true)); }
    @Override public void onDisconnected(String reason) { connectionOk = false; recordDiagnostic("disconnect", reason); runOnUiThread(() -> setConnection(t("reconnecting"), false)); }
    @Override public void onRaw(String raw) {
        if (raw == null) return; lastRawAt = System.currentTimeMillis();
        try {
            KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
            if (signal != null && signal.order != null) {
                String strictInvoice = StrictInvoiceExtractor.extract(raw); signal.order.displayNumber = strictInvoice; if (!strictInvoice.isEmpty()) signal.order.id = "invoice-" + strictInvoice;
            }
            processSignal(signal);
        } catch (Throwable error) { recordError("raw", error); }
    }
    @Override public void onOrder(OrderState order) { }
    @Override public void onDiagnostic(String stage, String detail) { recordDiagnostic(stage, detail); }

    private void processSignal(KitchenSignalV2.Signal signal) {
        if (signal == null || signal.parsed == null) return; KitchenOrderParser.Kind kind = signal.parsed.kind; KitchenOrder incoming = signal.order == null ? null : signal.order.copy(); long now = System.currentTimeMillis();
        if (kind == KitchenOrderParser.Kind.PAYMENT) {
            lastPaymentAt = now; pendingSaved = null; pendingSavedAt = 0L; KitchenOrder existing = existingByInvoice(incoming);
            if (existing != null) { store.updatePayment(existing.id, incoming == null || clean(incoming.paymentStatus).isEmpty() ? "PAID" : incoming.paymentStatus); runOnUiThread(this::renderBoard); }
            liveDraft = null; return;
        }
        if (kind == KitchenOrderParser.Kind.CANCELLED) {
            KitchenOrder existing = existingByInvoice(incoming); if (existing != null) { store.cancel(existing.id); beepCancel(); runOnUiThread(this::renderBoard); }
            pendingSaved = null; liveDraft = null; return;
        }
        if (kind == KitchenOrderParser.Kind.SAVED) {
            KitchenOrder saved = bestPayload(incoming, liveDraft); if (saved == null || saved.items.isEmpty()) return; saved.temporaryOrder = true;
            if (hasInvoice(saved)) { commitInvoice(saved, false); pendingSaved = null; pendingSavedAt = 0L; }
            else { pendingSaved = saved.copy(); pendingSavedAt = now; }
            liveDraft = null; return;
        }
        if (kind == KitchenOrderParser.Kind.SNAPSHOT || kind == KitchenOrderParser.Kind.UPDATED) {
            if (incoming == null) return;
            if (hasInvoice(incoming)) {
                KitchenOrder existing = existingByInvoice(incoming);
                if (existing != null && !incoming.items.isEmpty()) { commitInvoice(incoming, true); liveDraft = incoming.copy(); return; }
                if (pendingSaved != null && now - pendingSavedAt <= PENDING_SAVE_WINDOW_MS) {
                    KitchenOrder merged = bestPayload(incoming, pendingSaved); merged.temporaryOrder = true; commitInvoice(merged, false); pendingSaved = null; pendingSavedAt = 0L; liveDraft = merged.copy(); return;
                }
            }
            if (incoming.items.isEmpty()) return; liveDraft = incoming.copy();
            if (pendingSaved != null && now - pendingSavedAt <= PENDING_SAVE_WINDOW_MS) pendingSaved = mergeDraft(pendingSaved, incoming);
            return;
        }
        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            if (now - lastPaymentAt >= 0L && now - lastPaymentAt < 7000L) { liveDraft = null; return; }
            if (hasInvoice(liveDraft)) { liveDraft.temporaryOrder = true; commitInvoice(liveDraft.copy(), false); }
            else { pendingSaved = liveDraft.copy(); pendingSaved.temporaryOrder = true; pendingSaved.inferredTemporarySave = true; pendingSavedAt = now; }
            liveDraft = null;
        }
    }

    private KitchenOrder bestPayload(KitchenOrder first, KitchenOrder fallback) {
        if (first == null && fallback == null) return null; if (first == null) return fallback.copy(); KitchenOrder out = first.copy();
        if (out.items.isEmpty() && fallback != null) { out.items.clear(); for (KitchenOrder.Item item : fallback.items) out.items.add(KitchenOrder.copyItem(item)); }
        if (!hasInvoice(out) && fallback != null && hasInvoice(fallback)) out.displayNumber = fallback.displayNumber;
        if (clean(out.table).isEmpty() && fallback != null) out.table = fallback.table; if (clean(out.orderType).isEmpty() && fallback != null) out.orderType = fallback.orderType; if (clean(out.customerNote).isEmpty() && fallback != null) out.customerNote = fallback.customerNote; return out;
    }

    private KitchenOrder mergeDraft(KitchenOrder base, KitchenOrder latest) {
        KitchenOrder out = base.copy(); if (latest == null) return out;
        if (!latest.items.isEmpty()) { out.items.clear(); for (KitchenOrder.Item item : latest.items) out.items.add(KitchenOrder.copyItem(item)); }
        if (hasInvoice(latest)) out.displayNumber = latest.displayNumber; if (!clean(latest.table).isEmpty()) out.table = latest.table; if (!clean(latest.orderType).isEmpty()) out.orderType = latest.orderType; if (!clean(latest.customerNote).isEmpty()) out.customerNote = latest.customerNote; return out;
    }

    private void commitInvoice(KitchenOrder order, boolean updateExistingOnly) {
        if (order == null || order.items.isEmpty() || !hasInvoice(order)) return; String number = invoiceNumber(order); order.displayNumber = number; order.id = "invoice-" + number; order.temporaryOrder = true;
        KitchenOrder before = store.findByNumber(number); if (updateExistingOnly && before == null) return; boolean changed = store.upsert(order); if (before == null) beepNew(); else if (changed) beepModified(); runOnUiThread(this::renderBoard);
    }

    private KitchenOrder existingByInvoice(KitchenOrder incoming) { if (store == null || incoming == null || !hasInvoice(incoming)) return null; return store.findByNumber(invoiceNumber(incoming)); }
    private boolean hasInvoice(KitchenOrder order) { return order != null && !invoiceNumber(order).isEmpty(); }
    private String invoiceNumber(KitchenOrder order) { if (order == null) return ""; return KitchenSignalV2.cleanIdentity(order.displayNumber); }

    private void restoreConnection() { String ip = pair == null ? "" : pair.getString("ip", ""); int port = pair == null ? 4040 : pair.getInt("port", 4040); if (ip.isEmpty()) setConnection(t("notPaired"), false); else connect(ip, port); }
    private void connect(String ip, int port) {
        try { pair.edit().putString("ip", ip).putInt("port", port).apply(); lastConnectAttemptAt = System.currentTimeMillis(); connectionOk = false; setConnection(t("connecting"), false); if (client != null) try { client.stop(); } catch (Throwable ignored) { } client = new TechProClient(ip, port, this); client.start(); }
        catch (Throwable error) { recordError("connect", error); setConnection(t("reconnecting"), false); }
    }

    private void showPairDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText ip = new EditText(this); ip.setHint("192.168.1.20"); ip.setSingleLine(true); ip.setText(pair.getString("ip", "")); form.addView(ip);
        EditText port = new EditText(this); port.setHint("4040"); port.setSingleLine(true); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText(String.valueOf(pair.getInt("port", 4040))); form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("pair")).setView(form).setPositiveButton(ar() ? "ربط" : "Connect", null).setNegativeButton(ar() ? "إلغاء" : "Cancel", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { String host = ip.getText().toString().trim(); int p = Integer.parseInt(port.getText().toString().trim()); if (host.isEmpty() || p < 1 || p > 65535) throw new IllegalArgumentException(); dialog.dismiss(); connect(host, p); } catch (Throwable error) { port.setError("IP / Port"); } })); dialog.show();
    }

    private void setConnection(String value, boolean ok) { connectionOk = ok; if (connectionText != null) { connectionText.setText(value); connectionText.setTextColor(ok ? green : muted); connectionText.setBackground(round(ok ? softGreen() : surface2, 10)); } }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this); LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(22), dp(10), dp(22), dp(12));
        panel.addView(label(t("language"), 15, text, true)); RadioGroup languages = new RadioGroup(this); languages.setOrientation(RadioGroup.HORIZONTAL); RadioButton arabic = new RadioButton(this); arabic.setText("العربية"); RadioButton english = new RadioButton(this); english.setText("English"); languages.addView(arabic); languages.addView(english); (ar() ? arabic : english).setChecked(true); panel.addView(languages);
        TextView ap = label(t("appearance"), 15, text, true); ap.setPadding(0, dp(10), 0, 0); panel.addView(ap); RadioGroup themes = new RadioGroup(this); themes.setOrientation(RadioGroup.HORIZONTAL); RadioButton darkBtn = new RadioButton(this); darkBtn.setText("Dark"); RadioButton lightBtn = new RadioButton(this); lightBtn.setText("Light"); themes.addView(darkBtn); themes.addView(lightBtn); (dark ? darkBtn : lightBtn).setChecked(true); panel.addView(themes);
        CheckBox images = new CheckBox(this); images.setText(t("images")); images.setChecked(settings.getBoolean("show_images", true)); panel.addView(images);
        TextView pairButton = action(t("pair"), false); pairButton.setOnClickListener(v -> showPairDialog()); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); pp.setMargins(0, dp(10), 0, 0); panel.addView(pairButton, pp);
        TextView clearButton = action(t("clear"), false); clearButton.setTextColor(red); clearButton.setOnClickListener(v -> showClearMenu()); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); cp.setMargins(0, dp(8), 0, 0); panel.addView(clearButton, cp); scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("TechPro Kitchen 4.2").setView(scroll).setPositiveButton(ar() ? "حفظ" : "Save", (d, w) -> { settings.edit().putString("language", english.isChecked() ? "en" : "ar").putString("theme", lightBtn.isChecked() ? "light" : "dark").putBoolean("show_images", images.isChecked()).apply(); applyPalette(); applySystemBars(); buildUi(); renderBoard(); setConnection(connectionOk ? t("connected") : t("reconnecting"), connectionOk); }).setNegativeButton(t("logout"), (d, w) -> { session.clear(); openLogin(); }).show();
    }

    private void showClearMenu() {
        String[] choices = ar() ? new String[]{"مسح الطلبات الحالية", "مسح السجل", "مسح الكل"} : new String[]{"Clear active orders", "Clear history", "Clear all"}; new AlertDialog.Builder(this).setTitle(t("clear")).setItems(choices, (d, which) -> confirmClear(which)).show();
    }
    private void confirmClear(int which) {
        EditText pin = new EditText(this); pin.setHint("PIN"); pin.setGravity(Gravity.CENTER); pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("clear")).setView(pin).setPositiveButton(t("clear"), null).setNegativeButton(ar() ? "إلغاء" : "Cancel", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { String saved = settings.getString("pin", "0000"); if (!saved.equals(pin.getText().toString())) { pin.setError(ar() ? "رمز غير صحيح" : "Wrong PIN"); return; } if (which == 0) store.clearActive(); else if (which == 1) store.clearHistory(); else store.clearAll(); pendingSaved = null; liveDraft = null; dialog.dismiss(); renderBoard(); Toast.makeText(this, ar() ? "تم المسح" : "Cleared", Toast.LENGTH_SHORT).show(); })); dialog.show();
    }

    private int statusColor(KitchenOrder.Status status) { if (status == KitchenOrder.Status.READY) return green; if (status == KitchenOrder.Status.PREPARING) return amber; if (status == KitchenOrder.Status.CANCELLED) return red; return blue; }
    private int statusFill(KitchenOrder.Status status) { if (status == KitchenOrder.Status.READY) return dark ? 0xFF15372D : 0xFFE4F8F0; if (status == KitchenOrder.Status.PREPARING) return dark ? 0xFF3C3018 : 0xFFFFF3D8; return dark ? 0xFF192D46 : 0xFFE8F1FE; }
    private String statusLabel(KitchenOrder.Status status) { if (status == KitchenOrder.Status.READY) return t("ready"); if (status == KitchenOrder.Status.PREPARING) return t("preparing"); return ar() ? "بانتظار التحضير" : "Waiting to prepare"; }
    private TextView alertBand(String value, int color) { TextView v = label(value, 11, color, true); v.setGravity(Gravity.CENTER); v.setPadding(dp(10), dp(6), dp(10), dp(6)); v.setBackground(round(dark ? 0xFF211B1E : 0xFFFFF0F1, 10)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp(4), 0, dp(4)); v.setLayoutParams(lp); return v; }
    private TextView chip(String value, int fill, int color) { TextView v = label(value, 11, color, true); v.setGravity(Gravity.CENTER); v.setPadding(dp(9), dp(4), dp(9), dp(4)); v.setBackground(round(fill, 10)); return v; }
    private TextView action(String value, boolean primary) { TextView v = label(value, 13, primary ? Color.WHITE : text, true); v.setGravity(Gravity.CENTER); v.setClickable(true); v.setFocusable(true); GradientDrawable shape = round(primary ? purple : surface2, 13); shape.setStroke(dp(1), primary ? purple : border); v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape, null)); v.setPadding(dp(10), 0, dp(10), 0); return v; }
    private TextView label(String value, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(value == null ? "" : value); v.setTextSize(size); v.setTextColor(color); v.setGravity((ar() ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private GradientDrawable round(int fill, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(radius)); return g; }
    private GradientDrawable cardBg(int fill, int radius, int stroke) { GradientDrawable g = round(fill, radius); g.setStroke(dp(1), stroke); return g; }
    private int softGreen() { return dark ? 0xFF173228 : 0xFFE7F8F1; }
    private int cardWidthDp() { int width = getResources().getConfiguration().screenWidthDp; if (width >= 1400) return 350; if (width >= 900) return 330; if (width >= 600) return 300; return Math.max(270, width - 36); }
    private boolean wide() { return getResources().getConfiguration().screenWidthDp >= 900; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private String formatAge(long ms) { long total = Math.max(0L, ms / 1000L); long h = total / 3600L; long m = (total % 3600L) / 60L; long s = total % 60L; if (h > 0) return String.format(Locale.US, "%02d:%02d:%02d", h, m, s); return String.format(Locale.US, "%02d:%02d", m, s); }
    private String formatQty(double value) { if (Math.abs(value - Math.rint(value)) < 0.0001) return String.valueOf((long) Math.rint(value)); return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private long finalDuration(KitchenOrder order) { if (order == null) return 0L; long end = order.updatedAt > 0L ? order.updatedAt : (order.readyAt > 0L ? order.readyAt : order.createdAt); return Math.max(0L, end - order.createdAt); }
    private long prepDuration(KitchenOrder order) { if (order == null || order.startedAt <= 0L) return 0L; long end = order.readyAt > 0L ? order.readyAt : order.updatedAt; return Math.max(0L, end - order.startedAt); }
    private String historyStamp(KitchenOrder order) { long at = order == null ? 0L : order.updatedAt; if (at <= 0L) return "—"; try { return new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US).format(new Date(at)); } catch (Throwable ignored) { return "—"; } }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private void beepNew() { try { if (tone == null) return; tone.startTone(ToneGenerator.TONE_PROP_ACK, 180); handler.postDelayed(() -> { try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 180); } catch (Throwable ignored) { } }, 240L); } catch (Throwable ignored) { } }
    private void beepModified() { try { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 220); } catch (Throwable ignored) { } }
    private void beepCancel() { try { if (tone != null) tone.startTone(ToneGenerator.TONE_SUP_ERROR, 420); } catch (Throwable ignored) { } }
    private void beepLate() { try { if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 420); } catch (Throwable ignored) { } }
    private void beep(boolean positive) { try { if (tone != null) tone.startTone(positive ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, 160); } catch (Throwable ignored) { } }
    private void recordDiagnostic(String stage, String detail) { if (diagnostics == null) return; diagnostics.edit().putString("stage", clean(stage)).putString("detail", clean(detail)).putLong("at", System.currentTimeMillis()).apply(); }
    private void recordError(String stage, Throwable error) { recordDiagnostic(stage, error == null ? "unknown" : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())); }
    private void showStartupError(Throwable error) { try { FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(0xFF090B0F); TextView message = new TextView(this); message.setText("TechPro Kitchen\n\n" + (error == null ? "Startup error" : String.valueOf(error.getMessage()))); message.setTextColor(Color.WHITE); message.setTextSize(18); message.setGravity(Gravity.CENTER); frame.addView(message, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); setContentView(frame); } catch (Throwable ignored) { } }

    @Override protected void onDestroy() {
        handler.removeCallbacks(secondTick); handler.removeCallbacks(watchdog); try { if (client != null) client.stop(); } catch (Throwable ignored) { } try { if (tone != null) tone.release(); } catch (Throwable ignored) { } try { if (imageLoader != null) imageLoader.shutdown(); } catch (Throwable ignored) { } try { if (catalog != null) catalog.close(); } catch (Throwable ignored) { } super.onDestroy();
    }
}
