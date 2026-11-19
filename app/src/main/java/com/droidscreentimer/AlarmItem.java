package com.droidscreentimer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 单个闹钟配置。
 */
public class AlarmItem {

    /** 星期位掩码：bit0=周一 … bit6=周日；0 表示仅响一次。 */
    public static final int MON = 1;
    public static final int TUE = 1 << 1;
    public static final int WED = 1 << 2;
    public static final int THU = 1 << 3;
    public static final int FRI = 1 << 4;
    public static final int SAT = 1 << 5;
    public static final int SUN = 1 << 6;
    public static final int[] WEEK_BITS = {MON, TUE, WED, THU, FRI, SAT, SUN};
    public static final String[] WEEK_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    public long id;
    public int hour;
    public int minute;
    public boolean enabled;
    public int repeatDays;
    public boolean skipHolidays;

    public AlarmItem() {
    }

    public AlarmItem(long id, int hour, int minute) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.enabled = true;
        this.repeatDays = 0;
        this.skipHolidays = false;
    }

    /** Calendar.DAY_OF_WEEK(1=周日) 转换为位掩码索引(0=周一)。 */
    public static int dayBit(int calendarDayOfWeek) {
        return 1 << ((calendarDayOfWeek + 5) % 7);
    }

    public String describe() {
        if (repeatDays == 0) {
            return skipHolidays ? "仅一次 · 避公休" : "仅一次";
        }
        if (repeatDays == (MON | TUE | WED | THU | FRI | SAT | SUN)) {
            return skipHolidays ? "每天 · 避公休" : "每天";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((repeatDays & WEEK_BITS[i]) != 0) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append("周").append(WEEK_NAMES[i]);
            }
        }
        if (skipHolidays) {
            sb.append(" · 避公休");
        }
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("hour", hour);
        object.put("minute", minute);
        object.put("enabled", enabled);
        object.put("repeatDays", repeatDays);
        object.put("skipHolidays", skipHolidays);
        return object;
    }

    public static AlarmItem fromJson(JSONObject object) {
        AlarmItem item = new AlarmItem();
        item.id = object.optLong("id", System.currentTimeMillis());
        item.hour = object.optInt("hour", 7);
        item.minute = object.optInt("minute", 0);
        item.enabled = object.optBoolean("enabled", true);
        item.repeatDays = object.optInt("repeatDays", 0);
        item.skipHolidays = object.optBoolean("skipHolidays", false);
        return item;
    }
}
