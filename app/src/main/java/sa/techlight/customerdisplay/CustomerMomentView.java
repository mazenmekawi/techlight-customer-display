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

/** A lightweight four-second item-to-invoice story drawn without GIF or video allocations. */
final class CustomerMomentView extends View {
    static final long COMPLETION_ANIMATION_MS = OrderMomentPolicy.COMPLETION_ANIMATION_MS;

    private final boolean completed;
    private final int accent;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Drawable mascot;
    private final Drawable riyal;
    private long startedAt;
    private Shader halo;

    CustomerMomentView(Context context, boolean completed, int accent, boolean dark) {
        super(context);
        this.completed = completed;
        this.accent = accent;
        this.mascot = context.getDrawable(R.drawable.techlight_mascot);
        this.riyal = context.getDrawable(R.drawable.ic_saudi_riyal);
        if (riyal != null) riyal.mutate().setTint(accent);
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
        float radius = Math.min(width, height) * 0.78f;
        halo = new RadialGradient(
                width * (completed ? 0.56f : 0.50f),
                height * 0.50f,
                radius,
                new int[]{withAlpha(lighten(accent, 0.72f), 92), withAlpha(accent, 25), Color.TRANSPARENT},
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        long elapsedMs = Math.max(0L, SystemClock.uptimeMillis() - startedAt);
        float seconds = elapsedMs / 1000f;
        float unit = Math.min(getWidth(), getHeight());

        fill.setShader(halo);
        canvas.drawCircle(getWidth() * 0.53f, getHeight() * 0.50f, unit * 0.72f, fill);
        fill.setShader(null);
        drawCircuitField(canvas, seconds, unit);
        drawMascot(canvas, seconds, unit);

        if (completed) {
            drawItemTransformation(canvas, seconds, unit);
            drawSaudiReceipt(canvas, seconds, unit);
            drawFinalPulse(canvas, seconds, unit);
        } else {
            drawIdleData(canvas, seconds, unit);
        }

        if (isShown() && (!completed || elapsedMs < COMPLETION_ANIMATION_MS)) {
            postInvalidateOnAnimation();
        }
    }

    private void drawCircuitField(Canvas canvas, float seconds, float unit) {
        stroke.setStrokeWidth(Math.max(1f, unit * 0.004f));
        for (int i = 0; i < 4; i++) {
            float phase = completed
                    ? clamp((seconds - i * 0.09f) / 1.45f)
                    : 0.45f + 0.35f * (float) Math.sin(seconds * 0.9f + i);
            stroke.setColor(withAlpha(accent, Math.round(18 + 34 * phase)));
            float y = getHeight() * (0.23f + i * 0.17f);
            float start = getWidth() * 0.08f;
            float end = start + getWidth() * (0.30f + 0.50f * phase);
            canvas.drawLine(start, y, end, y, stroke);
            canvas.drawCircle(end, y, unit * 0.009f, stroke);
        }
    }

    private void drawMascot(Canvas canvas, float seconds, float unit) {
        float entrance = easeOutBack(clamp(seconds / 0.58f));
        float bob = completed
                ? (float) Math.sin(Math.min(seconds, 2.4f) * 3.1f) * unit * 0.006f * (1f - clamp(seconds / 3f))
                : (float) Math.sin(seconds * 1.7f) * unit * 0.012f;
        float desiredHeight = getHeight() * (completed ? 0.94f : 0.96f);
        float ratio = mascot == null || mascot.getIntrinsicHeight() <= 0
                ? 0.67f
                : (float) mascot.getIntrinsicWidth() / mascot.getIntrinsicHeight();
        float desiredWidth = Math.min(getWidth() * (completed ? 0.43f : 0.62f), desiredHeight * ratio);
        desiredHeight = desiredWidth / ratio;
        float finalX = getWidth() * (completed ? 0.34f : 0.50f);
        float centerX = finalX - getWidth() * 0.10f * (1f - entrance);
        float centerY = getHeight() * 0.51f + bob;

        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(0.90f + entrance * 0.10f, 0.90f + entrance * 0.10f);
        if (mascot != null) {
            mascot.setBounds(
                    Math.round(-desiredWidth * 0.5f),
                    Math.round(-desiredHeight * 0.5f),
                    Math.round(desiredWidth * 0.5f),
                    Math.round(desiredHeight * 0.5f)
            );
            mascot.setAlpha(Math.round(255 * clamp(entrance)));
            mascot.draw(canvas);
            mascot.setAlpha(255);
        } else {
            fill.setColor(accent);
            canvas.drawRect(-unit * 0.18f, -unit * 0.34f, unit * 0.18f, unit * 0.34f, fill);
        }
        canvas.restore();
    }

    private void drawIdleData(Canvas canvas, float seconds, float unit) {
        for (int i = 0; i < 5; i++) {
            float angle = seconds * 0.55f + i * ((float) Math.PI * 2f / 5f);
            float x = getWidth() * 0.50f + (float) Math.cos(angle) * getWidth() * 0.31f;
            float y = getHeight() * 0.50f + (float) Math.sin(angle) * getHeight() * 0.31f;
            float pulse = 0.65f + 0.35f * (float) Math.sin(seconds * 1.8f + i);
            fill.setColor(withAlpha(lighten(accent, 0.35f), Math.round(70 + 105 * pulse)));
            canvas.drawCircle(x, y, unit * (0.014f + 0.006f * pulse), fill);
        }
    }

    private void drawItemTransformation(Canvas canvas, float seconds, float unit) {
        for (int i = 0; i < 3; i++) {
            float appear = easeOutBack(clamp((seconds - i * 0.10f) / 0.48f));
            float travel = smooth(clamp((seconds - 0.72f - i * 0.10f) / 1.42f));
            float startX = getWidth() * (0.11f + i * 0.045f);
            float startY = getHeight() * (0.28f + i * 0.105f);
            float endX = getWidth() * 0.69f;
            float endY = getHeight() * (0.42f + i * 0.065f);
            float x = lerp(startX, endX, travel);
            float y = lerp(startY, endY, travel)
                    - (float) Math.sin(Math.PI * travel) * getHeight() * (0.15f + i * 0.012f);
            float fade = travel < 0.82f ? 1f : 1f - (travel - 0.82f) / 0.18f;
            float scale = Math.max(0f, appear) * (1f - 0.55f * travel);
            drawProductToken(canvas, x, y, unit * 0.115f * scale, i, clamp(fade));
        }

        float stream = clamp((seconds - 0.88f) / 1.65f);
        for (int i = 0; i < 9; i++) {
            float local = clamp(stream * 1.34f - i * 0.065f);
            float x = lerp(getWidth() * 0.40f, getWidth() * 0.72f, local);
            float y = getHeight() * 0.36f
                    + (float) Math.sin(local * Math.PI * 2f + i * 0.52f) * unit * 0.045f;
            fill.setColor(withAlpha(i % 2 == 0 ? accent : lighten(accent, 0.48f),
                    Math.round(190 * (1f - clamp((seconds - 2.35f) / 0.45f)))));
            canvas.drawCircle(x, y, unit * (0.010f + (i % 3) * 0.003f), fill);
        }
    }

    private void drawProductToken(Canvas canvas, float x, float y, float size, int type, float alpha) {
        if (size <= 0f || alpha <= 0f) return;
        int opacity = Math.round(255 * alpha);
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate((type - 1) * 5f);
        rect.set(-size + size * 0.06f, -size * 0.72f + size * 0.10f,
                size + size * 0.06f, size * 0.72f + size * 0.10f);
        fill.setColor(withAlpha(accent, Math.round(54 * alpha)));
        canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, fill);
        rect.set(-size, -size * 0.72f, size, size * 0.72f);
        fill.setColor(withAlpha(Color.WHITE, opacity));
        canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, fill);
        fill.setColor(withAlpha(lighten(accent, 0.84f), opacity));
        canvas.drawCircle(-size * 0.48f, 0, size * 0.30f, fill);
        stroke.setStrokeWidth(Math.max(2f, size * 0.10f));
        stroke.setColor(withAlpha(accent, opacity));

        float cx = -size * 0.48f;
        if (type == 0) {
            rect.set(cx - size * 0.12f, -size * 0.13f, cx + size * 0.10f, size * 0.15f);
            canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, stroke);
            canvas.drawLine(cx - size * 0.16f, -size * 0.19f, cx + size * 0.14f, -size * 0.19f, stroke);
            canvas.drawArc(cx + size * 0.04f, -size * 0.07f, cx + size * 0.20f, size * 0.10f,
                    -82f, 165f, false, stroke);
        } else if (type == 1) {
            rect.set(cx - size * 0.15f, -size * 0.12f, cx + size * 0.15f, size * 0.16f);
            canvas.drawRoundRect(rect, size * 0.03f, size * 0.03f, stroke);
            canvas.drawArc(cx - size * 0.08f, -size * 0.22f, cx + size * 0.08f, 0,
                    190f, 160f, false, stroke);
        } else {
            canvas.drawArc(cx - size * 0.18f, -size * 0.04f, cx + size * 0.18f, size * 0.16f,
                    8f, 164f, false, stroke);
            canvas.drawLine(cx - size * 0.18f, size * 0.05f, cx + size * 0.18f, size * 0.05f, stroke);
            canvas.drawLine(cx, -size * 0.16f, cx, -size * 0.06f, stroke);
        }

        fill.setColor(withAlpha(0xFF493D51, opacity));
        rect.set(-size * 0.02f, -size * 0.17f, size * 0.66f, -size * 0.08f);
        canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, fill);
        fill.setColor(withAlpha(0xFFB9AFBE, opacity));
        rect.set(-size * 0.02f, size * 0.02f, size * 0.46f, size * 0.10f);
        canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, fill);
        canvas.restore();
    }

    private void drawSaudiReceipt(Canvas canvas, float seconds, float unit) {
        float appear = easeOutBack(clamp((seconds - 1.62f) / 0.78f));
        if (appear <= 0f) return;
        float width = getWidth() * 0.27f;
        float height = getHeight() * 0.66f;
        float centerX = getWidth() * 0.80f;
        float centerY = getHeight() * 0.51f;

        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(0.18f + 0.82f * appear, 0.78f + 0.22f * appear);
        canvas.rotate((1f - appear) * 6f);

        float left = -width * 0.5f;
        float right = width * 0.5f;
        float top = -height * 0.5f;
        float bottom = height * 0.43f;
        rect.set(left + unit * 0.015f, top + unit * 0.025f,
                right + unit * 0.015f, bottom + unit * 0.075f);
        fill.setColor(0x2C1B0B27);
        canvas.drawRoundRect(rect, unit * 0.035f, unit * 0.035f, fill);

        path.reset();
        path.moveTo(left, top + unit * 0.038f);
        path.quadTo(left, top, left + unit * 0.038f, top);
        path.lineTo(right - unit * 0.038f, top);
        path.quadTo(right, top, right, top + unit * 0.038f);
        path.lineTo(right, bottom);
        float tooth = width / 10f;
        for (int i = 0; i < 5; i++) {
            path.lineTo(right - tooth * (i * 2 + 1), bottom + unit * 0.050f);
            path.lineTo(right - tooth * (i * 2 + 2), bottom);
        }
        path.lineTo(left, top + unit * 0.038f);
        path.close();
        fill.setColor(Color.WHITE);
        canvas.drawPath(path, fill);

        rect.set(left, top, right, top + height * 0.16f);
        fill.setColor(accent);
        canvas.drawRoundRect(rect, unit * 0.030f, unit * 0.030f, fill);
        fill.setColor(Color.WHITE);
        path.reset();
        float markX = left + width * 0.16f;
        float markY = top + height * 0.08f;
        float mark = unit * 0.030f;
        path.moveTo(markX - mark, markY - mark * 0.70f);
        path.lineTo(markX + mark, markY - mark * 0.70f);
        path.lineTo(markX + mark * 0.72f, markY);
        path.lineTo(markX + mark * 0.18f, markY);
        path.lineTo(markX + mark * 0.18f, markY + mark);
        path.lineTo(markX - mark * 0.16f, markY + mark * 0.72f);
        path.lineTo(markX - mark * 0.16f, markY);
        path.lineTo(markX - mark * 0.72f, markY);
        path.close();
        canvas.drawPath(path, fill);

        float reveal = smooth(clamp((seconds - 2.05f) / 0.92f));
        drawReceiptLine(canvas, left + width * 0.14f, top + height * 0.26f,
                width * 0.72f * reveal, unit * 0.018f, 0xFFD3CBD9);
        drawReceiptLine(canvas, left + width * 0.14f, top + height * 0.38f,
                width * 0.44f * reveal, unit * 0.014f, 0xFFE6E1EA);
        drawReceiptLine(canvas, left + width * 0.67f, top + height * 0.38f,
                width * 0.19f * reveal, unit * 0.014f, withAlpha(accent, 190));
        drawReceiptLine(canvas, left + width * 0.14f, top + height * 0.49f,
                width * 0.36f * reveal, unit * 0.014f, 0xFFE6E1EA);
        drawReceiptLine(canvas, left + width * 0.67f, top + height * 0.49f,
                width * 0.19f * reveal, unit * 0.014f, withAlpha(accent, 190));

        stroke.setStrokeWidth(Math.max(1f, unit * 0.005f));
        stroke.setColor(0xFFE2DCE7);
        canvas.drawLine(left + width * 0.14f, top + height * 0.61f,
                right - width * 0.14f, top + height * 0.61f, stroke);
        drawReceiptLine(canvas, left + width * 0.14f, top + height * 0.69f,
                width * 0.42f * reveal, unit * 0.022f, accent);
        if (riyal != null && reveal > 0.35f) {
            int icon = Math.round(unit * 0.070f);
            int x = Math.round(right - width * 0.14f - icon);
            int y = Math.round(top + height * 0.65f);
            riyal.setBounds(x, y, x + icon, y + icon);
            riyal.setAlpha(Math.round(255 * reveal));
            riyal.draw(canvas);
            riyal.setAlpha(255);
        }

        float stamp = easeOutBack(clamp((seconds - 3.02f) / 0.58f));
        if (stamp > 0f) {
            float checkX = right - width * 0.23f;
            float checkY = bottom - height * 0.11f;
            float checkRadius = unit * 0.065f * stamp;
            fill.setColor(0xFFE8F7EE);
            canvas.drawCircle(checkX, checkY, checkRadius * 1.16f, fill);
            fill.setColor(0xFF169B55);
            canvas.drawCircle(checkX, checkY, checkRadius, fill);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(Math.max(2f, unit * 0.018f));
            canvas.drawLine(checkX - checkRadius * 0.43f, checkY,
                    checkX - checkRadius * 0.10f, checkY + checkRadius * 0.34f, stroke);
            canvas.drawLine(checkX - checkRadius * 0.10f, checkY + checkRadius * 0.34f,
                    checkX + checkRadius * 0.50f, checkY - checkRadius * 0.40f, stroke);
        }
        canvas.restore();
    }

    private void drawFinalPulse(Canvas canvas, float seconds, float unit) {
        float progress = clamp((seconds - 3.10f) / 0.90f);
        if (progress <= 0f) return;
        float x = getWidth() * 0.80f;
        float y = getHeight() * 0.51f;
        stroke.setStrokeWidth(Math.max(1.5f, unit * 0.010f * (1f - progress * 0.55f)));
        stroke.setColor(withAlpha(lighten(accent, 0.34f), Math.round(160 * (1f - progress))));
        canvas.drawCircle(x, y, unit * (0.15f + progress * 0.29f), stroke);
        for (int i = 0; i < 6; i++) {
            float angle = (float) (Math.PI * 2f * i / 6f);
            float distance = unit * (0.18f + progress * 0.28f);
            fill.setColor(withAlpha(i % 2 == 0 ? accent : 0xFF169B55,
                    Math.round(175 * (1f - progress))));
            canvas.drawCircle(x + (float) Math.cos(angle) * distance,
                    y + (float) Math.sin(angle) * distance,
                    unit * 0.012f, fill);
        }
    }

    private void drawReceiptLine(Canvas canvas, float x, float y, float width, float height, int color) {
        fill.setColor(color);
        rect.set(x, y, x + Math.max(0f, width), y + height);
        canvas.drawRoundRect(rect, height * 0.5f, height * 0.5f, fill);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float smooth(float value) {
        float x = clamp(value);
        return x * x * (3f - 2f * x);
    }

    private static float easeOutBack(float value) {
        float x = clamp(value) - 1f;
        float c1 = 1.08f;
        float c3 = c1 + 1f;
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
