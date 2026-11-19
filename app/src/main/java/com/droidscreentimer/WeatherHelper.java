package com.droidscreentimer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 使用 Open-Meteo 免费接口获取当前天气与明天天气，无需 API Key。
 */
public final class WeatherHelper {

    /** 天气不可用时温度占位值。 */
    public static final int TEMP_UNAVAILABLE = Integer.MIN_VALUE;

    public interface Callback {
        void onResult(String today, String tomorrow, int currentTemp, String currentIcon);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private WeatherHelper() {
    }

    public static void fetch(double latitude, double longitude, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String today = null;
                String tomorrow = null;
                int currentTemp = TEMP_UNAVAILABLE;
                String currentIcon = "—";
                try {
                    String url = "https://api.open-meteo.com/v1/forecast"
                            + "?latitude=" + latitude
                            + "&longitude=" + longitude
                            + "&current=temperature_2m,weather_code"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                            + "&timezone=auto&forecast_days=3";

                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    connection.setRequestMethod("GET");
                    InputStream input = connection.getInputStream();
                    String json = readAll(input);
                    connection.disconnect();

                    JSONObject root = new JSONObject(json);
                    JSONObject current = root.getJSONObject("current");
                    int currentCode = current.getInt("weather_code");
                    currentTemp = (int) Math.round(current.getDouble("temperature_2m"));

                    JSONObject daily = root.getJSONObject("daily");
                    JSONArray codes = daily.getJSONArray("weather_code");
                    JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
                    JSONArray minTemps = daily.getJSONArray("temperature_2m_min");

                    int tomorrowCode = codes.getInt(1);
                    int tomorrowMax = (int) Math.round(maxTemps.getDouble(1));
                    int tomorrowMin = (int) Math.round(minTemps.getDouble(1));

                    today = "今天  " + describe(currentCode) + "  " + currentTemp + "°C";
                    tomorrow = "明天  " + describe(tomorrowCode) + "  "
                            + tomorrowMin + "~" + tomorrowMax + "°C";
                    currentIcon = iconOf(currentCode);
                } catch (Exception ignored) {
                    // 网络不可用时保持 null，由界面显示占位文案
                }
                final String resultToday = today;
                final String resultTomorrow = tomorrow;
                final int resultTemp = currentTemp;
                final String resultIcon = currentIcon;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(resultToday, resultTomorrow, resultTemp, resultIcon);
                    }
                });
            }
        });
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, count);
        }
        input.close();
        return buffer.toString("UTF-8");
    }

    /** Open-Meteo weather_code → 晴雨图标（Unicode emoji，无第三方依赖）。 */
    static String iconOf(int code) {
        if (code == 0) {
            return "☀️";
        }
        if (code == 1) {
            return "🌤️";
        }
        if (code == 2) {
            return "⛅";
        }
        if (code == 3) {
            return "☁️";
        }
        if (code == 45 || code == 48) {
            return "🌫️";
        }
        if ((code >= 51 && code <= 57) || (code >= 80 && code <= 82)) {
            return "🌦️";
        }
        if ((code >= 61 && code <= 67)) {
            return "🌧️";
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return "❄️";
        }
        if (code >= 95) {
            return "⛈️";
        }
        return "🌡️";
    }

    private static String describe(int code) {
        switch (code) {
            case 0:
                return "晴";
            case 1:
                return "大部晴朗";
            case 2:
                return "局部多云";
            case 3:
                return "阴";
            case 45:
            case 48:
                return "雾";
            case 51:
            case 53:
            case 55:
                return "毛毛雨";
            case 56:
            case 57:
                return "冻毛毛雨";
            case 61:
            case 63:
            case 65:
                return "雨";
            case 66:
            case 67:
                return "冻雨";
            case 71:
            case 73:
            case 75:
            case 77:
                return "雪";
            case 80:
            case 81:
            case 82:
                return "阵雨";
            case 85:
            case 86:
                return "阵雪";
            case 95:
                return "雷阵雨";
            case 96:
            case 99:
                return "雷暴伴冰雹";
            default:
                return "天气多变";
        }
    }
}
