package sa.techlight.customerdisplay;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

public final class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        boolean compact = getResources().getConfiguration().screenWidthDp < 600;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFDFBFF, 0xFFF3ECFA, 0xFFFFFFFF}
        );
        root.setBackground(background);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.techlight_brand_transparent);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(icon, new LinearLayout.LayoutParams(
                dp(compact ? 310 : 500), dp(compact ? 100 : 150)
        ));
        setContentView(root);

        icon.setAlpha(0f);
        icon.setScaleX(0.72f);
        icon.setScaleY(0.72f);
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(720).setInterpolator(new DecelerateInterpolator()).start();
        ObjectAnimator shine = ObjectAnimator.ofFloat(icon, View.ALPHA, 0.72f, 1f);
        shine.setDuration(640);
        shine.setRepeatMode(ValueAnimator.REVERSE);
        shine.setRepeatCount(1);
        shine.start();

        handler.postDelayed(() -> {
            TechProSession session = new TechProSession(this);
            ProductCatalog catalog = new ProductCatalog(this);
            boolean ready = session.isSignedIn() && catalog.count() > 0;
            catalog.close();
            startActivity(new Intent(this, ready ? MainActivity.class : LoginActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 1250);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
