package com.droidscreentimer;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 中国公休日判断：内置 2025-2027 年法定节假日（休）与调休上班日（班），
 * 数据外的日期按周六/周日为休息日处理。法定节假日安排以国务院公告为准。
 */
public final class ChineseHoliday {

    private static final int FLAG_OFF = 1;
    private static final int FLAG_WORK = 2;

    private static final Map<String, Integer> DAYS = new HashMap<>();

    static {
        // ---- 2025 ----
        put("2025-01-01", FLAG_OFF);
        putRange("2025-01-28", "2025-02-04", FLAG_OFF);
        put("2025-01-26", FLAG_WORK);
        put("2025-02-08", FLAG_WORK);
        putRange("2025-04-04", "2025-04-06", FLAG_OFF);
        putRange("2025-05-01", "2025-05-05", FLAG_OFF);
        put("2025-04-27", FLAG_WORK);
        putRange("2025-05-31", "2025-06-02", FLAG_OFF);
        putRange("2025-10-01", "2025-10-08", FLAG_OFF);
        put("2025-09-28", FLAG_WORK);
        put("2025-10-11", FLAG_WORK);

        // ---- 2026 ----
        putRange("2026-01-01", "2026-01-03", FLAG_OFF);
        putRange("2026-02-15", "2026-02-22", FLAG_OFF);
        put("2026-02-14", FLAG_WORK);
        put("2026-02-28", FLAG_WORK);
        putRange("2026-04-04", "2026-04-06", FLAG_OFF);
        putRange("2026-05-01", "2026-05-05", FLAG_OFF);
        put("2026-04-26", FLAG_WORK);
        putRange("2026-06-19", "2026-06-21", FLAG_OFF);
        putRange("2026-09-25", "2026-09-27", FLAG_OFF);
        putRange("2026-10-01", "2026-10-07", FLAG_OFF);
        put("2026-10-10", FLAG_WORK);

        // ---- 2027（国办发明电〔2026〕10号）----
        putRange("2027-01-01", "2027-01-03", FLAG_OFF);
        putRange("2027-02-05", "2027-02-13", FLAG_OFF);
        put("2027-01-31", FLAG_WORK);
        put("2027-02-14", FLAG_WORK);
        putRange("2027-04-03", "2027-04-05", FLAG_OFF);
        putRange("2027-05-01", "2027-05-05", FLAG_OFF);
        put("2027-05-08", FLAG_WORK);
        put("2027-06-09", FLAG_OFF);
        put("2027-09-15", FLAG_OFF);
        putRange("2027-10-01", "2027-10-07", FLAG_OFF);
        put("2027-09-26", FLAG_WORK);
        put("2027-10-09", FLAG_WORK);
    }

    private ChineseHoliday() {
    }

    private static void put(String date, int flag) {
        DAYS.put(date, flag);
    }

    private static void putRange(String start, String end, int flag) {
        Calendar cursor = parse(start);
        Calendar stop = parse(end);
        while (!cursor.after(stop)) {
            DAYS.put(format(cursor), flag);
            cursor.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private static Calendar parse(String date) {
        String[] parts = date.split("-");
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                Integer.parseInt(parts[2]));
        return calendar;
    }

    private static String format(Calendar day) {
        return String.format("%04d-%02d-%02d", day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
    }

    /** 是否为工作日（考虑法定节假日与调休上班日）。 */
    public static boolean isWorkday(Calendar day) {
        Integer flag = DAYS.get(format(day));
        if (flag != null) {
            return flag == FLAG_WORK;
        }
        int dow = day.get(Calendar.DAY_OF_WEEK);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
    }

    /** 是否为休息日。 */
    public static boolean isRestday(Calendar day) {
        return !isWorkday(day);
    }
}
