package sa.techlight.customerdisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/** Lightweight branded customer moment with no video/GIF allocations. */
final class CustomerMomentView extends View {
    private final boolean completed;
    private final int accent;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path receiptPath = new Path();
    private final Drawable mascot;
    private long startedAt;
    private Shader halo;

    CustomerMomentView(Context context, boolean completed, int accent, boolean dark) {
        super(context);
        this.completed = completed;
        this.accent = accent;
        this.mascot = context.getDrawable(R.drawable.techlight_mascot);
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
        float radius = Math.min(width, height) * 0.58f;
        halo = new RadialGradient(
                width * 0.50f,
                height * 0.52f,
                radius,
                new int[]{withAlpha(lighten(accent, 0.55f), 76), withAlpha(accent, 18), Color.TRANSPARENT},
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        float unit = Math.min(getWidth(), getHeight());

        fill.setShader(halo);
        canvas.drawCircle(getWidth() * 0.50f, getHeight() * 0.53f, unit * 0.55f, fill);
        fill.setShader(null);

        drawMascot(canvas, seconds, unit);
        if (completed) drawReceipt(canvas, seconds, unit);
        else drawWelcomeSparkles(canvas, seconds, unit);

        if (isShown()) postInvalidateDelayed(32L);
    }

    private void drawMascot(Canvas canvas, float seconds, float unit) {
        float bob = (float) Math.sin(seconds * 2.25f) * unit * 0.018f;
        float breathe = 1f + (float) Math.sin(seconds * 1.65f) * 0.012f;
        float rotation = (float) Math.sin(seconds * 1.35f) * 1.2f;
        float desiredHeight = getHeight() * (completed ? 0.83f : 0.94f);
        float ratio = mascot == null || mascot.getIntrinsicHeight() <= 0
                ? 0.82f
                : (float) mascot.getIntrinsicWidth() / mascot.getIntrinsicHeight();
        float desiredWidth = Math.min(getWidth() * 0.86f, desiredHeight * ratio);
        desiredHeight = desiredWidth / ratio;
        float centerX = getWidth() * (completed ? 0.40f : 0.50f);
        float centerY = getHeight() * 0.51f + bob;

        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.rotate(rotation);
        canvas.scale(breathe, breathe);
        if (mascot != null) {
            mascot.setBounds(
                    Math.round(-desiredWidth * 0.5f),
                    Math.round(-desiredHeight * 0.5f),
                    Math.round(desiredWidth * 0.5f),
                    Math.round(desiredHeight * 0.5f)
            );
            mascot.draw(canvas);
        } else {
            fill.setColor(accent);
            canvas.drawCircle(0, 0, unit * 0.28f, fill);
            fill.setColor(Color.WHITE);
            fill.setTextAlign(Paint.Align.CENTER);
            fill.setTextSize(unit * 0.30f);
            fill.setFakeBoldText(true);
            canvas.drawText("T", 0, unit * 0.10f, fill);
            fill.setFakeBoldText(false);
        }
        canvas.restore();
    }

    private void drawWelcomeSparkles(Canvas canvas, float seconds, float unit) {
        float pulse = 0.55f + 0.45f * (float) Math.sin(seconds * 2.2f);
        drawSparkle(canvas, getWidth() * 0.16f, getHeight() * 0.22f, unit * 0.075f, pulse);
        drawSparkle(canvas, getWidth() * 0.82f, getHeight() * 0.28f, unit * 0.052f, 1f - pulse * 0.35f);
        drawSparkle(canvas, getWidth() * 0.76f, getHeight() * 0.76f, unit * 0.038f,
                0.65f + 0.35f * (float) Math.sin(seconds * 1.7f + 1f));
    }

    private void drawSparkle(Canvas canvas, float x, float y, float size, float alpha) {
        stroke.setColor(withAlpha(lighten(accent, 0.18f), Math.round(90 + 155 * clamp(alpha))));
        stroke.setStrokeWidth(Math.max(2f, size * 0.18f));
        canvas.drawLine(x - size, y, x + size, y, stroke);
        canvas.drawLine(x, y - size, x, y + size, stroke);
        canvas.drawCircle(x, y, size * 0.18f, stroke);
    }

    private void drawReceipt(Canvas canvas, float seconds, float unit) {
        float progress = easeOutBack(clamp((seconds - 0.12f) / 0.82f));
        float width = getWidth() * 0.53f;
        float height = getHeight() * 0.65f;
        float centerX = getWidth() * 0.68f;
        float finalCenterY = getHeight() * 0.53f;
        float centerY = getHeight() + height * 0.35f
                + (finalCenterY - getHeight() - height * 0.35f) * progress;

        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(Math.max(0.72f, progress), Math.max(0.72f, progress));

        rect.set(-width * 0.5f + unit * 0.018f, -height * 0.5f + unit * 0.025f,
                width * 0.5f + unit * 0.018f, height * 0.5f + unit * 0.025f);
        fill.setColor(0x26000000);
        canvas.drawRoundRect(rect, unit * 0.055f, unit * 0.055f, fill);

        receiptPath.reset();
        float left = -width * 0.5f;
        float right = width * 0.5f;
        float top = -height * 0.5f;
        float bottom = height * 0.43f;
        float tooth = width / 12f;
        receiptPath.moveTo(left, top + unit * 0.055f);
        receiptPath.quadTo(left, top, left + unit * 0.055f, top);
        receiptPath.lineTo(right - unit * 0.055f, top);
        receiptPath.quadTo(right, top, right, top + unit * 0.055f);
        receiptPath.lineTo(right, bottom);
        for (int i = 0; i < 6; i++) {
            receiptPath.lineTo(right - tooth * (i * 2 + 1), bottom + unit * 0.055f);
            receiptPath.lineTo(right - tooth * (i * 2 + 2), bottom);
        }
        receiptPath.lineTo(left, top + unit * 0.055f);
        receiptPath.close();
        fill.setColor(Color.WHITE);
        canvas.drawPath(receiptPath, fill);

        float checkY = top + height * 0.20f;
        float checkRadius = unit * 0.105f;
        fill.setColor(lighten(accent, 0.84f));
        canvas.drawCircle(0, checkY, checkRadius, fill);
        fill.setColor(accent);
        canvas.drawCircle(0, checkY, checkRadius * 0.76f, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(unit * 0.038f);
        canvas.drawLine(-checkRadius * 0.38f, checkY, -checkRadius * 0.08f,
                checkY + checkRadius * 0.30f, stroke);
        canvas.drawLine(-checkRadius * 0.08f, checkY + checkRadius * 0.30f,
                checkRadius * 0.48f, checkY - checkRadius * 0.34f, stroke);

        float reveal = clamp((seconds - 0.52f) / 0.70f);
        drawReceiptLine(canvas, -width * 0.30f, top + height * 0.43f,
                width * 0.60f * reveal, unit * 0.025f, 0xFFDCD5E4);
        drawReceiptLine(canvas, -width * 0.30f, top + height * 0.56f,
                width * 0.40f * reveal, unit * 0.020f, 0xFFE9E4ED);
        drawReceiptLine(canvas, width * 0.13f, top + height * 0.56f,
                width * 0.17f * reveal, unit * 0.020f, withAlpha(accent, 185));
        drawReceiptLine(canvas, -width * 0.30f, top + height * 0.69f,
                width * 0.32f * reveal, unit * 0.020f, 0xFFE9E4ED);
        drawReceiptLine(canvas, width * 0.13f, top + height * 0.69f,
                width * 0.17f * reveal, unit * 0.020f, withAlpha(accent, 185));
        drawReceiptLine(canvas, -width * 0.30f, top + height * 0.84f,
                width * 0.60f * reveal, unit * 0.032f, accent);
        canvas.restore();
    }

    private void drawReceiptLine(Canvas canvas, float x, float y, float width, float height, int color) {
        fill.setColor(color);
        rect.set(x, y, x + Math.max(0f, width), y + height);
        canvas.drawRoundRect(rect, height * 0.5f, height * 0.5f, fill);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float easeOutBack(float value) {
        float c1 = 1.12f;
        float c3 = c1 + 1f;
        float x = value - 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int lighten(int color, float amount) {
        int red = Math.round(Color.red(color) + (255 - Color.red(color)) * amount);
        int green = Math.round(Color.green(color) + (255 - Color.green(color)) * amount);
        int blue = Math.round(Color.blue(color) + (255 - Color.blue(color)) * amount);
        return Color.rgb(red, green, blue);
    }
}
