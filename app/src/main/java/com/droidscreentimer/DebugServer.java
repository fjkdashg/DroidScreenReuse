package com.droidscreentimer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置 HTTP 调试服务器，供开发工具远程读取日志与诊断信息：
 * - GET /status  应用与录像运行状态、配置、录像文件列表
 * - GET /camera  相机能力诊断（各流支持尺寸、编码器列表）
 * - GET /log     logcat 日志（可加 ?pid=1 仅看本进程，?tag=Xxx 过滤关键字）
 * 流媒体服务（/view /stream）由独立的 StreamServer 提供，不受本服务器开关控制。
 */
public class DebugServer {

    public interface InfoProvider {
        String provide();
    }

    private static final int BASE_PORT = 8080;
    private static final int MAX_LOG_CHARS = 500_000;

    private final Context context;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private int port = -1;
    private InfoProvider infoProvider;

    private static volatile int activePort = -1;

    public DebugServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(InfoProvider provider) {
        if (serverSocket != null) {
            return;
        }
        infoProvider = provider;
        try {
            for (int p = BASE_PORT; p < BASE_PORT + 10; p++) {
                try {
                    serverSocket = new ServerSocket(p, 20, InetAddress.getByName("0.0.0.0"));
                    port = p;
                    break;
                } catch (Exception e) {
                    // 端口被占用则尝试下一个
                }
            }
            if (serverSocket == null) {
                Log.e("DebugServer", "no free port");
                return;
            }
            activePort = port;
            pool = Executors.newCachedThreadPool();
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "debug-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i("DebugServer", "started on port " + port);
        } catch (Exception e) {
            Log.e("DebugServer", "start failed", e);
        }
    }

    public synchronized void stop() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        acceptThread = null;
        port = -1;
        activePort = -1;
    }

    public boolean isRunning() {
        return serverSocket != null;
    }

    /** 当前运行实例占用的端口，未运行返回 -1。 */
    public static int getActivePort() {
        return activePort;
    }

    /** 当前运行实例的局域网地址，未运行时回退到默认 8080 端口。 */
    public static String getActiveLanAddress() {
        return getLanAddress(activePort > 0 ? activePort : 8080);
    }

    public int getPort() {
        return port;
    }

    /** 返回如 http://192.168.1.5:8080 的第一个可用局域网地址。 */
    public static String getLanAddress(int port) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        return "http://" + address.getHostAddress() + ":" + port;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "http://127.0.0.1:" + port;
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                final Socket client = socket;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(client);
                    }
                });
            } catch (Exception e) {
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            String path = "/";
            if (requestLine != null && requestLine.startsWith("GET ")) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
            }
            String body;
            if (path.startsWith("/log")) {
                body = readLogcat(path);
            } else if (path.startsWith("/camera")) {
                body = cameraDiagnostics();
            } else {
                body = statusInfo();
            }
            byte[] data = body.getBytes("UTF-8");
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + data.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(data);
            out.flush();
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private String statusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("== DroidScreenTimer debug status ==\n");
        sb.append("versionCode=").append(getVersionCode()).append('\n');
        sb.append("android=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("debugServer=on port=").append(port).append('\n');
        sb.append("\n-- config --\n");
        sb.append("time24=").append(Prefs.is24Hour(context)).append('\n');
        sb.append("autoStart=").append(Prefs.isAutoStart(context)).append('\n');
        sb.append("flip=").append(Prefs.isFlipEnabled(context)).append('\n');
        sb.append("recordOn=").append(Prefs.isRecordOn(context)).append('\n');
        sb.append("recordRes=").append(Prefs.getRecordResolutionIndex(context)).append('\n');
        sb.append("recordFps=").append(Prefs.getRecordFpsIndex(context)).append('\n');
        sb.append("recordBitrateMbps=").append(Prefs.getRecordBitrateIndex(context)).append('\n');
        sb.append("recordAudio=").append(Prefs.isRecordAudio(context)).append('\n');
        sb.append("recordSegmentMinutes=").append(Prefs.getRecordSegmentMinutes(context)).append('\n');
        sb.append("recordFolder=").append(Prefs.getRecordFolder(context)).append('\n');

        sb.append("\n-- recorder --\n");
        if (infoProvider != null) {
            sb.append(infoProvider.provide());
        } else {
            sb.append("infoProvider=null\n");
        }

        sb.append("\n-- stream --\n");
        sb.append(CameraStream.getInstance(context).describe());
        int streamPort = StreamServer.getActivePort();
        if (streamPort > 0) {
            sb.append("streamServer=on port=").append(streamPort).append('\n');
            sb.append("streamViewUrl=").append(DebugServer.getLanAddress(streamPort)).append("/view\n");
        } else {
            sb.append("streamServer=off\n");
        }

        sb.append("\n-- record files --\n");
        sb.append(listRecordFiles());
        return sb.toString();
    }

    private int getVersionCode() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private String listRecordFiles() {
        StringBuilder sb = new StringBuilder();
        try {
            ContentResolver resolver = context.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                Cursor cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Video.Media.DISPLAY_NAME,
                                MediaStore.Video.Media.SIZE,
                                MediaStore.Video.Media.RELATIVE_PATH},
                        MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                        new String[]{"Movies/DroidScreenTimer%"},
                        MediaStore.Video.Media.DATE_MODIFIED + " DESC");
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        sb.append("[mediastore] ").append(cursor.getString(0))
                                .append(" size=").append(cursor.getLong(1)).append('\n');
                    }
                    cursor.close();
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "DroidScreenTimer");
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        sb.append("[public] ").append(f.getName()).append(" size=").append(f.length()).append('\n');
                    }
                }
            }
            String safFolder = Prefs.getRecordFolder(context);
            if (safFolder != null && !safFolder.isEmpty()) {
                Uri treeUri = Uri.parse(safFolder);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                Cursor cursor = resolver.query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_SIZE,
                                DocumentsContract.Document.COLUMN_MIME_TYPE},
                        null, null, null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String mime = cursor.getString(2);
                        if (mime != null && mime.startsWith("video/")) {
                            sb.append("[saf] ").append(cursor.getString(0))
                                    .append(" size=").append(cursor.getLong(1)).append('\n');
                        }
                    }
                    cursor.close();
                }
            }
            // 旧版本可能写入的私有目录
            File legacy = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "DroidScreenTimer");
            File[] legacyFiles = legacy.listFiles();
            if (legacyFiles != null) {
                for (File f : legacyFiles) {
                    sb.append("[app-private] ").append(f.getName()).append(" size=").append(f.length()).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("list error: ").append(e).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("(no files)\n");
        }
        return sb.toString();
    }

    private String cameraDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("== camera diagnostics ==\n");
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                sb.append("camera id=").append(id)
                        .append(" facing=").append(facing == null ? "?" : facing)
                        .append(" hwLevel=").append(level == null ? "?" : level)
                        .append('\n');
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    sb.append("  streamConfigMap=null\n");
                    continue;
                }
                Size[] previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                sb.append("  preview(SurfaceTexture) sizes: ").append(joinSizes(previewSizes)).append('\n');
                Size[] recordSizes = map.getOutputSizes(android.media.MediaRecorder.class);
                sb.append("  record(MediaRecorder) sizes: ").append(joinSizes(recordSizes)).append('\n');
            }
        } catch (Exception e) {
            sb.append("camera error: ").append(e).append('\n');
        }
        sb.append("\n== video encoders ==\n");
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type.toLowerCase().contains("video")) {
                        sb.append(info.getName()).append(" : ").append(type).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            sb.append("codec error: ").append(e).append('\n');
        }
        return sb.toString();
    }

    private static String joinSizes(Size[] sizes) {
        if (sizes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (Size size : sizes) {
            sb.append(size.getWidth()).append('x').append(size.getHeight()).append(' ');
        }
        return sb.toString();
    }

    private String readLogcat(String path) {
        StringBuilder sb = new StringBuilder();
        try {
            boolean onlySelf = path.contains("pid=1");
            String tagFilter = null;
            int idx = path.indexOf("tag=");
            if (idx >= 0) {
                tagFilter = path.substring(idx + 4);
                int end = tagFilter.indexOf('&');
                if (end >= 0) {
                    tagFilter = tagFilter.substring(0, end);
                }
            }
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String myPid = String.valueOf(android.os.Process.myPid());
            String line;
            while ((line = reader.readLine()) != null) {
                if (onlySelf && !line.contains(" " + myPid + ": ")) {
                    continue;
                }
                if (tagFilter != null && !line.contains(tagFilter)) {
                    continue;
                }
                sb.append(line).append('\n');
                if (sb.length() > MAX_LOG_CHARS) {
                    sb.append("(truncated)\n");
                    break;
                }
            }
            reader.close();
            process.destroy();
        } catch (Exception e) {
            sb.append("logcat error: ").append(e).append('\n');
        }
        return sb.toString();
    }
}
