package sa.techlight.customerdisplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(42, 16, 69), Color.rgb(111, 57, 158)}
        );
        root.setBackground(background);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_techlight);
        int iconSize = dp(compact ? 104 : 132);
        root.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView arabic = new TextView(this);
        arabic.setText("ضوء التقنية");
        arabic.setTextColor(Color.WHITE);
        arabic.setTextSize(compact ? 31 : 38);
        arabic.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        arabic.setGravity(Gravity.CENTER);
        arabic.setPadding(0, dp(20), 0, dp(4));
        root.addView(arabic);

        TextView english = new TextView(this);
        english.setText("TechLight Customer Display");
        english.setTextColor(Color.rgb(225, 213, 239));
        english.setTextSize(compact ? 15 : 18);
        english.setGravity(Gravity.CENTER);
        root.addView(english);

        TextView subtitle = new TextView(this);
        subtitle.setText("شاشة عميل ذكية  •  طلبات مباشرة  •  محتوى إعلاني");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setAlpha(0.88f);
        subtitle.setTextSize(compact ? 12 : 15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(17), 0, 0);
        root.addView(subtitle);
        setContentView(root);

        icon.setAlpha(0f);
        icon.setScaleX(0.72f);
        icon.setScaleY(0.72f);
        icon.setRotation(-7f);
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(720).setInterpolator(new DecelerateInterpolator()).start();
        arabic.setAlpha(0f);
        arabic.setTranslationY(dp(10));
        arabic.animate().alpha(1f).translationY(0).setStartDelay(260).setDuration(520).start();
        english.setAlpha(0f);
        english.animate().alpha(1f).setStartDelay(480).setDuration(420).start();
        subtitle.setAlpha(0f);
        subtitle.animate().alpha(0.88f).setStartDelay(650).setDuration(420).start();

        handler.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 2200);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
