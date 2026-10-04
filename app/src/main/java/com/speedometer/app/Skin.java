package com.speedometer.app;

/**
 * 皮肤调色板：一套颜色控制整个界面的观感。
 */
public class Skin {

    public final String name;
    /** 背景渐变（上/中/下） */
    public final int bgTop, bgCenter, bgBottom;
    /** 表盘径向渐变 */
    public final int dialStart, dialEnd;
    /** 中心轴帽渐变 */
    public final int hubStart, hubEnd;
    /** 外圈描边（带透明度）、轨道弧 */
    public final int rim, track;
    /** 强调色（文字/按钮）、最高速数值色 */
    public final int accent, maxColor;
    /** 速度弧四段渐变：低速 → 中速 → 高速 → 极速 */
    public final int arcLow, arcMid, arcHigh, arcMax;
    /** 指针颜色与辉光 */
    public final int needle, needleGlow;
    /** 数字读数颜色与辉光 */
    public final int digital, digitalGlow;
    /** 刻度：主刻度 / 次刻度 / 数字标签 */
    public final int tickMajor, tickMinor, tickLabel;

    public Skin(String name, int bgTop, int bgCenter, int bgBottom,
                int dialStart, int dialEnd, int hubStart, int hubEnd,
                int rim, int track, int accent, int maxColor,
                int arcLow, int arcMid, int arcHigh, int arcMax,
                int needle, int needleGlow, int digital, int digitalGlow,
                int tickMajor, int tickMinor, int tickLabel) {
        this.name = name;
        this.bgTop = bgTop;
        this.bgCenter = bgCenter;
        this.bgBottom = bgBottom;
        this.dialStart = dialStart;
        this.dialEnd = dialEnd;
        this.hubStart = hubStart;
        this.hubEnd = hubEnd;
        this.rim = rim;
        this.track = track;
        this.accent = accent;
        this.maxColor = maxColor;
        this.arcLow = arcLow;
        this.arcMid = arcMid;
        this.arcHigh = arcHigh;
        this.arcMax = arcMax;
        this.needle = needle;
        this.needleGlow = needleGlow;
        this.digital = digital;
        this.digitalGlow = digitalGlow;
        this.tickMajor = tickMajor;
        this.tickMinor = tickMinor;
        this.tickLabel = tickLabel;
    }

    /** 按速度占比（0~1）取速度弧颜色 */
    public int arcColor(float fraction) {
        if (fraction <= 0.4f) {
            return lerp(arcLow, arcMid, fraction / 0.4f);
        } else if (fraction <= 0.7f) {
            return lerp(arcMid, arcHigh, (fraction - 0.4f) / 0.3f);
        }
        return lerp(arcHigh, arcMax, (fraction - 0.7f) / 0.3f);
    }

    private static int lerp(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return android.graphics.Color.rgb(r, g, b);
    }

    /** 内置皮肤 */
    public static final Skin[] ALL = {
            new Skin("深海蓝",
                    0xFF0C1A33, 0xFF0A1428, 0xFF04080F,
                    0xFF0E1E3C, 0xFF050A16,
                    0xFF3A5378, 0xFF101E36,
                    0x33284568, 0xFF16294A,
                    0xFF1DE9B6, 0xFFFF9100,
                    0xFF00E676, 0xFFFFD600, 0xFFFF9100, 0xFFFF1744,
                    0xFFFF3D5A, 0x88FF1744,
                    0xFFEAF6FF, 0x661DE9B6,
                    0xFFBFD4F0, 0xFF51678A, 0xFF9FB6D8),
            new Skin("烈焰红",
                    0xFF1A0E0E, 0xFF140A0A, 0xFF080404,
                    0xFF2A1414, 0xFF0C0606,
                    0xFF6E3A28, 0xFF1A0C08,
                    0x40683A32, 0xFF3A201A,
                    0xFFFF7043, 0xFFFFAB40,
                    0xFFFFD180, 0xFFFFAB40, 0xFFFF6D00, 0xFFDD2C00,
                    0xFFFFAB40, 0x88FF6D00,
                    0xFFFFF3E0, 0x66FF9100,
                    0xFFF0D4C4, 0xFF8A6A5A, 0xFFD8B6A6),
            new Skin("极光绿",
                    0xFF06141A, 0xFF051016, 0xFF020708,
                    0xFF0A2430, 0xFF03121A,
                    0xFF1E5468, 0xFF061820,
                    0x33285A68, 0xFF123240,
                    0xFF64FFDA, 0xFFC6FF00,
                    0xFF18FFFF, 0xFF00E676, 0xFFC6FF00, 0xFFFFEA00,
                    0xFF64FFDA, 0x8800E5FF,
                    0xFFE0FFFF, 0x6600E5FF,
                    0xFFB4E8F0, 0xFF4A7A8A, 0xFF9CD0DA),
            new Skin("星云紫",
                    0xFF170A26, 0xFF100722, 0xFF06030E,
                    0xFF241040, 0xFF0D0518,
                    0xFF4A2A78, 0xFF120822,
                    0x403A2868, 0xFF2A1846,
                    0xFFB388FF, 0xFFFF4081,
                    0xFFB388FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFF1744,
                    0xFFFF4081, 0x88F50057,
                    0xFFF3E8FF, 0x66B388FF,
                    0xFFD4C4F0, 0xFF6A5A8A, 0xFFB0A6D8),
    };
}
