package com.hongqi.flighting.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class CropSelectionView extends View {
    public interface OnCropChangeListener {
        void onCropChanged(int startX, int endX, int startY, int endY);
    }

    private static final float MIN_SIZE = 0.08f;
    private static final float HANDLE_RADIUS_DP = 14f;

    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screenRect = new RectF();
    private final RectF cropRect = new RectF(0f, 0f, 1f, 1f);

    private float lastX;
    private float lastY;
    private int activeMode = MODE_NONE;
    private OnCropChangeListener listener;

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_TOP_LEFT = 2;
    private static final int MODE_TOP_RIGHT = 3;
    private static final int MODE_BOTTOM_LEFT = 4;
    private static final int MODE_BOTTOM_RIGHT = 5;

    public CropSelectionView(Context context) {
        super(context);
        init();
    }

    public CropSelectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CropSelectionView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        screenPaint.setColor(Color.rgb(18, 19, 26));
        screenPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.argb(95, 217, 180, 106));
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setStyle(Paint.Style.STROKE);

        cropFillPaint.setColor(Color.argb(90, 181, 18, 27));
        cropFillPaint.setStyle(Paint.Style.FILL);

        cropStrokePaint.setColor(Color.rgb(217, 180, 106));
        cropStrokePaint.setStrokeWidth(dp(3));
        cropStrokePaint.setStyle(Paint.Style.STROKE);

        handlePaint.setColor(Color.rgb(246, 231, 195));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setStrokeWidth(dp(2));

        textPaint.setColor(Color.rgb(175, 167, 160));
        textPaint.setTextSize(dp(12));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnCropChangeListener(OnCropChangeListener listener) {
        this.listener = listener;
    }

    public void setCropRange(int startX, int endX, int startY, int endY) {
        float left = toUnit(startX);
        float right = toUnit(endX);
        float top = toUnit(startY);
        float bottom = toUnit(endY);
        if (right - left < MIN_SIZE) {
            right = Math.min(1f, left + MIN_SIZE);
            left = Math.max(0f, right - MIN_SIZE);
        }
        if (bottom - top < MIN_SIZE) {
            bottom = Math.min(1f, top + MIN_SIZE);
            top = Math.max(0f, bottom - MIN_SIZE);
        }
        cropRect.set(left, top, right, bottom);
        invalidate();
        notifyChanged();
    }

    public int getCropStartX() {
        return toRange(cropRect.left);
    }

    public int getCropEndX() {
        return toRange(cropRect.right);
    }

    public int getCropStartY() {
        return toRange(cropRect.top);
    }

    public int getCropEndY() {
        return toRange(cropRect.bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateScreenRect();
        canvas.drawRect(screenRect, screenPaint);
        drawGrid(canvas);
        RectF actualCrop = toActualRect(cropRect);
        canvas.drawRect(actualCrop, cropFillPaint);
        canvas.drawRect(actualCrop, cropStrokePaint);
        drawHandles(canvas, actualCrop);
        canvas.drawText("第一屏幕", screenRect.centerX(), screenRect.bottom + dp(18), textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                updateScreenRect();
                activeMode = hitTest(x, y);
                lastX = x;
                lastY = y;
                return activeMode != MODE_NONE;
            case MotionEvent.ACTION_MOVE:
                if (activeMode != MODE_NONE) {
                    applyDrag((x - lastX) / screenRect.width(), (y - lastY) / screenRect.height());
                    lastX = x;
                    lastY = y;
                    invalidate();
                    notifyChanged();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeMode = MODE_NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                break;
        }
        return true;
    }

    private void updateScreenRect() {
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom() - dp(24);
        float size = Math.max(1f, Math.min(availableWidth, availableHeight));
        float left = getPaddingLeft() + (availableWidth - size) / 2f;
        float top = getPaddingTop();
        screenRect.set(left, top, left + size, top + size);
    }

    private void drawGrid(Canvas canvas) {
        for (int i = 1; i < 4; i++) {
            float p = screenRect.left + screenRect.width() * i / 4f;
            canvas.drawLine(p, screenRect.top, p, screenRect.bottom, gridPaint);
            p = screenRect.top + screenRect.height() * i / 4f;
            canvas.drawLine(screenRect.left, p, screenRect.right, p, gridPaint);
        }
        canvas.drawRect(screenRect, gridPaint);
    }

    private void drawHandles(Canvas canvas, RectF rect) {
        float r = dp(HANDLE_RADIUS_DP);
        drawHandle(canvas, rect.left, rect.top, r);
        drawHandle(canvas, rect.right, rect.top, r);
        drawHandle(canvas, rect.left, rect.bottom, r);
        drawHandle(canvas, rect.right, rect.bottom, r);
    }

    private void drawHandle(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, handlePaint);
        canvas.drawCircle(x, y, radius, cropStrokePaint);
    }

    private int hitTest(float x, float y) {
        RectF actual = toActualRect(cropRect);
        float threshold = dp(28);
        if (distance(x, y, actual.left, actual.top) <= threshold) return MODE_TOP_LEFT;
        if (distance(x, y, actual.right, actual.top) <= threshold) return MODE_TOP_RIGHT;
        if (distance(x, y, actual.left, actual.bottom) <= threshold) return MODE_BOTTOM_LEFT;
        if (distance(x, y, actual.right, actual.bottom) <= threshold) return MODE_BOTTOM_RIGHT;
        if (actual.contains(x, y)) return MODE_MOVE;
        return MODE_NONE;
    }

    private void applyDrag(float dx, float dy) {
        switch (activeMode) {
            case MODE_MOVE:
                moveRect(dx, dy);
                break;
            case MODE_TOP_LEFT:
                cropRect.left = clamp(cropRect.left + dx, 0f, cropRect.right - MIN_SIZE);
                cropRect.top = clamp(cropRect.top + dy, 0f, cropRect.bottom - MIN_SIZE);
                break;
            case MODE_TOP_RIGHT:
                cropRect.right = clamp(cropRect.right + dx, cropRect.left + MIN_SIZE, 1f);
                cropRect.top = clamp(cropRect.top + dy, 0f, cropRect.bottom - MIN_SIZE);
                break;
            case MODE_BOTTOM_LEFT:
                cropRect.left = clamp(cropRect.left + dx, 0f, cropRect.right - MIN_SIZE);
                cropRect.bottom = clamp(cropRect.bottom + dy, cropRect.top + MIN_SIZE, 1f);
                break;
            case MODE_BOTTOM_RIGHT:
                cropRect.right = clamp(cropRect.right + dx, cropRect.left + MIN_SIZE, 1f);
                cropRect.bottom = clamp(cropRect.bottom + dy, cropRect.top + MIN_SIZE, 1f);
                break;
            default:
                break;
        }
    }

    private void moveRect(float dx, float dy) {
        float width = cropRect.width();
        float height = cropRect.height();
        float left = clamp(cropRect.left + dx, 0f, 1f - width);
        float top = clamp(cropRect.top + dy, 0f, 1f - height);
        cropRect.set(left, top, left + width, top + height);
    }

    private RectF toActualRect(RectF unitRect) {
        return new RectF(
            screenRect.left + unitRect.left * screenRect.width(),
            screenRect.top + unitRect.top * screenRect.height(),
            screenRect.left + unitRect.right * screenRect.width(),
            screenRect.top + unitRect.bottom * screenRect.height()
        );
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onCropChanged(getCropStartX(), getCropEndX(), getCropStartY(), getCropEndY());
        }
    }

    private float toUnit(int value) {
        return (clamp(value, -100, 100) + 100) / 200f;
    }

    private int toRange(float unit) {
        return Math.round(clamp(unit, 0f, 1f) * 200f - 100f);
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
