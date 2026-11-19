package com.droidscreentimer;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 独立的流媒体 Web 服务器（与调试服务器分开监听、独立开关）：
 * - GET /view    网页实时观看页（嵌入 MJPEG 画面）
 * - GET /stream  MJPEG 实时流（multipart/x-mixed-replace，按录像参数输出）
 * - GET /        推流状态文本
 */
public class StreamServer {

    private static final int BASE_PORT = 8090;
    private static final String BOUNDARY = "droidframe";

    private final Context context;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private int port = -1;

    private static volatile int activePort = -1;

    public StreamServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (serverSocket != null) {
            return;
        }
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
                Log.e("StreamServer", "no free port");
                return;
            }
            activePort = port;
            pool = Executors.newCachedThreadPool();
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "stream-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i("StreamServer", "started on port " + port);
        } catch (Exception e) {
            Log.e("StreamServer", "start failed", e);
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

    public int getPort() {
        return port;
    }

    /** 当前运行实例占用的端口，未运行返回 -1。 */
    public static int getActivePort() {
        return activePort;
    }

    /** 当前运行实例的局域网地址，未运行返回 null。 */
    public static String getActiveLanAddress() {
        return activePort > 0 ? DebugServer.getLanAddress(activePort) : null;
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
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            String path = "/";
            if (requestLine != null && requestLine.startsWith("GET ")) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
            }
            if (path.startsWith("/stream")) {
                serveStream(socket);
            } else if (path.startsWith("/view")) {
                serveView(socket);
            } else {
                serveInfo(socket);
            }
        } catch (Exception ignored) {
        }
    }

    /** 推流状态与地址说明。 */
    private void serveInfo(Socket socket) {
        String base = getActiveLanAddress();
        StringBuilder sb = new StringBuilder();
        sb.append("== DroidScreenTimer stream server ==\n");
        sb.append(CameraStream.getInstance(context).describe());
        if (base != null) {
            sb.append("viewUrl=").append(base).append("/view\n");
            sb.append("streamUrl=").append(base).append("/stream\n");
        }
        respondText(socket, sb.toString());
    }

    private void respondText(Socket socket, String body) {
        OutputStream out = null;
        try {
            byte[] data = body.getBytes("UTF-8");
            out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: ").getBytes("UTF-8"));
            out.write(String.valueOf(data.length).getBytes("UTF-8"));
            out.write("\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            closeQuietly(socket, out);
        }
    }

    /** MJPEG 实时流：multipart/x-mixed-replace，按流帧率节奏向客户端推送 JPEG 帧。 */
    private void serveStream(Socket socket) {
        CameraStream stream = CameraStream.getInstance(context);
        boolean clientAdded = false;
        OutputStream out = null;
        try {
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            stream.addClient();
            clientAdded = true;
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-cache, private\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            long lastTimestamp = 0;
            int frameInterval = Math.max(33, 1000 / Math.max(1, stream.getStreamFps()));
            while (true) {
                byte[] frame = stream.waitForFrame(lastTimestamp, 5000);
                if (frame == null) {
                    // 5 秒无新帧（相机异常或已停止）则断开，浏览器可自行重连
                    break;
                }
                lastTimestamp = stream.getFrameTimestamp();
                out.write(("--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: ").getBytes("UTF-8"));
                out.write(String.valueOf(frame.length).getBytes("UTF-8"));
                out.write("\r\n\r\n".getBytes("UTF-8"));
                out.write(frame);
                out.write("\r\n".getBytes("UTF-8"));
                out.flush();
                Thread.sleep(frameInterval);
            }
        } catch (Exception ignored) {
            // 客户端断开或线程被打断，正常退出
        } finally {
            if (clientAdded) {
                stream.removeClient();
            }
            closeQuietly(socket, out);
        }
    }

    /** 网页观看页：嵌入 /stream 的 MJPEG 画面。 */
    private void serveView(Socket socket) {
        OutputStream out = null;
        try {
            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<title>DroidScreenTimer 实时画面</title></head>"
                    + "<body style='background:#101018;color:#bbb;font-family:sans-serif;"
                    + "text-align:center;margin:0;padding:12px'>"
                    + "<h3 style='margin:8px 0;color:#B388FF'>DroidScreenTimer 实时画面</h3>"
                    + "<img src='/stream' alt='live' style='max-width:100%;max-height:80vh;"
                    + "background:#000;border-radius:8px'>"
                    + "<p style='font-size:12px'>按程序录像参数输出（分辨率/帧率/朝向）"
                    + " · 流中断会自动断开，刷新页面重连</p>"
                    + "</body></html>";
            byte[] data = html.getBytes("UTF-8");
            out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: ").getBytes("UTF-8"));
            out.write(String.valueOf(data.length).getBytes("UTF-8"));
            out.write("\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            closeQuietly(socket, out);
        }
    }

    private static void closeQuietly(Socket socket, OutputStream out) {
        try {
            if (out != null) {
                out.flush();
            }
        } catch (Exception ignored) {
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
