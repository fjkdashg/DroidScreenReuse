package com.droidscreentimer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * MJPEG 流媒体单例：按程序的录像参数（分辨率/帧率/朝向）抓取当前摄像头画面，
 * 转成 JPEG 供 DebugServer /stream 端点向局域网网页客户端推送。
 * - 录像进行中：把流的 ImageReader 作为附加输出面加入录像会话（复用同一相机）；
 * - 录像空闲：自开相机输出预览级重复请求；
 * - 无客户端 3 秒后自动释放相机资源。
 */
public class CameraStream {

    private static final int[][] RESOLUTIONS = {
            {320, 240}, {640, 480}, {1280, 720}, {1920, 1080}
    };
    private static final int[] FPS = {15, 24, 30, 60};
    private static final int[] ORIENTATIONS = {0, 90, 180, 270};
    private static final int JPEG_QUALITY = 70;
    private static final int READER_MAX_IMAGES = 2;
    private static final long IDLE_STOP_DELAY_MS = 3000;

    private static volatile CameraStream instance;

    public static CameraStream getInstance(Context context) {
        if (instance == null) {
            synchronized (CameraStream.class) {
                if (instance == null) {
                    instance = new CameraStream(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final Object frameLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageReader streamReader;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;

    private boolean usingRecorder;
    private int clientCount;
    private int streamWidth;
    private int streamHeight;
    private int streamFps;
    private byte[] latestJpeg;
    private long frameTimestamp;
    private long lastEncodeTimestamp;
    private String lastStatus = "空闲";

    private CameraStream(Context appContext) {
        this.appContext = appContext;
    }

    /** 有新的网页客户端连接 /stream 时调用。 */
    public synchronized void addClient() {
        clientCount++;
        mainHandler.removeCallbacks(stopWhenIdle);
        if (streamReader == null) {
            ensureRunning();
        }
    }

    /** 客户端断开时调用；无客户端一段时间后自动释放相机。 */
    public synchronized void removeClient() {
        clientCount = Math.max(0, clientCount - 1);
        if (clientCount == 0 && streamReader != null) {
            mainHandler.postDelayed(stopWhenIdle, IDLE_STOP_DELAY_MS);
        }
    }

    /** 录像即将启动时由 CameraRecorder 调用：释放自开相机，改挂录像会话。 */
    public synchronized void releaseForRecorder() {
        if (streamReader == null || usingRecorder) {
            return;
        }
        closeOwnCamera();
        usingRecorder = true;
        CameraRecorder.getInstance(appContext).attachStreamSurface(streamReader);
        lastStatus = "复用录像会话（录像中）";
    }

    /** 录像停止时由 CameraRecorder 调用：仍有客户端则改回自开相机。 */
    public synchronized void onRecorderIdle() {
        if (usingRecorder && streamReader != null) {
            usingRecorder = false;
            if (handler == null) {
                thread = new HandlerThread("droid-stream");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    startOwnCamera();
                }
            });
            lastStatus = "录像已停止，自开相机推流";
        }
    }

    /** 等待比 afterTimestamp 更新的帧，超时返回 null（表示流断流）。 */
    public byte[] waitForFrame(long afterTimestamp, long timeoutMs) throws InterruptedException {
        synchronized (frameLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (latestJpeg == null || frameTimestamp <= afterTimestamp) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    return null;
                }
                frameLock.wait(remain);
            }
            return latestJpeg;
        }
    }

    public long getFrameTimestamp() {
        synchronized (frameLock) {
            return frameTimestamp;
        }
    }

    public int getStreamFps() {
        return streamFps;
    }

    public String describe() {
        return "streamClients=" + clientCount + '\n'
                + "streamSize=" + streamWidth + "x" + streamHeight + '\n'
                + "streamFps=" + streamFps + '\n'
                + "streamUsingRecorderSession=" + usingRecorder + '\n'
                + "streamFrameTimestamp=" + frameTimestamp + '\n'
                + "streamStatus=" + lastStatus + '\n';
    }

    private synchronized void ensureRunning() {
        if (streamReader != null) {
            return;
        }
        streamWidth = RESOLUTIONS[clamp(Prefs.getRecordResolutionIndex(appContext), RESOLUTIONS.length)][0];
        streamHeight = RESOLUTIONS[clamp(Prefs.getRecordResolutionIndex(appContext), RESOLUTIONS.length)][1];
        streamFps = FPS[clamp(Prefs.getRecordFpsIndex(appContext), FPS.length)];

        if (thread == null) {
            thread = new HandlerThread("droid-stream");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        streamReader = ImageReader.newInstance(streamWidth, streamHeight,
                ImageFormat.YUV_420_888, READER_MAX_IMAGES);
        streamReader.setOnImageAvailableListener(onImageAvailable, handler);

        CameraRecorder recorder = CameraRecorder.getInstance(appContext);
        if (recorder.isBusy()) {
            usingRecorder = true;
            recorder.attachStreamSurface(streamReader);
            lastStatus = "复用录像会话（录像中）";
        } else {
            usingRecorder = false;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    startOwnCamera();
                }
            });
        }
    }

    private final Runnable stopWhenIdle = new Runnable() {
        @Override
        public void run() {
            if (clientCount == 0) {
                stopStreaming();
            }
        }
    };

    /** 彻底停止推流并释放全部资源。 */
    private synchronized void stopStreaming() {
        if (usingRecorder) {
            CameraRecorder.getInstance(appContext).detachStreamSurface(streamReader);
            usingRecorder = false;
        }
        closeOwnCamera();
        if (streamReader != null) {
            try {
                streamReader.close();
            } catch (Exception ignored) {
            }
            streamReader = null;
        }
        synchronized (frameLock) {
            latestJpeg = null;
            frameTimestamp = 0;
            lastEncodeTimestamp = 0;
            frameLock.notifyAll();
        }
        lastStatus = "空闲";
    }

    private void startOwnCamera() {
        try {
            CameraManager manager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("无法获取相机服务");
            }
            int wantedFacing = Prefs.getCameraFacingIndex(appContext) == 0
                    ? CameraCharacteristics.LENS_FACING_BACK
                    : CameraCharacteristics.LENS_FACING_FRONT;
            String pickedId = null;
            String fallbackId = null;
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing == null) {
                    continue;
                }
                if (fallbackId == null) {
                    fallbackId = id;
                }
                if (facing == wantedFacing) {
                    pickedId = id;
                    break;
                }
            }
            if (pickedId == null) {
                pickedId = fallbackId;
            }
            if (pickedId == null) {
                throw new IllegalStateException("设备没有可用摄像头");
            }
            manager.openCamera(pickedId, deviceCallback, handler);
            lastStatus = "自开相机推流 id=" + pickedId;
        } catch (Exception e) {
            lastStatus = "推流打开相机失败：" + e.getMessage();
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            device = camera;
            try {
                List<Surface> targets = new java.util.ArrayList<>();
                targets.add(streamReader.getSurface());
                camera.createCaptureSession(targets, sessionCallback, handler);
            } catch (Exception e) {
                lastStatus = "推流会话创建失败：" + e.getMessage();
                closeOwnCamera();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            closeOwnCamera();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            lastStatus = "推流相机错误：" + error;
            closeOwnCamera();
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    if (device == null) {
                        return;
                    }
                    session = configured;
                    try {
                        CaptureRequest.Builder builder =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(streamReader.getSurface());
                        try {
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    new Range<>(streamFps, streamFps));
                        } catch (Exception ignored) {
                            // 部分 HAL 不支持固定帧率范围，忽略
                        }
                        session.setRepeatingRequest(builder.build(), null, handler);
                        lastStatus = "推流中（自开相机）";
                    } catch (Exception e) {
                        lastStatus = "推流请求失败：" + e.getMessage();
                        closeOwnCamera();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    lastStatus = "推流会话配置失败";
                    closeOwnCamera();
                }
            };

    private void closeOwnCamera() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (device != null) {
                device.close();
            }
        } catch (Exception ignored) {
        }
        device = null;
    }

    private final ImageReader.OnImageAvailableListener onImageAvailable =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        // 无客户端时不编码，直接丢弃帧：录像中复用会话时相机仍会持续出帧，
                        // 若每帧都做 YUV→JPEG 会白白占用 CPU。
                        boolean hasClient;
                        synchronized (CameraStream.this) {
                            hasClient = clientCount > 0;
                        }
                        // 相机出帧率常高于设定推流帧率，按间隔跳过多余帧，避免无效编码。
                        long now = System.currentTimeMillis();
                        int minInterval = Math.max(16, 1000 / Math.max(1, streamFps) - 4);
                        boolean due = now - lastEncodeTimestamp >= minInterval;
                        if (!hasClient || !due) {
                            Image dropped = reader.acquireLatestImage();
                            if (dropped != null) {
                                dropped.close();
                            }
                            return;
                        }
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            byte[] jpeg = encodeJpeg(image);
                            lastEncodeTimestamp = now;
                            synchronized (frameLock) {
                                latestJpeg = jpeg;
                                frameTimestamp = now;
                                frameLock.notifyAll();
                            }
                        }
                    } catch (Exception e) {
                        lastStatus = "取帧失败：" + e.getMessage();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            };

    /** YUV_420_888 → NV21 → JPEG（按录像朝向旋转）。 */
    private byte[] encodeJpeg(Image image) throws Exception {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, first);
        byte[] jpeg = first.toByteArray();

        int degrees = ORIENTATIONS[clamp(
                Prefs.getRecordOrientationIndex(appContext), ORIENTATIONS.length)];
        if (degrees != 0) {
            Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (source != null) {
                Matrix matrix = new Matrix();
                matrix.postRotate(degrees);
                Bitmap rotated = Bitmap.createBitmap(source, 0, 0,
                        source.getWidth(), source.getHeight(), matrix, true);
                ByteArrayOutputStream second = new ByteArrayOutputStream();
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, second);
                second.flush();
                jpeg = second.toByteArray();
                if (rotated != source) {
                    source.recycle();
                }
                rotated.recycle();
            }
        }
        return jpeg;
    }

    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[width * height + 2 * (width / 2) * (height / 2)];
        int outPos = 0;
        byte[] rowBuf = new byte[yRowStride];
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(rowBuf, 0, Math.min(yRowStride, yBuffer.remaining()));
            System.arraycopy(rowBuf, 0, nv21, outPos, width);
            outPos += width;
        }
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                // NV21 布局：先 V 后 U
                vBuffer.position(row * vRowStride + col * vPixelStride);
                nv21[outPos++] = vBuffer.get();
                uBuffer.position(row * uRowStride + col * uPixelStride);
                nv21[outPos++] = uBuffer.get();
            }
        }
        return nv21;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
