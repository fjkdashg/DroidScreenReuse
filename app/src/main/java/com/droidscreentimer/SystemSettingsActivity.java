package com.droidscreentimer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 系统设置：调试服务器与流媒体服务开关（两个独立 Web 服务器）。
 */
public class SystemSettingsActivity extends Activity {

    private Switch debugServerSwitch;
    private TextView debugServerAddress;
    private Switch streamServerSwitch;
    private TextView streamServerAddress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_system);

        debugServerSwitch = findViewById(R.id.debugServerSwitch);
        debugServerAddress = findViewById(R.id.debugServerAddress);
        streamServerSwitch = findViewById(R.id.streamServerSwitch);
        streamServerAddress = findViewById(R.id.streamServerAddress);

        debugServerSwitch.setChecked(Prefs.isDebugServerOn(this));
        streamServerSwitch.setChecked(Prefs.isStreamServerOn(this));
        updateAddressText();

        debugServerAddress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStreamView();
            }
        });
        streamServerAddress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStreamView();
            }
        });

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
                save();
                finish();
            }
        });
    }

    private void save() {
        Prefs.saveSystem(SystemSettingsActivity.this,
                debugServerSwitch.isChecked(),
                streamServerSwitch.isChecked());
        Toast.makeText(SystemSettingsActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void updateAddressText() {
        String debugBase = DebugServer.getActiveLanAddress();
        boolean debugRunning = DebugServer.getActivePort() > 0;
        debugServerAddress.setText(
                (debugRunning ? "" : "（调试服务器未运行）\n")
                        + debugBase + "\n/status  /camera  /log?pid=1");

        String streamBase = StreamServer.getActiveLanAddress();
        if (streamBase == null) {
            streamServerAddress.setText("（流媒体服务未运行）");
        } else {
            streamServerAddress.setText(streamBase + "\n/view 网页观看页\n/stream MJPEG 原始流");
        }
    }

    private void openStreamView() {
        String base = StreamServer.getActiveLanAddress();
        if (base == null) {
            Toast.makeText(this, "流媒体服务未运行", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(base + "/view")));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }
}
