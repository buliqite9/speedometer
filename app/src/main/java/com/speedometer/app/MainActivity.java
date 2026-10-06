package com.speedometer.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;

public class MainActivity extends Activity {

    private static final String TAG = "Speedometer";
    private static final int PERMISSION_REQUEST_CODE = 10;
    /** 可选择的限速提醒阈值（km/h），0 表示关闭 */
    private static final int[] LIMIT_OPTIONS = {0, 60, 80, 100, 120};
    private static final String PREFS_NAME = "speedometer";
    private static final String KEY_LIMIT_INDEX = "limit_index";
    private static final String KEY_UNIT_MS = "unit_ms";
    /** 持续超速时重复提醒的间隔 */
    private static final long OVERSPEED_INTERVAL_MS = 10000L;

    private SpeedometerView gauge;
    private SpeedChartView chart;
    private TextView headerTitle;
    private TextView statusText;
    private TextView startBtn;
    private TextView coordsText;
    private TextView addressText;
    private TextView accuracyText;
    private TextView satelliteText;
    private TextView maxText;
    private TextView overspeedBtn;
    private TextView unitBtn;
    private TextView altText;
    private TextView gradeText;

    /** 轨迹点：测速期间每个被采纳的定位点记一个，用于 GPX 导出 */
    private static class TrackPoint {
        long time;      // epoch ms
        double lat, lon;
        float ele;      // 海拔 m
    }

    private static final int MAX_TRACK_POINTS = 20000;
    private static final int REQUEST_CREATE_GPX = 11;
    private final ArrayDeque<TrackPoint> track = new ArrayDeque<TrackPoint>();

    /** 坡度：由相邻定位点的海拔差/水平距离估算，EMA 平滑 */
    private Location lastGradeLoc = null;
    private float gradeSmooth = Float.NaN;

    private LocationManager locationManager;
    private Location lastLocation;
    private Vibrator vibrator;
    private SharedPreferences prefs;

    /** 逆地理编码：后台线程执行，避免阻塞 UI；移动 50m 或间隔 30s 才重新解析 */
    private Geocoder geocoder;
    private final ExecutorService geoExecutor = Executors.newSingleThreadExecutor();
    private double lastGeoLat = Double.NaN;
    private double lastGeoLon = Double.NaN;
    private long lastGeoTime = 0L;

    /** 常驻的低频定位监听：只用于经纬度/地址显示，与测速开关无关 */
    private final LocationListener infoListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updateCoordsUI(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final SpeedFilter speedFilter = new SpeedFilter();
    private float maxSpeed = 0f;
    private int limitIndex = 0;
    private boolean overspeeding = false;
    /** 是否使用 m/s 显示（false 为 km/h），内部数据恒为 km/h */
    private boolean useMs = false;
    /** 旋转重建视图后需要重新显示的运行时状态 */
    private int statusResId = R.string.idle_hint;
    private float lastAccuracyM = -1f;
    private int lastSatellites = -1;
    private float lastFilteredKmh = 0f;
    private Location lastCoordsLocation = null;
    /** 是否处于测速中：未点开始前不监听定位 */
    private boolean running = false;

    /** 持续超速时的周期提醒（每 10 秒震动一次） */
    private final Handler overspeedHandler = new Handler();
    private final Runnable overspeedReminder = new Runnable() {
        @Override
        public void run() {
            if (overspeeding) {
                vibrateAlert();
                overspeedHandler.postDelayed(this, OVERSPEED_INTERVAL_MS);
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onNewLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
            setStatus(R.string.waiting_gps);
        }
    };

    private final GpsStatus.Listener gpsStatusListener = new GpsStatus.Listener() {
        @Override
        public void onGpsStatusChanged(int event) {
            if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS && locationManager != null) {
                try {
                    GpsStatus status = locationManager.getGpsStatus(null);
                    int used = 0;
                    for (GpsSatellite s : status.getSatellites()) {
                        if (s.usedInFix()) used++;
                    }
                    lastSatellites = used;
                    satelliteText.setText(String.valueOf(used));
                } catch (SecurityException ignored) {
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        geocoder = new Geocoder(this, Locale.getDefault());

        // 最高速度每次启动从零开始，不持久化
        maxSpeed = 0f;
        limitIndex = prefs.getInt(KEY_LIMIT_INDEX, 0);
        useMs = prefs.getBoolean(KEY_UNIT_MS, false);

        bindViews();
        applyViewState();

        Log.d(TAG, "onCreate done, permission=" + (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED));

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startInfoUpdates();
        } else {
            handleMissingPermission();
        }
    }

    /** 绑定视图与点击事件（旋转屏幕后需重新调用） */
    private void bindViews() {
        gauge = (SpeedometerView) findViewById(R.id.speedometer);
        chart = (SpeedChartView) findViewById(R.id.speed_chart);
        headerTitle = (TextView) findViewById(R.id.header_title);
        statusText = (TextView) findViewById(R.id.status_text);
        startBtn = (TextView) findViewById(R.id.start_btn);
        coordsText = (TextView) findViewById(R.id.coords_text);
        addressText = (TextView) findViewById(R.id.address_text);
        accuracyText = (TextView) findViewById(R.id.accuracy_text);
        satelliteText = (TextView) findViewById(R.id.satellite_text);
        maxText = (TextView) findViewById(R.id.max_text);
        overspeedBtn = (TextView) findViewById(R.id.overspeed_btn);
        unitBtn = (TextView) findViewById(R.id.unit_btn);
        altText = (TextView) findViewById(R.id.alt_text);
        gradeText = (TextView) findViewById(R.id.grade_text);

        // 点击"最高"一栏清零重新累计
        findViewById(R.id.max_box).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetMaxSpeed();
            }
        });

        // 点击限速按钮弹出阈值选项
        overspeedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLimitOptions();
            }
        });

        // 点击表盘：HUD 模式下作为退出开关（表盘面积大，镜像状态下也好点）
        gauge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hudOn) {
                    toggleHud();
                }
            }
        });

        // 点击开始/停止按钮
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleStart();
            }
        });

        // 点击单位按钮切换 km/h / m/s
        unitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleUnit();
            }
        });

        // HUD：表盘上下镜像，供挡风玻璃反射
        findViewById(R.id.hud_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHud();
            }
        });

        // 导出 GPX 轨迹（系统文件选择器，无需存储权限）
        findViewById(R.id.gpx_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportGpx();
            }
        });
    }

    /** 把当前运行状态重新应用到视图（旋转屏幕后调用） */
    private void applyViewState() {
        // HUD 整屏镜像 + 沉浸隐藏（root 在 setContentView 后重建，需重设）
        findViewById(R.id.root_box).setScaleY(hudOn ? -1f : 1f);
        findViewById(R.id.btn_area).setVisibility(hudOn ? View.GONE : View.VISIBLE);
        gauge.setOverspeedLimit(LIMIT_OPTIONS[limitIndex]);
        gauge.setOverspeed(overspeeding);
        gauge.setSpeed(lastFilteredKmh);
        applySkin();
        applyUnit();
        maxText.setText(formatSpeed(maxSpeed));
        if (lastAccuracyM >= 0f) {
            accuracyText.setText(String.format(Locale.getDefault(), "±%.0fm", lastAccuracyM));
        }
        if (lastSatellites >= 0) {
            satelliteText.setText(String.valueOf(lastSatellites));
        }
        if (lastCoordsLocation != null) {
            updateCoordsUI(lastCoordsLocation);
        }
        if (Float.isNaN(gradeSmooth)) {
            gradeText.setText("--");
        } else {
            gradeText.setText(String.format(Locale.getDefault(), "坡度 %+.1f%%", gradeSmooth));
        }
        statusText.setText(statusResId);
        startBtn.setText(running ? R.string.stop_btn : R.string.start_btn);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged: orientation=" + newConfig.orientation);
        // 声明了 configChanges 自行处理旋转：重挂视图（系统自动选择横/竖屏布局），
        // 并把全部运行状态重新应用，测速不中断、数据不丢
        setContentView(R.layout.activity_main);
        bindViews();
        applyViewState();
    }

    private void setStatus(int resId) {
        statusResId = resId;
        statusText.setText(resId);
    }

    /**
     * 申请定位权限。注意：用户曾选"拒绝且不再询问"时 requestPermissions 会静默失败
     * （回调都不会触发），此时必须引导用户去系统设置手动开启。
     */
    private void handleMissingPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_title)
                    .setMessage(R.string.perm_msg)
                    .setPositiveButton(R.string.perm_go_settings,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        startActivity(new Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", getPackageName(), null)));
                                    } catch (Exception e) {
                                        Toast.makeText(MainActivity.this,
                                                R.string.permission_denied, Toast.LENGTH_LONG).show();
                                    }
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startInfoUpdates();
        } else {
            setStatus(R.string.permission_denied);
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** 应用固定主题（保留皮肤类作为配色中心，便于以后扩展） */
    private void applySkin() {
        Skin s = Skin.ALL[0];
        gauge.setSkin(s);

        View root = findViewById(R.id.root_box);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{s.bgTop, s.bgCenter, s.bgBottom});
        root.setBackground(bg);

        getWindow().setStatusBarColor(s.bgTop);
        getWindow().setNavigationBarColor(s.bgBottom);

        headerTitle.setTextColor(s.accent);
        accuracyText.setTextColor(s.accent);
        satelliteText.setTextColor(s.accent);
        maxText.setTextColor(s.maxColor);
        overspeedBtn.setTextColor(s.accent);
        unitBtn.setTextColor(s.accent);
        altText.setTextColor(s.accent);
        gradeText.setTextColor(s.tickLabel);
        coordsText.setTextColor(s.accent);
        addressText.setTextColor(s.tickLabel);
        ((TextView) findViewById(R.id.hud_btn)).setTextColor(s.accent);
        ((TextView) findViewById(R.id.gpx_btn)).setTextColor(s.accent);
        chart.setSkin(s);

        GradientDrawable startBg = new GradientDrawable();
        startBg.setCornerRadius(dp(21));
        startBg.setColor(s.accent);
        startBtn.setBackground(startBg);
        startBtn.setTextColor(0xFF06101E);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 开始/停止测速 */
    private void toggleStart() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            handleMissingPermission();
            return;
        }
        if (!running) {
            chart.reset();
            speedFilter.reset();
            track.clear();
            lastGradeLoc = null;
            gradeSmooth = Float.NaN;
            gradeText.setText("--");
            running = true;
            startBtn.setText(R.string.stop_btn);
            setStatus(R.string.waiting_gps);
            Toast.makeText(this, R.string.start_toast, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "start measuring");
            startGps();
        } else {
            running = false;
            startBtn.setText(R.string.start_btn);
            stopGps();
            setStatus(R.string.stopped_msg);
            Log.d(TAG, "stop measuring");
        }
    }

    private void stopGps() {
        overspeedHandler.removeCallbacks(overspeedReminder);
        overspeeding = false;
        gauge.setOverspeed(false);
        // 只停测速监听；坐标/地址的 infoListener 保持常驻
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
        lastLocation = null;
    }

    /** 启动常驻低频定位（坐标/地址显示用），不参与测速 */
    private void startInfoUpdates() {
        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }
        if (locationManager == null) {
            return;
        }
        try {
            boolean network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (network) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 0f, infoListener);
            } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 3000L, 0f, infoListener);
            }
            Location last = locationManager.getLastKnownLocation(network
                    ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER);
            if (last != null) {
                updateCoordsUI(last);
            }
        } catch (SecurityException ignored) {
        }
    }

    /** 更新经纬度与地址显示 */
    private void updateCoordsUI(Location location) {
        lastCoordsLocation = location;
        coordsText.setText(String.format(Locale.getDefault(), "%.5f°, %.5f°",
                location.getLatitude(), location.getLongitude()));
        altText.setText(String.format(Locale.getDefault(), "%.0fm", location.getAltitude()));
        maybeGeocode(location);
    }

    /** 单位换算与格式化：内部统一 km/h，显示时换算 */
    private String formatSpeed(float kmh) {
        if (useMs) {
            return String.format(Locale.getDefault(), "%.1f", kmh / 3.6f);
        }
        return String.format(Locale.getDefault(), "%.0f", kmh);
    }

    private void toggleUnit() {
        useMs = !useMs;
        prefs.edit().putBoolean(KEY_UNIT_MS, useMs).apply();
        applyUnit();
    }

    /** 切换单位：表盘、曲线、最高速显示同步换算（内部数据不动） */
    private void applyUnit() {
        String unit = useMs ? "m/s" : "km/h";
        gauge.setUnit(unit);
        chart.setUnit(unit);
        unitBtn.setText(getString(R.string.unit_btn_fmt, unit));
        maxText.setText(formatSpeed(maxSpeed));
    }

    private boolean hudOn = false;

    private void toggleHud() {
        hudOn = !hudOn;
        // 整屏上下镜像：表盘、数据、按钮全部反向，玻璃反射后即为正像
        findViewById(R.id.root_box).setScaleY(hudOn ? -1f : 1f);
        // 沉浸模式：隐藏功能按钮，只留表盘和数据；点击表盘退出
        findViewById(R.id.btn_area).setVisibility(hudOn ? View.GONE : View.VISIBLE);
        gauge.setHud(false);
        Toast.makeText(this, hudOn ? R.string.hud_on_toast : R.string.hud_off_toast,
                Toast.LENGTH_LONG).show();
    }

    /** 通过系统文件选择器导出 GPX（SAF，无需存储权限） */
    private void exportGpx() {
        if (track.isEmpty()) {
            Toast.makeText(this, R.string.gpx_none_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gpx+xml");
        String fname = "track_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".gpx";
        intent.putExtra(Intent.EXTRA_TITLE, fname);
        try {
            startActivityForResult(intent, REQUEST_CREATE_GPX);
        } catch (Exception e) {
            Toast.makeText(this, R.string.gpx_failed_toast, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREATE_GPX && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            writeGpx(data.getData());
        }
    }

    private void writeGpx(Uri uri) {
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    getContentResolver().openOutputStream(uri), "UTF-8"));
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<gpx version=\"1.1\" creator=\"Speedometer\">\n");
            writer.write("<trk><name>Speedometer Track</name><trkseg>\n");
            for (TrackPoint p : track) {
                writer.write(String.format(Locale.US,
                        "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",
                        p.lat, p.lon, p.ele, utc.format(new Date(p.time))));
            }
            writer.write("</trkseg></trk>\n</gpx>\n");
            writer.flush();
            Toast.makeText(this, R.string.gpx_saved_toast, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.d(TAG, "writeGpx failed: " + e);
            Toast.makeText(this, R.string.gpx_failed_toast, Toast.LENGTH_SHORT).show();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void startGps() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            setStatus(R.string.waiting_gps);
            return;
        }
        // 每个 provider 独立注册，一个失败不影响另一个
        try {
            locationManager.addGpsStatusListener(gpsStatusListener);
        } catch (SecurityException e) {
            Log.d(TAG, "addGpsStatusListener failed: " + e);
        }
        boolean any = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 500L, 0f, locationListener);
                any = true;
                Log.d(TAG, "GPS provider registered");
            } else {
                Log.d(TAG, "GPS provider disabled");
                Toast.makeText(this, "请先开启系统定位服务(GPS)", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            Log.d(TAG, "GPS register failed: " + e);
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener);
                any = true;
                Log.d(TAG, "Network provider registered");
            } else {
                Log.d(TAG, "Network provider disabled");
            }
        } catch (SecurityException e) {
            Log.d(TAG, "Network register failed: " + e);
        }
        if (!any) {
            setStatus(R.string.waiting_gps);
            return;
        }
        // 已有缓存定位时先显示一次，缩短首次出数时间
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                Log.d(TAG, "lastKnown: " + last.getProvider() + " age="
                        + (System.currentTimeMillis() - last.getTime()) / 1000 + "s");
                onNewLocation(last);
            }
        } catch (SecurityException e) {
            Log.d(TAG, "getLastKnownLocation failed: " + e);
        }
    }

    private void onNewLocation(Location location) {
        Log.d(TAG, "onLocationChanged: provider=" + location.getProvider()
                + " speed=" + (location.hasSpeed() ? location.getSpeed() * 3.6f : -1f)
                + " acc=" + location.getAccuracy());
        float accuracy = location.getAccuracy();
        lastAccuracyM = accuracy;
        accuracyText.setText(String.format(Locale.getDefault(), "±%.0fm", accuracy));

        updateCoordsUI(location);

        float raw; // km/h
        if (location.hasSpeed()) {
            raw = location.getSpeed() * 3.6f;
        } else if (lastLocation != null) {
            float dt = (location.getTime() - lastLocation.getTime()) / 1000f;
            if (dt > 0.5f && dt < 10f) {
                raw = location.distanceTo(lastLocation) / dt * 3.6f;
            } else {
                lastLocation = location;
                return;
            }
        } else {
            lastLocation = location;
            return;
        }

        // API 26+ 提供速度精度字段，比水平精度更对口地反映速度可信度
        float speedAccuracyKmh = -1f;
        if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            speedAccuracyKmh = location.getSpeedAccuracyMetersPerSecond() * 3.6f;
        }

        float filtered = speedFilter.update(
                new SpeedFilter.Sample(raw, accuracy, speedAccuracyKmh));

        if (speedFilter.lastSampleAccepted()) {
            if (filtered > maxSpeed) {
                maxSpeed = filtered;
                maxText.setText(formatSpeed(maxSpeed));
            }
            // 表盘与曲线内部均存 km/h，由各自视图按当前单位换算显示
            lastFilteredKmh = filtered;
            gauge.setSpeed(filtered);
            chart.addSample(filtered);

            // 轨迹记录（供 GPX 导出）
            TrackPoint tp = new TrackPoint();
            tp.time = location.getTime();
            tp.lat = location.getLatitude();
            tp.lon = location.getLongitude();
            tp.ele = (float) location.getAltitude();
            track.addLast(tp);
            while (track.size() > MAX_TRACK_POINTS) {
                track.pollFirst();
            }

            // 坡度：海拔差 / 水平距离，EMA 平滑抑制 GPS 垂直噪声
            if (lastGradeLoc != null) {
                float dist = location.distanceTo(lastGradeLoc);
                if (dist > 10f) {
                    float dAlt = (float) (location.getAltitude() - lastGradeLoc.getAltitude());
                    float grade = Math.max(-30f, Math.min(30f, dAlt / dist * 100f));
                    gradeSmooth = Float.isNaN(gradeSmooth) ? grade : gradeSmooth * 0.6f + grade * 0.4f;
                    lastGradeLoc = location;
                    gradeText.setText(String.format(Locale.getDefault(), "坡度 %+.1f%%", gradeSmooth));
                }
            } else {
                lastGradeLoc = location;
            }

            checkOverspeed(filtered);
        }

        setStatus(R.string.gps_ready);
        lastLocation = location;
    }

    /** 异步逆地理编码：把经纬度解析成可读地址显示在坐标下方 */
    private void maybeGeocode(final Location location) {
        if (!Geocoder.isPresent()) {
            return;
        }
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        long now = SystemClock.elapsedRealtime();
        boolean movedFar = Double.isNaN(lastGeoLat);
        if (!movedFar) {
            float[] dist = new float[1];
            Location.distanceBetween(lastGeoLat, lastGeoLon, lat, lon, dist);
            movedFar = dist[0] > 50f;
        }
        if (!movedFar && now - lastGeoTime < 30000L) {
            return;
        }
        lastGeoLat = lat;
        lastGeoLon = lon;
        lastGeoTime = now;

        geoExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String text = "--";
                try {
                    List<Address> results = geocoder.getFromLocation(lat, lon, 1);
                    if (results != null && !results.isEmpty()
                            && results.get(0).getMaxAddressLineIndex() >= 0) {
                        text = results.get(0).getAddressLine(0);
                    }
                } catch (Exception e) {
                    // 无网络或后端不可用时保持占位符
                }
                final String resolved = text;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        addressText.setText(resolved);
                    }
                });
            }
        });
    }

    private void checkOverspeed(float speed) {
        int limit = LIMIT_OPTIONS[limitIndex];
        boolean over = limit > 0 && speed > limit;
        if (over && !overspeeding) {
            // 刚越过阈值：立即提醒一次，之后每 10 秒重复，直到降回限速内
            vibrateAlert();
            overspeedHandler.removeCallbacks(overspeedReminder);
            overspeedHandler.postDelayed(overspeedReminder, OVERSPEED_INTERVAL_MS);
        } else if (!over && overspeeding) {
            overspeedHandler.removeCallbacks(overspeedReminder);
        }
        overspeeding = over;
        gauge.setOverspeed(over);
    }

    private void vibrateAlert() {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                vibrator.vibrate(400L);
            } catch (SecurityException ignored) {
            }
        }
    }

    private void showLimitOptions() {
        String[] names = new String[LIMIT_OPTIONS.length];
        for (int i = 0; i < LIMIT_OPTIONS.length; i++) {
            names[i] = LIMIT_OPTIONS[i] == 0
                    ? getString(R.string.overspeed_off)
                    : String.format(Locale.getDefault(),
                            getString(R.string.overspeed_value), LIMIT_OPTIONS[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.overspeed_title)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        limitIndex = which;
                        prefs.edit().putInt(KEY_LIMIT_INDEX, limitIndex).apply();
                        gauge.setOverspeedLimit(LIMIT_OPTIONS[limitIndex]);
                        overspeeding = false;
                        gauge.setOverspeed(false);
                        overspeedHandler.removeCallbacks(overspeedReminder);
                        updateOverspeedButton();
                    }
                })
                .show();
    }

    private void updateOverspeedButton() {
        int limit = LIMIT_OPTIONS[limitIndex];
        if (limit == 0) {
            overspeedBtn.setText(R.string.overspeed_off);
        } else {
            overspeedBtn.setText(String.format(Locale.getDefault(),
                    getString(R.string.overspeed_value), limit));
        }
    }

    private void resetMaxSpeed() {
        maxSpeed = 0f;
        maxText.setText("0");
        Toast.makeText(this, R.string.max_reset_toast, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (running) {
            if (locationManager != null) {
                try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER, 500L, 0f, locationListener);
                    }
                } catch (SecurityException ignored) {
                }
            }
        } else {
            startInfoUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        overspeedHandler.removeCallbacks(overspeedReminder);
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            locationManager.removeUpdates(infoListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        geoExecutor.shutdown();
        overspeedHandler.removeCallbacks(overspeedReminder);
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            locationManager.removeUpdates(infoListener);
            locationManager.removeGpsStatusListener(gpsStatusListener);
        }
        // 从最近任务划掉时彻底结束进程，避免后台残留
        if (isFinishing()) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
