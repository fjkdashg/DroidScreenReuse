package com.droidscreentimer;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 自包含的农历换算工具类，覆盖 1900-2100 年，无需任何第三方依赖。
 */
public final class LunarCalendar {

    private static final long[] LUNAR_INFO = {
            0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
            0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
            0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
            0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
            0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
            0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
            0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
            0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
            0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
            0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
            0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
            0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
            0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
            0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
            0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
            0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06aa0, 0x1a6c4, 0x0aae0,
            0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
            0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
            0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
            0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
            0x0d520
    };

    private static final String[] NUM_CN = {
            "日", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"
    };

    private static final String[] LUNAR_MONTHS = {
            "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"
    };

    private LunarCalendar() {
    }

    /** 返回 {农历年, 农历月, 农历日, 是否闰月(1/0)}，超出范围返回 null。 */
    public static int[] lunar(Calendar solar) {
        int year = solar.get(Calendar.YEAR);
        if (year < 1900 || year > 2100) {
            return null;
        }
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar base = Calendar.getInstance(utc);
        base.clear();
        base.set(1900, Calendar.JANUARY, 31);
        Calendar target = Calendar.getInstance(utc);
        target.clear();
        target.set(year, solar.get(Calendar.MONTH), solar.get(Calendar.DAY_OF_MONTH));
        int offset = (int) ((target.getTimeInMillis() - base.getTimeInMillis()) / 86400000L);
        if (offset < 0) {
            return null;
        }

        int temp = 0;
        int i;
        for (i = 1900; i < 2101 && offset > 0; i++) {
            temp = yearDays(i);
            offset -= temp;
        }
        if (offset < 0) {
            offset += temp;
            i--;
        }
        int lunarYear = i;

        int leap = leapMonth(lunarYear);
        boolean isLeap = false;
        for (i = 1; i < 13 && offset > 0; i++) {
            if (leap > 0 && i == leap + 1 && !isLeap) {
                --i;
                isLeap = true;
                temp = leapDays(lunarYear);
            } else {
                temp = monthDays(lunarYear, i);
            }
            if (isLeap && i == leap + 1) {
                isLeap = false;
            }
            offset -= temp;
        }
        if (offset == 0 && leap > 0 && i == leap + 1) {
            if (isLeap) {
                isLeap = false;
            } else {
                isLeap = true;
                --i;
            }
        }
        if (offset < 0) {
            offset += temp;
            --i;
        }
        int lunarMonth = i;
        int lunarDay = offset + 1;
        return new int[]{lunarYear, lunarMonth, lunarDay, isLeap ? 1 : 0};
    }

    /** 例如：农历九月廿八 */
    public static String getLunarText(Calendar solar) {
        int[] l = lunar(solar);
        if (l == null) {
            return "";
        }
        return "农历" + (l[3] == 1 ? "闰" : "") + LUNAR_MONTHS[l[1] - 1] + "月" + dayName(l[2]);
    }

    private static String dayName(int day) {
        if (day == 10) return "初十";
        if (day == 20) return "二十";
        if (day == 30) return "三十";
        if (day < 10) return "初" + NUM_CN[day];
        if (day < 20) return "十" + NUM_CN[day - 10];
        if (day < 30) return "廿" + NUM_CN[day - 20];
        return "卅" + NUM_CN[day - 30];
    }

    private static int leapDays(int year) {
        if (leapMonth(year) == 0) {
            return 0;
        }
        return (LUNAR_INFO[year - 1900] & 0x10000) != 0 ? 30 : 29;
    }

    private static int monthDays(int year, int month) {
        return (LUNAR_INFO[year - 1900] & (0x10000 >> month)) != 0 ? 30 : 29;
    }

    private static int leapMonth(int year) {
        return (int) (LUNAR_INFO[year - 1900] & 0xf);
    }

    private static int yearDays(int year) {
        int sum = 348;
        for (int i = 0x8000; i > 0x8; i >>= 1) {
            sum += (LUNAR_INFO[year - 1900] & i) != 0 ? 1 : 0;
        }
        return sum + leapDays(year);
    }
}
