package com.speedometer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * v-t 速度曲线图：滚动显示最近 60 秒的速度采样，配色由 Skin 驱动。
 * 暂停测速时冻结在最后一个采样。
 */
public class SpeedChartView extends View {

    private static final long WINDOW_MS = 60000L;

    /** 采样点 {时间戳ms, 速度km/h} */
    private final ArrayDeque<float[]> samples = new ArrayDeque<float[]>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF panel = new RectF();
    private Skin skin = Skin.ALL[0];
    /** 纵轴单位文字 */
    private String unit = "km/h";
    /** 显示值 = 内部存储的 km/h 值 × unitScale，切单位时历史曲线同步换算 */
    private float unitScale = 1f;

    public SpeedChartView(Context context) {
        super(context);
    }

    public SpeedChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SpeedChartView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setSkin(Skin s) {
        if (s != null) {
            skin = s;
            invalidate();
        }
    }

    public void setUnit(String unit) {
        if (unit == null || unit.equals(this.unit)) {
            return;
        }
        this.unit = unit;
        unitScale = "m/s".equals(unit) ? 1f / 3.6f : 1f;
        invalidate();
    }

    /** 追加一个速度采样（km/h） */
    public void addSample(float kmh) {
        long now = SystemClock.uptimeMillis();
        samples.addLast(new float[]{now, kmh});
        long cutoff = now - WINDOW_MS;
        while (!samples.isEmpty() && samples.peekFirst()[0] < cutoff) {
            samples.pollFirst();
        }
        invalidate();
    }

    /** 清空历史 */
    public void reset() {
        samples.clear();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float padL = dp(28);
        float padR = dp(12);
        float padT = dp(12);
        float padB = dp(16);

        // 面板底色与边框
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(skin.track);
        panel.set(0, 0, w, h);
        canvas.drawRoundRect(panel, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor((skin.tickMinor & 0x00FFFFFF) | 0x90000000);
        canvas.drawRoundRect(panel, dp(10), dp(10), paint);

        float drawW = w - padL - padR;
        float drawH = h - padT - padB;

        // 时间窗口锚点：暂停时冻结在最后一个采样
        long now = samples.isEmpty() ? SystemClock.uptimeMillis()
                : (long) samples.peekLast()[0];
        long t0 = now - WINDOW_MS;

        // 纵轴：km/h 网格 20/标签 40；m/s 网格 5/标签 10；量程按峰值向上取整
        int lineStep = unitScale == 1f ? 20 : 5;
        int labelStep = unitScale == 1f ? 40 : 10;
        float peak = labelStep;
        for (float[] s : samples) {
            if (s[1] * unitScale > peak) peak = s[1] * unitScale;
        }
        int yMax = (int) Math.ceil(peak / lineStep) * lineStep;

        // 坐标轴：网格线 + 左侧速度刻度值（0 是加粗的横轴基线）
        paint.setTypeface(Typeface.DEFAULT);
        for (int v = 0; v <= yMax; v += lineStep) {
            float y = padT + (1f - v / (float) yMax) * drawH;
            boolean baseline = v == 0;
            boolean labelled = v % labelStep == 0;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(baseline ? 1.5f : 1));
            paint.setColor((skin.tickMinor & 0x00FFFFFF)
                    | (baseline ? 0xB0000000 : (labelled ? 0x60000000 : 0x38000000)));
            canvas.drawLine(padL, y, w - padR, y, paint);

            if (labelled) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(skin.tickLabel);
                paint.setTextSize(dp(8.5f));
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.valueOf(v), padL - dp(4), y + dp(3), paint);
            }
        }

        // 时间轴标注：左端 -60s，右端 0s；右上角标单位
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(skin.tickLabel);
        paint.setTextSize(dp(8.5f));
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(unit, w - padR, padT + dp(9), paint);
        canvas.drawText("0s", w - padR, h - dp(4), paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("-60s", padL, h - dp(4), paint);

        if (samples.size() < 2) {
            paint.setColor(skin.tickLabel);
            paint.setTextSize(dp(12));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("开始测速后显示 v-t 曲线", w / 2f, padT + drawH / 2f, paint);
            return;
        }

        float x0 = padL;
        float x1 = w - padR;
        float yBase = padT + drawH;

        // 曲线填充区域
        fillPath.reset();
        boolean first = true;
        Iterator<float[]> it = samples.iterator();
        while (it.hasNext()) {
            float[] s = it.next();
            float x = x0 + (s[0] - t0) / (float) WINDOW_MS * drawW;
            float y = padT + (1f - Math.min(s[1] * unitScale, yMax) / yMax) * drawH;
            if (first) {
                fillPath.moveTo(x, y);
                first = false;
            } else {
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(x1, yBase);
        fillPath.lineTo(x0, yBase);
        fillPath.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, padT, 0, yBase,
                (skin.accent & 0x00FFFFFF) | 0x5A000000,
                (skin.accent & 0x00FFFFFF), Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, paint);
        paint.setShader(null);

        // 曲线描边
        linePath.reset();
        first = true;
        it = samples.iterator();
        float lastX = 0f;
        float lastY = 0f;
        while (it.hasNext()) {
            float[] s = it.next();
            float x = x0 + (s[0] - t0) / (float) WINDOW_MS * drawW;
            float y = padT + (1f - Math.min(s[1] * unitScale, yMax) / yMax) * drawH;
            if (first) {
                linePath.moveTo(x, y);
                first = false;
            } else {
                linePath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(skin.accent);
        canvas.drawPath(linePath, paint);

        // 最新采样点
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(skin.accent);
        canvas.drawCircle(lastX, lastY, dp(3.5f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor((skin.accent & 0x00FFFFFF) | 0x66000000);
        canvas.drawCircle(lastX, lastY, dp(6f), paint);
    }
}
