package sa.techlight.customerdisplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Loads the public TechLight website brand mark while preserving the local fallback image on failure. */
public final class WebsiteBrandLoader {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private Bitmap cached;

    public void load(String url, ImageView target) {
        if (target == null || url == null || url.trim().isEmpty()) return;
        target.setTag(url);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    Request request = new Request.Builder().url(url).get().header("Accept", "image/webp,image/png,image/jpeg,*/*").build();
                    try (Response response = http.newCall(request).execute()) {
                        if (!response.isSuccessful() || response.body() == null) return;
                        byte[] bytes = response.body().bytes();
                        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) return;
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bitmap == null) return;
                        cached = bitmap;
                        main.post(() -> {
                            if (!url.equals(String.valueOf(target.getTag()))) return;
                            target.setImageBitmap(bitmap);
                            target.setAlpha(0f);
                            target.animate().alpha(1f).setDuration(220).start();
                        });
                    }
                } catch (Throwable ignored) {
                    // Keep the bundled fallback mark visible.
                }
            });
        } catch (Throwable ignored) { }
    }

    public void shutdown() {
        worker.shutdownNow();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        if (cached != null && !cached.isRecycled()) cached.recycle();
        cached = null;
    }
}
