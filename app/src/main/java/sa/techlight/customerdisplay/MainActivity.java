package sa.techlight.customerdisplay;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;

import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity implements TechProClient.Listener {
    private static final int CAMERA_REQ = 501;
    private static final int QR_IMAGE_REQ = 503;
    private static final int SETTINGS_REQ = 77;
    private static final long SETTINGS_VISIBLE_MS = 6500;

    private FrameLayout shell;
    private LinearLayout root;
    private LinearLayout orderList;
    private LinearLayout body;
    private TextView status;
    private TextView total;
    private TextView title;
    private TextView statusDot;
    private TextView settingsPill;
    private TextView itemCount;
    private LinearLayout statusChip;
    private TechProClient client;
    private AbleSignController able;
    private SharedPreferences ui;
    private SharedPreferences diagnostics;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int accent = Color.rgb(91, 42, 134);
    private boolean compact;
    private boolean paired;

    private final Runnable hideSettingsTask = this::hideSettingsButton;
    private final Runnable idleTask = () -> {
        if (able == null || !ui.getBoolean("able_idle", false)) return;
        if (!able.openPlayer()) showToast("AbleSign غير مثبت على الجهاز");
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        able = new AbleSignController(this);
        ui = getSharedPreferences("ui", 0);
        if (!ui.getBoolean("v6_idle_migration", false)) {
            ui.edit()
                    .putBoolean("able_idle", false)
                    .putBoolean("v6_idle_migration", true)
                    .apply();
        }
        diagnostics = getSharedPreferences("diagnostics", 0);
        buildUi();
        restoreOrPair();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) showSettingsButton();
        return super.dispatchTouchEvent(event);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable strokeBg(int color, int strokeColor, int radius) {
        GradientDrawable drawable = round(color, radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private Drawable pressable(int color, int strokeColor, int radius, int rippleColor) {
        return new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                strokeBg(color, strokeColor, radius),
                null
        );
    }

    private TextView action(String label, int iconRes, boolean primary) {
        TextView button = text(label, compact ? 14 : 16, primary ? Color.WHITE : accent);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(58));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setCompoundDrawablePadding(dp(10));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        Drawable icon = button.getCompoundDrawablesRelative()[0];
        if (icon != null) icon.mutate().setTint(primary ? Color.WHITE : accent);
        button.setBackground(primary
                ? pressable(accent, accent, 18, 0x33FFFFFF)
                : pressable(Color.WHITE, 0xFFE2DDE7, 18, 0x185B2A86));
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(2));
        return button;
    }

    private void buildUi() {
        handler.removeCallbacks(hideSettingsTask);
        paired = getSharedPreferences("pair", 0).contains("ip");
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int heightDp = getResources().getConfiguration().screenHeightDp;
        compact = widthDp < 700 || heightDp > widthDp;
        try {
            accent = Color.parseColor(ui.getString("color", "#5B2A86"));
        } catch (Exception ignored) {
            accent = Color.rgb(91, 42, 134);
        }

        shell = new FrameLayout(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(compact ? 14 : 26), dp(compact ? 12 : 18), dp(compact ? 14 : 26), dp(12));
        root.setBackgroundColor(0xFFF8F9FA);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), dp(8));

        if (!paired) {
            LinearLayout brand = new LinearLayout(this);
            brand.setOrientation(LinearLayout.HORIZONTAL);
            brand.setGravity(Gravity.CENTER_VERTICAL);
            ImageView techIcon = new ImageView(this);
            techIcon.setImageResource(R.drawable.ic_techlight);
            brand.addView(techIcon, new LinearLayout.LayoutParams(dp(compact ? 38 : 46), dp(compact ? 38 : 46)));
            LinearLayout brandText = new LinearLayout(this);
            brandText.setOrientation(LinearLayout.VERTICAL);
            brandText.setPadding(dp(9), 0, 0, 0);
            TextView company = text("ضوء التقنية", compact ? 16 : 19, 0xFF222127);
            company.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            company.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            brandText.addView(company);
            if (!compact) {
                TextView product = text("شاشة العميل الذكية", 12, 0xFF85818B);
                product.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                brandText.addView(product);
            }
            brand.addView(brandText);
            top.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        } else {
            TextView screenName = text("شاشة الطلب", compact ? 13 : 15, 0xFF7C7680);
            screenName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            top.addView(screenName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }

        statusChip = new LinearLayout(this);
        statusChip.setOrientation(LinearLayout.HORIZONTAL);
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(compact ? 10 : 14), dp(7), dp(compact ? 10 : 14), dp(7));
        statusChip.setBackground(round(0xFFF1F3F4, 20));
        statusDot = text("●", 12, accent);
        statusDot.setPadding(0, 0, dp(5), 0);
        statusChip.addView(statusDot);
        status = text("جاهز", compact ? 12 : 13, 0xFF514C58);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, 0);
        statusChip.addView(status);
        top.addView(statusChip);
        root.addView(top);

        title = text(ui.getString("welcome", "أهلًا وسهلًا بك"), compact ? 23 : 29, 0xFF242128);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.RIGHT);
        title.setPadding(dp(8), dp(2), dp(8), dp(compact ? 5 : 8));
        root.addView(title);

        body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));
        buildTemplate();

        TextView footer = text(ui.getString("footer", "نسعد بخدمتكم دائمًا"), compact ? 11 : 13, 0xFF8A858F);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(7), dp(8), 0);
        root.addView(footer);

        shell.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        addHiddenSettingsButton();
        setContentView(shell);
        animateEntrance();
    }

    private void addHiddenSettingsButton() {
        settingsPill = text("الإعدادات", 14, accent);
        settingsPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        settingsPill.setGravity(Gravity.CENTER);
        settingsPill.setPadding(dp(16), dp(9), dp(16), dp(9));
        settingsPill.setCompoundDrawablePadding(dp(8));
        settingsPill.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
        Drawable settingsIcon = settingsPill.getCompoundDrawablesRelative()[0];
        if (settingsIcon != null) settingsIcon.mutate().setTint(accent);
        settingsPill.setBackground(pressable(Color.WHITE, 0xFFE0DAE5, 20, 0x185B2A86));
        settingsPill.setElevation(dp(10));
        settingsPill.setVisibility(View.GONE);
        settingsPill.setOnClickListener(view -> {
            handler.removeCallbacks(hideSettingsTask);
            hideSettingsButton();
            startActivityForResult(new Intent(this, SettingsActivity.class), SETTINGS_REQ);
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
                Gravity.TOP | Gravity.START
        );
        params.setMargins(dp(16), dp(14), dp(16), 0);
        shell.addView(settingsPill, params);
    }

    private void showSettingsButton() {
        if (settingsPill == null) return;
        handler.removeCallbacks(hideSettingsTask);
        if (settingsPill.getVisibility() != View.VISIBLE) {
            settingsPill.setVisibility(View.VISIBLE);
            settingsPill.setAlpha(0f);
            settingsPill.setTranslationY(-dp(12));
            settingsPill.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        handler.postDelayed(hideSettingsTask, SETTINGS_VISIBLE_MS);
    }

    private void hideSettingsButton() {
        if (settingsPill == null || settingsPill.getVisibility() != View.VISIBLE) return;
        settingsPill.animate().alpha(0f).translationY(-dp(10)).setDuration(170)
                .withEndAction(() -> {
                    if (settingsPill != null) settingsPill.setVisibility(View.GONE);
                }).start();
    }

    private void animateEntrance() {
        root.setAlpha(0f);
        root.setTranslationY(dp(10));
        root.animate().alpha(1f).translationY(0).setDuration(420)
                .setInterpolator(new DecelerateInterpolator()).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setStartDelay(160).setDuration(420).start();
    }

    private void buildTemplate() {
        body.removeAllViews();
        int template = ui.getInt("template", 0);

        LinearLayout orderCard = new LinearLayout(this);
        orderCard.setOrientation(LinearLayout.VERTICAL);
        orderCard.setPadding(dp(compact ? 14 : 22), dp(compact ? 12 : 18), dp(compact ? 14 : 22), dp(compact ? 12 : 18));
        orderCard.setBackground(strokeBg(Color.WHITE, 0xFFE0E3E7, 28));
        orderCard.setElevation(dp(1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView orderLabel = text("تفاصيل الطلب", compact ? 16 : 18, 0xFF353039);
        orderLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(orderLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        itemCount = text("0 صنف", 12, 0xFF615A67);
        itemCount.setGravity(Gravity.CENTER);
        itemCount.setBackground(round(0xFFF1F3F4, 18));
        itemCount.setPadding(dp(11), dp(5), dp(11), dp(5));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        countParams.setMargins(0, 0, dp(7), 0);
        header.addView(itemCount, countParams);
        TextView liveBadge = text("مباشر", 12, accent);
        liveBadge.setGravity(Gravity.CENTER);
        liveBadge.setBackground(round(lighten(accent, 0.91f), 18));
        liveBadge.setPadding(dp(11), dp(5), dp(11), dp(5));
        header.addView(liveBadge);
        orderCard.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFF0EDF2);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        );
        dividerParams.setMargins(0, dp(10), 0, dp(7));
        orderCard.addView(divider, dividerParams);

        orderList = new LinearLayout(this);
        orderList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(orderList);
        orderCard.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(dp(compact ? 18 : 26), dp(compact ? 16 : 26), dp(compact ? 18 : 26), dp(compact ? 16 : 26));
        GradientDrawable summaryBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(accent, 0.90f), 0xFFFFFFFF}
        );
        summaryBg.setCornerRadius(dp(28));
        summary.setBackground(summaryBg);
        summary.setElevation(dp(1));

        addCustomerLogo(summary);
        TextView totalLabel = text("إجمالي طلبك", compact ? 15 : 17, 0xFF605968);
        totalLabel.setGravity(Gravity.CENTER);
        summary.addView(totalLabel);
        total = text("0.00 ر.س", compact ? 32 : 45, accent);
        total.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        total.setGravity(Gravity.CENTER);
        total.setPadding(0, dp(3), 0, dp(6));
        summary.addView(total);
        TextView safe = text("يُحدَّث تلقائيًا من نقطة البيع", compact ? 11 : 13, 0xFF6F6A74);
        safe.setGravity(Gravity.CENTER);
        summary.addView(safe);

        if (compact) {
            body.setOrientation(LinearLayout.VERTICAL);
            if (template == 2) {
                LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
                );
                summaryParams.setMargins(0, 0, 0, dp(10));
                body.addView(summary, summaryParams);
                body.addView(orderCard, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 2
                ));
            } else {
                LinearLayout.LayoutParams orderParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 2
                );
                orderParams.setMargins(0, 0, 0, dp(10));
                body.addView(orderCard, orderParams);
                body.addView(summary, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
                ));
            }
        } else if (template == 1) {
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams orderParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 2
            );
            orderParams.setMargins(0, 0, 0, dp(12));
            body.addView(orderCard, orderParams);
            body.addView(summary, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
            ));
        } else {
            body.setOrientation(LinearLayout.HORIZONTAL);
            if (template == 2) {
                LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                summaryParams.setMargins(0, 0, dp(12), 0);
                body.addView(summary, summaryParams);
                body.addView(orderCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2));
            } else {
                LinearLayout.LayoutParams orderParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3);
                orderParams.setMargins(0, 0, dp(14), 0);
                body.addView(orderCard, orderParams);
                body.addView(summary, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2));
            }
        }
        showEmptyOrder("بانتظار أول طلب", "سيظهر الطلب هنا مباشرة عند إضافة صنف من الكاشير");
    }

    private void addCustomerLogo(LinearLayout summary) {
        String logoUri = ui.getString("logo", null);
        if (logoUri != null && !logoUri.trim().isEmpty()) {
            ImageView customerLogo = new ImageView(this);
            customerLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            try {
                customerLogo.setImageURI(Uri.parse(logoUri));
                if (customerLogo.getDrawable() != null) {
                    summary.addView(customerLogo, new LinearLayout.LayoutParams(dp(compact ? 100 : 150), dp(compact ? 62 : 100)));
                    return;
                }
            } catch (Exception ignored) {
                // A neutral store mark is used below. TechLight branding never leaks into the customer view.
            }
        }
        ImageView store = new ImageView(this);
        store.setImageResource(R.drawable.ic_store);
        store.setImageTintList(ColorStateList.valueOf(accent));
        store.setPadding(dp(13), dp(13), dp(13), dp(13));
        store.setBackground(round(lighten(accent, 0.86f), 22));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(compact ? 54 : 66), dp(compact ? 54 : 66));
        markParams.setMargins(0, 0, 0, dp(8));
        summary.addView(store, markParams);
    }

    private int lighten(int color, float factor) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        red = (int) (red + (255 - red) * factor);
        green = (int) (green + (255 - green) * factor);
        blue = (int) (blue + (255 - blue) * factor);
        return Color.rgb(Math.min(255, red), Math.min(255, green), Math.min(255, blue));
    }

    private void showEmptyOrder(String heading, String subheading) {
        if (orderList == null) return;
        if (itemCount != null) itemCount.setText("0 صنف • 0 قطعة");
        orderList.removeAllViews();
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(compact ? 18 : 28), dp(24), dp(compact ? 18 : 28));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_order);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(round(lighten(accent, 0.90f), 20));
        empty.addView(icon, new LinearLayout.LayoutParams(dp(compact ? 52 : 64), dp(compact ? 52 : 64)));
        TextView emptyTitle = text(heading, compact ? 18 : 22, 0xFF38323B);
        emptyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(dp(8), dp(8), dp(8), dp(3));
        empty.addView(emptyTitle);
        TextView emptySub = text(subheading, compact ? 12 : 14, 0xFF8B858F);
        emptySub.setGravity(Gravity.CENTER);
        empty.addView(emptySub);
        orderList.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        pulse(icon);
    }

    private void pulse(View view) {
        AlphaAnimation animation = new AlphaAnimation(0.55f, 1f);
        animation.setDuration(950);
        animation.setRepeatMode(Animation.REVERSE);
        animation.setRepeatCount(Animation.INFINITE);
        view.startAnimation(animation);
    }

    private void animatePairingIcon(View view) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ROTATION, -4f, 4f);
        animator.setDuration(700);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.start();
    }

    private void restoreOrPair() {
        SharedPreferences pair = getSharedPreferences("pair", 0);
        String ip = pair.getString("ip", null);
        int port = pair.getInt("port", 4040);
        if (ip != null) {
            connect(ip, port);
            return;
        }
        showPairingPanel();
    }

    private void showPairingPanel() {
        handler.removeCallbacks(idleTask);
        if (client != null) {
            client.stop();
            client = null;
        }
        body.removeAllViews();
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(compact ? 4 : 34), dp(4), dp(compact ? 4 : 34), dp(4));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(compact ? 20 : 40), dp(compact ? 18 : 28), dp(compact ? 20 : 40), dp(compact ? 18 : 28));
        card.setBackground(strokeBg(Color.WHITE, 0xFFE7E1EB, 28));
        card.setElevation(dp(3));

        ImageView scanIcon = new ImageView(this);
        scanIcon.setImageResource(R.drawable.ic_scan_qr);
        scanIcon.setImageTintList(ColorStateList.valueOf(accent));
        scanIcon.setPadding(dp(15), dp(15), dp(15), dp(15));
        scanIcon.setBackground(round(lighten(accent, 0.88f), 24));
        card.addView(scanIcon, new LinearLayout.LayoutParams(dp(compact ? 72 : 90), dp(compact ? 72 : 90)));
        animatePairingIcon(scanIcon);

        TextView heading = text("اربط شاشة العميل", compact ? 22 : 27, 0xFF302B33);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(8), dp(10), dp(8), dp(2));
        card.addView(heading);
        TextView detail = text("افتح QR شاشة العميل في Tech Pro، ثم وجّه كاميرا هذا الجهاز إليه.", compact ? 13 : 15, 0xFF77717C);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(16), dp(2), dp(16), dp(15));
        card.addView(detail);

        TextView liveScan = action("مسح QR بالكاميرا", R.drawable.ic_scan_qr, true);
        liveScan.setOnClickListener(view -> prepareLiveScanner());
        card.addView(liveScan, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        TextView gallery = action("اختيار صورة QR من الجهاز", R.drawable.ic_scan_qr, false);
        gallery.setOnClickListener(view -> launchQrImagePicker());
        LinearLayout.LayoutParams galleryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        galleryParams.setMargins(0, dp(8), 0, 0);
        card.addView(gallery, galleryParams);

        TextView alternative = text("أو استخدم الربط اليدوي", 12, 0xFF9A949E);
        alternative.setGravity(Gravity.CENTER);
        alternative.setPadding(dp(8), dp(6), dp(8), dp(6));
        card.addView(alternative);

        TextView manual = action("إدخال IP والمنفذ", R.drawable.ic_keyboard, false);
        manual.setOnClickListener(view -> manualPair());
        card.addView(manual, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        body.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        setConnectionState("غير مرتبط", false);
    }

    private void prepareLiveScanner() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
            return;
        }
        launchScanner();
    }

    private void launchScanner() {
        try {
            IntentIntegrator integrator = new IntentIntegrator(this);
            integrator.setCaptureActivity(QrCaptureActivity.class);
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("وجّه الكاميرا إلى QR الاقتران في Tech Pro");
            integrator.setBeepEnabled(true);
            integrator.setBarcodeImageEnabled(false);
            integrator.setOrientationLocked(false);
            integrator.setTimeout(300000);
            integrator.initiateScan();
            writeDiagnostic("CAMERA_LAUNCHED", "QrCaptureActivity");
        } catch (Exception error) {
            writeDiagnostic("CAMERA_FAILED", error.getClass().getSimpleName() + ": " + error.getMessage());
            new AlertDialog.Builder(this)
                    .setTitle("تعذّر فتح ماسح QR")
                    .setMessage("تعذّر تشغيل الكاميرا المباشرة. اختر صورة QR محفوظة أو أدخل IP والمنفذ.")
                    .setPositiveButton("اختيار صورة", (dialog, which) -> launchQrImagePicker())
                    .setNegativeButton("إدخال يدوي", (dialog, which) -> manualPair())
                    .show();
        }
    }

    private void launchQrImagePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, QR_IMAGE_REQ);
            writeDiagnostic("QR_IMAGE_PICKER_LAUNCHED", "ACTION_OPEN_DOCUMENT");
        } catch (Exception error) {
            writeDiagnostic("QR_IMAGE_PICKER_FAILED", error.getClass().getSimpleName() + ": " + error.getMessage());
            manualPair();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_REQ) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("صلاحية الكاميرا مطلوبة")
                .setMessage("فعّل صلاحية الكاميرا حتى يستطيع التطبيق مسح QR.")
                .setPositiveButton("فتح إعدادات التطبيق", (dialog, which) -> openAppSettings())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void manualPair() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), 0);
        EditText ip = new EditText(this);
        ip.setHint("IP مثال: 192.168.100.23");
        ip.setSingleLine(true);
        form.addView(ip);
        EditText port = new EditText(this);
        port.setHint("Port مثال: 4040");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        port.setSingleLine(true);
        port.setText("4040");
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("بيانات الربط مع Tech Pro")
                .setView(form)
                .setPositiveButton("ربط", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String ipValue = ip.getText().toString().trim();
            String portValue = port.getText().toString().trim();
            if (ipValue.isEmpty()) {
                ip.setError("اكتب IP");
                return;
            }
            try {
                int portNumber = Integer.parseInt(portValue);
                if (portNumber < 1 || portNumber > 65535) throw new NumberFormatException();
                savePair(ipValue, portNumber);
                dialog.dismiss();
            } catch (Exception error) {
                port.setError("منفذ غير صحيح");
            }
        }));
        dialog.show();
    }

    private void pairText(String raw) {
        try {
            PairingParser.PairingInfo pairing = PairingParser.parse(raw);
            savePair(pairing.ip, pairing.port);
        } catch (Exception error) {
            showToast("QR غير صحيح أو لا يخص Tech Pro");
            showPairingPanel();
        }
    }

    private void savePair(String ip, int port) {
        getSharedPreferences("pair", 0).edit()
                .putString("ip", ip)
                .putInt("port", port)
                .apply();
        writeDiagnostic("PAIR_SAVED", ip + ":" + port);
        buildUi();
        connect(ip, port);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == QR_IMAGE_REQ) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                decodeQrImage(data.getData());
            } else {
                writeDiagnostic("QR_IMAGE_PICKER_CANCELLED", "result=" + result);
            }
            return;
        }
        IntentResult qr = IntentIntegrator.parseActivityResult(request, result, data);
        if (qr != null) {
            if (qr.getContents() != null) {
                writeDiagnostic("LIVE_QR_DECODED", "characters=" + qr.getContents().length());
                pairText(qr.getContents());
            } else {
                writeDiagnostic("LIVE_SCANNER_CANCELLED", "no result");
            }
            return;
        }
        if (request == SETTINGS_REQ) {
            buildUi();
            restoreOrPair();
        }
    }

    private void decodeQrImage(Uri uri) {
        Intent data = new Intent();
        data.setData(uri);
        decodeQrBitmap(data);
    }

    private void decodeQrBitmap(Intent data) {
        Bitmap bitmap = null;
        try {
            if (data != null && data.getExtras() != null) {
                Object thumbnail = data.getExtras().get("data");
                if (thumbnail instanceof Bitmap) bitmap = (Bitmap) thumbnail;
            }
            if (bitmap == null && data != null && data.getData() != null) {
                bitmap = decodeCameraUri(data.getData());
            }
            if (bitmap == null) throw new IllegalArgumentException("Camera returned no image");

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

            MultiFormatReader reader = new MultiFormatReader();
            Result decoded;
            try {
                decoded = reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            } catch (Exception normalError) {
                reader.reset();
                decoded = reader.decode(new BinaryBitmap(new HybridBinarizer(source.invert())), hints);
            }
            writeDiagnostic("QR_IMAGE_DECODED", width + "x" + height);
            pairText(decoded.getText());
        } catch (Exception error) {
            writeDiagnostic("QR_IMAGE_FAILED", error.getClass().getSimpleName() + ": " + error.getMessage());
            new AlertDialog.Builder(this)
                    .setTitle("لم يظهر QR بوضوح")
                    .setMessage("قرّب الكاميرا من QR أو اختر صورة أوضح ثم حاول مرة أخرى.")
                    .setPositiveButton("فتح الكاميرا", (dialog, which) -> prepareLiveScanner())
                    .setNegativeButton("إدخال يدوي", (dialog, which) -> manualPair())
                    .show();
        }
    }

    private Bitmap decodeCameraUri(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / sample > 1800) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(stream, null, options);
        }
    }

    private void connect(String ip, int port) {
        setConnectionState("جارٍ الاتصال", false);
        showEmptyOrder("جارٍ الاتصال بـ Tech Pro", ip + ":" + port);
        writeDiagnostic("CONNECTING", "ws://" + ip + ":" + port);
        if (client != null) client.stop();
        client = new TechProClient(ip, port, this);
        client.start();
    }

    private void setConnectionState(String value, boolean ok) {
        if (status == null || statusDot == null) return;
        status.setText(value);
        status.setTextColor(ok ? 0xFF176B46 : 0xFF5F6368);
        statusDot.setTextColor(ok ? 0xFF188038 : accent);
        if (statusChip != null) {
            statusChip.setBackground(round(ok ? 0xFFE6F4EA : 0xFFF1F3F4, 20));
        }
    }

    private void showToast(String value) {
        runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_LONG).show());
    }

    @Override public void onConnected() {
        runOnUiThread(() -> {
            setConnectionState("متصل — بانتظار البيانات", true);
            showEmptyOrder("تم فتح اتصال Tech Pro", "بانتظار أول fullSnapshot من شاشة الكاشير");
        });
    }

    @Override public void onDisconnected(String reason) {
        writeDiagnostic("DISCONNECTED", reason);
        runOnUiThread(() -> {
            setConnectionState("إعادة الاتصال", false);
            showEmptyOrder("لم يصل Tech Pro بعد", "افتح وضع شاشة العميل في الكاشير وتأكد أن الجهازين على نفس شبكة الواي فاي");
        });
    }

    @Override public void onRaw(String raw) {
        if (raw == null) return;
        String compactRaw = raw.length() > 16000 ? raw.substring(0, 16000) + "…" : raw;
        JSONArray history;
        try {
            history = new JSONArray(diagnostics.getString("raw_history", "[]"));
        } catch (Exception ignored) {
            history = new JSONArray();
        }
        String historyFrame = raw.length() > 6000 ? raw.substring(0, 6000) + "…" : raw;
        history.put(historyFrame);
        while (history.length() > 12) history.remove(0);
        diagnostics.edit()
                .putString("last_raw", compactRaw)
                .putString("raw_history", history.toString())
                .putInt("last_raw_length", raw.length())
                .putLong("last_raw_at", System.currentTimeMillis())
                .apply();
    }

    @Override public void onDiagnostic(String stage, String detail) {
        writeDiagnostic(stage, detail);
    }

    @Override public void onOrder(OrderState order) {
        writeDiagnostic("ORDER_RENDERED", "items=" + order.items.size() + " — total=" + order.total);
        runOnUiThread(() -> {
            if (!paired) return;
            bringCustomerDisplayForward();
            handler.removeCallbacks(idleTask);
            orderList.removeAllViews();
            boolean empty = order.items == null || order.items.isEmpty();
            int rows = empty ? 0 : order.items.size();
            double units = 0;
            if (!empty) {
                for (OrderState.Item item : order.items) units += item.qty;
            }
            if (itemCount != null) {
                itemCount.setText(rows + " صنف • " + formatQuantity(units) + " قطعة");
            }
            if (empty) {
                if (order.total > 0.0001) {
                    showEmptyOrder("وصل الإجمالي بدون الأصناف", "Tech Pro أرسل قيمة الفاتورة لكن قائمة الأصناف فارغة؛ انسخ تقرير التشخيص من الإعدادات");
                } else {
                    showEmptyOrder("متصل وجاهز", "سيظهر أول صنف هنا فور إضافته من Tech Pro");
                }
                setConnectionState("متصل", true);
                scheduleIdle(30000);
            } else {
                addOrderColumns();
                for (OrderState.Item item : order.items) addOrderRow(item);
            }
            total.setText(String.format(Locale.US, "%.2f ر.س", order.total));
            total.setScaleX(0.93f);
            total.setScaleY(0.93f);
            total.animate().scaleX(1f).scaleY(1f).setDuration(190).start();
            if (order.completed) {
                setConnectionState(ui.getString("thanks", "شكرًا لزيارتكم"), true);
                scheduleIdle(7000);
            } else if (!empty) {
                setConnectionState("الطلب مباشر", true);
            }
        });
    }

    private void bringCustomerDisplayForward() {
        if (hasWindowFocus()) return;
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager != null) manager.moveTaskToFront(getTaskId(), 0);
            writeDiagnostic("TASK_FOREGROUND", "moveTaskToFront");
        } catch (Exception error) {
            writeDiagnostic("TASK_FOREGROUND_FAILED", error.getClass().getSimpleName());
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
    }

    private void writeDiagnostic(String stage, String detail) {
        if (diagnostics == null) return;
        diagnostics.edit()
                .putString("stage", stage == null ? "" : stage)
                .putString("detail", detail == null ? "" : detail)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    private void addOrderRow(OrderState.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(compact ? 10 : 14), dp(compact ? 8 : 11), dp(compact ? 10 : 14), dp(compact ? 8 : 11));
        row.setBackground(strokeBg(Color.WHITE, 0xFFE8EAED, 16));

        TextView qty = text(formatQuantity(item.qty) + " ×", compact ? 13 : 15, accent);
        qty.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        qty.setGravity(Gravity.CENTER);
        qty.setBackground(round(lighten(accent, 0.92f), 14));
        qty.setPadding(dp(10), dp(6), dp(10), dp(6));

        LinearLayout nameBlock = new LinearLayout(this);
        nameBlock.setOrientation(LinearLayout.VERTICAL);
        nameBlock.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(item.name, compact ? 16 : 20, 0xFF2D2930);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        name.setMaxLines(2);
        name.setPadding(dp(8), 0, dp(8), 0);
        nameBlock.addView(name);
        TextView unitPrice = text(
                String.format(Locale.US, "سعر الوحدة  %.2f ر.س", item.unitPrice),
                compact ? 11 : 13,
                0xFF747078
        );
        unitPrice.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        unitPrice.setPadding(dp(8), 0, dp(8), 0);
        nameBlock.addView(unitPrice);

        TextView price = text(String.format(Locale.US, "%.2f ر.س", item.total()), compact ? 15 : 19, 0xFF202124);
        price.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        price.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);

        row.addView(price, new LinearLayout.LayoutParams(dp(compact ? 104 : 170), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(nameBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(qty);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, dp(4), 0, dp(4));
        orderList.addView(row, rowParams);
        row.setAlpha(0f);
        row.setTranslationX(dp(20));
        row.animate().alpha(1f).translationX(0).setDuration(250).start();
    }

    private void addOrderColumns() {
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        columns.setPadding(dp(compact ? 10 : 14), dp(3), dp(compact ? 10 : 14), dp(3));

        TextView totalColumn = text("الإجمالي", compact ? 10 : 12, 0xFF70757A);
        totalColumn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        TextView itemColumn = text("الصنف", compact ? 10 : 12, 0xFF70757A);
        itemColumn.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        itemColumn.setPadding(dp(8), 0, dp(8), 0);
        TextView quantityColumn = text("الكمية", compact ? 10 : 12, 0xFF70757A);
        quantityColumn.setGravity(Gravity.CENTER);

        columns.addView(totalColumn, new LinearLayout.LayoutParams(dp(compact ? 104 : 170), LinearLayout.LayoutParams.WRAP_CONTENT));
        columns.addView(itemColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        columns.addView(quantityColumn, new LinearLayout.LayoutParams(dp(compact ? 64 : 82), LinearLayout.LayoutParams.WRAP_CONTENT));
        orderList.addView(columns);
    }

    private String formatQuantity(double quantity) {
        if (Math.abs(quantity - Math.rint(quantity)) < 0.00001) {
            return String.format(Locale.US, "%.0f", quantity);
        }
        String value = String.format(Locale.US, "%.3f", quantity);
        return value.replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private void scheduleIdle(long delayMs) {
        handler.removeCallbacks(idleTask);
        handler.postDelayed(idleTask, delayMs);
    }

    @Override protected void onDestroy() {
        if (client != null) client.stop();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
