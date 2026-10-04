package com.speedometer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自绘模拟车速表：渐变弧形仪表 + 指针 + 数字读数 + 流光动效，配色由 Skin 驱动。
 */
public class SpeedometerView extends View {

    private static final float MAX_SPEED = 260f; // 满量程，恒为 km/h
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    private float targetSpeed = 0f;
    private float displayedSpeed = 0f;
    /** 指针角速度（km/h/s），用于二阶弹簧-阻尼动画模拟机械表针惯性 */
    private float needleVelocity = 0f;
    private long lastFrameMs = 0L;
    /** 弹簧刚度与阻尼系数：调高刚度降低跟随滞后，ζ≈0.70，轻微过冲 */
    private static final float SPRING_K = 130f;
    private static final float SPRING_C = 16f;

    /** 数字读数单位文字："km/h" 或 "m/s" */
    private String unit = "km/h";
    /** 显示值 = 内部 km/h 值 × unitScale；切单位时表盘/数字整体换算 */
    private float unitScale = 1f;
    /** 表盘满量程（显示单位）：km/h 260，m/s 约 72.2 */
    private float scaleMax = MAX_SPEED;
    /** 小刻度/主刻度步长（显示单位） */
    private float lineStep = 10f;
    private float majorStep = 20f;

    /** 限速标记位置（km/h），<=0 表示不显示 */
    private float overspeedLimit = -1f;
    /** 是否处于超速状态（数字读数红闪） */
    private boolean overspeed = false;

    private Skin skin = Skin.ALL[0];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final Matrix gradientMatrix = new Matrix();
    private SweepGradient arcGradient;
    private boolean gradientReady = false;

    public SpeedometerView(Context context) {
        super(context);
        init();
    }

    public SpeedometerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpeedometerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        // 指针/文字的辉光需要软件层支持
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        rebuildGradient();
    }

    /** 设置当前速度，单位 km/h。指针/弧钳制在表盘量程内，数字读数不设上限 */
    public void setSpeed(float kmh) {
        targetSpeed = Math.max(0f, kmh);
        postInvalidateOnAnimation();
    }

    /** 在表盘刻度上标出限速线；<=0 表示不显示 */
    public void setOverspeedLimit(float kmh) {
        overspeedLimit = kmh;
        invalidate();
    }

    /** 超速时数字读数变红闪烁 */
    public void setOverspeed(boolean over) {
        if (overspeed != over) {
            overspeed = over;
            invalidate();
        }
    }

    /** 设置显示单位："km/h" 或 "m/s"。内部数据恒为 km/h，绘制时整体换算 */
    public void setUnit(String unit) {
        if (unit == null || unit.equals(this.unit)) {
            return;
        }
        this.unit = unit;
        if ("m/s".equals(unit)) {
            unitScale = 1f / 3.6f;
            scaleMax = MAX_SPEED / 3.6f; // ≈72.2 m/s
            lineStep = 4f;
            majorStep = 8f;
        } else {
            unitScale = 1f;
            scaleMax = MAX_SPEED;
            lineStep = 10f;
            majorStep = 20f;
        }
        invalidate();
    }

    /** 应用皮肤配色 */
    public void setSkin(Skin s) {
        if (s == null || s == skin) {
            return;
        }
        skin = s;
        rebuildGradient();
        invalidate();
    }

    private void rebuildGradient() {
        int steps = 96;
        int[] colors = new int[steps];
        float[] positions = new float[steps];
        for (int i = 0; i < steps; i++) {
            float angle = i * 360f / steps; // SweepGradient 从 0°（3 点钟）顺时针
            float f = -1f;
            if (angle >= START_ANGLE) {
                f = (angle - START_ANGLE) / SWEEP_ANGLE;
            } else if (angle <= (START_ANGLE + SWEEP_ANGLE) % 360f) {
                f = (angle + 360f - START_ANGLE) / SWEEP_ANGLE;
            }
            if (f >= 0f && f <= 1f) {
                colors[i] = skin.arcColor(f);
            } else {
                colors[i] = 0x00000000;
            }
            positions[i] = i / (float) (steps - 1);
        }
        arcGradient = new SweepGradient(0, 0, colors, positions);
        gradientReady = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f;
        float stroke = radius * 0.085f;
        float arcRadius = radius - stroke;
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 机械式指针动画：二阶弹簧-阻尼系统，带惯性，到顶轻微过冲后回稳
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0L ? 0.016f : Math.min(0.05f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;

        float diff = targetSpeed - displayedSpeed;
        needleVelocity += (diff * SPRING_K - needleVelocity * SPRING_C) * dt;
        if (needleVelocity > 800f) needleVelocity = 800f;
        if (needleVelocity < -800f) needleVelocity = -800f;
        displayedSpeed += needleVelocity * dt;
        if (displayedSpeed < 0f) {
            displayedSpeed = 0f;
            if (needleVelocity < 0f) needleVelocity = 0f;
        }
        // 注意：displayedSpeed 不设上限——数字读数显示真实速度，
        // 指针角度和弧长在绘制时单独钳制到表盘量程

        boolean settled = Math.abs(targetSpeed - displayedSpeed) < 0.05f
                && Math.abs(needleVelocity) < 0.5f;
        if (settled) {
            displayedSpeed = targetSpeed;
            needleVelocity = 0f;
        } else {
            postInvalidateOnAnimation();
        }

        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f * 0.94f;
        float stroke = radius * 0.085f;

        // ---- 表盘底：径向渐变圆 ----
        paint.setShader(new RadialGradient(cx, cy, radius,
                skin.dialStart, skin.dialEnd, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.012f);
        paint.setColor(skin.rim);
        canvas.drawCircle(cx, cy, radius - radius * 0.006f, paint);

        // ---- 背景轨道弧（方头）----
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(skin.track);
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, paint);

        // ---- 速度渐变弧（超过量程时钳到满弧，均为显示单位）----
        float fraction = Math.min(displayedSpeed * unitScale, scaleMax) / scaleMax;
        if (fraction > 0.005f && gradientReady) {
            gradientMatrix.reset();
            gradientMatrix.postTranslate(cx, cy);
            arcGradient.setLocalMatrix(gradientMatrix);
            paint.setShader(arcGradient);
            paint.setColor(0xFFFFFFFF);
            canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * fraction, false, paint);
            paint.setShader(null);
        }

        // ---- 限速标记：刻度上的红色短线 ----
        if (overspeedLimit > 0f && overspeedLimit <= MAX_SPEED) {
            double rad = Math.toRadians(START_ANGLE + SWEEP_ANGLE * overspeedLimit / MAX_SPEED);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float arcR = arcRect.width() / 2f;
            paint.setStrokeWidth(stroke * 0.45f);
            paint.setColor(0xFFFF1744);
            canvas.drawLine(cx + cos * (arcR - stroke * 1.2f), cy + sin * (arcR - stroke * 1.2f),
                    cx + cos * (arcR + stroke * 0.3f), cy + sin * (arcR + stroke * 0.3f), paint);
        }

        // ---- 刻度与数字 ----
        drawTicks(canvas, cx, cy, radius);

        // ---- 指针 ----
        drawNeedle(canvas, cx, cy, radius);

        // ---- 数字读数 ----
        drawDigital(canvas, cx, cy, radius);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float radius) {
        float outerR = radius - radius * 0.05f;
        int n = Math.round(scaleMax / lineStep);
        int majorEvery = Math.max(1, Math.round(majorStep / lineStep));
        for (int i = 0; i <= n; i++) {
            float v = i * lineStep; // 显示单位的刻度值
            double rad = Math.toRadians(START_ANGLE + SWEEP_ANGLE * v / scaleMax);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            boolean major = i % majorEvery == 0;
            float len = major ? radius * 0.11f : radius * 0.055f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(major ? radius * 0.012f : radius * 0.006f);
            paint.setColor(major ? skin.tickMajor : skin.tickMinor);
            canvas.drawLine(cx + cos * (outerR - len), cy + sin * (outerR - len),
                    cx + cos * outerR, cy + sin * outerR, paint);

            if (major) {
                float labelR = outerR - len - radius * 0.075f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(skin.tickLabel);
                paint.setTextSize(radius * 0.07f);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setShadowLayer(0, 0, 0, 0);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                float ty = cy + sin * labelR + paint.getTextSize() / 2.6f;
                canvas.drawText(String.valueOf(Math.round(v)), cx + cos * labelR, ty, paint);
            }
        }
    }

    private void drawNeedle(Canvas canvas, float cx, float cy, float radius) {
        double rad = Math.toRadians(START_ANGLE
                + SWEEP_ANGLE * Math.min(displayedSpeed * unitScale, scaleMax) / scaleMax);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float tailR = radius * 0.10f;
        float tipR = radius * 0.72f;

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.022f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(skin.needle);
        paint.setShadowLayer(radius * 0.03f, 0, 0, skin.needleGlow);
        canvas.drawLine(cx - cos * tailR, cy - sin * tailR,
                cx + cos * tipR, cy + sin * tipR, paint);
        paint.setShadowLayer(0, 0, 0, 0);

        // 中心轴帽
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius * 0.085f,
                skin.hubStart, skin.hubEnd, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * 0.085f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.006f);
        paint.setColor(skin.needle);
        canvas.drawCircle(cx, cy, radius * 0.085f, paint);
    }

    private void drawDigital(Canvas canvas, float cx, float cy, float radius) {
        boolean blinkRed = overspeed && SystemClock.uptimeMillis() / 400 % 2 == 0;
        if (blinkRed) {
            paint.setShadowLayer(radius * 0.03f, 0, 0, 0x99FF1744);
            paint.setColor(0xFFFF5252);
        } else {
            paint.setShadowLayer(radius * 0.02f, 0, 0, skin.digitalGlow);
            paint.setColor(skin.digital);
        }
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(radius * 0.24f);
        float displayValue = displayedSpeed * unitScale;
        String text = "m/s".equals(unit)
                ? String.format(java.util.Locale.US, "%.1f", displayValue)
                : String.valueOf(Math.round(displayValue));
        canvas.drawText(text, cx, cy + radius * 0.52f, paint);

        paint.setShadowLayer(0, 0, 0, 0);
        paint.setColor(skin.tickLabel);
        paint.setTextSize(radius * 0.075f);
        paint.setLetterSpacing(0.25f);
        canvas.drawText(unit, cx, cy + radius * 0.66f, paint);

        // 超速闪烁需要持续刷新
        if (overspeed) {
            postInvalidateOnAnimation();
        }
    }
}
