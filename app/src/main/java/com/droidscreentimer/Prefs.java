package com.droidscreentimer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 所有配置的统一存储入口。
 */
public final class Prefs {
    private static final String NAME = "droid_timer_prefs";

    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_FLIP = "flip_enabled";
    private static final String KEY_TIME_24 = "time_24";
    private static final String KEY_AUTO_LOC = "auto_location";
    private static final String KEY_LAT = "latitude";
    private static final String KEY_LON = "longitude";
    private static final String KEY_RECORD_ON = "record_on";
    private static final String KEY_REC_RES = "record_res";
    private static final String KEY_REC_FPS = "record_fps";
    private static final String KEY_REC_BITRATE = "record_bitrate";
    private static final String KEY_REC_AUDIO = "record_audio";
    private static final String KEY_ALARM_ON = "alarm_on";
    private static final String KEY_ALARM_REPEAT = "alarm_repeat";
    private static final String KEY_ALARM_HOUR = "alarm_hour";
    private static final String KEY_ALARM_MINUTE = "alarm_minute";
    private static final String KEY_ALARM_FOLDER = "alarm_folder";
    private static final String KEY_RECORD_FOLDER = "record_folder";
    private static final String KEY_REC_SEGMENT = "record_segment_minutes";
    private static final String KEY_REC_ORIENTATION = "record_orientation";
    private static final String KEY_CAMERA_FACING = "camera_facing";
    private static final String KEY_DEBUG_SERVER = "debug_server_on";
    private static final String KEY_STREAM_SERVER = "stream_server_on";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoStart(Context c) {
        return sp(c).getBoolean(KEY_AUTO_START, true);
    }

    public static boolean isFlipEnabled(Context c) {
        return sp(c).getBoolean(KEY_FLIP, true);
    }

    public static boolean is24Hour(Context c) {
        return sp(c).getBoolean(KEY_TIME_24, true);
    }

    public static boolean isAutoLocation(Context c) {
        return sp(c).getBoolean(KEY_AUTO_LOC, true);
    }

    public static double getLatitude(Context c) {
        return parseDouble(sp(c).getString(KEY_LAT, "39.9042"), 39.9042);
    }

    public static double getLongitude(Context c) {
        return parseDouble(sp(c).getString(KEY_LON, "116.4074"), 116.4074);
    }

    public static boolean isRecordOn(Context c) {
        return sp(c).getBoolean(KEY_RECORD_ON, false);
    }

    public static int getRecordResolutionIndex(Context c) {
        return sp(c).getInt(KEY_REC_RES, 2);
    }

    public static int getRecordFpsIndex(Context c) {
        return sp(c).getInt(KEY_REC_FPS, 2);
    }

    public static int getRecordBitrateIndex(Context c) {
        return sp(c).getInt(KEY_REC_BITRATE, 1);
    }

    public static boolean isRecordAudio(Context c) {
        return sp(c).getBoolean(KEY_REC_AUDIO, false);
    }

    public static boolean isAlarmOn(Context c) {
        return sp(c).getBoolean(KEY_ALARM_ON, false);
    }

    public static boolean isAlarmRepeat(Context c) {
        return sp(c).getBoolean(KEY_ALARM_REPEAT, true);
    }

    public static int getAlarmHour(Context c) {
        return sp(c).getInt(KEY_ALARM_HOUR, 7);
    }

    public static int getAlarmMinute(Context c) {
        return sp(c).getInt(KEY_ALARM_MINUTE, 0);
    }

    public static String getAlarmFolder(Context c) {
        return sp(c).getString(KEY_ALARM_FOLDER, "");
    }

    /** 录像保存目录（SAF tree URI）。为空表示默认公共目录 Movies/DroidScreenTimer。 */
    public static String getRecordFolder(Context c) {
        return sp(c).getString(KEY_RECORD_FOLDER, "");
    }

    /** 录像单文件时长（分钟）：1、3、5、10。 */
    public static int getRecordSegmentMinutes(Context c) {
        int minutes = sp(c).getInt(KEY_REC_SEGMENT, 1);
        if (minutes != 1 && minutes != 3 && minutes != 5 && minutes != 10) {
            return 1;
        }
        return minutes;
    }

    /** 录像保存画面角度索引：0/1/2/3 对应 0/90/180/270 度。 */
    public static int getRecordOrientationIndex(Context c) {
        int index = sp(c).getInt(KEY_REC_ORIENTATION, 1);
        return (index >= 0 && index <= 3) ? index : 1;
    }

    /** 摄像头朝向索引：0=后置，1=前置（默认前置）。 */
    public static int getCameraFacingIndex(Context c) {
        int index = sp(c).getInt(KEY_CAMERA_FACING, 1);
        return (index == 0 || index == 1) ? index : 1;
    }

    /** 调试 HTTP 服务器开关，开发阶段默认开启。 */
    public static boolean isDebugServerOn(Context c) {
        return sp(c).getBoolean(KEY_DEBUG_SERVER, true);
    }

    /** 流媒体 Web 服务器开关（独立于调试服务器），默认开启。 */
    public static boolean isStreamServerOn(Context c) {
        return sp(c).getBoolean(KEY_STREAM_SERVER, true);
    }

    /** 信息显示设置：时间制式、开机自启、重力翻转。 */
    public static void saveDisplay(Context c, boolean autoStart, boolean flip, boolean time24) {
        sp(c).edit()
                .putBoolean(KEY_AUTO_START, autoStart)
                .putBoolean(KEY_FLIP, flip)
                .putBoolean(KEY_TIME_24, time24)
                .apply();
    }

    /** 天气定位设置。 */
    public static void saveWeather(Context c, boolean autoLocation, double lat, double lon) {
        sp(c).edit()
                .putBoolean(KEY_AUTO_LOC, autoLocation)
                .putString(KEY_LAT, String.valueOf(lat))
                .putString(KEY_LON, String.valueOf(lon))
                .apply();
    }

    /** 录像设置。 */
    public static void saveRecord(Context c, boolean recordOn, int resIndex, int fpsIndex,
                                  int bitrateIndex, boolean recordAudio, int segmentMinutes,
                                  int orientationIndex, int cameraFacingIndex, String recordFolder) {
        sp(c).edit()
                .putBoolean(KEY_RECORD_ON, recordOn)
                .putInt(KEY_REC_RES, resIndex)
                .putInt(KEY_REC_FPS, fpsIndex)
                .putInt(KEY_REC_BITRATE, bitrateIndex)
                .putBoolean(KEY_REC_AUDIO, recordAudio)
                .putInt(KEY_REC_SEGMENT, segmentMinutes)
                .putInt(KEY_REC_ORIENTATION, orientationIndex)
                .putInt(KEY_CAMERA_FACING, cameraFacingIndex == 0 ? 0 : 1)
                .putString(KEY_RECORD_FOLDER, recordFolder == null ? "" : recordFolder)
                .apply();
    }

    /** 每次启动程序时录像功能默认关闭，需手动打开。 */
    public static void resetRecordOn(Context c) {
        sp(c).edit().putBoolean(KEY_RECORD_ON, false).apply();
    }

    /** 系统设置。 */
    public static void saveSystem(Context c, boolean debugServerOn, boolean streamServerOn) {
        sp(c).edit()
                .putBoolean(KEY_DEBUG_SERVER, debugServerOn)
                .putBoolean(KEY_STREAM_SERVER, streamServerOn)
                .apply();
    }

    /** 闹钟设置。 */
    public static void saveAlarm(Context c, boolean alarmOn, boolean alarmRepeat,
                                 int alarmHour, int alarmMinute, String alarmFolder) {
        sp(c).edit()
                .putBoolean(KEY_ALARM_ON, alarmOn)
                .putBoolean(KEY_ALARM_REPEAT, alarmRepeat)
                .putInt(KEY_ALARM_HOUR, alarmHour)
                .putInt(KEY_ALARM_MINUTE, alarmMinute)
                .putString(KEY_ALARM_FOLDER, alarmFolder)
                .apply();
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
