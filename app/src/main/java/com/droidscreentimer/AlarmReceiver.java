package com.droidscreentimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 闹钟到点后唤醒设备并启动响铃服务。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = powerManager == null ? null
                : powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droid:alarm_wake");
        if (wakeLock != null) {
            wakeLock.acquire(60000L);
        }

        // “仅一次”闹钟响铃后自动禁用
        if (intent != null && intent.hasExtra("alarm_id")) {
            long alarmId = intent.getLongExtra("alarm_id", -1L);
            AlarmItem alarm = AlarmStore.findById(context, alarmId);
            if (alarm != null && alarm.repeatDays == 0) {
                alarm.enabled = false;
                AlarmStore.update(context, alarm);
            }
        }

        Intent service = new Intent(context, AlarmSoundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }

        if (wakeLock != null) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                }
            }, 5000);
        }
    }
}
