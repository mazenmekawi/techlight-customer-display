package sa.techlight.customerdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Lightweight customer moment drawn directly on Canvas. It intentionally avoids GIFs,
 * videos and animation objects so idle screens stay smooth on older customer tablets.
 */
final class CustomerMomentView extends View {
    private final boolean cooking;
    private final int accent;
    private final boolean dark;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private long startedAt;
    private Shader faceShader;

    CustomerMomentView(Context context, boolean cooking, int accent, boolean dark) {
        super(context);
        this.cooking = cooking;
        this.accent = accent;
        this.dark = dark;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float radius = Math.min(width, height) * 0.30f;
        faceShader = new RadialGradient(
                width * 0.43f,
                height * 0.34f,
                radius * 1.35f,
                new int[]{0xFFFFF4A8, 0xFFFFD75E, 0xFFF8B831},
                new float[]{0f, 0.68f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        float unit = Math.min(getWidth(), getHeight());
        float bob = (float) Math.sin(seconds * 2.15f) * unit * 0.018f;
        float centerX = getWidth() * 0.5f;
        float centerY = getHeight() * (cooking ? 0.43f : 0.49f) + bob;
        float faceRadius = unit * (cooking ? 0.245f : 0.275f);

        fill.setShader(null);
        fill.setColor(dark ? 0x33000000 : 0x19000000);
        oval.set(centerX - faceRadius * 0.94f, centerY + faceRadius * 1.04f,
                centerX + faceRadius * 0.94f, centerY + faceRadius * 1.30f);
        canvas.drawOval(oval, fill);

        if (cooking) drawChefHat(canvas, centerX, centerY, faceRadius, seconds);
        drawFace(canvas, centerX, centerY, faceRadius, seconds);
        if (cooking) drawCooking(canvas, centerX, centerY, faceRadius, seconds);
        else drawWelcome(canvas, centerX, centerY, faceRadius, seconds);

        if (isShown()) postInvalidateDelayed(32L);
    }

    private void drawFace(Canvas canvas, float x, float y, float radius, float seconds) {
        fill.setShader(faceShader);
        canvas.drawCircle(x, y, radius, fill);
        fill.setShader(null);

        stroke.setColor(0x332A1830);
        stroke.setStrokeWidth(Math.max(2f, radius * 0.045f));
        canvas.drawCircle(x, y, radius, stroke);

        float blinkPhase = seconds % 4.2f;
        boolean blink = blinkPhase > 3.88f;
        float eyeY = y - radius * 0.16f;
        float eyeDx = radius * 0.36f;
        stroke.setColor(0xFF372B35);
        stroke.setStrokeWidth(radius * 0.085f);
        if (blink) {
            canvas.drawLine(x - eyeDx - radius * 0.09f, eyeY,
                    x - eyeDx + radius * 0.09f, eyeY, stroke);
            canvas.drawLine(x + eyeDx - radius * 0.09f, eyeY,
                    x + eyeDx + radius * 0.09f, eyeY, stroke);
        } else {
            fill.setColor(0xFF372B35);
            canvas.drawCircle(x - eyeDx, eyeY, radius * 0.075f, fill);
            canvas.drawCircle(x + eyeDx, eyeY, radius * 0.075f, fill);
            fill.setColor(0xCCFFFFFF);
            canvas.drawCircle(x - eyeDx - radius * 0.018f, eyeY - radius * 0.022f, radius * 0.018f, fill);
            canvas.drawCircle(x + eyeDx - radius * 0.018f, eyeY - radius * 0.022f, radius * 0.018f, fill);
        }

        fill.setColor(0x39ED6682);
        canvas.drawCircle(x - radius * 0.55f, y + radius * 0.10f, radius * 0.12f, fill);
        canvas.drawCircle(x + radius * 0.55f, y + radius * 0.10f, radius * 0.12f, fill);

        stroke.setColor(0xFF5B2940);
        stroke.setStrokeWidth(radius * 0.085f);
        oval.set(x - radius * 0.36f, y - radius * 0.02f,
                x + radius * 0.36f, y + radius * 0.48f);
        canvas.drawArc(oval, 20f, 140f, false, stroke);
    }

    private void drawWelcome(Canvas canvas, float x, float y, float radius, float seconds) {
        float wave = (float) Math.sin(seconds * 4.1f) * 10f;
        canvas.save();
        canvas.rotate(wave, x + radius * 1.05f, y + radius * 0.08f);
        fill.setColor(0xFFFFD469);
        canvas.drawCircle(x + radius * 1.05f, y + radius * 0.02f, radius * 0.23f, fill);
        stroke.setColor(0xFFB77B24);
        stroke.setStrokeWidth(radius * 0.07f);
        canvas.drawLine(x + radius * 0.94f, y - radius * 0.16f,
                x + radius * 0.85f, y - radius * 0.38f, stroke);
        canvas.drawLine(x + radius * 1.05f, y - radius * 0.19f,
                x + radius * 1.04f, y - radius * 0.43f, stroke);
        canvas.drawLine(x + radius * 1.15f, y - radius * 0.15f,
                x + radius * 1.22f, y - radius * 0.36f, stroke);
        canvas.drawLine(x + radius * 0.88f, y + radius * 0.18f,
                x + radius * 0.70f, y + radius * 0.40f, stroke);
        canvas.restore();

        float pulse = 0.75f + 0.25f * (float) Math.sin(seconds * 2.4f);
        drawSparkle(canvas, x - radius * 1.18f, y - radius * 0.68f, radius * 0.16f, pulse);
        drawSparkle(canvas, x + radius * 1.08f, y - radius * 0.73f, radius * 0.11f, 1f - pulse * 0.35f);
    }

    private void drawSparkle(Canvas canvas, float x, float y, float size, float alpha) {
        stroke.setColor(withAlpha(accent, Math.round(100 + 130 * Math.max(0f, Math.min(1f, alpha)))));
        stroke.setStrokeWidth(Math.max(2f, size * 0.18f));
        canvas.drawLine(x - size, y, x + size, y, stroke);
        canvas.drawLine(x, y - size, x, y + size, stroke);
    }

    private void drawChefHat(Canvas canvas, float x, float y, float radius, float seconds) {
        float top = y - radius * 1.30f;
        fill.setColor(0xFFFDFBFF);
        stroke.setColor(0xFFD9D1DF);
        stroke.setStrokeWidth(radius * 0.045f);
        canvas.drawCircle(x - radius * 0.38f, top, radius * 0.36f, fill);
        canvas.drawCircle(x, top - radius * 0.16f, radius * 0.42f, fill);
        canvas.drawCircle(x + radius * 0.38f, top, radius * 0.36f, fill);
        oval.set(x - radius * 0.67f, top, x + radius * 0.67f, y - radius * 0.74f);
        canvas.drawRoundRect(oval, radius * 0.12f, radius * 0.12f, fill);
        canvas.drawLine(x - radius * 0.55f, y - radius * 0.78f,
                x + radius * 0.55f, y - radius * 0.78f, stroke);
    }

    private void drawCooking(Canvas canvas, float x, float y, float radius, float seconds) {
        float potTop = y + radius * 0.70f;
        fill.setColor(accent);
        oval.set(x - radius * 0.78f, potTop, x + radius * 0.78f, y + radius * 1.23f);
        canvas.drawRoundRect(oval, radius * 0.16f, radius * 0.16f, fill);
        fill.setColor(lighten(accent, 0.28f));
        oval.set(x - radius * 0.88f, potTop - radius * 0.09f,
                x + radius * 0.88f, potTop + radius * 0.08f);
        canvas.drawRoundRect(oval, radius * 0.08f, radius * 0.08f, fill);

        float stir = (float) Math.sin(seconds * 3.2f) * radius * 0.18f;
        stroke.setColor(0xFF8A5B36);
        stroke.setStrokeWidth(radius * 0.095f);
        canvas.drawLine(x + stir, potTop, x + radius * 0.48f + stir,
                y + radius * 0.18f, stroke);

        stroke.setColor(0xAAFFFFFF);
        stroke.setStrokeWidth(radius * 0.055f);
        for (int i = -1; i <= 1; i++) {
            float phase = seconds * 1.35f + i * 0.7f;
            float lift = (phase % 1.5f) / 1.5f;
            float steamX = x + i * radius * 0.30f + (float) Math.sin(phase * 3f) * radius * 0.06f;
            float steamY = potTop - radius * (0.18f + lift * 0.62f);
            canvas.drawLine(steamX, steamY, steamX + radius * 0.04f,
                    steamY - radius * 0.16f, stroke);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int lighten(int color, float amount) {
        int red = Math.round(Color.red(color) + (255 - Color.red(color)) * amount);
        int green = Math.round(Color.green(color) + (255 - Color.green(color)) * amount);
        int blue = Math.round(Color.blue(color) + (255 - Color.blue(color)) * amount);
        return Color.rgb(red, green, blue);
    }
}
