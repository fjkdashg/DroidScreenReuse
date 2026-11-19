package com.droidscreentimer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 呼吸灯背景：整屏单一色相的柔和渐变，亮度按正弦周期缓慢呼吸。
 * 录像开启时为冷色（青蓝紫）且整体明亮；关闭时为暖色（红橙黄）且整体偏暗。
 * 色相周期性随机漂移，避免长时间静态画面烧屏。
 */
public class FlowingBackgroundView extends View {

    /** 暖色调区间（红橙黄） */
    private static final float WARM_START = 0f;
    private static final float WARM_SPAN = 60f;
    /** 冷色调区间（青蓝紫） */
    private static final float COOL_START = 170f;
    private static final float COOL_SPAN = 110f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean warm = true;
    private float breathPhase;
    private float hueOffset = random.nextFloat() * WARM_SPAN;
    private long lastHueChange = SystemClock.elapsedRealtime();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // 呼吸周期约 6 秒
            breathPhase += 1f / 60f;
            if (breathPhase >= 1f) {
                breathPhase -= 1f;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastHueChange > 12000) {
                hueOffset = random.nextFloat() * (warm ? WARM_SPAN : COOL_SPAN);
                lastHueChange = now;
            }
            invalidate();
            handler.postDelayed(this, 100);
        }
    };

    public FlowingBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 设置冷暖色调：true=暖色偏暗（未录像），false=冷色偏亮（录像中）。 */
    public void setWarm(boolean warmTone) {
        if (this.warm == warmTone) {
            return;
        }
        this.warm = warmTone;
        hueOffset = random.nextFloat() * (warm ? WARM_SPAN : COOL_SPAN);
        lastHueChange = SystemClock.elapsedRealtime();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastHueChange = SystemClock.elapsedRealtime();
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float start = warm ? WARM_START : COOL_START;
        float span = warm ? WARM_SPAN : COOL_SPAN;
        float hue = start + hueOffset;

        // 呼吸亮度：录像中 0.32→0.92（亮），未录像 0.10→0.54（暗）
        float wave = 0.5f + 0.5f * (float) Math.sin(breathPhase * (float) (2 * Math.PI));
        float value = warm
                ? 0.10f + 0.44f * wave
                : 0.32f + 0.60f * wave;

        // 轻微对角渐变：左上略亮，右下略暗，增加呼吸灯的立体感
        int topColor = Color.HSVToColor(new float[]{hue, 0.55f, Math.min(1f, value * 1.18f)});
        int bottomColor = Color.HSVToColor(new float[]{hue, 0.60f, value * 0.82f});
        LinearGradient gradient = new LinearGradient(0f, 0f, w, h,
                new int[]{topColor, bottomColor}, null, Shader.TileMode.CLAMP);

        paint.setShader(gradient);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }
}
