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

/** Four-second, interface-led order-to-invoice sequence with no mascot or video assets. */
final class CustomerMomentView extends View {
    static final long COMPLETION_ANIMATION_MS = OrderMomentPolicy.COMPLETION_ANIMATION_MS;
    private static final float INVOICE_ENTRANCE_START =
            OrderMomentPolicy.INVOICE_ENTRANCE_START_MS / 1000f;
    private static final float INVOICE_ENTRANCE_DURATION =
            OrderMomentPolicy.INVOICE_ENTRANCE_DURATION_MS / 1000f;
    private static final float ITEM_TRANSFER_START =
            OrderMomentPolicy.ITEM_TRANSFER_START_MS / 1000f;
    private static final float ITEM_TRANSFER_STAGGER =
            OrderMomentPolicy.ITEM_TRANSFER_STAGGER_MS / 1000f;
    private static final float ITEM_TRANSFER_DURATION =
            OrderMomentPolicy.ITEM_TRANSFER_DURATION_MS / 1000f;

    private final int accent;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path receiptPath = new Path();
    private final Path transferPath = new Path();
    private final Drawable riyal;
    private long startedAt;
    private Shader focusGlow;

    CustomerMomentView(Context context, boolean completed, int accent, boolean dark) {
        super(context);
        this.accent = accent;
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
        focusGlow = new RadialGradient(
                width * 0.68f,
                height * 0.50f,
                Math.min(width, height) * 0.78f,
                new int[]{withAlpha(lighten(accent, 0.72f), 96), withAlpha(accent, 25), Color.TRANSPARENT},
                new float[]{0f, 0.56f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        long elapsedMs = Math.max(0L, SystemClock.uptimeMillis() - startedAt);
        float seconds = Math.min(COMPLETION_ANIMATION_MS, elapsedMs) / 1000f;
        float unit = Math.min(getWidth(), getHeight());

        fill.setShader(focusGlow);
        canvas.drawCircle(getWidth() * 0.68f, getHeight() * 0.50f, unit * 0.74f, fill);
        fill.setShader(null);

        drawTechnicalGrid(canvas, seconds, unit);
        drawOrderCards(canvas, seconds, unit);
        drawDataStream(canvas, seconds, unit);
        drawInvoice(canvas, seconds, unit);
        drawSuccessPulse(canvas, seconds, unit);

        if (isShown() && elapsedMs < COMPLETION_ANIMATION_MS) postInvalidateOnAnimation();
    }

    private void drawTechnicalGrid(Canvas canvas, float seconds, float unit) {
        float reveal = smooth(clamp(seconds / 1.2f));
        stroke.setStrokeWidth(Math.max(1f, unit * 0.004f));
        stroke.setColor(withAlpha(accent, Math.round(25 + 28 * reveal)));
        for (int i = 0; i < 5; i++) {
            float y = getHeight() * (0.18f + i * 0.16f);
            float end = getWidth() * (0.13f + reveal * 0.74f);
            canvas.drawLine(getWidth() * 0.08f, y, end, y, stroke);
            canvas.drawCircle(end, y, unit * 0.008f, stroke);
        }
        for (int i = 0; i < 3; i++) {
            float x = getWidth() * (0.43f + i * 0.15f);
            canvas.drawLine(x, getHeight() * 0.13f, x, getHeight() * 0.87f, stroke);
        }
    }

    private void drawOrderCards(Canvas canvas, float seconds, float unit) {
        for (int i = 0; i < 3; i++) {
            float enter = easeOutCubic(clamp((seconds - i * 0.10f) / 0.58f));
            float transfer = smooth(clamp((seconds - transferStart(i)) / ITEM_TRANSFER_DURATION));
            float startX = getWidth() * 0.08f;
            float finalX = getWidth() * 0.31f;
            float targetX = getWidth() * 0.685f;
            float x = lerp(startX - getWidth() * 0.18f, finalX, enter);
            x = lerp(x, targetX, transfer);
            float baseY = getHeight() * (0.30f + i * 0.20f);
            float targetY = invoiceRowY(i);
            float y = lerp(baseY, targetY, transfer)
                    - (float) Math.sin(Math.PI * transfer) * getHeight() * 0.065f;
            float disappear = transfer < 0.68f ? 1f : 1f - (transfer - 0.68f) / 0.32f;
            float scale = (0.88f + 0.12f * enter) * (1f - 0.56f * transfer);
            drawOrderCard(canvas, x, y, unit, i, scale, clamp(disappear));
        }
    }

    private void drawOrderCard(
            Canvas canvas,
            float centerX,
            float centerY,
            float unit,
            int index,
            float scale,
            float alpha
    ) {
        if (scale <= 0f || alpha <= 0f) return;
        float width = getWidth() * 0.34f * scale;
        float height = getHeight() * 0.14f * scale;
        int opacity = Math.round(255 * alpha);
        canvas.save();
        canvas.translate(centerX, centerY);

        rect.set(-width * 0.5f + unit * 0.012f, -height * 0.5f + unit * 0.014f,
                width * 0.5f + unit * 0.012f, height * 0.5f + unit * 0.014f);
        fill.setColor(withAlpha(accent, Math.round(38 * alpha)));
        canvas.drawRoundRect(rect, unit * 0.035f, unit * 0.035f, fill);
        rect.set(-width * 0.5f, -height * 0.5f, width * 0.5f, height * 0.5f);
        fill.setColor(withAlpha(Color.WHITE, opacity));
        canvas.drawRoundRect(rect, unit * 0.035f, unit * 0.035f, fill);

        float iconX = -width * 0.36f;
        fill.setColor(withAlpha(lighten(accent, 0.84f), opacity));
        canvas.drawCircle(iconX, 0, height * 0.29f, fill);
        stroke.setColor(withAlpha(accent, opacity));
        stroke.setStrokeWidth(Math.max(2f, unit * 0.010f));
        drawProductGlyph(canvas, iconX, 0, height * 0.26f, index);

        float textLeft = -width * 0.18f;
        drawLine(canvas, textLeft, -height * 0.18f, width * 0.44f, unit * 0.019f,
                withAlpha(0xFF3E3544, opacity));
        drawLine(canvas, textLeft, height * 0.11f, width * 0.30f, unit * 0.014f,
                withAlpha(0xFFB7AEBB, opacity));
        drawLine(canvas, width * 0.30f, height * 0.11f, width * 0.13f, unit * 0.014f,
                withAlpha(accent, opacity));
        canvas.restore();
    }

    private void drawProductGlyph(Canvas canvas, float x, float y, float size, int type) {
        if (type == 0) {
            rect.set(x - size * 0.32f, y - size * 0.26f, x + size * 0.22f, y + size * 0.32f);
            canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, stroke);
            canvas.drawLine(x - size * 0.40f, y - size * 0.38f,
                    x + size * 0.32f, y - size * 0.38f, stroke);
        } else if (type == 1) {
            rect.set(x - size * 0.38f, y - size * 0.24f, x + size * 0.38f, y + size * 0.30f);
            canvas.drawRoundRect(rect, size * 0.09f, size * 0.09f, stroke);
            canvas.drawArc(x - size * 0.21f, y - size * 0.48f,
                    x + size * 0.21f, y, 188f, 164f, false, stroke);
        } else {
            canvas.drawArc(x - size * 0.44f, y - size * 0.10f,
                    x + size * 0.44f, y + size * 0.30f, 7f, 166f, false, stroke);
            canvas.drawLine(x - size * 0.44f, y + size * 0.05f,
                    x + size * 0.44f, y + size * 0.05f, stroke);
            canvas.drawLine(x, y - size * 0.38f, x, y - size * 0.14f, stroke);
        }
    }

    private void drawDataStream(Canvas canvas, float seconds, float unit) {
        for (int item = 0; item < 3; item++) {
            float progress = smooth(clamp((seconds - transferStart(item)) / ITEM_TRANSFER_DURATION));
            if (progress <= 0f || progress >= 1f) continue;
            float startX = getWidth() * 0.36f;
            float startY = getHeight() * (0.30f + item * 0.20f);
            float endX = getWidth() * 0.705f;
            float endY = invoiceRowY(item);

            transferPath.reset();
            transferPath.moveTo(startX, startY);
            transferPath.cubicTo(
                    getWidth() * 0.47f, startY - getHeight() * 0.10f,
                    getWidth() * 0.59f, endY - getHeight() * 0.07f,
                    endX, endY
            );
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, unit * 0.004f));
            stroke.setColor(withAlpha(accent, Math.round(58 * (1f - progress))));
            canvas.drawPath(transferPath, stroke);

            for (int particle = 0; particle < 5; particle++) {
                float dot = clamp(progress * 1.18f - particle * 0.075f);
                if (dot <= 0f || dot >= 1f) continue;
                float inv = 1f - dot;
                float x = inv * inv * inv * startX
                        + 3f * inv * inv * dot * getWidth() * 0.47f
                        + 3f * inv * dot * dot * getWidth() * 0.59f
                        + dot * dot * dot * endX;
                float y = inv * inv * inv * startY
                        + 3f * inv * inv * dot * (startY - getHeight() * 0.10f)
                        + 3f * inv * dot * dot * (endY - getHeight() * 0.07f)
                        + dot * dot * dot * endY;
                int color = particle == 0 ? 0xFF159B55 : accent;
                fill.setColor(withAlpha(color, Math.round(220 * (1f - particle * 0.12f))));
                canvas.drawCircle(x, y, unit * (0.006f + (4 - particle) * 0.0015f), fill);
            }
        }
    }

    private void drawInvoice(Canvas canvas, float seconds, float unit) {
        float enter = easeOutCubic(clamp(
                (seconds - INVOICE_ENTRANCE_START) / INVOICE_ENTRANCE_DURATION));
        float unfold = smooth(clamp(
                (seconds - INVOICE_ENTRANCE_START - 0.10f) / (INVOICE_ENTRANCE_DURATION - 0.10f)));
        if (enter <= 0f) return;
        float width = getWidth() * 0.28f;
        float height = getHeight() * 0.72f;
        float centerX = lerp(getWidth() * 1.08f, getWidth() * 0.79f, enter);
        float centerY = getHeight() * 0.51f;

        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.rotate((1f - enter) * 2.2f);

        float left = -width * 0.5f;
        float right = width * 0.5f;
        float top = -height * 0.5f;
        float bottom = height * 0.43f;
        canvas.translate(0f, top);
        canvas.scale(0.94f + 0.06f * enter, 0.28f + 0.72f * unfold);
        canvas.translate(0f, -top);
        rect.set(left + unit * 0.014f, top + unit * 0.024f,
                right + unit * 0.014f, bottom + unit * 0.072f);
        fill.setColor(withAlpha(0xFF170524, Math.round(48 * enter)));
        canvas.drawRoundRect(rect, unit * 0.035f, unit * 0.035f, fill);

        buildReceiptPath(left, right, top, bottom, width, unit);
        fill.setColor(Color.WHITE);
        canvas.drawPath(receiptPath, fill);

        rect.set(left, top, right, top + height * 0.17f);
        fill.setColor(accent);
        canvas.drawRoundRect(rect, unit * 0.030f, unit * 0.030f, fill);
        drawTMark(canvas, left + width * 0.17f, top + height * 0.085f, unit * 0.033f);
        drawLine(canvas, left + width * 0.34f, top + height * 0.066f,
                width * 0.46f * enter, unit * 0.017f, 0xDFFFFFFF);
        drawLine(canvas, left + width * 0.34f, top + height * 0.108f,
                width * 0.30f * enter, unit * 0.012f, 0xAFFFFFFF);

        float reveal = smooth(clamp((seconds - 1.20f) / 0.62f));
        drawLine(canvas, left + width * 0.13f, top + height * 0.27f,
                width * 0.74f * reveal, unit * 0.016f, 0xFFD1CAD7);
        float firstRow = rowReveal(seconds, 0);
        float secondRow = rowReveal(seconds, 1);
        float thirdRow = rowReveal(seconds, 2);
        drawInvoiceMetric(canvas, left, top, width, height, 0.38f, 0.47f, firstRow);
        drawInvoiceMetric(canvas, left, top, width, height, 0.49f, 0.38f, secondRow);
        drawInvoiceMetric(canvas, left, top, width, height, 0.60f, 0.43f, thirdRow);
        drawArrivalScan(canvas, seconds, left, top, width, height, 0);
        drawArrivalScan(canvas, seconds, left, top, width, height, 1);
        drawArrivalScan(canvas, seconds, left, top, width, height, 2);

        stroke.setColor(0xFFE2DCE7);
        stroke.setStrokeWidth(Math.max(1f, unit * 0.005f));
        canvas.drawLine(left + width * 0.13f, top + height * 0.70f,
                right - width * 0.13f, top + height * 0.70f, stroke);
        float totalReveal = easeOutCubic(clamp((seconds - 2.45f) / 0.62f));
        drawLine(canvas, left + width * 0.13f, top + height * 0.78f,
                width * 0.42f * totalReveal, unit * 0.024f, accent);
        if (riyal != null && totalReveal > 0.32f) {
            int icon = Math.round(unit * 0.073f);
            int x = Math.round(right - width * 0.13f - icon);
            int y = Math.round(top + height * 0.735f);
            riyal.setBounds(x, y, x + icon, y + icon);
            riyal.setAlpha(Math.round(255 * totalReveal));
            riyal.draw(canvas);
            riyal.setAlpha(255);
        }

        float stamp = easeOutBack(clamp((seconds - 3.12f) / 0.52f));
        if (stamp > 0f) drawApprovedStamp(canvas, right - width * 0.22f,
                bottom - height * 0.09f, unit * 0.062f * stamp);
        canvas.restore();
    }

    private void drawArrivalScan(
            Canvas canvas,
            float seconds,
            float left,
            float top,
            float width,
            float height,
            int rowIndex
    ) {
        float started = OrderMomentPolicy.invoiceRowRevealStartMs(rowIndex) / 1000f;
        float progress = clamp((seconds - started) / 0.42f);
        if (progress <= 0f || progress >= 1f) return;
        float row = 0.38f + rowIndex * 0.11f;
        float sweepX = left + width * (0.10f + progress * 0.80f);
        fill.setShader(new RadialGradient(
                sweepX,
                top + height * row,
                width * 0.16f,
                withAlpha(0xFF159B55, Math.round(105 * (1f - progress))),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(sweepX, top + height * row, width * 0.16f, fill);
        fill.setShader(null);
    }

    private float rowReveal(float seconds, int rowIndex) {
        float started = OrderMomentPolicy.invoiceRowRevealStartMs(rowIndex) / 1000f;
        return easeOutCubic(clamp((seconds - started) / 0.42f));
    }

    private float transferStart(int itemIndex) {
        return ITEM_TRANSFER_START + itemIndex * ITEM_TRANSFER_STAGGER;
    }

    private float invoiceRowY(int rowIndex) {
        float top = getHeight() * 0.15f;
        float invoiceHeight = getHeight() * 0.72f;
        return top + invoiceHeight * (0.38f + rowIndex * 0.11f);
    }

    private void buildReceiptPath(float left, float right, float top, float bottom, float width, float unit) {
        receiptPath.reset();
        receiptPath.moveTo(left, top + unit * 0.038f);
        receiptPath.quadTo(left, top, left + unit * 0.038f, top);
        receiptPath.lineTo(right - unit * 0.038f, top);
        receiptPath.quadTo(right, top, right, top + unit * 0.038f);
        receiptPath.lineTo(right, bottom);
        float tooth = width / 10f;
        for (int i = 0; i < 5; i++) {
            receiptPath.lineTo(right - tooth * (i * 2 + 1), bottom + unit * 0.050f);
            receiptPath.lineTo(right - tooth * (i * 2 + 2), bottom);
        }
        receiptPath.lineTo(left, top + unit * 0.038f);
        receiptPath.close();
    }

    private void drawInvoiceMetric(
            Canvas canvas,
            float left,
            float top,
            float width,
            float height,
            float row,
            float labelWidth,
            float reveal
    ) {
        drawLine(canvas, left + width * 0.13f, top + height * row,
                width * labelWidth * reveal, Math.max(2f, height * 0.018f), 0xFFE6E1EA);
        drawLine(canvas, left + width * 0.69f, top + height * row,
                width * 0.18f * reveal, Math.max(2f, height * 0.018f), withAlpha(accent, 190));
    }

    private void drawTMark(Canvas canvas, float x, float y, float size) {
        fill.setColor(Color.WHITE);
        receiptPath.reset();
        receiptPath.moveTo(x - size, y - size * 0.72f);
        receiptPath.lineTo(x + size, y - size * 0.72f);
        receiptPath.lineTo(x + size * 0.72f, y);
        receiptPath.lineTo(x + size * 0.18f, y);
        receiptPath.lineTo(x + size * 0.18f, y + size);
        receiptPath.lineTo(x - size * 0.16f, y + size * 0.72f);
        receiptPath.lineTo(x - size * 0.16f, y);
        receiptPath.lineTo(x - size * 0.72f, y);
        receiptPath.close();
        canvas.drawPath(receiptPath, fill);
    }

    private void drawApprovedStamp(Canvas canvas, float x, float y, float radius) {
        fill.setColor(0xFFE8F7EE);
        canvas.drawCircle(x, y, radius * 1.18f, fill);
        fill.setColor(0xFF159B55);
        canvas.drawCircle(x, y, radius, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(Math.max(2f, radius * 0.26f));
        canvas.drawLine(x - radius * 0.42f, y, x - radius * 0.10f, y + radius * 0.33f, stroke);
        canvas.drawLine(x - radius * 0.10f, y + radius * 0.33f,
                x + radius * 0.50f, y - radius * 0.40f, stroke);
    }

    private void drawSuccessPulse(Canvas canvas, float seconds, float unit) {
        float progress = clamp((seconds - 3.10f) / 0.90f);
        if (progress <= 0f) return;
        float x = getWidth() * 0.79f;
        float y = getHeight() * 0.51f;
        stroke.setStrokeWidth(Math.max(1.5f, unit * 0.010f * (1f - progress * 0.55f)));
        stroke.setColor(withAlpha(0xFF159B55, Math.round(175 * (1f - progress))));
        canvas.drawCircle(x, y, unit * (0.18f + progress * 0.29f), stroke);
    }

    private void drawLine(Canvas canvas, float x, float y, float width, float height, int color) {
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

    private static float easeOutCubic(float value) {
        float x = 1f - clamp(value);
        return 1f - x * x * x;
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
