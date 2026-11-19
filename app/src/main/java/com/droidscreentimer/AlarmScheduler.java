package com.droidscreentimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.List;

/**
 * 多闹钟调度：为每个启用闹钟计算下一次触发时间（考虑每周生效日与公休日避让）。
 */
public final class AlarmScheduler {

    private AlarmScheduler() {
    }

    /** 重新调度所有闹钟（先全部取消，再逐一设置）。 */
    public static void scheduleAll(Context context) {
        List<AlarmItem> alarms = AlarmStore.load(context);
        for (AlarmItem alarm : alarms) {
            cancel(context, alarm);
        }
        for (AlarmItem alarm : alarms) {
            if (!alarm.enabled) {
                continue;
            }
            Calendar trigger = nextTrigger(alarm, Calendar.getInstance());
            if (trigger == null) {
                continue;
            }
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) {
                continue;
            }
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    trigger.getTimeInMillis(), pendingIntent(context, alarm.id));
        }
    }

    /** 计算闹钟下一次触发时间；找不到（未来 8 天内无生效日）返回 null。 */
    public static Calendar nextTrigger(AlarmItem alarm, Calendar now) {
        for (int offset = 0; offset < 8; offset++) {
            Calendar candidate = (Calendar) now.clone();
            candidate.add(Calendar.DAY_OF_YEAR, offset);
            candidate.set(Calendar.HOUR_OF_DAY, alarm.hour);
            candidate.set(Calendar.MINUTE, alarm.minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            if (candidate.getTimeInMillis() <= now.getTimeInMillis()) {
                continue;
            }
            if (alarm.repeatDays != 0
                    && (alarm.repeatDays & AlarmItem.dayBit(candidate.get(Calendar.DAY_OF_WEEK))) == 0) {
                continue;
            }
            if (alarm.skipHolidays && ChineseHoliday.isRestday(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static void cancel(Context context, AlarmItem alarm) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            manager.cancel(pendingIntent(context, alarm.id));
        }
    }

    private static PendingIntent pendingIntent(Context context, long alarmId) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("alarm_id", alarmId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, requestCode(alarmId), intent, flags);
    }

    private static int requestCode(long alarmId) {
        return (int) (alarmId & 0x7FFFFFFFL);
    }
}
