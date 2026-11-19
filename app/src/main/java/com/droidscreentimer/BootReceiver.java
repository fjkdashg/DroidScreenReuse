package com.droidscreentimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机/应用更新完成后，按配置拉起主界面并恢复闹钟。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.isAutoStart(context)) {
            return;
        }
        Intent startup = new Intent(context, StartupService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startup);
        } else {
            context.startService(startup);
        }
        AlarmScheduler.scheduleAll(context);
    }
}
