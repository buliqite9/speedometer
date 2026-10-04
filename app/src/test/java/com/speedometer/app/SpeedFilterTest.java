package com.speedometer.app;

/**
 * SpeedFilter 的自检测试（纯 Java，无 JUnit 依赖）。
 * 运行方式：电脑上 `java SpeedFilterTest`，或 Android Studio 中右键 Run main()。
 * AIDE 不编译 src/test，但该文件保证逻辑回归有据可查。
 */
public class SpeedFilterTest {

    private static int failures = 0;

    public static void main(String[] args) {
        check("拒绝水平精度差的采样",
                !accepts(60f, 80f, -1f, 0f));

        check("拒绝速度精度差的采样",
                !accepts(100f, 8f, 30f, 0f));

        check("接受正常采样",
                accepts(50f, 8f, 2f, 0f));

        check("低速时速度暴增被当作尖峰拒绝（无速度精度字段的设备）",
                !accepts(120f, 25f, -1f, 0f));

        check("同样的暴增在精度好时不拒绝",
                accepts(120f, 8f, -1f, 0f));

        check("加速平滑：单次更新不会直接跳到目标值",
                convergesGradually(50f));

        check("减速快速回落：一步至少收敛六成差距",
                deceleratesFast());

        check("停车快速归零",
                stopsToZero());

        check("持续采样能收敛到目标速度",
                convergesToTarget(120f));

        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static boolean accepts(float speedKmh, float hacc, float sacc, float current) {
        SpeedFilter f = new SpeedFilter();
        for (int i = 0; i < 10; i++) f.update(new SpeedFilter.Sample(current, 8f, 2f));
        f.update(new SpeedFilter.Sample(speedKmh, hacc, sacc));
        return f.lastSampleAccepted();
    }

    private static boolean convergesGradually(float target) {
        SpeedFilter f = new SpeedFilter();
        float v = f.update(new SpeedFilter.Sample(target, 8f, 2f));
        return v > 0f && v < target * 0.9f;
    }

    private static boolean deceleratesFast() {
        SpeedFilter f = new SpeedFilter();
        for (int i = 0; i < 30; i++) f.update(new SpeedFilter.Sample(100f, 8f, 2f));
        float v = f.update(new SpeedFilter.Sample(40f, 8f, 2f));
        // alpha >= 0.6，一步后应 <= 100 - 0.6*60 = 64
        return v <= 64f;
    }

    private static boolean stopsToZero() {
        SpeedFilter f = new SpeedFilter();
        for (int i = 0; i < 30; i++) f.update(new SpeedFilter.Sample(80f, 8f, 2f));
        float v = f.update(new SpeedFilter.Sample(0.5f, 8f, 2f));
        // 0.5 < 1.0 视为静止，80*0.2 = 16
        return v <= 16f;
    }

    private static boolean convergesToTarget(float target) {
        SpeedFilter f = new SpeedFilter();
        float v = 0f;
        for (int i = 0; i < 200; i++) {
            v = f.update(new SpeedFilter.Sample(target, 8f, 2f));
        }
        return Math.abs(v - target) < 1f;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("[PASS] " + name);
        } else {
            System.out.println("[FAIL] " + name);
            failures++;
        }
    }
}
