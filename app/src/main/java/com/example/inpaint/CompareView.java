package com.example.inpaint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 双模画布：
 *  - EDIT 模式：显示原图 + 涂抹遮罩，手指拖动涂画
 *  - COMPARE 模式：左右分屏（原图 vs 修复结果），中间可拖拽分割线
 *
 * 历史：每次抬笔生成一笔遮罩快照，支持 undo/redo（回退/向前）。
 */
public class CompareView extends View {

    public static final int MODE_EDIT = 0;
    public static final int MODE_COMPARE = 1;

    private Bitmap baseBmp;
    private Bitmap resultBmp;
    private Bitmap paintBmp;
    private Canvas paintCanvas;

    // 笔迹历史（每个条目 = 一笔完成后的完整遮罩快照）
    private final Bitmap[] history = new Bitmap[32];
    private int histCount = 0;
    private int histPos = -1;

    private float scale = 1f, offX = 0f, offY = 0f;
    private float lastX, lastY;

    private final Paint brush = new Paint();
    private final Paint maskAlpha = new Paint();
    private final Paint dividerPaint = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint arrowPaint = new Paint();

    private int mode = MODE_EDIT;
    private int brushSize = 64;

    private float splitX = 0.5f;
    private boolean draggingSplit = false;
    private static final float HANDLE_DP = 22f;

    private OnHistoryChangedListener historyListener;

    public interface OnHistoryChangedListener {
        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }
    public void setOnHistoryChangedListener(OnHistoryChangedListener l) { historyListener = l; }

    public CompareView(Context ctx) { this(ctx, null); }

    public CompareView(Context ctx, AttributeSet a) {
        super(ctx, a);

        brush.setColor(Color.WHITE);
        brush.setStyle(Paint.Style.STROKE);
        brush.setStrokeWidth(brushSize);
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeJoin(Paint.Join.ROUND);
        brush.setAntiAlias(true);

        maskAlpha.setAlpha(120);

        dividerPaint.setColor(Color.WHITE);
        dividerPaint.setStrokeWidth(2f);
        dividerPaint.setAntiAlias(true);

        handlePaint.setColor(0xFF0E3B2D);
        handlePaint.setAntiAlias(true);

        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStrokeWidth(2f);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPaint.setAntiAlias(true);
    }

    public void setBaseBitmap(Bitmap bmp) {
        baseBmp = bmp;
        resultBmp = null;
        paintBmp = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
        paintCanvas = new Canvas(paintBmp);
        // 历史重置为单一空状态
        for (int i = 0; i < history.length; i++) history[i] = null;
        histCount = 0; histPos = -1;
        pushHistory(); // 空初始状态
        mode = MODE_EDIT;
        splitX = 0.5f;
        computeTransform();
        notifyHistory();
        invalidate();
    }

    public void setResultBitmap(Bitmap bmp) {
        resultBmp = bmp;
        mode = MODE_COMPARE;
        splitX = 0.5f;
        invalidate();
    }

    public boolean hasResult() { return resultBmp != null; }

    public int cycleBrushSize() {
        if (brushSize == 64) brushSize = 100;
        else if (brushSize == 100) brushSize = 150;
        else brushSize = 64;
        brush.setStrokeWidth(brushSize);
        return brushSize;
    }

    public int getBrushSize() { return brushSize; }

    private void pushHistory() {
        if (paintBmp == null) return;
        Bitmap copy = Bitmap.createBitmap(paintBmp.getWidth(), paintBmp.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(copy).drawBitmap(paintBmp, 0, 0, null);
        // 丢弃 redo 分支
        if (histPos < histCount - 1) histCount = histPos + 1;
        if (histCount < history.length) {
            history[histCount++] = copy;
        } else {
            for (int i = 0; i < history.length - 1; i++) history[i] = history[i + 1];
            history[history.length - 1] = copy;
            histCount = history.length;
        }
        histPos = histCount - 1;
    }

    private void restoreHistory() {
        if (paintBmp == null || histPos < 0) return;
        Bitmap snap = history[histPos];
        paintCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (snap != null) paintCanvas.drawBitmap(snap, 0, 0, null);
    }

    public void undo() {
        if (histPos > 0) {
            histPos--;
            restoreHistory();
            if (resultBmp != null) { resultBmp = null; mode = MODE_EDIT; }
            notifyHistory();
            invalidate();
        }
    }

    public void redo() {
        if (histPos < histCount - 1) {
            histPos++;
            restoreHistory();
            if (resultBmp != null) { resultBmp = null; mode = MODE_EDIT; }
            notifyHistory();
            invalidate();
        }
    }

    /** 清空：把修复效果全部重置，回到原始图片（空遮罩） */
    public void resetAll() {
        if (paintBmp != null) {
            paintCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
        resultBmp = null;
        mode = MODE_EDIT;
        for (int i = 0; i < history.length; i++) history[i] = null;
        histCount = 0; histPos = -1;
        pushHistory();
        splitX = 0.5f;
        notifyHistory();
        invalidate();
    }

    public boolean canUndo() { return histPos > 0; }
    public boolean canRedo() { return histPos < histCount - 1; }

    private void notifyHistory() {
        if (historyListener != null) historyListener.onHistoryChanged(canUndo(), canRedo());
    }

    public int[] getMaskPixels(int w, int h) {
        int[] out = new int[w * h];
        if (paintBmp == null) return out;
        int[] px = new int[w * h];
        paintBmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < out.length; i++) {
            int a = (px[i] >>> 24) & 0xFF;
            out[i] = a > 10 ? 255 : 0;
        }
        return out;
    }

    private void computeTransform() {
        if (baseBmp == null) return;
        float vw = Math.max(getWidth(), 1);
        float vh = Math.max(getHeight(), 1);
        scale = Math.min(vw / baseBmp.getWidth(), vh / baseBmp.getHeight());
        offX = (vw - baseBmp.getWidth() * scale) / 2f;
        offY = (vh - baseBmp.getHeight() * scale) / 2f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeTransform();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseBmp == null) return;

        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(scale, scale);

        if (mode == MODE_COMPARE && resultBmp != null) {
            int sx = (int) (splitX * baseBmp.getWidth());
            canvas.save();
            canvas.clipRect(0, 0, sx, baseBmp.getHeight());
            canvas.drawBitmap(baseBmp, 0, 0, null);
            canvas.drawBitmap(paintBmp, 0, 0, maskAlpha);
            canvas.restore();

            canvas.save();
            canvas.clipRect(sx, 0, baseBmp.getWidth(), baseBmp.getHeight());
            canvas.drawBitmap(resultBmp, 0, 0, null);
            canvas.restore();

            canvas.drawLine(sx, 0, sx, baseBmp.getHeight(), dividerPaint);
        } else {
            canvas.drawBitmap(baseBmp, 0, 0, null);
            if (paintBmp != null) canvas.drawBitmap(paintBmp, 0, 0, maskAlpha);
        }
        canvas.restore();

        if (mode == MODE_COMPARE && resultBmp != null) {
            float density = getResources().getDisplayMetrics().density;
            float hx = offX + splitX * baseBmp.getWidth() * scale;
            float hy = offY + (baseBmp.getHeight() * scale) / 2f;
            float hr = HANDLE_DP * density;
            canvas.drawCircle(hx, hy, hr, handlePaint);
            canvas.drawLine(hx - 7 * density, hy - 5 * density, hx - 2 * density, hy, arrowPaint);
            canvas.drawLine(hx - 7 * density, hy + 5 * density, hx - 2 * density, hy, arrowPaint);
            canvas.drawLine(hx + 7 * density, hy - 5 * density, hx + 2 * density, hy, arrowPaint);
            canvas.drawLine(hx + 7 * density, hy + 5 * density, hx + 2 * density, hy, arrowPaint);
            handlePaint.setStyle(Paint.Style.STROKE);
            handlePaint.setStrokeWidth(1.5f);
            canvas.drawCircle(hx, hy, hr, handlePaint);
            handlePaint.setStyle(Paint.Style.FILL);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (baseBmp == null) return false;
        float density = getResources().getDisplayMetrics().density;

        if (mode == MODE_COMPARE && resultBmp != null) {
            float hx = offX + splitX * baseBmp.getWidth() * scale;
            float hy = offY + (baseBmp.getHeight() * scale) / 2f;
            float hr = HANDLE_DP * density;
            float dx = event.getX() - hx;
            float dy = event.getY() - hy;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (Math.abs(dx) <= hr + 16 * density && Math.abs(dy) <= hr + 32 * density) {
                    draggingSplit = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                // 点手柄以外区域 -> 回到编辑模式继续涂抹
                mode = MODE_EDIT;
                resultBmp = null;
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (draggingSplit) {
                    float vx = (event.getX() - offX) / scale;
                    splitX = Math.max(0f, Math.min(1f, vx / baseBmp.getWidth()));
                    invalidate();
                    return true;
                }
                mode = MODE_EDIT;
                resultBmp = null;
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (draggingSplit) { draggingSplit = false; return true; }
                mode = MODE_EDIT;
                resultBmp = null;
            }
            if (mode == MODE_COMPARE) return false;
        }

        // EDIT 模式：涂抹
        float bx = (event.getX() - offX) / scale;
        float by = (event.getY() - offY) / scale;
        if (bx < 0 || by < 0 || bx >= baseBmp.getWidth() || by >= baseBmp.getHeight()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = bx; lastY = by;
                paintCanvas.drawPoint(bx, by, brush);
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                paintCanvas.drawLine(lastX, lastY, bx, by, brush);
                lastX = bx; lastY = by;
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pushHistory();
                notifyHistory();
                break;
        }
        return true;
    }
}
