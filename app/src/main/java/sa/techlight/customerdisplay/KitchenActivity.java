package sa.techlight.customerdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
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

import java.util.List;
import java.util.Locale;

/**
 * TechPro Kitchen / TechLight KDS.
 *
 * Important policy:
 *  - live cashier cart snapshots are treated as drafts, not kitchen orders.
 *  - an explicit temporary-save event is queued immediately.
 *  - as a compatibility fallback, a cart clear without a nearby payment event queues the last draft.
 *  - payment updates an existing kitchen order only; it never creates a duplicate order.
 */
public final class KitchenActivity extends Activity implements TechProClient.Listener {
    private static final int BG = 0xFF101217;
    private static final int SURFACE = 0xFF191D24;
    private static final int TEXT = 0xFFF5F7FA;
    private static final int MUTED = 0xFF9EA7B3;
    private static final int ACCENT = 0xFF7A35C5;
    private static final int GREEN = 0xFF2CB67D;
    private static final int ORANGE = 0xFFF59E0B;
    private static final int RED = 0xFFEF4444;
    private static final long INFER_SAVE_DELAY_MS = 2200L;
    private static final long PAYMENT_GUARD_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            renderBoard(false);
            handler.postDelayed(this, 15000L);
        }
    };

    private FrameLayout shell;
    private GridLayout board;
    private TextView connectionText;
    private TextView newCount;
    private TextView prepCount;
    private TextView readyCount;
    private TextView emptyTitle;
    private TextView emptySub;
    private LinearLayout emptyState;
    private TechProSession session;
    private KitchenOrderStore store;
    private TechProClient client;
    private SharedPreferences pair;
    private SharedPreferences settings;
    private ToneGenerator tone;
    private KitchenOrder liveDraft;
    private Runnable pendingInferredSave;
    private long lastPaymentSignalAt;
    private String lastEvent = "";

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
        store = new KitchenOrderStore(this);
        pair = getSharedPreferences("kitchen_pair", MODE_PRIVATE);
        settings = getSharedPreferences("kitchen_settings", MODE_PRIVATE);
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85); }
        catch (Throwable ignored) { tone = null; }
        buildUi();
        renderBoard(true);
        restoreConnection();
        handler.postDelayed(clockTick, 15000L);
    }

    private void buildUi() {
        shell = new FrameLayout(this);
        shell.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        header.setPadding(dp(18), dp(10), dp(18), dp(10));
        header.setBackground(round(SURFACE, 22));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.techlight_mark);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, dp(12), 0);
        TextView title = text("TechPro Kitchen", wide() ? 23 : 19, TEXT, true);
        TextView branch = text(session.accountName().isEmpty() ? "شاشة المطبخ" : session.accountName(), 12, MUTED, false);
        titleBox.addView(title);
        titleBox.addView(branch);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout counts = new LinearLayout(this);
        counts.setOrientation(LinearLayout.HORIZONTAL);
        counts.setGravity(Gravity.CENTER_VERTICAL);
        counts.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        newCount = countChip(counts, "جديد", "0", 0xFF3B3150);
        prepCount = countChip(counts, "تحضير", "0", 0xFF4A3B20);
        readyCount = countChip(counts, "جاهز", "0", 0xFF173A31);
        header.addView(counts);

        LinearLayout connectionBox = new LinearLayout(this);
        connectionBox.setOrientation(LinearLayout.VERTICAL);
        connectionBox.setGravity(Gravity.CENTER);
        connectionBox.setPadding(dp(14), 0, dp(8), 0);
        connectionText = text("غير مرتبط", 12, MUTED, true);
        connectionText.setGravity(Gravity.CENTER);
        TextView event = text("", 9, 0xFF6F7884, false);
        event.setTag("event_label");
        event.setGravity(Gravity.CENTER);
        connectionBox.addView(connectionText);
        connectionBox.addView(event);
        header.addView(connectionBox);

        TextView settingsButton = pill("الإعدادات", false);
        settingsButton.setOnClickListener(view -> requestSettingsPin());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(104), dp(44)));
        root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(74)));

        TextView help = text("الحفظ المؤقت يذهب للمطبخ • السداد لا يعيد إرسال الطلب • اضغط زر البطاقة لتغيير الحالة", 11, MUTED, false);
        help.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        help.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(help, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        board = new GridLayout(this);
        board.setUseDefaultMargins(false);
        board.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        board.setPadding(0, dp(2), 0, dp(24));
        scroll.addView(board, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        content.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        ImageView emptyMark = new ImageView(this);
        emptyMark.setImageResource(R.drawable.techlight_mark);
        emptyMark.setAlpha(0.34f);
        emptyMark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        emptyState.addView(emptyMark, new LinearLayout.LayoutParams(dp(100), dp(100)));
        emptyTitle = text("بانتظار الطلبات المحفوظة", wide() ? 25 : 20, TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(4));
        emptyState.addView(emptyTitle);
        emptySub = text("لن تظهر سلة الكاشير المباشرة هنا. يظهر الطلب بعد الحفظ المؤقت فقط.", 13, MUTED, false);
        emptySub.setGravity(Gravity.CENTER);
        emptyState.addView(emptySub);
        TextView pairNow = pill("ربط TechPro", true);
        pairNow.setOnClickListener(view -> showPairDialog());
        LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(dp(180), dp(50));
        pairParams.setMargins(0, dp(18), 0, 0);
        emptyState.addView(pairNow, pairParams);
        content.addView(emptyState, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));
        shell.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(shell);
    }

    private TextView countChip(LinearLayout parent, String label, String value, int fill) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chip.setBackground(round(fill, 14));
        TextView number = text(value, 18, TEXT, true);
        number.setGravity(Gravity.CENTER);
        TextView name = text(label, 9, MUTED, false);
        name.setGravity(Gravity.CENTER);
        chip.addView(number);
        chip.addView(name);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(wide() ? 74 : 62), dp(52));
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(chip, params);
        return number;
    }

    private void renderBoard(boolean force) {
        if (board == null || store == null) return;
        List<KitchenOrder> orders = store.active();
        int columns = columns();
        board.setColumnCount(columns);
        board.removeAllViews();
        long now = System.currentTimeMillis();
        for (KitchenOrder order : orders) {
            View card = orderCard(order, now);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            board.addView(card, params);
        }
        emptyState.setVisibility(orders.isEmpty() ? View.VISIBLE : View.GONE);
        newCount.setText(String.valueOf(store.count(KitchenOrder.Status.NEW)));
        prepCount.setText(String.valueOf(store.count(KitchenOrder.Status.PREPARING)));
        readyCount.setText(String.valueOf(store.count(KitchenOrder.Status.READY)));
    }

    private View orderCard(KitchenOrder order, long now) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        int border = statusColor(order, now);
        GradientDrawable background = round(SURFACE, 20);
        background.setStroke(dp(2), border);
        card.setBackground(background);
        card.setElevation(dp(3));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnLongClickListener(view -> {
            showOrderActions(order);
            return true;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView number = text("#" + empty(order.bestNumber(), "بدون رقم"), wide() ? 22 : 18, TEXT, true);
        top.addView(number, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView age = text(formatAge(now - order.createdAt), 12, border, true);
        age.setGravity(Gravity.CENTER);
        age.setBackground(round(0xFF11141A, 12));
        age.setPadding(dp(8), dp(4), dp(8), dp(4));
        top.addView(age);
        card.addView(top);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView table = text(order.table.isEmpty() ? orderTypeLabel(order) : "طاولة " + order.table,
                wide() ? 20 : 17, 0xFFE8D7FF, true);
        table.setPadding(0, dp(4), 0, dp(4));
        meta.addView(table, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!order.orderType.isEmpty() && !order.table.isEmpty()) {
            TextView type = miniChip(order.orderType, 0xFF2D2440, 0xFFD9C4F2);
            meta.addView(type);
        }
        card.addView(meta);

        if (order.changedAt > 0 && now - order.changedAt < 18000L) {
            TextView changed = miniChip("تم تعديل الطلب الآن", 0xFF4B2F13, 0xFFFFCC80);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
            p.setMargins(0, dp(2), 0, dp(6));
            card.addView(changed, p);
        }
        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) {
            TextView cancelled = miniChip("⚠ تم إلغاء الطلب من الكاشير", 0xFF4A191D, 0xFFFFA6AD);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
            p.setMargins(0, dp(2), 0, dp(8));
            card.addView(cancelled, p);
        }

        View divider = new View(this);
        divider.setBackgroundColor(0xFF323843);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(4), 0, dp(7));
        card.addView(divider, dividerParams);

        int maxRows = wide() ? 10 : 7;
        for (int i = 0; i < order.items.size() && i < maxRows; i++) addItem(card, order.items.get(i));
        if (order.items.size() > maxRows) {
            TextView more = text("+ " + (order.items.size() - maxRows) + " أصناف أخرى", 12, MUTED, true);
            more.setPadding(0, dp(4), 0, dp(4));
            card.addView(more);
        }

        if (!order.customerNote.isEmpty()) {
            TextView note = text("ملاحظة الطلب: " + order.customerNote, 12, 0xFFFFE0A3, true);
            note.setPadding(dp(10), dp(8), dp(10), dp(8));
            note.setBackground(round(0xFF372D1D, 12));
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            noteParams.setMargins(0, dp(8), 0, dp(4));
            card.addView(note, noteParams);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView state = miniChip(statusArabic(order.kitchenStatus), statusFill(order.kitchenStatus), statusText(order.kitchenStatus));
        footer.addView(state);
        if (settings.getBoolean("show_payment", true)) {
            TextView paid = miniChip(order.isPaid() ? "مدفوع" : "غير مدفوع",
                    order.isPaid() ? 0xFF173A31 : 0xFF2B3038,
                    order.isPaid() ? 0xFF8CE7C2 : 0xFFB6BEC9);
            LinearLayout.LayoutParams paidParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            paidParams.setMargins(dp(6), 0, 0, 0);
            footer.addView(paid, paidParams);
        }
        card.addView(footer);

        TextView action = pill(actionLabel(order.kitchenStatus), true);
        action.setTag(order.id);
        action.setOnClickListener(view -> advanceOrder(order));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        actionParams.setMargins(0, dp(10), 0, 0);
        card.addView(action, actionParams);
        return card;
    }

    private void addItem(LinearLayout card, KitchenOrder.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView name = text(formatQty(item.qty) + " × " + empty(item.name, "صنف"), wide() ? 17 : 15, TEXT, true);
        row.addView(name);
        for (String modifier : item.modifiers) {
            TextView option = text("+ " + modifier, 12, 0xFF9DE2BF, true);
            option.setPadding(dp(16), dp(1), 0, dp(1));
            row.addView(option);
        }
        for (String removed : item.removed) {
            TextView option = text("− " + removed, 12, 0xFFFFA3AA, true);
            option.setPadding(dp(16), dp(1), 0, dp(1));
            row.addView(option);
        }
        if (!item.note.isEmpty()) {
            TextView note = text("ملاحظة: " + item.note, 12, 0xFFFFD694, false);
            note.setPadding(dp(16), dp(2), 0, dp(1));
            row.addView(note);
        }
        card.addView(row);
    }

    private void advanceOrder(KitchenOrder order) {
        if (order.kitchenStatus == KitchenOrder.Status.NEW) {
            store.setStatus(order.id, KitchenOrder.Status.PREPARING);
            beep(false);
        } else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) {
            store.setStatus(order.id, KitchenOrder.Status.READY);
            beep(true);
        } else if (order.kitchenStatus == KitchenOrder.Status.READY || order.kitchenStatus == KitchenOrder.Status.CANCELLED) {
            store.setStatus(order.id, KitchenOrder.Status.DONE);
        }
        renderBoard(true);
    }

    private void showOrderActions(KitchenOrder order) {
        String[] actions = {"جديد", "بدء التحضير", "جاهز", "إنهاء/إخفاء", "إلغاء محلي"};
        new AlertDialog.Builder(this)
                .setTitle("الطلب #" + order.bestNumber())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) store.setStatus(order.id, KitchenOrder.Status.NEW);
                    if (which == 1) store.setStatus(order.id, KitchenOrder.Status.PREPARING);
                    if (which == 2) store.setStatus(order.id, KitchenOrder.Status.READY);
                    if (which == 3) store.setStatus(order.id, KitchenOrder.Status.DONE);
                    if (which == 4) store.cancel(order.id);
                    renderBoard(true);
                }).show();
    }

    private void restoreConnection() {
        String ip = pair.getString("ip", "");
        int port = pair.getInt("port", 4040);
        if (ip.isEmpty()) {
            setConnection("غير مرتبط", false);
            showPairDialog();
        } else {
            connect(ip, port);
        }
    }

    private void connect(String ip, int port) {
        pair.edit().putString("ip", ip).putInt("port", port).apply();
        setConnection("جارٍ الاتصال", false);
        if (client != null) client.stop();
        client = new TechProClient(ip, port, this);
        client.start();
        if (emptyState != null) {
            emptyTitle.setText("جارٍ الاتصال بـ TechPro");
            emptySub.setText(ip + ":" + port);
        }
    }

    private void showPairDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), 0, dp(22), 0);
        EditText ip = new EditText(this);
        ip.setHint("IP الكاشير مثال 192.168.1.20");
        ip.setSingleLine(true);
        ip.setText(pair == null ? "" : pair.getString("ip", ""));
        form.addView(ip);
        EditText port = new EditText(this);
        port.setHint("Port");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(pair == null ? 4040 : pair.getInt("port", 4040)));
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ربط شاشة المطبخ مع TechPro")
                .setMessage("استخدم نفس IP والمنفذ اللذين تستخدمهما شاشة العميل. المنفذ الافتراضي 4040.")
                .setView(form)
                .setPositiveButton("ربط", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String host = ip.getText().toString().trim();
            if (host.isEmpty()) { ip.setError("اكتب IP"); return; }
            try {
                int value = Integer.parseInt(port.getText().toString().trim());
                if (value < 1 || value > 65535) throw new NumberFormatException();
                dialog.dismiss();
                connect(host, value);
            } catch (Exception error) {
                port.setError("منفذ غير صحيح");
            }
        }));
        dialog.show();
    }

    private void requestSettingsPin() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        pin.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(28), 0, dp(28), 0);
        box.addView(pin, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إعدادات المطبخ")
                .setView(box)
                .setPositiveButton("فتح", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String savedPin = settings.getString("pin", "0000");
            if (!savedPin.equals(pin.getText().toString())) {
                pin.setError("الرمز غير صحيح");
                return;
            }
            dialog.dismiss();
            showSettings();
        }));
        dialog.show();
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), dp(8));
        CheckBox infer = new CheckBox(this);
        infer.setText("استنتاج الحفظ المؤقت عند اختفاء السلة بدون دفع");
        infer.setChecked(settings.getBoolean("infer_temp_save", true));
        panel.addView(infer);
        CheckBox payment = new CheckBox(this);
        payment.setText("إظهار حالة الدفع داخل بطاقة المطبخ");
        payment.setChecked(settings.getBoolean("show_payment", true));
        panel.addView(payment);
        TextView info = text("آخر حدث: " + empty(lastEvent, "لا يوجد") + "\nالحساب: " + session.userName()
                + "\nالربط: " + pair.getString("ip", "—") + ":" + pair.getInt("port", 4040), 12, 0xFF333333, false);
        info.setPadding(0, dp(10), 0, dp(10));
        panel.addView(info);
        new AlertDialog.Builder(this)
                .setTitle("إعدادات TechPro Kitchen")
                .setView(panel)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    settings.edit().putBoolean("infer_temp_save", infer.isChecked())
                            .putBoolean("show_payment", payment.isChecked()).apply();
                    renderBoard(true);
                })
                .setNeutralButton("تغيير الربط", (dialog, which) -> handler.post(this::showPairDialog))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    @Override public void onConnected() {
        setConnection("متصل", true);
        if (emptyState != null && store.active().isEmpty()) {
            emptyTitle.setText("بانتظار الطلبات المحفوظة");
            emptySub.setText("أضف الطلب في الكاشير ثم اضغط حفظ مؤقت. السداد لن ينشئ طلبًا جديدًا هنا.");
        }
    }

    @Override public void onDisconnected(String reason) {
        setConnection("إعادة الاتصال", false);
    }

    @Override public void onRaw(String raw) {
        KitchenOrderParser.ParsedEvent event = KitchenOrderParser.parse(raw);
        lastEvent = event.kind.name() + (event.eventName.isEmpty() ? "" : " • " + event.eventName);
        updateEventLabel();
        processEvent(event);
    }

    @Override public void onOrder(OrderState order) {
        // The richer kitchen parser consumes onRaw(). We intentionally do not enqueue the
        // Customer Display OrderState because it cannot prove that the cashier pressed temporary save.
    }

    @Override public void onDiagnostic(String stage, String detail) {
        // Keep diagnostics intentionally lightweight on the production kitchen screen.
    }

    private void processEvent(KitchenOrderParser.ParsedEvent event) {
        long now = System.currentTimeMillis();
        KitchenOrder incoming = event.order;
        KitchenOrder existing = resolveExisting(incoming);

        if (event.kind == KitchenOrderParser.Kind.PAYMENT) {
            lastPaymentSignalAt = now;
            cancelPendingInferredSave();
            if (existing != null) {
                store.updatePayment(existing.id,
                        incoming == null || incoming.paymentStatus.isEmpty() ? "PAID" : incoming.paymentStatus);
                renderBoard(true);
            }
            liveDraft = null;
            return;
        }

        if (event.kind == KitchenOrderParser.Kind.CANCELLED) {
            cancelPendingInferredSave();
            if (existing != null) {
                store.cancel(existing.id);
                beepCancel();
                renderBoard(true);
            }
            liveDraft = null;
            return;
        }

        if (event.kind == KitchenOrderParser.Kind.SAVED && incoming != null && !incoming.items.isEmpty()) {
            cancelPendingInferredSave();
            enqueueSaved(incoming, false);
            liveDraft = null;
            return;
        }

        if ((event.kind == KitchenOrderParser.Kind.UPDATED || event.kind == KitchenOrderParser.Kind.SNAPSHOT)
                && incoming != null && !incoming.items.isEmpty()) {
            if (existing != null) {
                incoming.id = existing.id;
                boolean changed = store.upsert(incoming);
                if (changed) beepModification();
                renderBoard(true);
            } else {
                liveDraft = incoming.copy();
            }
            return;
        }

        if (event.kind == KitchenOrderParser.Kind.CLEARED) {
            if (!settings.getBoolean("infer_temp_save", true)) {
                liveDraft = null;
                return;
            }
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            scheduleInferredSave(liveDraft.copy());
        }
    }

    private void scheduleInferredSave(KitchenOrder draft) {
        cancelPendingInferredSave();
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

    private void cancelPendingInferredSave() {
        if (pendingInferredSave != null) handler.removeCallbacks(pendingInferredSave);
        pendingInferredSave = null;
    }

    private void enqueueSaved(KitchenOrder order, boolean inferred) {
        ensureIdentity(order);
        if (order.id.isEmpty()) return;
        KitchenOrder before = resolveExisting(order);
        order.inferredTemporarySave = inferred;
        boolean changed = store.upsert(order);
        if (before == null) beepNewOrder();
        else if (changed) beepModification();
        renderBoard(true);
    }

    private KitchenOrder resolveExisting(KitchenOrder incoming) {
        if (incoming == null || store == null) return null;
        if (!incoming.id.isEmpty()) {
            KitchenOrder byId = store.find(incoming.id);
            if (byId != null) return byId;
        }
        if (!incoming.displayNumber.isEmpty()) return store.findByNumber(incoming.displayNumber);
        return null;
    }

    private void ensureIdentity(KitchenOrder order) {
        if (!order.id.isEmpty()) return;
        if (!order.displayNumber.isEmpty()) {
            order.id = "invoice-" + order.displayNumber;
            return;
        }
        String seed = order.table + "|" + order.orderType + "|" + order.contentSignature();
        order.id = "local-" + Integer.toHexString(seed.hashCode()) + "-" + (System.currentTimeMillis() / 1000L);
    }

    private void setConnection(String label, boolean ok) {
        runOnUiThread(() -> {
            if (connectionText == null) return;
            connectionText.setText(label);
            connectionText.setTextColor(ok ? 0xFF79D9B2 : MUTED);
        });
    }

    private void updateEventLabel() {
        runOnUiThread(() -> {
            if (shell == null) return;
            View value = shell.findViewWithTag("event_label");
            if (value instanceof TextView) ((TextView) value).setText(lastEvent.length() > 24 ? lastEvent.substring(0, 24) : lastEvent);
        });
    }

    private int statusColor(KitchenOrder order, long now) {
        if (order.kitchenStatus == KitchenOrder.Status.CANCELLED) return RED;
        long age = now - order.createdAt;
        if (age >= 10 * 60 * 1000L) return RED;
        if (age >= 7 * 60 * 1000L) return ORANGE;
        if (order.kitchenStatus == KitchenOrder.Status.READY) return GREEN;
        if (order.kitchenStatus == KitchenOrder.Status.PREPARING) return ORANGE;
        return ACCENT;
    }

    private int statusFill(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.READY) return 0xFF173A31;
        if (status == KitchenOrder.Status.PREPARING) return 0xFF4A3B20;
        if (status == KitchenOrder.Status.CANCELLED) return 0xFF4A191D;
        return 0xFF332544;
    }

    private int statusText(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.READY) return 0xFF90E9C6;
        if (status == KitchenOrder.Status.PREPARING) return 0xFFFFD27C;
        if (status == KitchenOrder.Status.CANCELLED) return 0xFFFFA6AD;
        return 0xFFDCC5F4;
    }

    private String statusArabic(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.PREPARING) return "قيد التحضير";
        if (status == KitchenOrder.Status.READY) return "جاهز";
        if (status == KitchenOrder.Status.CANCELLED) return "ملغي";
        if (status == KitchenOrder.Status.DONE) return "منتهي";
        return "جديد";
    }

    private String actionLabel(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.PREPARING) return "جاهز ✓";
        if (status == KitchenOrder.Status.READY) return "تم التسليم";
        if (status == KitchenOrder.Status.CANCELLED) return "إخفاء الطلب الملغي";
        return "بدء التحضير";
    }

    private String orderTypeLabel(KitchenOrder order) {
        if (!order.orderType.isEmpty()) return order.orderType;
        return "طلب كاشير";
    }

    private void beepNewOrder() {
        if (tone != null) {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180);
            handler.postDelayed(() -> {
                try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 180); } catch (Throwable ignored) { }
            }, 240L);
        }
    }

    private void beepModification() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 220);
    }

    private void beepCancel() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_SUP_ERROR, 500);
    }

    private void beep(boolean positive) {
        if (tone != null) tone.startTone(positive ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, 160);
    }

    private TextView miniChip(String label, int fill, int color) {
        TextView view = text(label, 11, color, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setBackground(round(fill, 11));
        return view;
    }

    private TextView pill(String label, boolean primary) {
        TextView view = text(label, 13, primary ? Color.WHITE : 0xFFE0D7E8, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        GradientDrawable shape = round(primary ? ACCENT : 0xFF2B3038, 14);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
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
        if (width >= 1500) return 5;
        if (width >= 1100) return 4;
        if (width >= 760) return 3;
        if (width >= 520) return 2;
        return 1;
    }

    private String formatAge(long ageMs) {
        long seconds = Math.max(0, ageMs / 1000L);
        long minutes = seconds / 60L;
        long remain = seconds % 60L;
        if (minutes >= 60) return (minutes / 60) + "س " + (minutes % 60) + "د";
        return String.format(Locale.US, "%02d:%02d", minutes, remain);
    }

    private String formatQty(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String empty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        cancelPendingInferredSave();
        handler.removeCallbacks(clockTick);
        if (client != null) client.stop();
        if (tone != null) {
            try { tone.release(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }
}
