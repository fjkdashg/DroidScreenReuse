package com.droidscreentimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 闹钟响铃服务：从用户指定文件夹中随机挑选音频文件播放。
 */
public class AlarmSoundService extends Service {

    private static final String CHANNEL_ID = "droid_alarm_channel";
    private static final String ACTION_STOP = "com.droidscreentimer.STOP_ALARM";
    private static final int NOTIFICATION_ID = 2001;

    private MediaPlayer player;
    private Handler timeoutHandler;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        playRandomMusic();
        timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutHandler.postDelayed(timeout, 5 * 60 * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "闹钟",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("闹钟响铃通知");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, AlarmSoundService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getService(this, 2, stopIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.app_name))
                .setContentText("闹钟响铃中，点击停止")
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "停止", pendingIntent);
        return builder.build();
    }

    private void playRandomMusic() {
        player = new MediaPlayer();
        boolean prepared = false;

        String folder = Prefs.getAlarmFolder(this);
        if (!TextUtils.isEmpty(folder)) {
            ParcelFileDescriptor descriptor = null;
            try {
                Uri treeUri = Uri.parse(folder);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                Cursor cursor = getContentResolver().query(children,
                        new String[]{
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                        }, null, null, null);
                List<String> audioIds = new ArrayList<>();
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String mime = cursor.getString(1);
                        if (mime != null && mime.startsWith("audio/")) {
                            audioIds.add(cursor.getString(0));
                        }
                    }
                    cursor.close();
                }
                if (!audioIds.isEmpty()) {
                    String chosen = audioIds.get(new Random().nextInt(audioIds.size()));
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, chosen);
                    descriptor = getContentResolver().openFileDescriptor(documentUri, "r");
                    if (descriptor != null) {
                        FileDescriptor fd = descriptor.getFileDescriptor();
                        player.setDataSource(fd);
                        player.prepare();
                        prepared = true;
                    }
                }
            } catch (Exception ignored) {
                prepared = false;
            } finally {
                if (descriptor != null) {
                    try {
                        descriptor.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (!prepared) {
            try {
                player.reset();
                Uri fallback = android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM);
                if (fallback != null) {
                    player.setDataSource(this, fallback);
                    player.prepare();
                    prepared = true;
                }
            } catch (Exception ignored) {
                prepared = false;
            }
        }

        if (!prepared) {
            stopSelf();
            return;
        }
        player.setVolume(1f, 1f);
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                stopSelf();
            }
        });
        player.start();
    }

    @Override
    public void onDestroy() {
        if (timeoutHandler != null) {
            timeoutHandler.removeCallbacks(timeout);
        }
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
