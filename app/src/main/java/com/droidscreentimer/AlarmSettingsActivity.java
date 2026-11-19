package com.droidscreentimer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 闹钟设置：多闹钟列表管理、每周生效日、公休日不响铃、随机音乐文件夹。
 */
public class AlarmSettingsActivity extends Activity {

    private static final int REQUEST_FOLDER = 3001;

    private android.widget.LinearLayout alarmListContainer;
    private TextView emptyHint;
    private TextView folderPathText;
    private String folderUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_alarm);

        alarmListContainer = findViewById(R.id.alarmListContainer);
        emptyHint = findViewById(R.id.alarmEmptyHint);
        folderPathText = findViewById(R.id.folderPathText);

        folderUri = Prefs.getAlarmFolder(this);
        folderPathText.setText(folderUri.isEmpty() ? getString(R.string.alarm_folder_hint) : folderUri);

        findViewById(R.id.backButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.addAlarmButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(null);
            }
        });
        findViewById(R.id.chooseFolderButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFolderPicker();
            }
        });
        findViewById(R.id.testAlarmButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAlarm();
            }
        });

        renderAlarmList();
    }

    private void renderAlarmList() {
        List<AlarmItem> alarms = AlarmStore.load(this);
        alarmListContainer.removeAllViews();
        emptyHint.setVisibility(alarms.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AlarmItem alarm : alarms) {
            View row = inflater.inflate(R.layout.item_alarm_row, alarmListContainer, false);
            TextView timeLabel = row.findViewById(R.id.alarmTimeLabel);
            TextView descLabel = row.findViewById(R.id.alarmDescLabel);
            Switch itemSwitch = row.findViewById(R.id.alarmItemSwitch);
            View deleteButton = row.findViewById(R.id.alarmItemDelete);

            SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(java.util.Calendar.HOUR_OF_DAY, alarm.hour);
            calendar.set(java.util.Calendar.MINUTE, alarm.minute);
            timeLabel.setText(format.format(calendar.getTime()));
            descLabel.setText(alarm.describe());
            itemSwitch.setChecked(alarm.enabled);

            itemSwitch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    alarm.enabled = ((Switch) v).isChecked();
                    AlarmStore.update(AlarmSettingsActivity.this, alarm);
                    AlarmScheduler.scheduleAll(AlarmSettingsActivity.this);
                }
            });
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AlarmStore.remove(AlarmSettingsActivity.this, alarm.id);
                    AlarmScheduler.scheduleAll(AlarmSettingsActivity.this);
                    renderAlarmList();
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditDialog(alarm);
                }
            });
            alarmListContainer.addView(row);
        }
    }

    private void showEditDialog(final AlarmItem existing) {
        final AlarmItem alarm = existing != null
                ? existing
                : new AlarmItem(System.currentTimeMillis(), 7, 0);

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_alarm_edit, null, false);
        final TimePicker timePicker = content.findViewById(R.id.editTimePicker);
        final Switch dayMon = content.findViewById(R.id.dayMon);
        final Switch dayTue = content.findViewById(R.id.dayTue);
        final Switch dayWed = content.findViewById(R.id.dayWed);
        final Switch dayThu = content.findViewById(R.id.dayThu);
        final Switch dayFri = content.findViewById(R.id.dayFri);
        final Switch daySat = content.findViewById(R.id.daySat);
        final Switch daySun = content.findViewById(R.id.daySun);
        final Switch skipHolidays = content.findViewById(R.id.skipHolidaysSwitch);

        timePicker.setIs24HourView(true);
        timePicker.setHour(alarm.hour);
        timePicker.setMinute(alarm.minute);
        final Switch[] daySwitches = {dayMon, dayTue, dayWed, dayThu, dayFri, daySat, daySun};
        for (int i = 0; i < daySwitches.length; i++) {
            daySwitches[i].setChecked((alarm.repeatDays & AlarmItem.WEEK_BITS[i]) != 0);
        }
        skipHolidays.setChecked(alarm.skipHolidays);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.alarm_add : R.string.alarm_time)
                .setView(content)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        alarm.hour = timePicker.getHour();
                        alarm.minute = timePicker.getMinute();
                        int days = 0;
                        for (int i = 0; i < daySwitches.length; i++) {
                            if (daySwitches[i].isChecked()) {
                                days |= AlarmItem.WEEK_BITS[i];
                            }
                        }
                        alarm.repeatDays = days;
                        alarm.skipHolidays = skipHolidays.isChecked();
                        if (existing == null) {
                            AlarmStore.add(AlarmSettingsActivity.this, alarm);
                        } else {
                            AlarmStore.update(AlarmSettingsActivity.this, alarm);
                        }
                        AlarmScheduler.scheduleAll(AlarmSettingsActivity.this);
                        renderAlarmList();
                        Toast.makeText(AlarmSettingsActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                folderUri = uri.toString();
                folderPathText.setText(folderUri);
                // 即时保存音乐文件夹配置
                Prefs.saveAlarm(this, Prefs.isAlarmOn(this), Prefs.isAlarmRepeat(this),
                        Prefs.getAlarmHour(this), Prefs.getAlarmMinute(this), folderUri);
            }
        }
    }

    private void testAlarm() {
        Intent intent = new Intent(this, AlarmSoundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
