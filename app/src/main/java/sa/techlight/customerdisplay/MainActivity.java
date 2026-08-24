package sa.techlight.customerdisplay;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
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
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import org.json.JSONArray;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity implements TechProClient.Listener {
    private static final int CAMERA_REQ = 501;
    private static final int QR_IMAGE_REQ = 503;
    private static final int SETTINGS_REQ = 77;
    private static final long SETTINGS_VISIBLE_MS = 6500;

    private FrameLayout shell;
    private LinearLayout root;
    private LinearLayout orderList;
    private LinearLayout body;
    private ScrollView orderScroll;
    private TextView status;
    private TextView total;
    private TextView title;
    private TextView statusDot;
    private TextView settingsPill;
    private TextView itemCount;
    private TextView unitCount;
    private TextView subtotalValue;
    private TextView taxValue;
    private TextView discountValue;
    private LinearLayout statusChip;
    private TechProClient client;
    private ProductCatalog catalog;
    private TechProSession session;
    private AbleSignController able;
    private AbleSignEmbeddedPlayer embeddedAble;
    private ProductImageLoader imageLoader;
    private SharedPreferences ui;
    private SharedPreferences diagnostics;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int accent = Color.rgb(77, 14, 129);
    private int panelColor = Color.rgb(77, 14, 129);
    private int panelEndColor = Color.rgb(38, 4, 65);
    private boolean compact;
    private boolean portrait;
    private boolean paired;
    private boolean dark;
    private boolean orderVisible;
    private boolean ableExternalVisible;
    private boolean scannerWaitingForPermission;
    private boolean scannerActive;
    private boolean scannerTorchOn;
    private boolean scannerOrientationChanged;
    private FrameLayout scannerOverlay;
    private DecoratedBarcodeView barcodeScanner;
    private long completionMomentUntil;
    private int pageColor;
    private int surfaceColor;
    private int softColor;
    private int borderColor;
    private int primaryTextColor;
    private int secondaryTextColor;
    private boolean denseRows;
    private boolean showProductImages = true;
    private boolean showBreakdown = true;
    private int rowStyle;
    private double renderedTotal;
    private ValueAnimator totalAnimator;
    private final List<ValueAnimator> decorativeAnimators = new ArrayList<>();
    private final Set<String> renderedItemKeys = new HashSet<>();
    private final Map<String, View> renderedRows = new LinkedHashMap<>();
    private OrderState latestOrder;

    private final Runnable hideSettingsTask = this::hideSettingsButton;
    private final Runnable idleTask = () -> {
        int mode = ui == null ? 0 : ui.getInt("able_mode", 0);
        if (mode == 0 || orderVisible) return;
        if (mode == 1) {
            if (ensureEmbeddedPlayer()) {
                embeddedAble.show();
                return;
            }
            showToast("تعذّر فتح شاشة المحتوى؛ تحقق من مكوّن عرض الويب في الجهاز");
            return;
        }
        if (mode == 2 && able != null && able.openPlayer()) {
            ableExternalVisible = true;
            writeDiagnostic("ABLESIGN_EXTERNAL", "Opened installed AbleSign compatibility player");
            return;
        }
        showToast("تطبيق شاشة العرض غير مثبت؛ اختر المشغّل المدمج من الإعدادات");
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        able = new AbleSignController(this);
        ui = getSharedPreferences("ui", 0);
        applyOrientationPreference();
        session = new TechProSession(this);
        imageLoader = new ProductImageLoader(this, session.token());
        catalog = new ProductCatalog(this);
        if (!session.isSignedIn() || catalog.count() <= 0) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (!ui.getBoolean("v6_idle_migration", false)) {
            ui.edit()
                    .putBoolean("able_idle", false)
                    .putInt("able_mode", 0)
                    .putBoolean("v6_idle_migration", true)
                    .apply();
        }
        diagnostics = getSharedPreferences("diagnostics", 0);
        buildUi();
        if (state == null) showStartupOverlay();
        restoreOrPair();
        refreshCatalogForImages();
    }

    private void showStartupOverlay() {
        if (shell == null || isFinishing()) return;
        FrameLayout overlay = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A0641, 0xFF5A0794, 0xFF792EE8}
        );
        overlay.setBackground(background);
        overlay.setClickable(true);

        LinearLayout brandStack = new LinearLayout(this);
        brandStack.setOrientation(LinearLayout.VERTICAL);
        brandStack.setGravity(Gravity.CENTER);
        brandStack.setTranslationY(-dp(compact ? 24 : 34));

        FrameLayout glowHost = new FrameLayout(this);
        glowHost.setBackground(brandGlowBackground(compact ? 150 : 220));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.techlight_brand_transparent);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(compact ? 26 : 38), dp(12), dp(compact ? 26 : 38), dp(12));
        glowHost.addView(logo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        brandStack.addView(glowHost, new LinearLayout.LayoutParams(
                dp(compact ? 320 : 500), dp(compact ? 106 : 154)
        ));

        TextView website = text("techlight.sa", compact ? 14 : 18, Color.WHITE);
        website.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        website.setGravity(Gravity.CENTER);
        website.setLetterSpacing(0.10f);
        website.setShadowLayer(dp(10), 0, 0, 0xCCB97BFF);
        brandStack.addView(website, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        overlay.addView(brandStack, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
        shell.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        brandStack.setAlpha(0f);
        brandStack.setScaleX(0.94f);
        brandStack.setScaleY(0.94f);
        brandStack.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(310)
                .setInterpolator(new DecelerateInterpolator()).start();
        handler.postDelayed(() -> {
            if (overlay.getParent() != shell) return;
            overlay.animate().alpha(0f).setDuration(230).withEndAction(() -> {
                if (overlay.getParent() == shell) shell.removeView(overlay);
            }).start();
        }, 620);
    }

    private void refreshCatalogForImages() {
        long now = System.currentTimeMillis();
        long previousAttempt = ui.getLong("v14_image_sync_attempt", 0);
        if (now - previousAttempt < 12L * 60L * 60L * 1000L) return;
        String token = session == null ? null : session.token();
        if (token == null || token.trim().isEmpty()) return;
        ui.edit().putLong("v14_image_sync_attempt", now).apply();
        TechProAccountClient account = new TechProAccountClient();
        account.syncCatalog(token, new TechProAccountClient.SyncListener() {
            @Override public void onProgress(String message, int productsFound) {
                writeDiagnostic("IMAGE_CATALOG_SYNC", message + " — " + productsFound);
            }

            @Override public void onSuccess(java.util.List<ProductCatalog.Product> products) {
                try {
                    int count = catalog == null ? 0 : catalog.replaceAll(products);
                    writeDiagnostic("IMAGE_CATALOG_READY", "records=" + count);
                } catch (Exception error) {
                    writeDiagnostic("IMAGE_CATALOG_FAILED", String.valueOf(error.getMessage()));
                } finally {
                    account.shutdown();
                }
            }

            @Override public void onFailure(String message, boolean unauthorized) {
                writeDiagnostic("IMAGE_CATALOG_FAILED", message);
                account.shutdown();
            }
        });
    }

    private void applyOrientationPreference() {
        int orientation = ui.getInt("orientation", 0);
        int requested = orientation == 1
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : orientation == 2
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        if (getRequestedOrientation() != requested) setRequestedOrientation(requested);
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
                : pressable(surfaceColor, borderColor, 18, 0x185B2A86));
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(2));
        return button;
    }

    private void buildUi() {
        closeScannerOverlay();
        handler.removeCallbacks(hideSettingsTask);
        handler.removeCallbacks(idleTask);
        stopDecorativeAnimations();
        if (totalAnimator != null) {
            totalAnimator.cancel();
            totalAnimator = null;
        }
        if (embeddedAble != null) {
            embeddedAble.shutdown();
            embeddedAble = null;
        }
        renderedRows.clear();
        renderedItemKeys.clear();
        paired = getSharedPreferences("pair", 0).contains("ip");
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int heightDp = getResources().getConfiguration().screenHeightDp;
        portrait = heightDp > widthDp;
        compact = widthDp < 700 || portrait;
        try {
            accent = Color.parseColor(ui.getString("color", "#4D0E81"));
        } catch (Exception ignored) {
            accent = Color.rgb(77, 14, 129);
        }
        try {
            panelColor = Color.parseColor(ui.getString("panel_color", "#4D0E81"));
        } catch (Exception ignored) {
            panelColor = accent;
        }
        try {
            panelEndColor = Color.parseColor(ui.getString("panel_end_color", "#260441"));
        } catch (Exception ignored) {
            panelEndColor = mix(panelColor, 0xFF210432, 0.36f);
        }
        denseRows = ui.getInt("row_density", 0) == 1;
        rowStyle = ui.getInt("row_style", 0);
        showProductImages = ui.getBoolean("show_product_images", true);
        showBreakdown = ui.getBoolean("show_breakdown", true);
        dark = "dark".equals(ui.getString("theme", "light"));
        pageColor = dark ? 0xFF0C0711 : 0xFFF7F4FA;
        surfaceColor = dark ? 0xFF17101D : Color.WHITE;
        softColor = dark ? 0xFF261A30 : 0xFFF4EEF8;
        borderColor = dark ? 0xFF3A2947 : 0xFFE8DFEE;
        primaryTextColor = dark ? 0xFFF8F4FA : 0xFF26212B;
        secondaryTextColor = dark ? 0xFFC0B4C8 : 0xFF6D6573;

        shell = new FrameLayout(this);
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{pageColor, dark ? mix(accent, pageColor, 0.84f) : lighten(accent, 0.965f)}
        );
        shell.setBackground(backdrop);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(0, 0, 0, 0);
        root.setBackgroundColor(Color.TRANSPARENT);
        statusChip = null;
        statusDot = null;
        status = null;
        title = null;

        body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));
        buildTemplate();

        shell.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        boolean resetAble = ui.getBoolean("able_reset_requested", false);
        boolean previewAble = ui.getBoolean("able_preview_requested", false);
        ui.edit()
                .putBoolean("able_reset_requested", false)
                .putBoolean("able_preview_requested", false)
                .apply();
        if (resetAble || previewAble) {
            handler.postDelayed(() -> {
                if (!ensureEmbeddedPlayer()) return;
                if (resetAble) embeddedAble.resetPairing();
                if (!orderVisible) embeddedAble.show();
                else showToast("ستظهر شاشة المحتوى بعد انتهاء الطلب الحالي");
            }, 900);
        }
        addHiddenSettingsButton();
        setContentView(shell);
        animateEntrance();
    }

    /** Creates the WebView only when content is about to be shown. */
    private boolean ensureEmbeddedPlayer() {
        if (embeddedAble != null) return true;
        if (shell == null || isFinishing()) return false;
        try {
            embeddedAble = new AbleSignEmbeddedPlayer(this, shell, (message, error) -> {
                writeDiagnostic(error ? "SIGNAGE_ERROR" : "SIGNAGE_EMBEDDED", message);
                if (error) showToast(message);
            });
            return true;
        } catch (Throwable error) {
            embeddedAble = null;
            writeDiagnostic("SIGNAGE_WEBVIEW_UNAVAILABLE",
                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            return false;
        }
    }

    private void addHeaderIdentity(LinearLayout top) {
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setPadding(dp(7), dp(7), dp(7), dp(7));
        logo.setBackground(strokeBg(surfaceColor, borderColor, 18));
        logo.setClipToOutline(true);
        boolean hasLogo = false;
        String logoUri = ui.getString("logo", null);
        if (logoUri != null && !logoUri.trim().isEmpty()) {
            try {
                logo.setImageURI(Uri.parse(logoUri));
                hasLogo = logo.getDrawable() != null;
            } catch (Exception ignored) { }
        }
        if (!hasLogo) {
            logo.setImageResource(R.drawable.ic_store);
            logo.setImageTintList(ColorStateList.valueOf(accent));
            logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        int logoSize = dp(compact ? 46 : 58);
        identity.addView(logo, new LinearLayout.LayoutParams(logoSize, logoSize));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(10), 0, dp(10), 0);
        TextView label = text(paired ? "طلبك الآن" : "شاشة العميل", compact ? 15 : 18, primaryTextColor);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        label.setPadding(0, 0, 0, 0);
        copy.addView(label);
        TextView sub = text(paired ? "يتم التحديث مباشرة من نقطة البيع" : "اربطها مع Tech Pro للبدء",
                compact ? 10 : 12, secondaryTextColor);
        sub.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        sub.setPadding(0, 0, 0, 0);
        copy.addView(sub);
        identity.addView(copy);
        top.addView(identity, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void addAmbientMotion(FrameLayout host) {
        View first = ambientOrb(lighten(accent, dark ? 0.35f : 0.68f), dark ? 0.13f : 0.20f);
        FrameLayout.LayoutParams firstParams = new FrameLayout.LayoutParams(dp(compact ? 220 : 330), dp(compact ? 220 : 330), Gravity.TOP | Gravity.START);
        firstParams.setMargins(-dp(70), -dp(90), 0, 0);
        host.addView(first, firstParams);
        animateOrb(first, dp(compact ? 28 : 54), dp(compact ? 34 : 62), 10500);

        View second = ambientOrb(0xFF9B59C2, dark ? 0.10f : 0.13f);
        FrameLayout.LayoutParams secondParams = new FrameLayout.LayoutParams(dp(compact ? 190 : 280), dp(compact ? 190 : 280), Gravity.BOTTOM | Gravity.END);
        secondParams.setMargins(0, 0, -dp(70), -dp(70));
        host.addView(second, secondParams);
        animateOrb(second, -dp(compact ? 26 : 45), -dp(compact ? 34 : 58), 12300);
    }

    private View ambientOrb(int color, float alpha) {
        View orb = new View(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        orb.setBackground(shape);
        orb.setAlpha(alpha);
        return orb;
    }

    private void animateOrb(View view, float x, float y, long duration) {
        ObjectAnimator horizontal = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, x);
        horizontal.setDuration(duration);
        horizontal.setRepeatMode(ValueAnimator.REVERSE);
        horizontal.setRepeatCount(ValueAnimator.INFINITE);
        horizontal.start();
        decorativeAnimators.add(horizontal);
        ObjectAnimator vertical = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, y);
        vertical.setDuration(duration + 1700);
        vertical.setRepeatMode(ValueAnimator.REVERSE);
        vertical.setRepeatCount(ValueAnimator.INFINITE);
        vertical.start();
        decorativeAnimators.add(vertical);
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
        settingsPill.setBackground(pressable(surfaceColor, borderColor, 20, 0x185B2A86));
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
        root.setTranslationY(dp(6));
        root.animate().alpha(1f).translationY(0).setDuration(340)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void buildTemplate() {
        body.removeAllViews();
        int template = ui.getInt("template", 0);
        LinearLayout orderCard = new LinearLayout(this);
        orderCard.setOrientation(LinearLayout.VERTICAL);
        orderCard.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        orderCard.setPadding(dp(portrait ? 12 : (compact ? 14 : 22)), dp(portrait ? 7 : (compact ? 10 : 16)),
                dp(portrait ? 12 : (compact ? 14 : 22)), dp(portrait ? 7 : (compact ? 10 : 16)));
        orderCard.setBackgroundColor(dark ? 0xFF17101D : Color.WHITE);

        if (portrait) {
            LinearLayout.LayoutParams portraitLogoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(84)
            );
            portraitLogoParams.setMargins(0, 0, 0, dp(4));
            orderCard.addView(createCustomerBrand(false), portraitLogoParams);
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView orderLabel = text("طلبك", portrait ? 18 : (compact ? 20 : 26), primaryTextColor);
        orderLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        orderLabel.setPadding(dp(4), 0, dp(4), 0);
        header.addView(orderLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        itemCount = text("◉  0 أصناف", portrait ? 10 : (compact ? 11 : 13), Color.WHITE);
        itemCount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        itemCount.setGravity(Gravity.CENTER);
        itemCount.setBackground(round(accent, 20));
        itemCount.setPadding(dp(portrait ? 9 : 13), dp(portrait ? 5 : 7), dp(portrait ? 9 : 13), dp(portrait ? 5 : 7));
        itemCount.setElevation(dp(2));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        countParams.setMargins(0, 0, dp(7), 0);
        header.addView(itemCount, countParams);
        unitCount = text("×  0 قطعة", portrait ? 10 : (compact ? 11 : 13), accent);
        unitCount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        unitCount.setGravity(Gravity.CENTER);
        unitCount.setBackground(strokeBg(dark ? mix(accent, surfaceColor, 0.76f) : lighten(accent, 0.94f),
                dark ? mix(accent, borderColor, 0.34f) : lighten(accent, 0.76f), 20));
        unitCount.setPadding(dp(portrait ? 9 : 13), dp(portrait ? 5 : 7), dp(portrait ? 9 : 13), dp(portrait ? 5 : 7));
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        unitParams.setMargins(0, 0, dp(7), 0);
        header.addView(unitCount, unitParams);
        LinearLayout connection = createStatusChip();
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        connectionParams.setMargins(0, 0, dp(portrait ? 4 : 8), 0);
        header.addView(connection, connectionParams);
        orderCard.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(borderColor);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        );
        dividerParams.setMargins(0, dp(portrait ? 7 : 12), 0, dp(4));
        orderCard.addView(divider, dividerParams);

        orderCard.addView(createOrderColumns());

        orderList = new LinearLayout(this);
        orderList.setOrientation(LinearLayout.VERTICAL);
        orderList.setPadding(0, dp(3), 0, dp(5));
        orderScroll = new ScrollView(this);
        orderScroll.setFillViewport(true);
        orderScroll.setClipToPadding(false);
        orderScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        orderScroll.addView(orderList);
        orderCard.addView(orderScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setGravity(Gravity.CENTER_HORIZONTAL);
        summary.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        summary.setPadding(0, 0, 0, 0);
        GradientDrawable summaryBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(panelColor, dark ? 0.015f : 0.08f), panelColor, panelEndColor}
        );
        summary.setBackground(summaryBg);

        if (!portrait) {
            LinearLayout.LayoutParams customerLogoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(compact ? 94 : 116)
            );
            customerLogoParams.setMargins(dp(compact ? 16 : 22), dp(compact ? 14 : 20),
                    dp(compact ? 16 : 22), dp(4));
            summary.addView(createCustomerBrand(true), customerLogoParams);
        }

        if (showBreakdown) {
            LinearLayout metrics = new LinearLayout(this);
            metrics.setOrientation(portrait ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            metrics.setPadding(0, dp(portrait ? 5 : (compact ? 8 : 14)), 0, dp(portrait ? 4 : 5));
            if (portrait) {
                subtotalValue = addPortraitSummaryMetric(metrics, "المجموع الفرعي", "0.00");
                taxValue = addPortraitSummaryMetric(metrics, "الضريبة", "0.00");
                discountValue = addPortraitSummaryMetric(metrics, "الخصم", "0.00");
            } else {
                subtotalValue = addSummaryMetric(metrics, "المجموع الفرعي", "0.00");
                taxValue = addSummaryMetric(metrics, "الضريبة", "0.00");
                discountValue = addSummaryMetric(metrics, "الخصم", "0.00");
            }
            int metricSymbolSize = portrait ? 15 : (compact ? 14 : 16);
            subtotalValue.setText(money(0, metricSymbolSize, Color.WHITE));
            taxValue.setText(money(0, metricSymbolSize, Color.WHITE));
            discountValue.setText(money(0, metricSymbolSize, Color.WHITE));
            LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            metricsParams.setMargins(dp(portrait ? 12 : (compact ? 16 : 23)), 0,
                    dp(portrait ? 12 : (compact ? 16 : 23)), 0);
            summary.addView(metrics, metricsParams);
        } else {
            subtotalValue = null;
            taxValue = null;
            discountValue = null;
        }

        if (!portrait) {
            View summarySpacer = new View(this);
            summary.addView(summarySpacer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
            ));
        }

        LinearLayout totalPanel = new LinearLayout(this);
        totalPanel.setOrientation(portrait ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        totalPanel.setGravity(Gravity.CENTER);
        totalPanel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        totalPanel.setPadding(dp(portrait ? 18 : 8), dp(portrait ? 8 : (compact ? 9 : 12)),
                dp(portrait ? 18 : 8), dp(portrait ? 8 : (compact ? 10 : 14)));
        totalPanel.setBackgroundColor(0x18FFFFFF);

        View summaryDivider = new View(this);
        summaryDivider.setBackgroundColor(0x35FFFFFF);
        if (!portrait) {
            totalPanel.addView(summaryDivider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ));
        }

        TextView totalLabel = text("الإجمالي", portrait ? 18 : (compact ? 12 : 15), 0xFFEEDFF7);
        totalLabel.setGravity(Gravity.CENTER);
        totalLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        totalLabel.setPadding(dp(portrait ? 10 : 0), dp(portrait ? 0 : (compact ? 7 : 9)),
                dp(portrait ? 10 : 0), dp(portrait ? 0 : 2));
        if (portrait) totalPanel.addView(totalLabel);
        else totalPanel.addView(totalLabel);
        total = text("0.00", portrait ? 44 : (compact ? 32 : 42), Color.WHITE);
        total.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        total.setGravity(Gravity.CENTER);
        total.setPadding(dp(8), 0, dp(8), 0);
        total.setText(money(0, portrait ? 34 : (compact ? 26 : 34), Color.WHITE));
        totalPanel.addView(total, portrait
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams totalPanelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                portrait ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT,
                portrait ? 1 : 0
        );
        summary.addView(totalPanel, totalPanelParams);

        LinearLayout.LayoutParams companyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(portrait ? 66 : (compact ? 68 : 78))
        );
        companyParams.setMargins(dp(portrait ? 24 : 16), 0,
                dp(portrait ? 24 : 16), dp(5));
        summary.addView(createCompanyBrand(), companyParams);

        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setGravity(Gravity.CENTER);
        dashboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        dashboard.setBackgroundColor(dark ? 0xFF17101D : Color.WHITE);
        if (portrait) {
            dashboard.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams orderParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
            );
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(showBreakdown ? 250 : 196)
            );
            dashboard.addView(orderCard, orderParams);
            dashboard.addView(summary, summaryParams);
        } else {
            dashboard.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams orderParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, compact ? 2.65f : 3.35f
            );
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, compact ? 0.92f : 1.05f
            );
            if (template == 2) {
                dashboard.addView(summary, summaryParams);
                dashboard.addView(orderCard, orderParams);
            } else {
                dashboard.addView(orderCard, orderParams);
                dashboard.addView(summary, summaryParams);
            }
        }
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(dashboard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        showEmptyOrder("بانتظار أول طلب", "سيظهر الطلب هنا مباشرة عند إضافة صنف من الكاشير");
    }

    private LinearLayout createStatusChip() {
        statusChip = new LinearLayout(this);
        statusChip.setOrientation(LinearLayout.HORIZONTAL);
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(portrait ? 7 : (compact ? 9 : 12)), dp(portrait ? 4 : 6),
                dp(portrait ? 7 : (compact ? 9 : 12)), dp(portrait ? 4 : 6));
        statusChip.setBackground(strokeBg(dark ? 0xAA17101D : 0xEFFFFFFF, borderColor, 20));
        statusDot = text("●", portrait ? 8 : 10, accent);
        statusDot.setPadding(0, 0, dp(4), 0);
        statusChip.addView(statusDot);
        status = text("جاهز", portrait ? 9 : (compact ? 10 : 12), secondaryTextColor);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, 0);
        status.setMaxLines(1);
        if (portrait) {
            status.setMaxWidth(dp(76));
            status.setEllipsize(TextUtils.TruncateAt.END);
        }
        statusChip.addView(status);
        return statusChip;
    }

    private View createCustomerBrand(boolean onGradient) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(onGradient ? 12 : 8), dp(5), dp(onGradient ? 12 : 8), dp(5));
        if (onGradient) {
            frame.setBackground(strokeBg(0x20FFFFFF, 0x32FFFFFF, 18));
        }

        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        boolean hasLogo = false;
        String logoUri = ui.getString("logo", null);
        if (logoUri != null && !logoUri.trim().isEmpty()) {
            try {
                logo.setImageURI(Uri.parse(logoUri));
                hasLogo = logo.getDrawable() != null;
            } catch (Throwable ignored) { }
        }
        if (!hasLogo) {
            logo.setImageResource(R.drawable.ic_store);
            logo.setImageTintList(ColorStateList.valueOf(onGradient ? Color.WHITE : accent));
            logo.setPadding(dp(18), dp(10), dp(18), dp(10));
        }
        frame.addView(logo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        ObjectAnimator breathe = ObjectAnimator.ofFloat(logo, View.ALPHA, 0.90f, 1f);
        breathe.setDuration(2600);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.start();
        decorativeAnimators.add(breathe);
        return frame;
    }

    private View createCompanyBrand() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER);
        block.setTranslationY(-dp(3));

        FrameLayout glowHost = new FrameLayout(this);
        glowHost.setBackground(brandGlowBackground(portrait ? 112 : 132));
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.techlight_brand_transparent);
        brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        brand.setPadding(dp(12), dp(5), dp(12), dp(4));
        glowHost.addView(brand, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        block.addView(glowHost, new LinearLayout.LayoutParams(
                dp(portrait ? 164 : (compact ? 158 : 184)),
                0,
                1
        ));

        TextView website = text("techlight.sa", portrait ? 10 : (compact ? 10 : 11), Color.WHITE);
        website.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        website.setGravity(Gravity.CENTER);
        website.setLetterSpacing(0.08f);
        website.setShadowLayer(dp(6), 0, 0, 0xCCB974FF);
        block.addView(website, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ObjectAnimator shine = ObjectAnimator.ofFloat(brand, View.ALPHA, 0.86f, 1f);
        shine.setDuration(2100);
        shine.setRepeatMode(ValueAnimator.REVERSE);
        shine.setRepeatCount(ValueAnimator.INFINITE);
        shine.start();
        decorativeAnimators.add(shine);
        return block;
    }

    private GradientDrawable brandGlowBackground(int radiusDp) {
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.5f);
        glow.setGradientRadius(dp(radiusDp));
        glow.setColors(new int[]{0xF5FFFFFF, 0xAEEBDDFF, 0x38C69AFF, 0x00FFFFFF});
        return glow;
    }

    private TextView addSummaryMetric(LinearLayout host, String label, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(compact ? 6 : 8), 0, dp(compact ? 6 : 8));
        TextView name = text(label, compact ? 14 : 16, 0xFFE7DAF0);
        name.setPadding(0, 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text(initialValue, compact ? 15 : 17, Color.WHITE);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        value.setPadding(0, 0, 0, 0);
        row.addView(value);
        host.addView(row);
        return value;
    }

    private TextView addPortraitSummaryMetric(LinearLayout host, String label, String initialValue) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(4), dp(3), dp(4), dp(3));
        TextView name = text(label, 13, 0xFFE7DAF0);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, 0, 0, dp(1));
        column.addView(name);
        TextView value = text(initialValue, 16, Color.WHITE);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, 0, 0, 0);
        column.addView(value);
        host.addView(column, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
        ));
        return value;
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

    private int mix(int first, int second, float secondWeight) {
        float weight = Math.max(0f, Math.min(1f, secondWeight));
        int red = (int) (Color.red(first) * (1f - weight) + Color.red(second) * weight);
        int green = (int) (Color.green(first) * (1f - weight) + Color.green(second) * weight);
        int blue = (int) (Color.blue(first) * (1f - weight) + Color.blue(second) * weight);
        return Color.rgb(red, green, blue);
    }

    private void showEmptyOrder(String heading, String subheading) {
        String customerMessage = ui == null
                ? "أهلًا وسهلًا بك"
                : ui.getString("idle_message", ui.getString("welcome", "أهلًا وسهلًا بك"));
        showCustomerMoment(false, customerMessage, heading, subheading);
    }

    private void showCompletionMoment() {
        String customerMessage = ui == null
                ? "تم اعتماد طلبك بنجاح"
                : ui.getString("completed_message", "تم اعتماد طلبك بنجاح");
        showCustomerMoment(true, customerMessage, "تم إنشاء فاتورتك", "تحولت الأصناف إلى فاتورة سعودية معتمدة");
    }

    private void showCustomerMoment(
            boolean cooking,
            String customerMessage,
            String heading,
            String subheading
    ) {
        if (orderList == null) return;
        orderVisible = false;
        if (itemCount != null) itemCount.setText("◉  0 أصناف");
        if (unitCount != null) unitCount.setText("×  0 قطعة");
        orderList.removeAllViews();
        renderedRows.clear();
        renderedItemKeys.clear();
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(compact ? 10 : 18), dp(24), dp(compact ? 10 : 18));

        TextView message = text(
                customerMessage == null || customerMessage.trim().isEmpty()
                        ? (cooking ? "تم اعتماد طلبك بنجاح" : "أهلًا وسهلًا بك")
                        : customerMessage.trim(),
                portrait ? 21 : (compact ? 20 : 27),
                primaryTextColor
        );
        message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(8), 0, dp(8), dp(3));
        empty.addView(message);

        CustomerMomentView moment = new CustomerMomentView(this, cooking, accent, dark);
        LinearLayout.LayoutParams momentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(portrait ? 220 : (compact ? 236 : 300))
        );
        momentParams.setMargins(0, dp(3), 0, dp(3));
        empty.addView(moment, momentParams);

        TextView emptyTitle = text(heading, portrait ? 15 : (compact ? 14 : 18), accent);
        emptyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(dp(8), dp(2), dp(8), dp(2));
        empty.addView(emptyTitle);
        TextView emptySub = text(subheading, portrait ? 11 : (compact ? 10 : 12), secondaryTextColor);
        emptySub.setGravity(Gravity.CENTER);
        empty.addView(emptySub);
        orderList.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void animatePairingIcon(View view) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ROTATION, -4f, 4f);
        animator.setDuration(700);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.start();
        decorativeAnimators.add(animator);
    }

    private void stopDecorativeAnimations() {
        for (ValueAnimator animator : decorativeAnimators) {
            try { animator.cancel(); } catch (Exception ignored) { }
        }
        decorativeAnimators.clear();
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
        card.setBackground(strokeBg(surfaceColor, borderColor, 28));
        card.setElevation(dp(3));

        ImageView scanIcon = new ImageView(this);
        scanIcon.setImageResource(R.drawable.ic_scan_qr);
        scanIcon.setImageTintList(ColorStateList.valueOf(accent));
        scanIcon.setPadding(dp(15), dp(15), dp(15), dp(15));
        scanIcon.setBackground(round(dark ? mix(accent, surfaceColor, 0.72f) : lighten(accent, 0.88f), 24));
        card.addView(scanIcon, new LinearLayout.LayoutParams(dp(compact ? 72 : 90), dp(compact ? 72 : 90)));
        animatePairingIcon(scanIcon);

        TextView heading = text("اربط شاشة العميل", compact ? 22 : 27, primaryTextColor);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(dp(8), dp(10), dp(8), dp(2));
        card.addView(heading);
        TextView detail = text("افتح QR شاشة العميل في Tech Pro، ثم وجّه كاميرا هذا الجهاز إليه.", compact ? 13 : 15, secondaryTextColor);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(16), dp(2), dp(16), dp(15));
        card.addView(detail);

        TextView liveScan = action("مسح QR أو الباركود بالكاميرا", R.drawable.ic_scan_qr, true);
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

        TextView alternative = text("أو استخدم الربط اليدوي", 12, secondaryTextColor);
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
            scannerWaitingForPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
            return;
        }
        launchScanner();
    }

    private void launchScanner() {
        scannerWaitingForPermission = false;
        if (scannerActive || shell == null || isFinishing()) return;
        try {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setBackgroundColor(Color.BLACK);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            overlay.setElevation(dp(50));

            DecoratedBarcodeView scanner = new DecoratedBarcodeView(this);
            List<BarcodeFormat> formats = new ArrayList<>();
            formats.add(BarcodeFormat.QR_CODE);
            formats.add(BarcodeFormat.DATA_MATRIX);
            formats.add(BarcodeFormat.AZTEC);
            formats.add(BarcodeFormat.CODE_128);
            scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(formats));
            scanner.setStatusText("وجّه الكاميرا إلى رمز الاقتران");
            overlay.addView(scanner, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            controls.setPadding(dp(12), dp(12), dp(12), dp(12));
            controls.setBackgroundColor(0x99000000);

            TextView close = scannerButton("إغلاق");
            close.setOnClickListener(view -> closeScannerOverlay());
            controls.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1));

            TextView torch = scannerButton("الإضاءة");
            torch.setOnClickListener(view -> {
                if (barcodeScanner == null) return;
                try {
                    scannerTorchOn = !scannerTorchOn;
                    if (scannerTorchOn) barcodeScanner.setTorchOn();
                    else barcodeScanner.setTorchOff();
                    torch.setText(scannerTorchOn ? "إطفاء الإضاءة" : "الإضاءة");
                } catch (Throwable error) {
                    scannerTorchOn = false;
                    showToast("الإضاءة غير متاحة في كاميرا هذا الجهاز");
                }
            });
            LinearLayout.LayoutParams torchParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            torchParams.setMargins(dp(8), 0, dp(8), 0);
            controls.addView(torch, torchParams);

            TextView gallery = scannerButton("اختيار صورة");
            gallery.setOnClickListener(view -> {
                closeScannerOverlay();
                launchQrImagePicker();
            });
            controls.addView(gallery, new LinearLayout.LayoutParams(0, dp(48), 1));

            overlay.addView(controls, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
            ));

            scannerOverlay = overlay;
            barcodeScanner = scanner;
            scannerActive = true;
            scannerTorchOn = false;
            shell.addView(overlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            scanner.getBarcodeView().addStateListener(new CameraPreview.StateListener() {
                @Override public void previewSized() { }
                @Override public void previewStarted() {
                    writeDiagnostic("CAMERA_PREVIEW_READY", "Embedded scanner preview started");
                }
                @Override public void previewStopped() { }
                @Override public void cameraClosed() { }
                @Override public void cameraError(Exception error) {
                    runOnUiThread(() -> {
                        if (barcodeScanner == scanner) handleScannerFailure(error);
                    });
                }
            });
            scanner.decodeSingle(new BarcodeCallback() {
                @Override public void barcodeResult(BarcodeResult result) {
                    if (result == null || result.getText() == null) return;
                    runOnUiThread(() -> {
                        if (barcodeScanner != scanner) return;
                        String value = result.getText();
                        writeDiagnostic("LIVE_CODE_DECODED", "characters=" + value.length());
                        closeScannerOverlay();
                        pairText(value);
                    });
                }

                @Override public void possibleResultPoints(List<ResultPoint> resultPoints) { }
            });
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(180).start();
            handler.postDelayed(() -> {
                if (scannerActive && barcodeScanner == scanner) {
                    try {
                        scanner.resume();
                    } catch (Throwable error) {
                        handleScannerFailure(error);
                    }
                }
            }, 120);
            writeDiagnostic("CAMERA_LAUNCHED", "EmbeddedBarcodeView");
        } catch (Throwable error) {
            handleScannerFailure(error);
        }
    }

    private TextView scannerButton(String label) {
        TextView button = text(label, compact ? 12 : 14, Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(pressable(0xCC24152D, 0x55FFFFFF, 14, 0x33FFFFFF));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void closeScannerOverlay() {
        boolean rebuildForRotation = scannerOrientationChanged;
        scannerOrientationChanged = false;
        scannerActive = false;
        scannerTorchOn = false;
        DecoratedBarcodeView scanner = barcodeScanner;
        FrameLayout overlay = scannerOverlay;
        barcodeScanner = null;
        scannerOverlay = null;
        if (scanner != null) {
            try { scanner.setTorchOff(); } catch (Throwable ignored) { }
            try { scanner.pause(); } catch (Throwable ignored) { }
        }
        if (overlay != null && overlay.getParent() instanceof FrameLayout) {
            try { ((FrameLayout) overlay.getParent()).removeView(overlay); }
            catch (Throwable ignored) { }
        }
        if (rebuildForRotation && shell != null && !isFinishing()) {
            handler.post(() -> {
                if (scannerActive || isFinishing()) return;
                buildUi();
                if (client == null) restoreOrPair();
                else if (latestOrder != null) onOrder(latestOrder);
            });
        }
    }

    private void handleScannerFailure(Throwable error) {
        String detail = error == null
                ? "unknown camera error"
                : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        writeDiagnostic("CAMERA_FAILED", detail);
        closeScannerOverlay();
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("تعذّر تشغيل كاميرا هذا الجهاز")
                .setMessage("لن يغلق التطبيق. يمكنك اختيار صورة رمز الاقتران أو الربط بالعنوان يدويًا.")
                .setPositiveButton("اختيار صورة", (dialog, which) -> launchQrImagePicker())
                .setNegativeButton("إدخال يدوي", (dialog, which) -> manualPair())
                .show();
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
            scannerWaitingForPermission = false;
            launchScanner();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("صلاحية الكاميرا مطلوبة")
                .setMessage("فعّل صلاحية الكاميرا حتى يستطيع التطبيق مسح رمز الاقتران.")
                .setPositiveButton("فتح إعدادات التطبيق", (dialog, which) -> openAppSettings())
                .setNegativeButton("اختيار صورة", (dialog, which) -> launchQrImagePicker())
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
        if (request == SETTINGS_REQ) {
            if (result == RESULT_OK) {
                applyOrientationPreference();
                buildUi();
                restoreOrPair();
            }
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
            List<BarcodeFormat> formats = new ArrayList<>();
            formats.add(BarcodeFormat.QR_CODE);
            formats.add(BarcodeFormat.DATA_MATRIX);
            formats.add(BarcodeFormat.AZTEC);
            formats.add(BarcodeFormat.CODE_128);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
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
        // Reaching this method means a saved or newly validated pairing exists.
        // Keep the in-memory flag in sync even when SharedPreferences/app UI is rebuilding.
        paired = true;
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
        status.setTextColor(ok ? (dark ? 0xFF6ED5A8 : 0xFF176B46) : secondaryTextColor);
        statusDot.setTextColor(ok ? 0xFF188038 : accent);
        if (statusChip != null) {
            statusChip.setBackground(round(ok ? (dark ? 0xFF17372B : 0xFFE6F4EA) : softColor, 20));
        }
    }

    private void showToast(String value) {
        runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_LONG).show());
    }

    @Override public void onConnected() {
        runOnUiThread(() -> {
            setConnectionState("جاهز — بانتظار الطلب", true);
            showEmptyOrder("تم فتح اتصال Tech Pro", "بانتظار أول fullSnapshot من شاشة الكاشير");
            if (ui.getInt("able_mode", 0) > 0) scheduleIdle(10000);
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
        if (order == null) return;
        latestOrder = order;
        int catalogResolved = 0;
        try {
            catalogResolved = catalog == null ? 0 : catalog.enrich(order);
        } catch (Exception error) {
            // Live snapshots already include the display name and price. A catalog problem
            // must never prevent those authoritative values from reaching the customer.
            writeDiagnostic("CATALOG_ENRICH_FAILED",
                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
        final int resolved = catalogResolved;
        writeDiagnostic("ORDER_RECEIVED", "items=" + (order.items == null ? 0 : order.items.size())
                + " — catalogResolved=" + resolved + " — total=" + order.total);
        runOnUiThread(() -> {
            try {
                // A successfully parsed snapshot is stronger evidence than a stale UI flag.
                paired = true;
                if (orderList == null) buildUi();
                handler.removeCallbacks(idleTask);
                boolean empty = order.items == null || order.items.isEmpty();
                boolean wasOrderVisible = orderVisible;
                boolean completionEvent = OrderMomentPolicy.isCompletionEvent(
                        empty, wasOrderVisible, order.completed, completionMomentUntil
                );
                if (!empty && !order.completed) {
                    completionMomentUntil = 0;
                    orderVisible = true;
                    hideAdvertisingForOrder();
                }
                boolean holdCompletion = OrderMomentPolicy.shouldHoldCompletion(
                        completionEvent,
                        empty,
                        order.completed,
                        completionMomentUntil,
                        System.currentTimeMillis(),
                        ui.getInt("able_mode", 0) == 0
                );
                int rows = empty ? 0 : order.items.size();
                double units = 0;
                if (!empty) {
                    for (OrderState.Item item : order.items) units += item.qty;
                }
                if (itemCount != null) {
                    itemCount.setText("◉  " + rows + " صنف");
                    animateCounter(itemCount, true);
                }
                if (unitCount != null) {
                    unitCount.setText("×  " + formatQuantity(units) + " قطعة");
                    animateCounter(unitCount, false);
                }
                if (empty || order.completed) {
                    orderVisible = false;
                    if (completionEvent) {
                        // The branded receipt moment is rendered after totals are preserved below.
                    } else if (holdCompletion) {
                        long remaining = Math.max(250L, completionMomentUntil - System.currentTimeMillis());
                        if (ui.getInt("able_mode", 0) > 0) scheduleIdle(remaining);
                    } else {
                        completionMomentUntil = 0;
                        if (order.total > 0.0001) {
                            showEmptyOrder("وصل الإجمالي بدون الأصناف", "Tech Pro أرسل قيمة الفاتورة لكن قائمة الأصناف فارغة؛ انسخ تقرير التشخيص من الإعدادات");
                        } else {
                            showEmptyOrder("جاهز لاستقبال الطلب", "سيظهر أول صنف هنا فور إضافته من Tech Pro");
                        }
                        if (ui.getInt("able_mode", 0) > 0) scheduleIdle(10000);
                    }
                    setConnectionState("جاهز", true);
                } else {
                    renderOrderRows(order);
                }
                boolean preserveLastSummary = OrderMomentPolicy.shouldPreserveLastSummary(
                        completionEvent, empty, order.total
                );
                if (!holdCompletion && !preserveLastSummary) {
                    updateSummaryValues(order);
                    animateTotal(order.total);
                }
                if (completionEvent) {
                    setConnectionState(ui.getString("thanks", "شكرًا لزيارتكم"), true);
                    orderVisible = false;
                    long completionDisplayMs = OrderMomentPolicy.completionDisplayMs(ableDelayMs());
                    completionMomentUntil = System.currentTimeMillis() + completionDisplayMs;
                    showCompletionMoment();
                    if (ui.getInt("able_mode", 0) > 0) scheduleIdle(completionDisplayMs);
                } else if (!empty && !order.completed) {
                    setConnectionState("الطلب مباشر", true);
                }
                writeDiagnostic("ORDER_RENDERED", "items=" + rows
                        + " — catalogResolved=" + resolved + " — total=" + order.total);
            } catch (Exception error) {
                writeDiagnostic("ORDER_RENDER_FAILED",
                        error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                setConnectionState("وصل الطلب — تعذّر العرض", false);
            }
        });
    }

    private void hideAdvertisingForOrder() {
        if (embeddedAble != null) embeddedAble.hide();
        if (!ableExternalVisible) return;
        ableExternalVisible = false;
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) manager.moveTaskToFront(getTaskId(), ActivityManager.MOVE_TASK_WITH_HOME);
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            writeDiagnostic("ABLESIGN_RETURN", "Order received; customer display restored");
        } catch (Exception error) {
            writeDiagnostic("ABLESIGN_RETURN_FAILED",
                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private String itemKey(OrderState.Item item) {
        if (item.itemId > 0) return item.itemId + ":" + item.unitId;
        if (item.barcode != null && !item.barcode.trim().isEmpty()) return "b:" + item.barcode.trim();
        return "n:" + String.valueOf(item.name).trim().toLowerCase(Locale.US);
    }

    private void animateCounter(View counter, boolean clockwise) {
        if (counter == null) return;
        counter.animate().cancel();
        counter.setScaleX(0.86f);
        counter.setScaleY(0.86f);
        counter.setRotation(clockwise ? -2.5f : 2.5f);
        counter.animate()
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(330)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void updateSummaryValues(OrderState order) {
        if (order == null) return;
        double subtotal = order.subtotalIncluded ? order.subtotal : 0;
        if (!order.subtotalIncluded && order.items != null) {
            for (OrderState.Item item : order.items) subtotal += item.total();
        }
        double tax = order.taxIncluded ? order.tax : 0;
        double discount = order.discountIncluded ? Math.abs(order.discount) : 0;
        int metricSymbolSize = portrait ? 15 : (compact ? 14 : 16);
        if (subtotalValue != null) subtotalValue.setText(money(subtotal, metricSymbolSize, Color.WHITE));
        if (taxValue != null) taxValue.setText(money(tax, metricSymbolSize, Color.WHITE));
        if (discountValue != null) {
            SpannableStringBuilder discountText = new SpannableStringBuilder();
            if (discount > 0.0001) discountText.append("− ");
            discountText.append(money(discount, metricSymbolSize, Color.WHITE));
            discountValue.setText(discountText);
        }
    }

    private CharSequence money(double value, int symbolSizeDp, int symbolColor) {
        SpannableStringBuilder result = new SpannableStringBuilder(
                String.format(Locale.US, "%.2f ", value)
        );
        int symbolStart = result.length();
        result.append('\uFFFC');
        Drawable symbol = getDrawable(R.drawable.ic_saudi_riyal).mutate();
        symbol.setTint(symbolColor);
        int height = dp(symbolSizeDp);
        int width = Math.max(dp(8), Math.round(height * 0.895f));
        symbol.setBounds(0, 0, width, height);
        result.setSpan(new ImageSpan(symbol, ImageSpan.ALIGN_BASELINE), symbolStart,
                symbolStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private void animateTotal(double target) {
        if (total == null) return;
        if (totalAnimator != null) totalAnimator.cancel();
        totalAnimator = ValueAnimator.ofFloat((float) renderedTotal, (float) target);
        totalAnimator.setDuration(360);
        totalAnimator.setInterpolator(new DecelerateInterpolator());
        totalAnimator.addUpdateListener(value -> total.setText(money(
                ((Float) value.getAnimatedValue()).doubleValue(),
                portrait ? 34 : (compact ? 26 : 34),
                Color.WHITE
        )));
        totalAnimator.start();
        total.animate().cancel();
        total.setScaleX(0.92f);
        total.setScaleY(0.92f);
        total.setAlpha(0.82f);
        total.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator()).start();
        renderedTotal = target;
    }

    private void writeDiagnostic(String stage, String detail) {
        if (diagnostics == null) return;
        diagnostics.edit()
                .putString("stage", stage == null ? "" : stage)
                .putString("detail", detail == null ? "" : detail)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    private static final class RowHolder {
        TextView quantity;
        TextView name;
        TextView unitPrice;
        TextView lineTotal;
        FrameLayout imageFrame;
        ImageView image;
        String imagePath = "";
        double lastQuantity = Double.NaN;
        double lastTotal = Double.NaN;
        ValueAnimator highlightAnimator;
    }

    private void renderOrderRows(OrderState order) {
        if (orderList == null || order == null || order.items == null) return;
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Map<String, OrderState.Item> incoming = new LinkedHashMap<>();
        for (int sourceIndex = 0; sourceIndex < order.items.size(); sourceIndex++) {
            OrderState.Item item = order.items.get(sourceIndex);
            String base = itemKey(item);
            int occurrence = occurrences.containsKey(base) ? occurrences.get(base) + 1 : 0;
            occurrences.put(base, occurrence);
            incoming.put(base + "#" + occurrence, item);
        }

        Set<String> stale = new HashSet<>(renderedRows.keySet());
        stale.removeAll(incoming.keySet());
        for (String key : stale) {
            View row = renderedRows.remove(key);
            if (row != null) orderList.removeView(row);
        }

        List<String> previousVisualOrder = new ArrayList<>();
        for (int childIndex = 0; childIndex < orderList.getChildCount(); childIndex++) {
            View child = orderList.getChildAt(childIndex);
            for (Map.Entry<String, View> rendered : renderedRows.entrySet()) {
                if (rendered.getValue() == child
                        && incoming.containsKey(rendered.getKey())) {
                    previousVisualOrder.add(rendered.getKey());
                    break;
                }
            }
        }
        List<String> displayOrder = OrderDisplayOrder.arrange(
                incoming.keySet(), renderedRows.keySet(), previousVisualOrder
        );

        if (renderedRows.isEmpty()) orderList.removeAllViews();
        int targetIndex = 0;
        boolean hasNewRow = false;
        for (String key : displayOrder) {
            OrderState.Item item = incoming.get(key);
            if (item == null) continue;
            View row = renderedRows.get(key);
            boolean newlyAdded = row == null;
            if (newlyAdded) {
                row = createOrderRow();
                renderedRows.put(key, row);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(0, dp(denseRows ? 2 : 4), 0, dp(denseRows ? 2 : 4));
                orderList.addView(row, Math.min(targetIndex, orderList.getChildCount()), params);
                hasNewRow = true;
            } else {
                int currentIndex = orderList.indexOfChild(row);
                if (currentIndex != targetIndex && currentIndex >= 0) {
                    orderList.removeViewAt(currentIndex);
                    orderList.addView(row, Math.min(targetIndex, orderList.getChildCount()));
                }
            }
            updateOrderRow(row, item, targetIndex, newlyAdded);
            targetIndex++;
        }
        renderedItemKeys.clear();
        renderedItemKeys.addAll(incoming.keySet());
        if (hasNewRow && orderScroll != null) orderScroll.smoothScrollTo(0, 0);
    }

    private View createOrderRow() {
        boolean tight = compact || denseRows;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(tight ? 88 : 110));
        row.setPadding(dp(tight ? 9 : 14), dp(tight ? 6 : 10), dp(tight ? 9 : 14), dp(tight ? 6 : 10));

        RowHolder holder = new RowHolder();
        TextView qty = text("0 ×", tight ? 12 : 16, Color.WHITE);
        qty.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        qty.setGravity(Gravity.CENTER);
        qty.setBackground(round(accent, 16));
        qty.setMinWidth(dp(tight ? 46 : 62));
        qty.setPadding(dp(9), dp(tight ? 6 : 8), dp(9), dp(tight ? 6 : 8));
        holder.quantity = qty;

        LinearLayout product = new LinearLayout(this);
        product.setOrientation(LinearLayout.HORIZONTAL);
        product.setGravity(Gravity.CENTER_VERTICAL);
        product.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout nameBlock = new LinearLayout(this);
        nameBlock.setOrientation(LinearLayout.VERTICAL);
        nameBlock.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("", tight ? 16 : 22, primaryTextColor);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        name.setMaxLines(2);
        name.setPadding(dp(9), 0, dp(9), 0);
        nameBlock.addView(name);
        holder.name = name;
        TextView unitPrice = text("", tight ? 10 : 14, secondaryTextColor);
        unitPrice.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        unitPrice.setPadding(dp(9), dp(2), dp(9), 0);
        nameBlock.addView(unitPrice);
        holder.unitPrice = unitPrice;
        product.addView(nameBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        FrameLayout imageFrame = new FrameLayout(this);
        imageFrame.setBackground(strokeBg(softColor, borderColor, 17));
        imageFrame.setPadding(dp(2), dp(2), dp(2), dp(2));
        imageFrame.setVisibility(View.GONE);
        ImageView productImage = new ImageView(this);
        productImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        productImage.setBackground(round(softColor, 15));
        productImage.setClipToOutline(true);
        imageFrame.addView(productImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        int imageSize = dp(tight ? 72 : 94);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(imageSize, imageSize);
        imageParams.setMargins(dp(4), 0, dp(4), 0);
        product.addView(imageFrame, imageParams);
        holder.imageFrame = imageFrame;
        holder.image = productImage;

        TextView price = text("", tight ? 15 : 21, accent);
        price.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        price.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        holder.lineTotal = price;

        row.addView(price, new LinearLayout.LayoutParams(dp(tight ? 100 : 170), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(product, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(qty);
        row.setTag(holder);
        return row;
    }

    private void updateOrderRow(View view, OrderState.Item item, int index, boolean newlyAdded) {
        RowHolder holder = (RowHolder) view.getTag();
        int normalFill = rowStyle == 1
                ? surfaceColor
                : (index % 2 == 0 ? (dark ? 0xFF1E1625 : 0xFFF9F6FB) : surfaceColor);
        int normalStroke = rowStyle == 1 ? lighten(accent, 0.84f) : borderColor;
        int radius = rowStyle == 1 ? 9 : 19;
        if (holder.highlightAnimator != null) {
            holder.highlightAnimator.cancel();
            holder.highlightAnimator = null;
        }
        view.setBackground(strokeBg(
                newlyAdded
                        ? (dark ? mix(accent, surfaceColor, 0.70f) : lighten(accent, 0.93f))
                        : normalFill,
                newlyAdded ? mix(accent, borderColor, 0.30f) : normalStroke,
                radius
        ));
        boolean quantityChanged = !Double.isNaN(holder.lastQuantity)
                && Math.abs(holder.lastQuantity - item.qty) > 0.00001;
        boolean totalChanged = !Double.isNaN(holder.lastTotal)
                && Math.abs(holder.lastTotal - item.total()) > 0.00001;
        holder.quantity.setText(formatQuantity(item.qty) + " ×");
        holder.name.setText(item.name == null || item.name.trim().isEmpty() ? "صنف" : item.name.trim());
        SpannableStringBuilder unitPriceText = new SpannableStringBuilder("سعر الوحدة  ");
        unitPriceText.append(money(item.unitPrice, compact || denseRows ? 9 : 12, secondaryTextColor));
        holder.unitPrice.setText(unitPriceText);
        holder.lineTotal.setText(money(item.total(), compact || denseRows ? 13 : 18, accent));
        holder.lastQuantity = item.qty;
        holder.lastTotal = item.total();

        String imagePath = showProductImages ? ProductCatalog.clean(item.imagePath) : "";
        if (imagePath.isEmpty() || imageLoader == null) {
            holder.imagePath = "";
            holder.imageFrame.setVisibility(View.GONE);
            holder.image.setImageDrawable(null);
        } else if (!imagePath.equals(holder.imagePath)) {
            holder.imagePath = imagePath;
            holder.imageFrame.setVisibility(View.GONE);
            imageLoader.load(imagePath, holder.image, () -> {
                if (!imagePath.equals(holder.imagePath)) return;
                holder.imageFrame.setVisibility(View.VISIBLE);
                holder.imageFrame.setAlpha(0f);
                holder.imageFrame.setTranslationY(-dp(10));
                holder.imageFrame.setScaleX(0.92f);
                holder.imageFrame.setScaleY(0.92f);
                holder.imageFrame.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                        .setDuration(340)
                        .setInterpolator(new DecelerateInterpolator()).start();
            });
        }

        if (newlyAdded) {
            view.animate().cancel();
            view.setAlpha(0f);
            view.setTranslationY(-dp(24));
            view.setTranslationX(dp(8));
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            view.animate()
                    .alpha(1f)
                    .translationY(0)
                    .translationX(0)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(420)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            animateCounter(holder.quantity, true);
            animateCounter(holder.lineTotal, false);
            int highlightFill = dark ? mix(accent, surfaceColor, 0.70f) : lighten(accent, 0.93f);
            int highlightStroke = mix(accent, borderColor, 0.30f);
            holder.highlightAnimator = ValueAnimator.ofFloat(0f, 1f);
            holder.highlightAnimator.setStartDelay(120);
            holder.highlightAnimator.setDuration(680);
            holder.highlightAnimator.setInterpolator(new DecelerateInterpolator());
            holder.highlightAnimator.addUpdateListener(animation -> {
                float progress = (Float) animation.getAnimatedValue();
                view.setBackground(strokeBg(
                        mix(highlightFill, normalFill, progress),
                        mix(highlightStroke, normalStroke, progress),
                        radius
                ));
            });
            holder.highlightAnimator.start();
        } else {
            view.animate().cancel();
            view.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(180).start();
            if (quantityChanged) animateCounter(holder.quantity, true);
            if (totalChanged) animateCounter(holder.lineTotal, false);
        }
    }

    private LinearLayout createOrderColumns() {
        boolean tight = compact || denseRows;
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        columns.setPadding(dp(compact ? 10 : 14), dp(5), dp(compact ? 10 : 14), dp(5));

        TextView totalColumn = text("الإجمالي", tight ? 10 : 12, secondaryTextColor);
        totalColumn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        TextView itemColumn = text("الصنف وسعر الوحدة", tight ? 10 : 12, secondaryTextColor);
        itemColumn.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        itemColumn.setPadding(dp(8), 0, dp(8), 0);
        TextView quantityColumn = text("الكمية", tight ? 10 : 12, secondaryTextColor);
        quantityColumn.setGravity(Gravity.CENTER);

        columns.addView(totalColumn, new LinearLayout.LayoutParams(dp(tight ? 100 : 170), LinearLayout.LayoutParams.WRAP_CONTENT));
        columns.addView(itemColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        columns.addView(quantityColumn, new LinearLayout.LayoutParams(dp(tight ? 62 : 82), LinearLayout.LayoutParams.WRAP_CONTENT));
        return columns;
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

    private long ableDelayMs() {
        int seconds = ui == null ? 10 : ui.getInt("able_delay_seconds", 10);
        return Math.max(4, Math.min(60, seconds)) * 1000L;
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        if (scannerWaitingForPermission
                && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            handler.postDelayed(this::launchScanner, 180);
        } else if (scannerActive && barcodeScanner != null) {
            try { barcodeScanner.resume(); }
            catch (Throwable error) { handleScannerFailure(error); }
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        if (scannerActive) {
            scannerOrientationChanged = true;
            writeDiagnostic("ORIENTATION_CHANGED_DURING_SCAN", "Camera kept alive without Activity restart");
            return;
        }
        boolean keepCompletionMoment = completionMomentUntil > 0 && !orderVisible;
        buildUi();
        if (keepCompletionMoment) {
            showCompletionMoment();
            if (ui.getInt("able_mode", 0) > 0) {
                long remaining = Math.max(250L, completionMomentUntil - System.currentTimeMillis());
                scheduleIdle(remaining);
            }
            return;
        }
        if (client == null) {
            restoreOrPair();
        } else if (latestOrder != null) {
            onOrder(latestOrder);
        }
    }

    @Override protected void onPause() {
        if (scannerActive && barcodeScanner != null) {
            try { barcodeScanner.pause(); } catch (Throwable ignored) { }
        }
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ableExternalVisible = false;
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && imageLoader != null) {
            imageLoader.trimMemory();
        }
    }

    @Override public void onBackPressed() {
        if (scannerActive) {
            closeScannerOverlay();
            return;
        }
        if (embeddedAble != null && embeddedAble.isVisible()) {
            embeddedAble.hide();
            if (!orderVisible && ui.getInt("able_mode", 0) > 0) scheduleIdle(10000);
            return;
        }
        showSettingsButton();
        Toast.makeText(this, "شاشة العميل تعمل دائمًا — افتح الإعدادات للخروج أو تغيير الربط",
                Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        closeScannerOverlay();
        if (client != null) client.stop();
        if (catalog != null) catalog.close();
        if (imageLoader != null) imageLoader.shutdown();
        if (embeddedAble != null) embeddedAble.shutdown();
        if (totalAnimator != null) totalAnimator.cancel();
        stopDecorativeAnimations();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
