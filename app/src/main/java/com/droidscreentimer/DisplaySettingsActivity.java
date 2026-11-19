package com.droidscreentimer;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

/**
 * 信息显示设置：时间制式、开机自启、重力翻转、天气定位。
 */
public class DisplaySettingsActivity extends Activity {

    private Switch time24Switch;
    private Switch bootSwitch;
    private Switch flipSwitch;
    private Switch autoLocationSwitch;
    private EditText latEdit;
    private EditText lonEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_display);

        time24Switch = findViewById(R.id.time24Switch);
        bootSwitch = findViewById(R.id.bootSwitch);
        flipSwitch = findViewById(R.id.flipSwitch);
        autoLocationSwitch = findViewById(R.id.autoLocationSwitch);
        latEdit = findViewById(R.id.latEdit);
        lonEdit = findViewById(R.id.lonEdit);

        time24Switch.setChecked(Prefs.is24Hour(this));
        bootSwitch.setChecked(Prefs.isAutoStart(this));
        flipSwitch.setChecked(Prefs.isFlipEnabled(this));
        autoLocationSwitch.setChecked(Prefs.isAutoLocation(this));
        latEdit.setText(String.valueOf(Prefs.getLatitude(this)));
        lonEdit.setText(String.valueOf(Prefs.getLongitude(this)));

        findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.saveOnlyButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.saveButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndFinish();
            }
        });
    }

    private void save() {
        double latitude = parseDouble(latEdit.getText().toString(), Prefs.getLatitude(this));
        double longitude = parseDouble(lonEdit.getText().toString(), Prefs.getLongitude(this));
        Prefs.saveDisplay(this, bootSwitch.isChecked(), flipSwitch.isChecked(), time24Switch.isChecked());
        Prefs.saveWeather(this, autoLocationSwitch.isChecked(), latitude, longitude);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void saveAndFinish() {
        save();
        finish();
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
