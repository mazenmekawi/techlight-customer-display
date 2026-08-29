package sa.techlight.customerdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Loads optional TechPro item pictures without ever blocking order rendering. */
public final class ProductImageLoader {
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final String NEW_API_ORIGIN = "https://posapifornewapp.techlight.sa/";
    private static final String LEGACY_API_ORIGIN = "https://posapi.techlight.sa/";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory;
    private final OkHttpClient http;
    private final String bearerToken;

    public ProductImageLoader(Context context, String token) {
        bearerToken = token == null ? "" : token.trim();
        int cacheKilobytes = Math.max(4096, (int) (Runtime.getRuntime().maxMemory() / 1024L / 10L));
        memory = new LruCache<String, Bitmap>(cacheKilobytes) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        File disk = new File(context.getCacheDir(), "techpro_product_images");
        http = new OkHttpClient.Builder()
                .cache(new Cache(disk, 48L * 1024L * 1024L))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void load(String rawPath, ImageView target, Runnable onLoaded) {
        String key = ProductCatalog.clean(rawPath);
        target.setTag(key);
        // Preserve a bundled fallback drawable (used by the website brand mark) while network loading.
        if (target.getDrawable() == null) target.setVisibility(View.GONE);
        else target.setVisibility(View.VISIBLE);
        if (key.isEmpty()) return;

        Bitmap cached;
        synchronized (memory) {
            cached = memory.get(key);
        }
        if (cached != null && !cached.isRecycled()) {
            showIfCurrent(key, cached, target, onLoaded);
            return;
        }

        if (worker.isShutdown()) return;
        try {
            worker.execute(() -> {
                Bitmap decoded = decodeDataUri(key);
                if (decoded == null) {
                    for (String candidate : candidateUrls(key)) {
                        decoded = download(candidate);
                        if (decoded != null) break;
                    }
                }
                if (decoded == null) return;
                try {
                    synchronized (memory) {
                        memory.put(key, decoded);
                    }
                } catch (Throwable ignored) { }
                showIfCurrent(key, decoded, target, onLoaded);
            });
        } catch (RuntimeException ignored) {
            // The activity may have closed between the request and executor dispatch.
        }
    }

    private Bitmap download(String url) {
        try {
            Request.Builder request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*");
            if (!bearerToken.isEmpty() && url.toLowerCase(Locale.US).contains("techlight.sa")) {
                request.header("Authorization", "Bearer " + stripBearer(bearerToken));
            }
            try (Response response = http.newCall(request.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                long length = response.body().contentLength();
                if (length > MAX_IMAGE_BYTES) return null;
                byte[] bytes = response.body().bytes();
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) return null;
                return decodeSampled(bytes, 900, 900);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Bitmap decodeDataUri(String value) {
        if (!value.toLowerCase(Locale.US).startsWith("data:image/")) return null;
        int comma = value.indexOf(',');
        if (comma < 0 || comma == value.length() - 1) return null;
        try {
            byte[] bytes = Base64.decode(value.substring(comma + 1), Base64.DEFAULT);
            if (bytes.length > MAX_IMAGE_BYTES) return null;
            return decodeSampled(bytes, 900, 900);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Bitmap decodeSampled(byte[] bytes, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > targetWidth * 2
                || bounds.outHeight / sample > targetHeight * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void showIfCurrent(String key, Bitmap bitmap, ImageView target, Runnable onLoaded) {
        main.post(() -> {
            if (!key.equals(String.valueOf(target.getTag()))) return;
            target.setImageBitmap(bitmap);
            target.setVisibility(View.VISIBLE);
            target.setAlpha(0f);
            target.setScaleX(0.9f);
            target.setScaleY(0.9f);
            target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
            if (onLoaded != null) onLoaded.run();
        });
    }

    static List<String> candidateUrls(String rawPath) {
        String value = ProductCatalog.clean(rawPath).replace("&amp;", "&");
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        List<String> result = new ArrayList<>();
        if (value.isEmpty() || value.toLowerCase(Locale.US).startsWith("data:image/")) return result;
        if (value.startsWith("//")) {
            result.add("https:" + value);
            return result;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            result.add(value);
            return result;
        }
        String relative = value.startsWith("/") ? value.substring(1) : value;
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(NEW_API_ORIGIN + relative);
        candidates.add(LEGACY_API_ORIGIN + relative);
        result.addAll(candidates);
        return result;
    }

    private static String stripBearer(String token) {
        String value = token == null ? "" : token.trim();
        return value.toLowerCase(Locale.US).startsWith("bearer ")
                ? value.substring(7).trim() : value;
    }

    public void shutdown() {
        worker.shutdownNow();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        synchronized (memory) {
            memory.evictAll();
        }
    }

    public void trimMemory() {
        synchronized (memory) {
            memory.evictAll();
        }
    }
}
