package sa.techlight.customerdisplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** TechPro account login dedicated to the kitchen app. Password is never persisted. */
public final class KitchenLoginActivity extends Activity {
    private static final int ACCENT = 0xFF4C8DFF;
    private TechProAccountClient accountClient;
    private TechProSession session;
    private ProductCatalog catalog;
    private EditText posCode;
    private EditText userName;
    private EditText password;
    private TextView action;
    private TextView status;
    private ProgressBar progress;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0B0D10);
        getWindow().setNavigationBarColor(0xFF0B0D10);
        accountClient = new TechProAccountClient();
        session = new TechProSession(this);
        catalog = new ProductCatalog(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0B0D10);
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER);
        stage.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        stage.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(stage);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(30), dp(30), dp(30), dp(30));
        GradientDrawable bg = round(0xFF15181D, 28);
        bg.setStroke(dp(1), 0xFF2A3038);
        card.setBackground(bg);
        card.setElevation(dp(12));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.techlight_brand_white_transparent);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        card.addView(logo, new LinearLayout.LayoutParams(dp(270), dp(78)));

        TextView heading = text("TechPro Kitchen", 29, Color.WHITE, true);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(12), 0, dp(2));
        card.addView(heading);
        TextView detail = text("سجّل بنفس حساب TechPro • Sign in with the same TechPro account", 14, 0xFFABB4C0, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(4), 0, dp(4), dp(20));
        card.addView(detail);

        posCode = input("كود نقطة البيع / POS Code");
        posCode.setText(session.posCode());
        card.addView(posCode, fieldParams(0));
        userName = input("اسم المستخدم / Username");
        userName.setText(session.userName());
        card.addView(userName, fieldParams(10));
        password = input("كلمة المرور / Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(password, fieldParams(10));

        action = text("تسجيل الدخول / Sign in", 15, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackground(round(ACCENT, 16));
        action.setOnClickListener(view -> submit());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        actionParams.setMargins(0, dp(14), 0, 0);
        card.addView(action, actionParams);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        progressParams.setMargins(0, dp(12), 0, 0);
        card.addView(progress, progressParams);

        status = text("كلمة المرور لا يتم حفظها على الجهاز.", 12, 0xFF98A1AD, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, 0);
        card.addView(status);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                getResources().getConfiguration().screenWidthDp < 700 ? LinearLayout.LayoutParams.MATCH_PARENT : dp(560),
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stage.addView(card, cardParams);
        setContentView(scroll);
    }

    private void submit() {
        if (busy) return;
        String point = posCode.getText().toString().trim();
        String user = userName.getText().toString().trim();
        String pass = password.getText().toString();
        if (point.isEmpty()) { posCode.setError("POS Code"); return; }
        if (user.isEmpty()) { userName.setError("Username"); return; }
        if (pass.isEmpty()) { password.setError("Password"); return; }
        setBusy(true, "جاري الاتصال بـ TechPro…");
        accountClient.login(point, user, pass, new TechProAccountClient.LoginListener() {
            @Override public void onSuccess(String token, String accountName) {
                password.setText("");
                try {
                    session.save(token, point, user, accountName);
                } catch (Exception error) {
                    showError("تعذّر حفظ جلسة TechPro بأمان");
                    return;
                }
                status.setText("تم تسجيل الدخول • جاري تحميل الأصناف والصور…");
                accountClient.syncCatalog(token, new TechProAccountClient.SyncListener() {
                    @Override public void onProgress(String message, int productsFound) {
                        status.setText(message + (productsFound > 0 ? " • " + productsFound : ""));
                        status.setTextColor(0xFFABB4C0);
                    }

                    @Override public void onSuccess(List<ProductCatalog.Product> products) {
                        try { catalog.replaceAll(products); }
                        catch (Throwable ignored) { }
                        launchKitchen();
                    }

                    @Override public void onFailure(String message, boolean unauthorized) {
                        if (unauthorized) {
                            session.clear();
                            showError(message);
                            return;
                        }
                        // Orders still work without catalog; only optional names/images may be incomplete.
                        status.setText("تم الدخول • تعذرت مزامنة الصور الآن، ويمكن متابعة التشغيل");
                        status.setTextColor(0xFFFFD27A);
                        status.postDelayed(KitchenLoginActivity.this::launchKitchen, 650L);
                    }
                });
            }

            @Override public void onFailure(String message) {
                showError(message);
            }
        });
    }

    private void launchKitchen() {
        status.setText("جاهز • Ready");
        status.setTextColor(0xFF7FE0B7);
        status.postDelayed(() -> {
            startActivity(new Intent(KitchenLoginActivity.this, KitchenActivityV2.class));
            finish();
        }, 300L);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        action.setEnabled(!value);
        action.setAlpha(value ? 0.55f : 1f);
        posCode.setEnabled(!value);
        userName.setEnabled(!value);
        password.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        status.setText(message);
        status.setTextColor(0xFFABB4C0);
    }

    private void showError(String message) {
        setBusy(false, message);
        status.setTextColor(0xFFFF9CA5);
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(0xFF7D8794);
        field.setTextColor(Color.WHITE);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = round(0xFF101318, 14);
        bg.setStroke(dp(1), 0xFF353C47);
        field.setBackground(bg);
        return field;
    }

    private LinearLayout.LayoutParams fieldParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.setMargins(0, dp(top), 0, 0);
        return params;
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

    @Override protected void onDestroy() {
        if (accountClient != null) accountClient.shutdown();
        if (catalog != null) catalog.close();
        super.onDestroy();
    }
}
