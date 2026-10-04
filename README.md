# 车速表（GPS Speedometer）

Android 实时测速 App，**AndroidX + Gradle** 工程结构。

## 功能

- **实时速度显示**：模拟指针仪表盘 + 大号数字读数，量程 0–260 km/h（分度 20），中央数字不设上限；可切换 km/h / m/s，按钮、表盘、曲线全界面联动
- **精准测速**：优先使用 GPS 芯片的多普勒测速 `location.getSpeed()`（比"位移/时间"精确得多），设备不支持时自动回退到位置差分计算；`SpeedFilter` 纯逻辑类负责采样验收（水平精度、速度精度、尖峰启发式）+ 非对称自适应低通滤波（加速紧贴目标、减速快速回落、停车约 1 秒内归零）
- **超速提醒**：弹窗选择 60/80/100/120 km/h 阈值，超速时表盘数字红闪 + 震动（持续超速每 10 秒重复），刻度上出现红色限速标记线
- **最高速度**：本次运行内累计，点击"最高"一栏清零；每次启动 App 自动从零开始
- **开始/停止控制**：点击「开始测速」才启动 GPS 测速，再点停止；经纬度/地址显示独立于该开关常驻更新
- **v-t 速度曲线**：表盘下方滚动显示最近 60 秒的速度曲线，纵轴按峰值自适应，填充渐变 + 最新采样点，暂停时冻结
- **位置显示**：实时经纬度坐标 + 系统级逆地理编码（Geocoder）显示当前地址，移动 50m 或每 30 秒才重新解析，后台线程执行不卡 UI
- **精美外观**：深色渐变表盘、绿→黄→红速度渐变弧、带辉光的指针、GPS 精度 / 参与定位卫星数 / 本次最高速度
- **机械感指针**：指针动画采用二阶弹簧-阻尼系统，带惯性、到位轻微过冲后回稳，接近真实机械表针的物理表现
- 常亮屏幕，竖屏锁定，运行时定位权限自动申请，划掉最近任务即彻底退出

## 工程结构

```
车速表/
├── build.gradle               # 顶层构建脚本（AGP 3.2.1）
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/            # Gradle 4.6
└── app/
    ├── build.gradle           # minSdk 23 / targetSdk 28，零第三方依赖
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/speedometer/app/
        │   ├── MainActivity.java      # 开始/停止控制、定位监听、超速提醒、换肤、持久化
        │   ├── SpeedFilter.java       # 纯逻辑：采样验收 + 滤波（可独立测试）
        │   ├── SpeedChartView.java    # v-t 速度曲线图（60 秒滚动窗口）
        │   ├── SpeedometerView.java   # 自绘仪表盘（渐变弧、刻度、限速线、机械感指针）
        │   └── Skin.java              # 皮肤调色板（4 套内置配色）
        ├── res/                       # 布局、主题、图标
        └── (src/test) SpeedFilterTest.java  # 滤波逻辑自检（main 方法，无 JUnit 依赖）
```

> **为什么没有 AndroidX 依赖？** AIDE 的内置编译器（IncrementalAAPT2 流程）无法解析 AndroidX AAR 中的资源，会报 `style/Theme.AppCompat not found`。因此工程保留 Gradle 结构、使用纯系统框架 API（`Activity` + `Theme.Material`），AIDE 与 Android Studio 均可直接构建。需要在 Android Studio 里引入 Material 组件时，把主题换回 `Theme.AppCompat/Theme.Material3` 并添加对应依赖即可。

## 构建

### Android Studio
直接 `Open` 本文件夹即可，Gradle 版本由 wrapper 指定（4.6），首次同步会自动下载 AGP 3.2.1 和 AndroidX 1.0.2 依赖。

### AIDE
AIDE 对 Gradle 工程的支持有限，本工程的版本特意选在其兼容区间内：

1. 把整个 `车速表` 文件夹复制到手机存储，例如 `/sdcard/AppProjects/车速表`
2. AIDE → 菜单「打开项目」→ 选中该文件夹（识别其中的 Gradle 工程）
3. 若提示 SDK 版本问题，把 `app/build.gradle` 中 `compileSdkVersion 28` 改成 AIDE 内置版本（如 26）
4. 若依赖下载失败，检查网络（需要能访问 maven.google.com / jcenter）

## 测速准确性说明

- 手机 GPS 速度误差通常在 **±1–2 km/h** 以内，冷启动首次定位需要 1–3 分钟
- 尽量将手机固定在能看到天空的位置（挡风玻璃下），避免在隧道、高楼峡谷中测量
- 仪表底部显示 `±Xm` 为当前 GPS 水平精度，数值越小速度越可信

## 升级提示

工程刻意使用较旧的 AGP/AndroidX 版本以兼容 AIDE。若只在 Android Studio 使用，可升级到：AGP 8.x + Gradle 8.x + `androidx.appcompat:appcompat:1.7.0`，同时把 `MainActivity` 中的 `GpsStatus` 卫星监听换成 `LocationManager.addOnNmeaMessageListener` 或 GnssStatus 回调（API 24+）。
