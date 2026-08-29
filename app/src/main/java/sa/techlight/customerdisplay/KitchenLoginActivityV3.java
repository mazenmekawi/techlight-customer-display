package sa.techlight.customerdisplay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Robust login screen. Successful authentication never closes the app on a later startup error. */
public final class KitchenLoginActivityV3 extends Activity {
    private static final String BRAND_LOGO = "https://images.leadconnectorhq.com/image/f_webp/q_80/r_1200/u_https%3A//assets.cdn.filesafe.space/RrpygctF85S4KPExuRDV/media/678e63b989e1f5731ca4114c.png";

    private final Handler handler = new Handler();
    private TechProAccountClient accountClient;
    private TechProSession session;
    private ProductCatalog catalog;
    private ProductImageLoader brandLoader;
    private SharedPreferences settings;
    private EditText posCode;
    private EditText userName;
    private EditText password;
    private TextView action;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;
    private boolean dark;
    private int bg;
    private int surface;
    private int border;
    private int text;
    private int muted;
    private int input;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            settings = getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE);
            session = new TechProSession(this);
            accountClient = new TechProAccountClient();
            try { catalog = new ProductCatalog(this); } catch (Throwable ignored) { }
            try { brandLoader = new ProductImageLoader(this, ""); } catch (Throwable ignored) { }
            applyPalette();
            buildUi();
        } catch (Throwable error) {
            showSafeError(error);
        }
    }

    private boolean ar() {
        return !"en".equalsIgnoreCase(settings.getString("language", "ar"));
    }

    private int direction() {
        return ar() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    private void applyPalette() {
        dark = !"light".equalsIgnoreCase(settings.getString("theme", "dark"));
        if (dark) {
            bg = 0xFF0B0D10;
            surface = 0xFF15181D;
            border = 0xFF2B323B;
            text = 0xFFF7F9FC;
            muted = 0xFFA2ACB8;
            input = 0xFF0F1216;
        } else {
            bg = 0xFFF3F6F9;
            surface = 0xFFFFFFFF;
            border = 0xFFD9E0E7;
            text = 0xFF15202B;
            muted = 0xFF667587;
            input = 0xFFF8FAFC;
        }
        try {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            int flags = 0;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    private String t(String key) {
        boolean a = ar();
        switch (key) {
            case "title": return "TechPro Kitchen";
            case "subtitle": return a ? "سجّل بنفس حساب TechPro" : "Sign in with the same TechPro account";
            case "pos": return a ? "كود نقطة البيع" : "POS Code";
            case "user": return a ? "اسم المستخدم" : "Username";
            case "pass": return a ? "كلمة المرور" : "Password";
            case "sign": return a ? "تسجيل الدخول" : "Sign in";
            case "connecting": return a ? "جاري الاتصال بـ TechPro…" : "Connecting to TechPro…";
            case "sync": return a ? "تم الدخول • جاري مزامنة الأصناف والصور…" : "Signed in • Syncing products and images…";
            case "ready": return a ? "تم تسجيل الدخول بنجاح" : "Signed in successfully";
            case "passwordNote": return a ? "كلمة المرور لا يتم حفظها على الجهاز." : "Your password is never stored on this device.";
            case "continue": return a ? "متابعة بدون الصور" : "Continue without images";
            case "arabic": return "العربية";
            case "english": return "English";
            case "dark": return a ? "داكن" : "Dark";
            case "light": return a ? "فاتح" : "Light";
            default: return key;
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER);
        stage.setLayoutDirection(direction());
        stage.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(stage);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setLayoutDirection(direction());
        card.setPadding(dp(28), dp(24), dp(28), dp(28));
        GradientDrawable cardBg = round(surface, 28);
        cardBg.setStroke(dp(1), border);
        card.setBackground(cardBg);
        card.setElevation(dp(dark ? 10 : 5));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setLayoutDirection(direction());
        TextView arabic = smallAction("العربية", ar());
        TextView english = smallAction("English", !ar());
        TextView theme = smallAction(dark ? t("dark") : t("light"), true);
        arabic.setOnClickListener(v -> changeLanguage("ar"));
        english.setOnClickListener(v -> changeLanguage("en"));
        theme.setOnClickListener(v -> changeTheme(dark ? "light" : "dark"));
        controls.addView(arabic, controlParams());
        controls.addView(english, controlParams());
        controls.addView(theme, controlParams());
        card.addView(controls, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        FrameLayout logoTile = new FrameLayout(this);
        logoTile.setBackground(round(Color.WHITE, 20));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.techlight_mark);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        logoTile.addView(logo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(128), dp(128));
        logoParams.setMargins(0, dp(15), 0, dp(8));
        card.addView(logoTile, logoParams);
        if (brandLoader != null) brandLoader.load(BRAND_LOGO, logo, null);

        TextView heading = label(t("title"), 29, text, true);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        TextView sub = label(t("subtitle"), 14, muted, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(4), dp(2), dp(4), dp(20));
        card.addView(sub);

        posCode = input(t("pos"));
        posCode.setText(session.posCode());
        card.addView(posCode, fieldParams(0));
        userName = input(t("user"));
        userName.setText(session.userName());
        card.addView(userName, fieldParams(10));
        password = input(t("pass"));
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(password, fieldParams(10));

        action = label(t("sign"), 15, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackground(round(0xFF1769E0, 16));
        action.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        actionParams.setMargins(0, dp(14), 0, 0);
        card.addView(action, actionParams);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        progressParams.setMargins(0, dp(12), 0, 0);
        card.addView(progress, progressParams);

        status = label(t("passwordNote"), 12, muted, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, 0);
        card.addView(status);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                getResources().getConfiguration().screenWidthDp < 700 ? LinearLayout.LayoutParams.MATCH_PARENT : dp(560),
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stage.addView(card, cp);
        setContentView(scroll);
    }

    private void changeLanguage(String value) {
        if (busy) return;
        settings.edit().putString("language", value).apply();
        buildUi();
    }

    private void changeTheme(String value) {
        if (busy) return;
        settings.edit().putString("theme", value).apply();
        applyPalette();
        buildUi();
    }

    private void submit() {
        if (busy) return;
        String point = posCode.getText().toString().trim();
        String user = userName.getText().toString().trim();
        String pass = password.getText().toString();
        if (point.isEmpty()) { posCode.setError(t("pos")); return; }
        if (user.isEmpty()) { userName.setError(t("user")); return; }
        if (pass.isEmpty()) { password.setError(t("pass")); return; }

        setBusy(true, t("connecting"));
        accountClient.login(point, user, pass, new TechProAccountClient.LoginListener() {
            @Override public void onSuccess(String token, String accountName) {
                password.setText("");
                try {
                    session.save(token, point, user, accountName);
                } catch (Throwable error) {
                    showError((ar() ? "نجح الدخول لكن تعذر حفظ الجلسة: " : "Login succeeded but session storage failed: ")
                            + error.getClass().getSimpleName());
                    return;
                }
                status.setText(t("sync"));
                status.setTextColor(muted);
                syncCatalog(token);
            }

            @Override public void onFailure(String message) {
                showError(message);
            }
        });
    }

    private void syncCatalog(String token) {
        try {
            accountClient.syncCatalog(token, new TechProAccountClient.SyncListener() {
                @Override public void onProgress(String message, int productsFound) {
                    status.setText(message + (productsFound > 0 ? " • " + productsFound : ""));
                    status.setTextColor(muted);
                }

                @Override public void onSuccess(List<ProductCatalog.Product> products) {
                    try { if (catalog != null) catalog.replaceAll(products); } catch (Throwable ignored) { }
                    launchKitchen();
                }

                @Override public void onFailure(String message, boolean unauthorized) {
                    if (unauthorized) {
                        session.clear();
                        showError(message);
                        return;
                    }
                    // Authentication is already valid. Keep the app open and provide a deliberate continue path.
                    setBusy(false, ar() ? "تم تسجيل الدخول، لكن تعذرت مزامنة الصور الآن." : "Signed in, but image sync is unavailable right now.");
                    TextView continueButton = label(t("continue"), 13, 0xFF1769E0, true);
                    continueButton.setGravity(Gravity.CENTER);
                    continueButton.setPadding(0, dp(10), 0, 0);
                    continueButton.setClickable(true);
                    continueButton.setOnClickListener(v -> launchKitchen());
                    if (status.getParent() instanceof LinearLayout) ((LinearLayout) status.getParent()).addView(continueButton);
                }
            });
        } catch (Throwable error) {
            setBusy(false, ar() ? "تم الدخول، وتعذرت المزامنة فقط." : "Signed in; only catalog sync failed.");
            handler.postDelayed(this::launchKitchen, 700L);
        }
    }

    private void launchKitchen() {
        if (isFinishing()) return;
        status.setText(t("ready"));
        status.setTextColor(0xFF16865D);
        setBusy(true, t("ready"));
        handler.postDelayed(() -> {
            try {
                Intent intent = new Intent(KitchenLoginActivityV3.this, KitchenActivityV3.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                // Do not call finish immediately. If startup fails on broken firmware, login remains in back stack.
                handler.postDelayed(() -> {
                    try { if (!isFinishing()) finish(); } catch (Throwable ignored) { }
                }, 1200L);
            } catch (Throwable error) {
                showError((ar() ? "تعذر فتح شاشة المطبخ: " : "Could not open kitchen screen: ") + error.getClass().getSimpleName());
            }
        }, 250L);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (action != null) {
            action.setEnabled(!value);
            action.setAlpha(value ? 0.55f : 1f);
        }
        if (posCode != null) posCode.setEnabled(!value);
        if (userName != null) userName.setEnabled(!value);
        if (password != null) password.setEnabled(!value);
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (status != null) {
            status.setText(message);
            status.setTextColor(muted);
        }
    }

    private void showError(String message) {
        setBusy(false, message == null ? "TechPro" : message);
        status.setTextColor(0xFFD1434D);
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(dark ? 0xFF778392 : 0xFF8794A3);
        field.setTextColor(text);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable b = round(input, 14);
        b.setStroke(dp(1), border);
        field.setBackground(b);
        return field;
    }

    private TextView smallAction(String value, boolean selected) {
        TextView v = label(value, 12, selected ? Color.WHITE : text, true);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        GradientDrawable b = round(selected ? 0xFF1769E0 : input, 12);
        b.setStroke(dp(1), selected ? 0xFF1769E0 : border);
        v.setBackground(b);
        return v;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(36));
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private LinearLayout.LayoutParams fieldParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showSafeError(Throwable error) {
        try {
            FrameLayout shell = new FrameLayout(this);
            shell.setBackgroundColor(0xFF0B0D10);
            TextView message = new TextView(this);
            message.setText("TechPro Kitchen\n\n" + (error == null ? "Startup error" : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
            message.setTextColor(Color.WHITE);
            message.setTextSize(18);
            message.setGravity(Gravity.CENTER);
            shell.addView(message, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(shell);
        } catch (Throwable ignored) { }
    }

    @Override protected void onDestroy() {
        try { if (accountClient != null) accountClient.shutdown(); } catch (Throwable ignored) { }
        try { if (catalog != null) catalog.close(); } catch (Throwable ignored) { }
        try { if (brandLoader != null) brandLoader.shutdown(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
