package sa.techlight.customerdisplay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** Lightweight TechLight-branded customer mascot with welcome and chef states. */
public final class TechLightMascotView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private boolean cooking;
    private int accent = 0xFF4D0E81;
    private float phase;

    public TechLightMascotView(Context context) {
        super(context);
        init();
    }

    public TechLightMascotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    public void configure(int accentColor, boolean chefMode) {
        accent = accentColor;
        cooking = chefMode;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startMotion();
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }

    private void startMotion() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2300);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(value -> {
            phase = (Float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopMotion() {
        if (animator != null) animator.cancel();
        animator = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float unit = Math.min(width, height) / 100f;
        float bob = (float) Math.sin(phase * Math.PI * 2) * unit * 1.4f;
        float cx = width / 2f;
        float cy = height / 2f + bob + (cooking ? unit * 3f : 0f);
        float radius = unit * (cooking ? 27f : 31f);

        paint.setShader(null);
        paint.setColor(withAlpha(accent, 28));
        canvas.drawCircle(cx, cy, radius + unit * (8f + phase * 2f), paint);
        paint.setColor(withAlpha(accent, 45));
        canvas.drawCircle(cx, cy, radius + unit * 4f, paint);

        if (cooking) drawChefHat(canvas, cx, cy - radius, unit);

        paint.setShader(new LinearGradient(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius,
                lighten(accent, 0.28f),
                darken(accent, 0.22f),
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        paint.setColor(0x35FFFFFF);
        canvas.drawOval(new RectF(cx - radius * 0.58f, cy - radius * 0.66f,
                cx + radius * 0.12f, cy - radius * 0.17f), paint);

        float blink = phase > 0.91f ? 0.16f : 1f;
        paint.setColor(Color.WHITE);
        canvas.drawOval(eye(cx - radius * 0.32f, cy - radius * 0.12f, radius, blink), paint);
        canvas.drawOval(eye(cx + radius * 0.32f, cy - radius * 0.12f, radius, blink), paint);
        if (blink > 0.5f) {
            paint.setColor(0xFF2A173A);
            canvas.drawCircle(cx - radius * 0.29f, cy - radius * 0.10f, radius * 0.065f, paint);
            canvas.drawCircle(cx + radius * 0.29f, cy - radius * 0.10f, radius * 0.065f, paint);
        }

        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(unit * 3.1f);
        rect.set(cx - radius * 0.42f, cy - radius * 0.06f,
                cx + radius * 0.42f, cy + radius * 0.52f);
        canvas.drawArc(rect, cooking ? 18f : 15f, cooking ? 144f : 150f, false, stroke);

        paint.setColor(0x45FFFFFF);
        canvas.drawCircle(cx - radius * 0.58f, cy + radius * 0.14f, radius * 0.11f, paint);
        canvas.drawCircle(cx + radius * 0.58f, cy + radius * 0.14f, radius * 0.11f, paint);

        if (cooking) drawCookingScene(canvas, cx, cy, radius, unit);
    }

    private RectF eye(float cx, float cy, float radius, float blink) {
        float halfWidth = radius * 0.11f;
        float halfHeight = Math.max(radius * 0.018f, radius * 0.15f * blink);
        return new RectF(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
    }

    private void drawChefHat(Canvas canvas, float cx, float faceTop, float unit) {
        paint.setColor(Color.WHITE);
        float baseY = faceTop + unit * 5f;
        canvas.drawCircle(cx - unit * 12f, baseY - unit * 8f, unit * 9f, paint);
        canvas.drawCircle(cx, baseY - unit * 12f, unit * 11f, paint);
        canvas.drawCircle(cx + unit * 12f, baseY - unit * 8f, unit * 9f, paint);
        rect.set(cx - unit * 20f, baseY - unit * 7f, cx + unit * 20f, baseY + unit * 5f);
        canvas.drawRoundRect(rect, unit * 5f, unit * 5f, paint);
        paint.setColor(accent);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(unit * 9f);
        paint.setFakeBoldText(true);
        canvas.drawText("T", cx, baseY + unit * 2f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawCookingScene(Canvas canvas, float cx, float cy, float radius, float unit) {
        float potTop = cy + radius * 0.78f;
        paint.setColor(0xFF34213F);
        rect.set(cx - unit * 24f, potTop, cx + unit * 24f, potTop + unit * 13f);
        canvas.drawRoundRect(rect, unit * 5f, unit * 5f, paint);
        paint.setColor(lighten(accent, 0.18f));
        rect.set(cx - unit * 27f, potTop - unit * 2f, cx + unit * 27f, potTop + unit * 3f);
        canvas.drawRoundRect(rect, unit * 3f, unit * 3f, paint);

        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(unit * 2.2f);
        float stir = (float) Math.sin(phase * Math.PI * 4) * unit * 4f;
        canvas.drawLine(cx + stir, potTop - unit * 2f, cx + unit * 13f + stir, potTop - unit * 21f, stroke);
        canvas.drawCircle(cx + unit * 14f + stir, potTop - unit * 23f, unit * 3f, stroke);

        stroke.setColor(0xAAFFFFFF);
        stroke.setStrokeWidth(unit * 1.7f);
        for (int i = -1; i <= 1; i++) {
            float steamOffset = ((phase + (i + 1) * 0.18f) % 1f) * unit * 10f;
            float x = cx + i * unit * 9f;
            canvas.drawLine(x, potTop - unit * 5f - steamOffset,
                    x + unit * 2f, potTop - unit * 10f - steamOffset, stroke);
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int lighten(int color, float factor) {
        return Color.rgb(
                (int) (Color.red(color) + (255 - Color.red(color)) * factor),
                (int) (Color.green(color) + (255 - Color.green(color)) * factor),
                (int) (Color.blue(color) + (255 - Color.blue(color)) * factor)
        );
    }

    private int darken(int color, float factor) {
        return Color.rgb(
                (int) (Color.red(color) * (1f - factor)),
                (int) (Color.green(color) * (1f - factor)),
                (int) (Color.blue(color) * (1f - factor))
        );
    }
}
