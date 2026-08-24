package sa.techlight.customerdisplay;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** A small in-app AbleSign playlist renderer for images, videos, and web apps. */
public final class AbleSignEmbeddedPlayer {
    private static final String API = "https://api.ablesign.tv/api/v1/";

    public interface Listener {
        void onState(String message, boolean error);
    }

    private static final class PlaylistItem {
        long mediaId;
        long webAppId;
        int sequence;
        int durationSeconds = 10;
        String type = "";
        String title = "";
        String url = "";
    }

    private final Activity activity;
    private final FrameLayout overlay;
    private final FrameLayout stage;
    private final TextView state;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final ProductImageLoader imageLoader;
    private final Listener listener;

    private List<PlaylistItem> items = Collections.emptyList();
    private int index;
    private int generation;
    private boolean visible;
    private VideoView video;
    private WebView web;
    private String apiKey = "";
    private String workspaceId = "";

    private final Runnable nextTask = this::playNext;

    public AbleSignEmbeddedPlayer(Activity activity, FrameLayout host, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        imageLoader = new ProductImageLoader(activity, "");
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setVisibility(View.GONE);

        stage = new FrameLayout(activity);
        overlay.addView(stage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout badge = new LinearLayout(activity);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(13), dp(7), dp(13), dp(7));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0x99000000);
        badgeBg.setCornerRadius(dp(20));
        badge.setBackground(badgeBg);
        TextView dot = label("●", 11, 0xFF9B59C2);
        dot.setPadding(0, 0, dp(6), 0);
        badge.addView(dot);
        state = label("AbleSign", 12, Color.WHITE);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.addView(state);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        badgeParams.setMargins(dp(16), dp(14), dp(16), 0);
        overlay.addView(badge, badgeParams);

        host.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    public void show(AbleSignSession session) {
        if (session == null || !session.isConfigured()) {
            report("إعداد AbleSign المدمج غير مكتمل", true);
            return;
        }
        apiKey = session.apiKey();
        workspaceId = session.workspaceId();
        visible = true;
        generation++;
        int requestGeneration = generation;
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(360).start();
        state.setText("AbleSign — جاري تحميل المحتوى");
        worker.execute(() -> loadPlaylist(session.screenId(), requestGeneration));
    }

    public void hide() {
        if (!visible && overlay.getVisibility() != View.VISIBLE) return;
        visible = false;
        generation++;
        main.removeCallbacks(nextTask);
        stopActiveMedia();
        overlay.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (!visible) {
                stage.removeAllViews();
                overlay.setVisibility(View.GONE);
            }
        }).start();
    }

    public boolean isVisible() {
        return visible;
    }

    private void loadPlaylist(long screenId, int requestGeneration) {
        try {
            JSONObject response = getObject(API + "screens/" + screenId + "/playlist");
            JSONObject data = response.optJSONObject("data");
            JSONArray rawItems = data == null ? null : data.optJSONArray("items");
            List<PlaylistItem> parsed = new ArrayList<>();
            if (rawItems != null) {
                for (int position = 0; position < rawItems.length(); position++) {
                    JSONObject raw = rawItems.optJSONObject(position);
                    if (raw == null) continue;
                    PlaylistItem item = new PlaylistItem();
                    item.mediaId = raw.optLong("mediafileId", 0);
                    item.webAppId = raw.optLong("webAppId", 0);
                    item.sequence = raw.optInt("sequenceNumber", position);
                    item.durationSeconds = Math.max(3, raw.optInt("displayDuration", 10));
                    item.type = raw.optString("fileType", "").toLowerCase(Locale.US);
                    item.title = raw.optString("title", "AbleSign");
                    if (item.mediaId > 0 || item.webAppId > 0) parsed.add(item);
                }
            }
            parsed.sort(Comparator.comparingInt(item -> item.sequence));
            for (PlaylistItem item : parsed) resolveItem(item);
            for (int position = parsed.size() - 1; position >= 0; position--) {
                PlaylistItem item = parsed.get(position);
                if (item.url == null || item.url.trim().isEmpty()) parsed.remove(position);
            }
            main.post(() -> {
                if (!visible || requestGeneration != generation) return;
                if (parsed.isEmpty()) {
                    state.setText("AbleSign — لا يوجد محتوى قابل للعرض");
                    report("قائمة AbleSign فارغة أو تعذر تنزيلها", true);
                    return;
                }
                items = parsed;
                index = 0;
                report("AbleSign مدمج — " + parsed.size() + " محتوى", false);
                playCurrent();
            });
        } catch (Exception error) {
            main.post(() -> {
                if (!visible || requestGeneration != generation) return;
                state.setText("AbleSign — تعذر تحميل المحتوى");
                report("تعذر اتصال AbleSign: " + friendly(error), true);
            });
        }
    }

    private void resolveItem(PlaylistItem item) {
        try {
            if (item.mediaId > 0) {
                JSONObject response = getObject(API + "media_files/" + item.mediaId);
                JSONObject data = response.optJSONObject("data");
                if (data == null) return;
                item.url = data.optString("accessUrl", "");
                if (item.type.isEmpty()) item.type = data.optString("fileType", "image");
                if ("video".equals(item.type) && data.optInt("duration", 0) > 0) {
                    item.durationSeconds = data.optInt("duration", item.durationSeconds);
                }
            } else if (item.webAppId > 0) {
                JSONObject response = getObject(API + "web_apps/" + item.webAppId);
                JSONObject data = response.optJSONObject("data");
                if (data == null) return;
                item.type = "webapp";
                item.url = data.optString("url", "");
                if (item.url.isEmpty()) item.url = data.optString("accessUrl", "");
            }
        } catch (Exception ignored) {
            item.url = "";
        }
    }

    private JSONObject getObject(String url) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + stripBearer(apiKey));
        if (!workspaceId.trim().isEmpty()) builder.header("Workspace-Id", workspaceId.trim());
        try (Response response = http.newCall(builder.build()).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
            return new JSONObject(raw);
        }
    }

    private void playCurrent() {
        if (!visible || items.isEmpty()) return;
        main.removeCallbacks(nextTask);
        stopActiveMedia();
        PlaylistItem item = items.get(Math.max(0, Math.min(index, items.size() - 1)));
        state.setText("AbleSign — " + (item.title.isEmpty() ? "محتوى إعلاني" : item.title));

        if ("video".equals(item.type)) {
            video = new VideoView(activity);
            video.setVideoURI(Uri.parse(item.url));
            video.setOnPreparedListener(player -> {
                player.setLooping(false);
                video.start();
            });
            video.setOnCompletionListener(player -> playNext());
            video.setOnErrorListener((player, what, extra) -> {
                main.postDelayed(nextTask, 700);
                return true;
            });
            swapStage(video);
            main.postDelayed(nextTask, Math.max(8, item.durationSeconds + 4) * 1000L);
            return;
        }

        if ("webapp".equals(item.type)) {
            web = new WebView(activity);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            web.setBackgroundColor(Color.BLACK);
            web.loadUrl(item.url);
            swapStage(web);
            main.postDelayed(nextTask, item.durationSeconds * 1000L);
            return;
        }

        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Color.BLACK);
        swapStage(image);
        imageLoader.load(item.url, image,
                () -> main.postDelayed(nextTask, item.durationSeconds * 1000L));
        main.postDelayed(nextTask, Math.max(12, item.durationSeconds + 8) * 1000L);
    }

    private void swapStage(View next) {
        stage.removeAllViews();
        next.setAlpha(0f);
        next.setScaleX(1.025f);
        next.setScaleY(1.025f);
        stage.addView(next, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void playNext() {
        if (!visible || items.isEmpty()) return;
        index = (index + 1) % items.size();
        playCurrent();
    }

    private void stopActiveMedia() {
        main.removeCallbacks(nextTask);
        if (video != null) {
            try { video.stopPlayback(); } catch (Exception ignored) { }
            video = null;
        }
        if (web != null) {
            try {
                web.onPause();
                web.stopLoading();
                web.destroy();
            } catch (Exception ignored) { }
            web = null;
        }
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void report(String message, boolean error) {
        if (listener != null) listener.onState(message, error);
    }

    private static String stripBearer(String value) {
        String key = value == null ? "" : value.trim();
        return key.toLowerCase(Locale.US).startsWith("bearer ") ? key.substring(7).trim() : key;
    }

    private static String friendly(Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("401") || message.contains("403")) return "مفتاح API غير صحيح";
        if (message.contains("404")) return "رقم شاشة AbleSign غير موجود";
        return message.trim().isEmpty() ? "تحقق من الإنترنت" : message;
    }

    public void shutdown() {
        hide();
        worker.shutdownNow();
        imageLoader.shutdown();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
    }
}
