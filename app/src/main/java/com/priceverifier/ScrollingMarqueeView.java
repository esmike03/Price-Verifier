package com.priceverifier;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/** A single-line ticker that scrolls even when its message fits in the bar. */
public final class ScrollingMarqueeView extends TextView {
    private static final float SPEED_DP_PER_SECOND = 40f;

    private String message;
    private float distance;
    private long previousFrame;

    public ScrollingMarqueeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        String next = text == null ? "" : text.toString();
        // Applying unchanged server settings must not restart a running message.
        if (!next.equals(message)) {
            message = next;
            distance = 0f;
            previousFrame = 0L;
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        distance = 0f;
        previousFrame = 0L;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        previousFrame = 0L;
        if (visibility == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        previousFrame = 0L;
        if (visibility == VISIBLE) {
            invalidate();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        previousFrame = 0L;
        if (hasFocus) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isInEditMode()) {
            super.onDraw(canvas);
            return;
        }
        int left = getPaddingLeft();
        int right = getWidth() - getPaddingRight();
        int width = right - left;
        if (width <= 0 || message == null || message.isEmpty()) {
            previousFrame = 0L;
            return;
        }

        TextPaint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        Layout layout = getLayout();
        float textWidth = layout == null ? paint.measureText(message) : layout.getLineWidth(0);
        float travel = width + textWidth;
        boolean running = isShown() && getWindowVisibility() == VISIBLE && hasWindowFocus();
        long now = SystemClock.uptimeMillis();
        if (running && previousFrame != 0L) {
            // Resume smoothly after a blocked frame instead of jumping across the bar.
            long elapsed = Math.min(100L, now - previousFrame);
            float speed = SPEED_DP_PER_SECOND * getResources().getDisplayMetrics().density;
            distance = (distance + speed * elapsed / 1000f) % travel;
        }
        previousFrame = running ? now : 0L;

        int saved = canvas.save();
        canvas.clipRect(left, getPaddingTop(), right, getHeight() - getPaddingBottom());
        canvas.drawText(message, left + width - distance, getBaseline(), paint);
        canvas.restoreToCount(saved);
        if (running) {
            postInvalidateOnAnimation();
        }
    }
}
