package com.droidscreentimer;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.animation.LinearInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 主界面：时间/公历/农历/天气、幻彩背景、防烧屏移动、重力翻转、录像与三连点击设置入口。
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 2001;
    private static final long WEATHER_INTERVAL = 30 * 60 * 1000L;
    private static final String[] WEEK_NAMES = {"日", "一", "二", "三", "四", "五", "六"};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private FrameLayout root;
    private LinearLayout infoGroup;
    private FlowingBackgroundView backgroundView;
    private TextView timeText;
    private TextView gregorianText;
    private TextView lunarText;
    private TextView todayWeatherText;
    private TextView tomorrowWeatherText;
    // 横屏布局独有：右侧温度与晴雨图标（竖屏布局中为 null）
    private TextView landscapeTempText;
    private TextView landscapeIconText;

    // 天气缓存：横竖屏布局切换后立即恢复显示
    private String lastToday;
    private String lastTomorrow;
    private int lastCurrentTemp = WeatherHelper.TEMP_UNAVAILABLE;
    private String lastCurrentIcon = "—";

    private ObjectAnimator moveX;
    private ObjectAnimator moveY;

    private SensorManager sensorManager;
    private SensorEventListener sensorListener;
    private float currentRotation;

    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver shutdownReceiver;
    private boolean batteryLow;

    private CameraRecorder recorder;
    private boolean recording;
    private DebugServer debugServer;
    private StreamServer streamServer;

    private final CameraRecorder.Listener cameraListener = new CameraRecorder.Listener() {
        @Override
        public void onRecordingStateChanged(boolean isRecording) {
            recording = isRecording;
            if (backgroundView != null) {
                backgroundView.setWarm(!isRecording);
            }
            updateClock();
        }

        @Override
        public void onError(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    private final long[] tapTimes = new long[3];
    private int tapCount;

    /** 各信息文本的初始字号（px），用于自适应缩放。 */
    private final Map<TextView, Float> baseTextSizes = new HashMap<>();

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            long delay;
            if (recording) {
                // 半秒对齐刷新，实现“:”分隔符 1 秒循环闪动
                delay = 500L - now % 500L;
            } else {
                delay = 1000L - now % 1000L;
            }
            mainHandler.postDelayed(this, Math.max(50L, delay));
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            mainHandler.postDelayed(this, WEATHER_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.resetRecordOn(this);
        applyLayout();

        readInitialBatteryState();
        syncDebugServerState();
        syncStreamServerState();
    }

    /** 按当前方向加载对应布局（竖屏/横屏），并重新绑定视图。 */
    private void applyLayout() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setContentView(landscape ? R.layout.activity_main_land : R.layout.activity_main);

        root = findViewById(R.id.root);
        infoGroup = findViewById(R.id.infoGroup);
        backgroundView = findViewById(R.id.backgroundView);
        timeText = findViewById(R.id.timeText);
        gregorianText = findViewById(R.id.gregorianText);
        lunarText = findViewById(R.id.lunarText);
        todayWeatherText = findViewById(R.id.weatherTodayText);
        tomorrowWeatherText = findViewById(R.id.weatherTomorrowText);
        landscapeTempText = findViewById(R.id.landscapeTempText);
        landscapeIconText = findViewById(R.id.landscapeIconText);
        rememberBaseSizes();

        applyFullscreen();
        setupTripleTap();
        infoGroup.post(new Runnable() {
            @Override
            public void run() {
                setupInfoGroupBounds();
            }
        });
        applyWeatherViews();
        if (backgroundView != null) {
            backgroundView.setWarm(!recording);
        }
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
        mainHandler.post(clockTick);
        mainHandler.removeCallbacks(weatherTick);
        mainHandler.post(weatherTick);
        registerBatteryReceiver();
        registerShutdownReceiver();
        applyKeepScreenOn();
        requestPermissionsIfNeeded();
        updateRecordingState();
        applyFlipState();
        updateClock();
        // 从设置页返回时同步服务器开关状态
        syncDebugServerState();
        syncStreamServerState();
        if (recorder != null) {
            // 回到前台：恢复监听，停止后台录像服务
            recorder.setListener(cameraListener);
            stopService(new Intent(this, RecordService.class));
        }
        if (backgroundView != null) {
            backgroundView.setWarm(!recording);
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(clockTick);
        mainHandler.removeCallbacks(weatherTick);
        // 离开前台：不停录像，改为启动 camera 前台服务以获得后台相机权限
        if (Prefs.isRecordOn(this) && hasPermission(Manifest.permission.CAMERA)) {
            Intent recordService = new Intent(this, RecordService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(recordService);
            } else {
                startService(recordService);
            }
        }
        if (recorder != null) {
            recorder.detachPreview();
        }
        unregisterShutdownReceiver();
        unregisterBatteryReceiver();
        teardownSensor();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopRecording();
        stopService(new Intent(this, RecordService.class));
        stopMovement();
        stopDebugServer();
        if (recorder != null) {
            recorder.releaseAll();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayout();
        applyFlipState();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void rememberBaseSizes() {
        TextView[] views = {timeText, gregorianText, lunarText,
                todayWeatherText, tomorrowWeatherText};
        for (TextView view : views) {
            if (view != null) {
                view.setSingleLine(true);
                view.setHorizontallyScrolling(false);
                baseTextSizes.put(view, view.getTextSize());
            }
        }
    }

    /**
     * 信息不折行：若超出视图可用宽度，则按比例缩小字号（最低缩至初始的一半）。
     */
    private void fitText(TextView view, String text) {
        if (view == null || text == null) {
            return;
        }
        int available = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        if (available <= 0 && infoGroup != null) {
            available = infoGroup.getWidth()
                    - infoGroup.getPaddingLeft() - infoGroup.getPaddingRight();
        }
        Float baseSize = baseTextSizes.get(view);
        float base = baseSize == null ? view.getTextSize() : baseSize;

        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base);
        float textWidth = view.getPaint().measureText(text);
        if (available > 0 && textWidth > available) {
            float scaled = Math.max(base * available / textWidth * 0.98f, base * 0.5f);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, scaled);
        }
    }

    /**
     * 横屏时间字号尽可能铺满其区域（左上角宽 3/4、高 2/3 的区块），
     * 按视图实际宽高与文本度量计算字号。
     */
    private void fitTimeToFill() {
        if (timeText == null) {
            return;
        }
        String text = timeText.getText().toString();
        int availW = timeText.getWidth() - timeText.getPaddingLeft() - timeText.getPaddingRight();
        int availH = timeText.getHeight() - timeText.getPaddingTop() - timeText.getPaddingBottom();
        if (availW <= 0 || availH <= 0 || text.length() == 0) {
            return;
        }
        android.graphics.Paint paint = timeText.getPaint();
        float current = timeText.getTextSize();
        float unitWidth = paint.measureText(text) / current;
        if (unitWidth <= 0) {
            return;
        }
        float byWidth = availW / unitWidth * 0.96f;
        float byHeight = availH * 0.82f;
        float size = Math.min(byWidth, byHeight);
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(size, 40f));
    }

    /** 按配置开关同步调试服务器状态（onCreate/onResume 时调用）。 */
    private void syncDebugServerState() {
        if (!Prefs.isDebugServerOn(this)) {
            if (debugServer != null) {
                debugServer.stop();
            }
            return;
        }
        if (debugServer == null) {
            debugServer = new DebugServer(this);
        }
        if (!debugServer.isRunning()) {
            debugServer.start(new DebugServer.InfoProvider() {
                @Override
                public String provide() {
                    StringBuilder sb = new StringBuilder();
                    sb.append("activityRecording=").append(recording).append('\n');
                    if (recorder != null) {
                        sb.append(recorder.describeState());
                    } else {
                        sb.append("recorder=null\n");
                    }
                    return sb.toString();
                }
            });
        }
    }

    /** 按配置开关同步流媒体服务器状态（onCreate/onResume 时调用）。 */
    private void syncStreamServerState() {
        if (!Prefs.isStreamServerOn(this)) {
            if (streamServer != null) {
                streamServer.stop();
            }
            return;
        }
        if (streamServer == null) {
            streamServer = new StreamServer(this);
        }
        if (!streamServer.isRunning()) {
            streamServer.start();
        }
    }

    private void stopDebugServer() {
        if (debugServer != null) {
            debugServer.stop();
        }
        if (streamServer != null) {
            streamServer.stop();
        }
    }

    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * 信息组尺寸设为屏幕的 70%，居中时四周留白 15%；
     * 再在其上叠加 ±5% 的缓慢位移，使四周间距始终处于 10%~20%，满足 5%~20% 的要求。
     */
    private void setupInfoGroupBounds() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;
        if (screenW <= 0 || screenH <= 0) {
            return;
        }

        int width = (int) (screenW * 0.70f);
        int height = (int) (screenH * 0.70f);
        applyInfoGroupParams(width, height);

        startMovement(screenW * 0.05f, screenH * 0.05f);
    }

    private void applyInfoGroupParams(int width, int height) {
        android.widget.FrameLayout.LayoutParams params =
                (android.widget.FrameLayout.LayoutParams) infoGroup.getLayoutParams();
        params.width = width;
        params.height = height;
        params.gravity = android.view.Gravity.CENTER;
        infoGroup.setLayoutParams(params);
    }

    private void startMovement(float rangeX, float rangeY) {
        stopMovement();
        moveX = ObjectAnimator.ofFloat(infoGroup, "translationX", -rangeX, rangeX);
        moveX.setDuration(60000L + random.nextInt(40000));
        moveX.setRepeatCount(ValueAnimator.INFINITE);
        moveX.setRepeatMode(ValueAnimator.REVERSE);
        moveX.setInterpolator(new LinearInterpolator());
        moveX.start();

        moveY = ObjectAnimator.ofFloat(infoGroup, "translationY", rangeY, -rangeY);
        moveY.setDuration(75000L + random.nextInt(40000));
        moveY.setRepeatCount(ValueAnimator.INFINITE);
        moveY.setRepeatMode(ValueAnimator.REVERSE);
        moveY.setInterpolator(new LinearInterpolator());
        moveY.start();
    }

    private void stopMovement() {
        if (moveX != null) {
            moveX.cancel();
            moveX = null;
        }
        if (moveY != null) {
            moveY.cancel();
            moveY = null;
        }
    }

    private void setupSensor() {
        if (!Prefs.isFlipEnabled(this) || sensorListener != null) {
            return;
        }
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            return;
        }
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            return;
        }
        sensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float ay = event.values[1];
                // 设备顶部朝上时 ay ≈ +9.8（正常），倒置时 ay ≈ -9.8（翻转 180°）
                float target = currentRotation == 0f ? (ay < -7f ? 180f : 0f) : (ay > -5f ? 0f : 180f);
                if (Math.abs(target - currentRotation) > 90f) {
                    currentRotation = target;
                    infoGroup.animate().rotation(target).setDuration(700L).start();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    private void teardownSensor() {
        if (sensorManager != null && sensorListener != null) {
            sensorManager.unregisterListener(sensorListener);
        }
        sensorListener = null;
        sensorManager = null;
    }

    private void applyFlipState() {
        if (!Prefs.isFlipEnabled(this)) {
            currentRotation = 0f;
            infoGroup.setRotation(0f);
            teardownSensor();
        } else {
            setupSensor();
        }
    }

    private void readInitialBatteryState() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = registerReceiver(null, filter);
        if (sticky != null) {
            batteryLow = batteryPercent(sticky) < 5;
        }
    }

    private void registerBatteryReceiver() {
        if (batteryReceiver != null) {
            return;
        }
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean low = batteryPercent(intent) < 5;
                if (low != batteryLow) {
                    batteryLow = low;
                    applyKeepScreenOn();
                    if (low) {
                        Toast.makeText(MainActivity.this, R.string.low_battery, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void unregisterBatteryReceiver() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            batteryReceiver = null;
        }
    }

    /**
     * 设备关机/重启前收到广播，立即结束当前录像段，确保 MP4 索引（moov）写入、文件完整可播。
     */
    private void registerShutdownReceiver() {
        if (shutdownReceiver != null) {
            return;
        }
        shutdownReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopRecording();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction("android.intent.action.QUICKBOOT_POWEROFF");
        registerReceiver(shutdownReceiver, filter);
    }

    private void unregisterShutdownReceiver() {
        if (shutdownReceiver != null) {
            try {
                unregisterReceiver(shutdownReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            shutdownReceiver = null;
        }
    }

    private static int batteryPercent(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            return 100;
        }
        return level * 100 / scale;
    }

    private void applyKeepScreenOn() {
        if (batteryLow) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        // 时:分 显示，无秒，无 AM/PM 指示
        SimpleDateFormat timeFormat = new SimpleDateFormat(
                Prefs.is24Hour(this) ? "HH:mm" : "h:mm", Locale.getDefault());
        String timeTextValue = timeFormat.format(now.getTime());
        // 录像开启时“:”分隔符按 1 秒循环闪动，关闭时固定显示
        if (recording && System.currentTimeMillis() % 1000L >= 500L) {
            int colonIndex = timeTextValue.indexOf(':');
            if (colonIndex >= 0) {
                timeTextValue = timeTextValue.substring(0, colonIndex) + ' '
                        + timeTextValue.substring(colonIndex + 1);
            }
        }
        timeText.setText(timeTextValue);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());
        String week = WEEK_NAMES[now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY];
        String gregorianTextValue = dateFormat.format(now.getTime()) + "  星期" + week;
        gregorianText.setText(gregorianTextValue);
        String lunarTextValue = LunarCalendar.getLunarText(now);
        lunarText.setText(lunarTextValue);

        if (landscapeTempText != null) {
            // 横屏：时间区域独立（宽 3/4、高 2/3），字号铺满
            fitTimeToFill();
        } else {
            fitText(timeText, timeTextValue);
        }
        fitText(gregorianText, gregorianTextValue);
        fitText(lunarText, lunarTextValue);
    }

    private void refreshWeather() {
        double latitude = Prefs.getLatitude(this);
        double longitude = Prefs.getLongitude(this);
        if (Prefs.isAutoLocation(this) && hasLocationPermission()) {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                Location location = null;
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (location == null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                if (location != null) {
                    latitude = location.getLatitude();
                    longitude = location.getLongitude();
                }
            }
        }

        final double lat = latitude;
        final double lon = longitude;
        WeatherHelper.fetch(lat, lon, new WeatherHelper.Callback() {
            @Override
            public void onResult(String today, String tomorrow, int currentTemp, String currentIcon) {
                lastToday = today;
                lastTomorrow = tomorrow;
                lastCurrentTemp = currentTemp;
                lastCurrentIcon = currentIcon;
                applyWeatherViews();
            }
        });
    }

    /** 将缓存的天气数据写入当前布局对应的视图（竖屏文本 / 横屏温度+图标）。 */
    private void applyWeatherViews() {
        if (todayWeatherText != null) {
            String todayValue = lastToday == null ? getString(R.string.weather_unavailable) : lastToday;
            todayWeatherText.setText(todayValue);
            fitText(todayWeatherText, todayValue);
        }
        if (tomorrowWeatherText != null) {
            String tomorrowValue = lastTomorrow == null
                    ? getString(R.string.weather_unavailable) : lastTomorrow;
            tomorrowWeatherText.setText(tomorrowValue);
            fitText(tomorrowWeatherText, tomorrowValue);
        }
        if (landscapeTempText != null) {
            landscapeTempText.setText(lastCurrentTemp == WeatherHelper.TEMP_UNAVAILABLE
                    ? "--°" : lastCurrentTemp + "°");
        }
        if (landscapeIconText != null) {
            landscapeIconText.setText(lastCurrentIcon);
        }
    }

    private void setupTripleTap() {
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    long now = SystemClock.elapsedRealtime();
                    if (tapCount >= 2 && now - tapTimes[2] < 700L
                            && now - tapTimes[1] < 1400L) {
                        tapCount = 0;
                        tapTimes[0] = tapTimes[1] = tapTimes[2] = 0L;
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    } else {
                        if (tapCount < 3) {
                            tapCount++;
                        }
                        tapTimes[0] = tapTimes[1];
                        tapTimes[1] = tapTimes[2];
                        tapTimes[2] = now;
                    }
                }
                return false;
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        List<String> needed = new ArrayList<>();
        if (Prefs.isRecordOn(this) && !hasPermission(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (Prefs.isRecordOn(this) && Prefs.isRecordAudio(this)
                && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Prefs.isRecordOn(this) && Build.VERSION.SDK_INT <= 28
                && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Prefs.isAutoLocation(this) && !hasLocationPermission()) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            updateRecordingState();
            refreshWeather();
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void updateRecordingState() {
        boolean shouldRecord = Prefs.isRecordOn(this) && hasPermission(Manifest.permission.CAMERA);
        if (shouldRecord && !recording && !isRecorderBusy()) {
            startRecording();
        } else if (!shouldRecord && recording) {
            stopRecording();
        }
    }

    private boolean isRecorderBusy() {
        return recorder != null && recorder.isBusy();
    }

    private void startRecording() {
        recorder = CameraRecorder.getInstance(this);
        recorder.setListener(cameraListener);
        recorder.start(Prefs.getRecordResolutionIndex(this),
                Prefs.getRecordFpsIndex(this),
                Prefs.getRecordBitrateIndex(this),
                Prefs.isRecordAudio(this) && hasPermission(Manifest.permission.RECORD_AUDIO));
        updateClock();
    }

    private void stopRecording() {
        if (recorder != null) {
            // 异步关闭并 finalize 当前文件，保证“分段重启间隙”发起的关闭也能真正停止
            recorder.stop();
        }
        if (recording) {
            recording = false;
            updateClock();
        }
    }
}
