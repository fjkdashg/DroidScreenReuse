package com.droidscreentimer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 闹钟列表的持久化存储（SharedPreferences + JSON）。
 */
public final class AlarmStore {

    private static final String KEY = "alarms_json";

    private AlarmStore() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences("droid_alarms", Context.MODE_PRIVATE);
    }

    public static List<AlarmItem> load(Context context) {
        List<AlarmItem> alarms = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp(context).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                alarms.add(AlarmItem.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(alarms, new Comparator<AlarmItem>() {
            @Override
            public int compare(AlarmItem a, AlarmItem b) {
                int ta = a.hour * 60 + a.minute;
                int tb = b.hour * 60 + b.minute;
                return Integer.compare(ta, tb);
            }
        });
        return alarms;
    }

    public static void save(Context context, List<AlarmItem> alarms) {
        try {
            JSONArray array = new JSONArray();
            for (AlarmItem alarm : alarms) {
                array.put(alarm.toJson());
            }
            sp(context).edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void add(Context context, AlarmItem alarm) {
        List<AlarmItem> alarms = load(context);
        alarms.add(alarm);
        save(context, alarms);
    }

    public static void update(Context context, AlarmItem alarm) {
        List<AlarmItem> alarms = load(context);
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).id == alarm.id) {
                alarms.set(i, alarm);
                break;
            }
        }
        save(context, alarms);
    }

    public static void remove(Context context, long id) {
        List<AlarmItem> alarms = load(context);
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).id == id) {
                alarms.remove(i);
                break;
            }
        }
        save(context, alarms);
    }

    public static AlarmItem findById(Context context, long id) {
        for (AlarmItem alarm : load(context)) {
            if (alarm.id == id) {
                return alarm;
            }
        }
        return null;
    }
}
