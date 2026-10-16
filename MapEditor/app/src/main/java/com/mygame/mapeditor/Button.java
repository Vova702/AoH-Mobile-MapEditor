package com.mygame.mapeditor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;

public class Button {
    protected int iPosX;
    protected int iPosY;
    protected int iWidth;
    protected int iHeight;
    protected boolean visible;
    protected String sText;

    public String tooltipText = "";

    private final Paint textPaint;
    private final Paint bgPaint;
    private final Paint borderPaint;
    private final Rect textBounds = new Rect();
    private Runnable onClick;
    private boolean pressed;
    private boolean disabled;

    private boolean held;
    private long heldDownTime;
    static final long TOOLTIP_DELAY_MS = 420;
    private static final float TOOLTIP_PADDING_H = 12f;
    private static final float TOOLTIP_PADDING_V = 6f;
    private static final float TOOLTIP_ARROW_SIZE = 7f;
    private static final float TOOLTIP_CORNER = 10f;
    private static final float TOOLTIP_MAX_WIDTH = 260f;

    private final Paint tooltipBgPaint;
    private final Paint tooltipTextPaint;
    private final Paint tooltipBorderPaint;
    private final Path tooltipPath = new Path();
    private final Rect tooltipTextBounds = new Rect();

    public Button(String sText, int iPosX, int iPosY, int iWidth, int iHeight, boolean visible) {
        this.sText = sText != null ? sText : "";
        this.iPosX = iPosX;
        this.iPosY = iPosY;
        this.iWidth = iWidth;
        this.iHeight = iHeight;
        this.visible = visible;

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextSize(14f);
        textPaint.setColor(Color.WHITE);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setColor(Color.argb(230, 210, 210, 210));

        tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipBgPaint.setStyle(Paint.Style.FILL);
        tooltipBgPaint.setColor(Color.argb(240, 30, 30, 30));

        tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        tooltipTextPaint.setTextSize(13f);
        tooltipTextPaint.setColor(Color.WHITE);

        tooltipBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipBorderPaint.setStyle(Paint.Style.STROKE);
        tooltipBorderPaint.setStrokeWidth(1.5f);
        tooltipBorderPaint.setColor(Color.argb(180, 160, 160, 160));
    }

    public void setTooltip(String text) {
        this.tooltipText = text != null ? text : "";
    }

    public void setOnClickListener(Runnable onClick) {
        this.onClick = onClick;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void onPress() {
        held = true;
        heldDownTime = System.currentTimeMillis();
    }

    public void onRelease() {
        held = false;
        heldDownTime = 0L;
    }

    public boolean isHeld() {
        return held;
    }

    public boolean shouldShowTooltip() {
        return held && !tooltipText.isEmpty()
                && System.currentTimeMillis() - heldDownTime >= TOOLTIP_DELAY_MS;
    }

    public void draw(Canvas canvas) {
        if (!visible) return;

        bgPaint.setColor(pressed
                ? Color.argb(235, 70, 70, 70)
                : Color.argb(190, 25, 25, 25));

        canvas.drawRoundRect(iPosX, iPosY, iPosX + iWidth, iPosY + iHeight,
                8f, 8f, bgPaint);

        int borderAlpha = disabled ? 80 : 230;
        borderPaint.setColor(Color.argb(borderAlpha, 210, 210, 210));
        canvas.drawRoundRect(iPosX, iPosY, iPosX + iWidth, iPosY + iHeight,
                8f, 8f, borderPaint);

        textPaint.getTextBounds(sText, 0, sText.length(), textBounds);
        float tx = iPosX + (iWidth - textBounds.width()) / 2f - textBounds.left;
        float ty = iPosY + (iHeight - textBounds.height()) / 2f - textBounds.top;
        if (disabled) {
            textPaint.setColor(Color.argb(100, 255, 255, 255));
        } else {
            textPaint.setColor(Color.WHITE);
        }
        canvas.drawText(sText, tx, ty, textPaint);

        if (shouldShowTooltip()) {
            drawTooltip(canvas);
        }
    }

    private void drawTooltip(Canvas canvas) {
        tooltipTextPaint.getTextBounds(tooltipText, 0, tooltipText.length(), tooltipTextBounds);

        float textW = tooltipTextBounds.width();
        float textH = tooltipTextBounds.height();
        float boxW = Math.min(TOOLTIP_MAX_WIDTH, textW + TOOLTIP_PADDING_H * 2);
        float boxH = textH + TOOLTIP_PADDING_V * 2;

        float bubbleCX = iPosX + iWidth / 2f;
        float bubbleTop = iPosY - boxH - TOOLTIP_ARROW_SIZE - 4f;

        if (bubbleTop < 4f) {
            bubbleTop = iPosY + iHeight + TOOLTIP_ARROW_SIZE + 4f;
        }

        float bubbleLeft = bubbleCX - boxW / 2f;
        float viewMaxX = canvas.getWidth();
        if (bubbleLeft < 4f) bubbleLeft = 4f;
        if (bubbleLeft + boxW > viewMaxX - 4f) bubbleLeft = viewMaxX - 4f - boxW;
        float bubbleRight = bubbleLeft + boxW;
        float bubbleBottom = bubbleTop + boxH;

        boolean arrowPointsUp = (bubbleBottom < iPosY);

        tooltipPath.reset();
        tooltipPath.moveTo(bubbleLeft + TOOLTIP_CORNER, bubbleTop);
        tooltipPath.lineTo(bubbleRight - TOOLTIP_CORNER, bubbleTop);
        tooltipPath.quadTo(bubbleRight, bubbleTop, bubbleRight, bubbleTop + TOOLTIP_CORNER);
        tooltipPath.lineTo(bubbleRight, bubbleBottom - TOOLTIP_CORNER);
        tooltipPath.quadTo(bubbleRight, bubbleBottom, bubbleRight - TOOLTIP_CORNER, bubbleBottom);
        tooltipPath.lineTo(bubbleLeft + TOOLTIP_CORNER, bubbleBottom);
        tooltipPath.quadTo(bubbleLeft, bubbleBottom, bubbleLeft, bubbleBottom - TOOLTIP_CORNER);
        tooltipPath.lineTo(bubbleLeft, bubbleTop + TOOLTIP_CORNER);
        tooltipPath.quadTo(bubbleLeft, bubbleTop, bubbleLeft + TOOLTIP_CORNER, bubbleTop);
        tooltipPath.close();

        float arrowTipX = Math.max(bubbleLeft + TOOLTIP_ARROW_SIZE + 2,
                Math.min(bubbleRight - TOOLTIP_ARROW_SIZE - 2, bubbleCX));

        if (arrowPointsUp) {
            float arrowBaseY = bubbleTop;
            tooltipPath.moveTo(arrowTipX - TOOLTIP_ARROW_SIZE, arrowBaseY);
            tooltipPath.lineTo(arrowTipX, arrowBaseY - TOOLTIP_ARROW_SIZE);
            tooltipPath.lineTo(arrowTipX + TOOLTIP_ARROW_SIZE, arrowBaseY);
            tooltipPath.close();
        } else {
            float arrowBaseY = bubbleBottom;
            tooltipPath.moveTo(arrowTipX - TOOLTIP_ARROW_SIZE, arrowBaseY);
            tooltipPath.lineTo(arrowTipX, arrowBaseY + TOOLTIP_ARROW_SIZE);
            tooltipPath.lineTo(arrowTipX + TOOLTIP_ARROW_SIZE, arrowBaseY);
            tooltipPath.close();
        }

        canvas.drawPath(tooltipPath, tooltipBgPaint);
        canvas.drawPath(tooltipPath, tooltipBorderPaint);

        float drawTextX = bubbleLeft + (boxW - textW) / 2f - tooltipTextBounds.left;
        float drawTextY = bubbleTop + TOOLTIP_PADDING_V - tooltipTextBounds.top;
        canvas.drawText(tooltipText, drawTextX, drawTextY, tooltipTextPaint);
    }

    public boolean isClicked(float touchX, float touchY) {
        return visible && !disabled
                && touchX >= iPosX && touchX <= iPosX + iWidth
                && touchY >= iPosY && touchY <= iPosY + iHeight;
    }

    public boolean handleTap(float touchX, float touchY) {
        if (!isClicked(touchX, touchY)) return false;
        pressed = true;
        if (onClick != null) onClick.run();
        pressed = false;
        return true;
    }
}
