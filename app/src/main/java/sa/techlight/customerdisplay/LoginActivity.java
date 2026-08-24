package sa.techlight.customerdisplay;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class LoginActivity extends Activity {
    private static final int ACCENT = 0xFF4D0E81;

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
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF160C20);
        accountClient = new TechProAccountClient();
        session = new TechProSession(this);
        catalog = new ProductCatalog(this);
        buildUi();
        if (getIntent().getBooleanExtra("resync", false)) {
            String token = session.token();
            if (token == null) {
                showError("انتهت جلسة TechPro. سجّل الدخول مرة أخرى.");
            } else {
                setBusy(true, "جاري تحديث دليل الأصناف…");
                sync(token, true);
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        return view;
    }

    private void buildUi() {
        boolean compact = getResources().getConfiguration().screenWidthDp < 700;
        FrameLayout shell = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF160C20, 0xFF4D256B, 0xFF7E47A8}
        );
        shell.setBackground(background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER);
        stage.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        stage.setPadding(dp(compact ? 18 : 54), dp(28), dp(compact ? 18 : 54), dp(28));
        scroll.addView(stage);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(compact ? 22 : 38), dp(compact ? 24 : 34), dp(compact ? 22 : 38), dp(compact ? 24 : 34));
        GradientDrawable cardBg = round(0xFFFDFBFF, 30);
        cardBg.setStroke(dp(1), 0x30FFFFFF);
        card.setBackground(cardBg);
        card.setElevation(dp(14));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_techlight);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        card.addView(logo, new LinearLayout.LayoutParams(dp(compact ? 76 : 92), dp(compact ? 76 : 92)));

        TextView heading = text("تسجيل شاشة العميل", compact ? 25 : 30, 0xFF241D29);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(8), dp(13), dp(8), dp(3));
        card.addView(heading);

        TextView detail = text("سجّل بنفس حساب TechPro لتحميل أسماء الأصناف والأسعار، ثم اربط الشاشة بالـQR.", compact ? 13 : 15, 0xFF746D79);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(6), 0, dp(6), dp(18));
        card.addView(detail);

        posCode = input("كود النقطة", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        posCode.setText(session.posCode());
        card.addView(posCode, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        userName = input("اسم المستخدم", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        userName.setText(session.userName());
        LinearLayout.LayoutParams userParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        userParams.setMargins(0, dp(10), 0, 0);
        card.addView(userName, userParams);

        password = input("كلمة المرور", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        passwordParams.setMargins(0, dp(10), 0, 0);
        card.addView(password, passwordParams);

        CheckBox showPassword = new CheckBox(this);
        showPassword.setText("إظهار كلمة المرور");
        showPassword.setTextSize(13);
        showPassword.setTextColor(0xFF625B67);
        showPassword.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, 0xFFAAA3AE}
        ));
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            int position = password.getSelectionStart();
            password.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            password.setSelection(Math.max(0, position));
        });
        card.addView(showPassword);

        action = text("تسجيل الدخول وتحميل الأصناف", 15, Color.WHITE);
        action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        action.setGravity(Gravity.CENTER);
        action.setClickable(true);
        action.setFocusable(true);
        action.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                round(ACCENT, 18),
                null
        ));
        action.setOnClickListener(view -> submit());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        actionParams.setMargins(0, dp(7), 0, 0);
        card.addView(action, actionParams);

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        progressParams.setMargins(0, dp(14), 0, 0);
        card.addView(progress, progressParams);

        status = text("لن يتم حفظ كلمة المرور على الجهاز.", 12, 0xFF817985);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(4), dp(11), dp(4), 0);
        card.addView(status);

        TextView note = text("تطبيق شاشة العميل فقط • لا يحتوي وضع الكاشير", 11, 0xFF9B93A0);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        card.addView(note);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                compact ? LinearLayout.LayoutParams.MATCH_PARENT : dp(560),
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stage.addView(card, cardParams);
        shell.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(shell);

        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start();
    }

    private EditText input(String hint, int type) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(0xFF9A929E);
        field.setTextColor(0xFF2A242D);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = round(Color.WHITE, 16);
        bg.setStroke(dp(1), 0xFFE0D9E5);
        field.setBackground(bg);
        return field;
    }

    private void submit() {
        if (busy) return;
        String point = posCode.getText().toString().trim();
        String user = userName.getText().toString().trim();
        String pass = password.getText().toString();
        if (point.isEmpty()) {
            posCode.setError("اكتب كود النقطة");
            return;
        }
        if (user.isEmpty()) {
            userName.setError("اكتب اسم المستخدم");
            return;
        }
        if (pass.isEmpty()) {
            password.setError("اكتب كلمة المرور");
            return;
        }
        setBusy(true, "جاري تسجيل الدخول إلى TechPro…");
        accountClient.login(point, user, pass, new TechProAccountClient.LoginListener() {
            @Override public void onSuccess(String token, String accountName) {
                password.setText("");
                try {
                    session.save(token, point, user, accountName);
                } catch (Exception error) {
                    showError("تعذّر حفظ جلسة TechPro بأمان على الجهاز");
                    return;
                }
                status.setText("تم تسجيل الدخول • جاري تحميل دليل الأصناف…");
                sync(token, false);
            }

            @Override public void onFailure(String message) {
                showError(message);
            }
        });
    }

    private void sync(String token, boolean resyncOnly) {
        accountClient.syncCatalog(token, new TechProAccountClient.SyncListener() {
            @Override public void onProgress(String message, int productsFound) {
                status.setText(message + (productsFound > 0 ? " • " + productsFound + " سجل" : ""));
                status.setTextColor(0xFF625B67);
            }

            @Override public void onSuccess(List<ProductCatalog.Product> products) {
                int count;
                try {
                    count = catalog.replaceAll(products);
                } catch (Exception error) {
                    showError("وصلت الأصناف لكن تعذّر حفظها على الجهاز");
                    return;
                }
                status.setText("تمت مزامنة " + count + " سجل بنجاح");
                status.setTextColor(0xFF137A55);
                if (resyncOnly) {
                    setResult(RESULT_OK);
                    status.postDelayed(LoginActivity.this::finish, 650);
                } else {
                    status.postDelayed(() -> {
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    }, 650);
                }
            }

            @Override public void onFailure(String message, boolean unauthorized) {
                if (unauthorized) session.clear();
                showError(message);
            }
        });
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
            status.setTextColor(0xFF625B67);
        }
    }

    private void showError(String message) {
        setBusy(false, message);
        status.setTextColor(0xFFB42318);
    }

    @Override protected void onDestroy() {
        if (accountClient != null) accountClient.shutdown();
        if (catalog != null) catalog.close();
        super.onDestroy();
    }
}
