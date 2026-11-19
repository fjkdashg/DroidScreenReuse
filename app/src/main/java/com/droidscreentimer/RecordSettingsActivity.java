package com.droidscreentimer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 录像设置：启用录像、参数、单文件时长、保存路径、调试预览。
 */
public class RecordSettingsActivity extends Activity {

    private static final int REQUEST_RECORD_FOLDER = 3002;
    private static final int REQUEST_CAMERA_PERMISSION = 3003;

    private static final int[] SEGMENT_MINUTES = {1, 3, 5, 10};

    private Switch recordSwitch;
    private Spinner facingSpinner;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner bitrateSpinner;
    private Switch recordAudioSwitch;
    private Spinner durationSpinner;
    private Spinner orientationSpinner;
    private TextView recordPathText;
    private TextView previewStatusText;
    private TextureView previewView;
    private CameraDebugPreview debugPreview;
    private int appliedFacingIndex;

    private String recordFolderUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_record);

        recordSwitch = findViewById(R.id.recordSwitch);
        facingSpinner = findViewById(R.id.facingSpinner);
        resolutionSpinner = findViewById(R.id.resolutionSpinner);
        fpsSpinner = findViewById(R.id.fpsSpinner);
        bitrateSpinner = findViewById(R.id.bitrateSpinner);
        recordAudioSwitch = findViewById(R.id.recordAudioSwitch);
        durationSpinner = findViewById(R.id.durationSpinner);
        orientationSpinner = findViewById(R.id.orientationSpinner);
        recordPathText = findViewById(R.id.recordPathText);
        previewView = findViewById(R.id.previewView);
        previewStatusText = findViewById(R.id.previewStatusText);
        debugPreview = new CameraDebugPreview(this, previewView, previewStatusText);

        recordSwitch.setChecked(Prefs.isRecordOn(this));
        appliedFacingIndex = Prefs.getCameraFacingIndex(this);
        facingSpinner.setSelection(appliedFacingIndex);
        resolutionSpinner.setSelection(clamp(Prefs.getRecordResolutionIndex(this), 4));
        fpsSpinner.setSelection(clamp(Prefs.getRecordFpsIndex(this), 4));
        bitrateSpinner.setSelection(clamp(Prefs.getRecordBitrateIndex(this), 4));
        recordAudioSwitch.setChecked(Prefs.isRecordAudio(this));
        durationSpinner.setSelection(indexOfMinutes(Prefs.getRecordSegmentMinutes(this)));
        orientationSpinner.setSelection(Prefs.getRecordOrientationIndex(this));

        recordFolderUri = Prefs.getRecordFolder(this);
        updateRecordPathText();

        findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.chooseRecordFolderButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRecordFolderPicker();
            }
        });
        findViewById(R.id.resetRecordFolderButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recordFolderUri = "";
                updateRecordPathText();
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
        facingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != appliedFacingIndex) {
                    appliedFacingIndex = position;
                    debugPreview.restart();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        startPreviewIfPermitted();
    }

    @Override
    protected void onStop() {
        debugPreview.stop();
        super.onStop();
    }

    private void startPreviewIfPermitted() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            debugPreview.start();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                debugPreview.start();
            } else {
                previewStatusText.setText("未授予摄像头权限，无法显示预览");
            }
        }
    }

    private void updateRecordPathText() {
        if (recordFolderUri == null || recordFolderUri.isEmpty()) {
            recordPathText.setText(R.string.record_default_path);
        } else {
            recordPathText.setText(recordFolderUri);
        }
    }

    private void openRecordFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_RECORD_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RECORD_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                recordFolderUri = uri.toString();
                updateRecordPathText();
            }
        }
    }

    private int indexOfMinutes(int minutes) {
        for (int i = 0; i < SEGMENT_MINUTES.length; i++) {
            if (SEGMENT_MINUTES[i] == minutes) {
                return i;
            }
        }
        return 0;
    }

    private int currentSegmentMinutes() {
        return SEGMENT_MINUTES[clamp(durationSpinner.getSelectedItemPosition(), SEGMENT_MINUTES.length)];
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private void save() {
        Prefs.saveRecord(this,
                recordSwitch.isChecked(),
                resolutionSpinner.getSelectedItemPosition(),
                fpsSpinner.getSelectedItemPosition(),
                bitrateSpinner.getSelectedItemPosition(),
                recordAudioSwitch.isChecked(),
                currentSegmentMinutes(),
                clamp(orientationSpinner.getSelectedItemPosition(), 4),
                facingSpinner.getSelectedItemPosition(),
                recordFolderUri == null ? "" : recordFolderUri);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void saveAndFinish() {
        save();
        finish();
    }
}
