package com.speedometer.app;

/**
 * 纯 Java 逻辑类：GPS 速度采样的质量验收 + 自适应滤波。
 * 不依赖任何 Android API，可独立做单元测试 / main 自检。
 */
public class SpeedFilter {

    /** 水平精度劣于此值（米）的采样点直接丢弃 */
    public static final float MAX_HORIZONTAL_ACCURACY_M = 50f;
    /** 速度精度劣于此值（km/h）的采样点直接丢弃（设备支持该字段时） */
    public static final float MAX_SPEED_ACCURACY_KMH = 15f;
    /** 低于该速度视为静止 */
    public static final float ZERO_SPEED_KMH = 1.0f;

    private float filteredSpeed = 0f;
    private boolean lastAccepted = false;

    /** 一次 GPS 速度采样 */
    public static class Sample {
        public final float speedKmh;
        public final float horizontalAccuracyM;
        /** 速度精度（km/h），设备不支持该字段时传 -1 */
        public final float speedAccuracyKmh;

        public Sample(float speedKmh, float horizontalAccuracyM, float speedAccuracyKmh) {
            this.speedKmh = speedKmh;
            this.horizontalAccuracyM = horizontalAccuracyM;
            this.speedAccuracyKmh = speedAccuracyKmh;
        }
    }

    public float getFiltered() {
        return filteredSpeed;
    }

    /** 上一次 update() 的采样是否被采纳 */
    public boolean lastSampleAccepted() {
        return lastAccepted;
    }

    public void reset() {
        filteredSpeed = 0f;
        lastAccepted = false;
    }

    /**
     * 输入一个采样，返回滤波后的速度。
     * 被拒绝的采样不参与滤波，返回值保持不变。
     */
    public float update(Sample s) {
        lastAccepted = accept(s);
        if (!lastAccepted) {
            return filteredSpeed;
        }

        float raw = s.speedKmh;
        if (raw < ZERO_SPEED_KMH) {
            // 停车：GPS 报 0 时迅速衰减归零（约 3 个定位更新内到 0）
            filteredSpeed = filteredSpeed < 2f ? 0f : filteredSpeed * 0.2f;
        } else {
            // 非对称自适应低通：减速快速回落，加速紧贴目标
            float diff = raw - filteredSpeed;
            float alpha;
            if (diff < 0) {
                alpha = Math.min(0.9f, 0.6f + Math.abs(diff) / 12f);
            } else {
                alpha = Math.min(0.85f, 0.45f + diff / 25f);
            }
            filteredSpeed += alpha * diff;
        }
        return filteredSpeed;
    }

    private boolean accept(Sample s) {
        if (s.horizontalAccuracyM > MAX_HORIZONTAL_ACCURACY_M) {
            return false;
        }
        if (s.speedAccuracyKmh >= 0f && s.speedAccuracyKmh > MAX_SPEED_ACCURACY_KMH) {
            return false;
        }
        // 速度精度字段不可用的老设备：用"低速时速度暴增"作为尖峰启发式判断
        if (s.speedAccuracyKmh < 0f
                && s.speedKmh - filteredSpeed > 60f
                && s.horizontalAccuracyM > 15f) {
            return false;
        }
        return true;
    }
}
