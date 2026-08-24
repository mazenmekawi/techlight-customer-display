package sa.techlight.customerdisplay;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslError;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Hosts AbleSign's official web player inside the customer-display activity.
 *
 * The official player generates its own six-digit pairing code and persists the
 * pairing in WebView storage. No AbleSign API key or screen id is required.
 */
public final class AbleSignEmbeddedPlayer {
    private static final String PLAYER_URL = "https://player.ablesign.tv";

    public interface Listener {
        void onState(String message, boolean error);
    }

    private final FrameLayout overlay;
    private final WebView player;
    private final TextView state;
    private final LinearLayout statusBadge;
    private final Listener listener;
    private boolean loaded;
    private boolean visible;
    private boolean resetting;
    private boolean destroyed;

    public AbleSignEmbeddedPlayer(Activity activity, FrameLayout host, Listener listener) {
        this.listener = listener;
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setVisibility(View.GONE);

        player = new WebView(activity);
        player.setBackgroundColor(Color.BLACK);
        player.setOverScrollMode(View.OVER_SCROLL_NEVER);
        configurePlayer(player);
        overlay.addView(player, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout badge = new LinearLayout(activity);
        statusBadge = badge;
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 13), dp(activity, 7), dp(activity, 13), dp(activity, 7));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xB3000000);
        badgeBg.setCornerRadius(dp(activity, 20));
        badge.setBackground(badgeBg);
        TextView dot = label(activity, "●", 11, 0xFF9B59C2);
        dot.setPadding(0, 0, dp(activity, 6), 0);
        badge.addView(dot);
        state = label(activity, "AbleSign — جاري تشغيل المشغّل", 12, Color.WHITE);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.addView(state);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        badgeParams.setMargins(dp(activity, 16), dp(activity, 14), dp(activity, 16), 0);
        overlay.addView(badge, badgeParams);

        host.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void configurePlayer(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + " TechLightCustomerDisplay/1.6");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                statusBadge.setAlpha(1f);
                state.setText("AbleSign — جاري التحميل");
                report("جاري فتح مشغّل AbleSign الرسمي", false);
            }

            @Override public void onPageFinished(WebView view, String url) {
                loaded = true;
                state.setText("AbleSign — جاهز");
                statusBadge.animate().alpha(0f).setStartDelay(1600).setDuration(450).start();
                report("AbleSign جاهز — أدخل كود الاقتران ذي 6 أرقام إذا ظهر", false);
            }

            @Override public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request != null && request.isForMainFrame()) {
                    statusBadge.setAlpha(1f);
                    state.setText("AbleSign — تحقق من الإنترنت");
                    report("تعذر تحميل AbleSign؛ ستحاول الشاشة مجددًا عند العرض", true);
                }
            }

            @Override public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error
            ) {
                handler.cancel();
                statusBadge.setAlpha(1f);
                state.setText("AbleSign — اتصال غير آمن");
                report("أوقف AbleSign اتصالًا غير آمن لحماية الاقتران", true);
            }
        });
    }

    public void show() {
        if (destroyed) return;
        visible = true;
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(360)
                .setInterpolator(new DecelerateInterpolator()).start();
        player.onResume();
        player.resumeTimers();
        if (!loaded && !resetting) {
            statusBadge.animate().cancel();
            statusBadge.setAlpha(1f);
            state.setText("AbleSign — جاري تشغيل المشغّل");
            player.loadUrl(PLAYER_URL);
        }
    }

    public void hide() {
        if (!visible && overlay.getVisibility() != View.VISIBLE) return;
        visible = false;
        overlay.animate().cancel();
        player.onPause();
        overlay.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (!visible) overlay.setVisibility(View.GONE);
        }).start();
    }

    /** Clears the official player's saved pairing so it generates a fresh code. */
    public void resetPairing() {
        if (destroyed) return;
        loaded = false;
        resetting = true;
        statusBadge.animate().cancel();
        statusBadge.setAlpha(1f);
        state.setText("AbleSign — جارٍ إنشاء كود اقتران جديد");
        try {
            WebStorage.getInstance().deleteAllData();
            player.stopLoading();
            player.clearCache(true);
            player.clearHistory();
            player.clearFormData();
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(removed -> {
                if (destroyed) return;
                player.post(() -> {
                    if (destroyed) return;
                cookies.flush();
                resetting = false;
                report("تم مسح اقتران AbleSign؛ سيظهر كود جديد الآن", false);
                if (visible) player.loadUrl(PLAYER_URL);
                });
            });
        } catch (Throwable error) {
            resetting = false;
            report("تعذر مسح اقتران AbleSign بأمان", true);
        }
    }

    public boolean isVisible() {
        return visible;
    }

    static String playerUrl() {
        return PLAYER_URL;
    }

    public void shutdown() {
        destroyed = true;
        visible = false;
        resetting = false;
        overlay.animate().cancel();
        try {
            player.stopLoading();
            player.onPause();
            player.destroy();
        } catch (Exception ignored) { }
    }

    private void report(String message, boolean error) {
        if (listener != null) listener.onState(message, error);
    }

    private static TextView label(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
