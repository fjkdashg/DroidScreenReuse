package com.droidscreentimer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 使用前置摄像头录像：ImageReader 抓帧 → MediaCodec H.264 编码 → MediaMuxer 写 MP4。
 * （模拟器 LEGACY HAL 的 MediaRecorder surface 路径输出黑帧，故弃用 MediaRecorder）
 * - 默认保存到公共目录 Movies/DroidScreenTimer（Android 10+ 走 MediaStore）；
 * - 可通过 SAF 选择任意公共文件夹作为保存位置；
 * - 按 1/3/5/10 分钟分段保存；剩余空间不足 2GB 时删除最旧录像，循环录入；
 * - 前台含预览、后台自动切换为仅录像会话，配合 RecordService 持续录制；
 * - 显式关闭闪光灯/补光灯。
 */
public class CameraRecorder {

    public interface Listener {
        void onRecordingStateChanged(boolean recording);

        void onError(String message);
    }

    private static final int[][] RESOLUTIONS = {
            {320, 240}, {640, 480}, {1280, 720}, {1920, 1080}
    };
    private static final int[] FPS = {15, 24, 30, 60};
    private static final int[] BITRATES = {1, 2, 4, 8};

    private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String DEFAULT_PUBLIC_SUBDIR = "Movies/DroidScreenTimer";
    private static final String MIME_AVC = "video/avc";

    private static final int MODE_PUBLIC_LEGACY = 0;
    private static final int MODE_MEDIASTORE = 1;
    private static final int MODE_SAF = 2;

    private static volatile CameraRecorder instance;

    /** 应用级单例，进程存活期间保持录像资源与状态。 */
    public static CameraRecorder getInstance(Context context) {
        if (instance == null) {
            synchronized (CameraRecorder.class) {
                if (instance == null) {
                    instance = new CameraRecorder(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextureView preview;
    private Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private Surface previewSurface;
    private String cameraId;
    private Size previewSize;
    private Size recordSize;
    private int fps;
    private int bitrate;
    private boolean withAudio;
    private boolean recording;
    private boolean pendingStart;
    private int currentResolutionIndex;
    private long segmentMs = 60_000L;
    private String lastError = "-";

    // 编码链路：ImageReader 抓帧 → MediaCodec → MediaMuxer
    private ImageReader frameReader;
    // 流媒体调试附加输出面（CameraStream 持有，录像中复用同一会话输出）
    private ImageReader streamReader;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int muxerTrack = -1;
    private boolean muxerStarted;
    private long encodedFrameCount;
    private long frameCount;
    private int lastFrameLuma = -1;
    private long firstFramePtsNs = -1;

    private int outputMode = MODE_PUBLIC_LEGACY;
    private Uri safTreeUri;
    private Uri outputUri;
    private ParcelFileDescriptor outputPfd;
    private File legacyOutputFile;

    private CameraRecorder(Context appContext) {
        this.appContext = appContext;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 前台时挂载预览；回到前台需重新挂载。 */
    public synchronized void attachPreview(TextureView view) {
        preview = view;
        if (view != null) {
            view.setSurfaceTextureListener(surfaceListener);
            if (handler != null && recording && previewSurface == null && view.isAvailable()) {
                final TextureView target = view;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        attachPreviewSurface(target);
                    }
                });
            }
        }
    }

    /** 进入后台时卸载预览，会话自动切换为仅录像。 */
    public synchronized void detachPreview() {
        if (preview != null) {
            preview.setSurfaceTextureListener(null);
        }
        preview = null;
        if (handler != null && previewSurface != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (previewSurface != null) {
                        previewSurface.release();
                        previewSurface = null;
                        rebuildSession();
                    }
                }
            });
        }
    }

    /** 程序被关闭时彻底释放。 */
    public synchronized void releaseAll() {
        stop();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (preview != null) {
                    preview.setSurfaceTextureListener(null);
                }
                preview = null;
                listener = null;
            }
        });
    }

    /** 流媒体调试：把 CameraStream 的 ImageReader 面加入当前捕获会话。 */
    public synchronized void attachStreamSurface(final ImageReader reader) {
        streamReader = reader;
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (cameraDevice != null) {
                        rebuildSession();
                    }
                }
            });
        }
    }

    /** 流媒体调试结束：移除附加输出面并重建会话。 */
    public synchronized void detachStreamSurface(final ImageReader reader) {
        if (streamReader == reader) {
            streamReader = null;
        }
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (cameraDevice != null) {
                        rebuildSession();
                    }
                }
            });
        }
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isBusy() {
        return recording || pendingStart;
    }

    public String describeState() {
        StringBuilder sb = new StringBuilder();
        sb.append("recording=").append(recording).append('\n');
        sb.append("pendingStart=").append(pendingStart).append('\n');
        sb.append("previewAttached=").append(preview != null).append('\n');
        sb.append("previewSurfaceActive=").append(previewSurface != null).append('\n');
        sb.append("segmentMs=").append(segmentMs).append('\n');
        sb.append("withAudio=").append(withAudio).append('\n');
        sb.append("fps=").append(fps).append('\n');
        sb.append("bitrateMbps=").append(bitrate).append('\n');
        sb.append("outputMode=").append(outputMode).append('\n');
        sb.append("cameraId=").append(cameraId).append('\n');
        sb.append("cameraFacing=").append(cameraId == null ? "-"
                : (Prefs.getCameraFacingIndex(appContext) == 0 ? "back" : "front")).append('\n');
        sb.append("previewSize=").append(previewSize).append('\n');
        sb.append("recordSize=").append(recordSize).append('\n');
        sb.append("outputUri=").append(outputUri).append('\n');
        sb.append("frameCount=").append(frameCount).append('\n');
        sb.append("lastFrameLuma=").append(lastFrameLuma).append('\n');
        sb.append("encodedFrameCount=").append(encodedFrameCount).append('\n');
        sb.append("muxerStarted=").append(muxerStarted).append('\n');
        sb.append("lastError=").append(lastError).append('\n');
        return sb.toString();
    }

    public synchronized void start(int resolutionIndex, int fpsIndex, int bitrateIndex, boolean audio) {
        if (recording || pendingStart) {
            return;
        }
        pendingStart = true;
        currentResolutionIndex = resolutionIndex;
        fps = FPS[Math.max(0, Math.min(FPS.length - 1, fpsIndex))];
        bitrate = BITRATES[Math.max(0, Math.min(BITRATES.length - 1, bitrateIndex))];
        withAudio = audio;
        segmentMs = Prefs.getRecordSegmentMinutes(appContext) * 60_000L;
        resolveOutputMode();

        if (!ensureFreeSpace()) {
            pendingStart = false;
            notifyError("存储空间不足且无旧录像可清理，无法录像");
            return;
        }

        thread = new HandlerThread("droid-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        openCamera();
    }

    public synchronized void stop() {
        pendingStart = false;
        if (handler == null) {
            return;
        }
        Handler target = handler;
        target.post(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        });
    }

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    if (handler != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                attachPreviewSurface(preview);
                            }
                        });
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    previewSurface = null;
                    if (handler != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                rebuildSession();
                            }
                        });
                    }
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    private void attachPreviewSurface(TextureView view) {
        if (view == null || cameraDevice == null || frameReader == null || previewSize == null) {
            return;
        }
        SurfaceTexture texture = view.getSurfaceTexture();
        if (texture == null || previewSurface != null) {
            return;
        }
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);
        rebuildSession();
    }

    /** 依据预览面是否存在重建捕获会话（前台含预览，后台仅录像）。 */
    private void rebuildSession() {
        if (cameraDevice == null || frameReader == null) {
            return;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        try {
            List<Surface> targets = new ArrayList<>();
            targets.add(frameReader.getSurface());
            if (previewSurface != null) {
                targets.add(previewSurface);
            }
            if (streamReader != null) {
                targets.add(streamReader.getSurface());
            }
            request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface target : targets) {
                request.addTarget(target);
            }
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            cameraDevice.createCaptureSession(targets, sessionCallback, handler);
        } catch (Exception e) {
            notifyError("会话重建失败：" + e.getMessage());
        }
    }

    private void openCamera() {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 流媒体正在占用相机时立即让位，改为挂载到录像会话
                    CameraStream.getInstance(appContext).releaseForRecorder();
                    CameraManager manager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
                    if (manager == null) {
                        throw new IllegalStateException("无法获取相机服务");
                    }
                    int facingIndex = Prefs.getCameraFacingIndex(appContext);
                    int wantedFacing = facingIndex == 0
                            ? CameraCharacteristics.LENS_FACING_BACK
                            : CameraCharacteristics.LENS_FACING_FRONT;
                    String fallbackId = null;
                    for (String id : manager.getCameraIdList()) {
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (facing == null) {
                            continue;
                        }
                        if (fallbackId == null) {
                            fallbackId = id;
                        }
                        if (facing == wantedFacing) {
                            cameraId = id;
                            break;
                        }
                    }
                    if (cameraId == null) {
                        // 没有期望朝向的摄像头时回退到第一个可用摄像头
                        cameraId = fallbackId;
                    }
                    if (cameraId == null) {
                        throw new IllegalStateException("设备没有可用摄像头");
                    }

                    StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    int wantedWidth = RESOLUTIONS[Math.max(0, Math.min(RESOLUTIONS.length - 1,
                            currentResolutionIndex))][0];
                    int wantedHeight = RESOLUTIONS[Math.max(0, Math.min(RESOLUTIONS.length - 1,
                            currentResolutionIndex))][1];
                    previewSize = chooseSize(map, wantedWidth, wantedHeight);
                    recordSize = chooseRecordSize(map, wantedWidth, wantedHeight);

                    manager.openCamera(cameraId, deviceCallback, handler);
                } catch (Exception e) {
                    notifyError("录像启动失败：" + e.getMessage());
                    pendingStart = false;
                }
            }
        });
    }

    private Size chooseSize(StreamConfigurationMap map, int wantedWidth, int wantedHeight) {
        if (map == null) {
            return new Size(wantedWidth, wantedHeight);
        }
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(wantedWidth, wantedHeight);
        }
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() <= wantedWidth && size.getHeight() <= wantedHeight) {
                if (best == null || size.getWidth() * size.getHeight()
                        > best.getWidth() * best.getHeight()) {
                    best = size;
                }
            }
        }
        if (best != null) {
            return best;
        }
        Size smallest = sizes[0];
        for (Size size : sizes) {
            if (size.getWidth() * size.getHeight() < smallest.getWidth() * smallest.getHeight()) {
                smallest = size;
            }
        }
        return smallest;
    }

    private Size chooseRecordSize(StreamConfigurationMap map, int wantedWidth, int wantedHeight) {
        if (map != null) {
            Size[] sizes = map.getOutputSizes(ImageReader.class);
            if (sizes != null && sizes.length > 0) {
                for (Size size : sizes) {
                    if (size.getWidth() == wantedWidth && size.getHeight() == wantedHeight) {
                        return size;
                    }
                }
                Size best = sizes[0];
                int bestDiff = Integer.MAX_VALUE;
                for (Size size : sizes) {
                    int diff = Math.abs(size.getWidth() * size.getHeight()
                            - wantedWidth * wantedHeight);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = size;
                    }
                }
                return best;
            }
        }
        return new Size(wantedWidth, wantedHeight);
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            try {
                prepareEncoder();
            } catch (Exception e) {
                notifyError("录像初始化失败：" + e.getMessage());
                shutdown();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            notifyError("摄像头已断开");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            notifyError("摄像头错误：" + error);
        }
    };

    /** 根据 SAF 配置决定输出模式；SAF 无效时回退到公共目录。 */
    private void resolveOutputMode() {
        outputUri = null;
        outputPfd = null;
        safTreeUri = null;
        legacyOutputFile = null;
        String folder = Prefs.getRecordFolder(appContext);
        if (folder != null && !folder.isEmpty()) {
            try {
                Uri tree = Uri.parse(folder);
                appContext.getContentResolver()
                        .takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                DocumentsContract.getTreeDocumentId(tree);
                safTreeUri = tree;
                outputMode = MODE_SAF;
                return;
            } catch (Exception e) {
                safTreeUri = null;
            }
        }
        outputMode = Build.VERSION.SDK_INT >= 29 ? MODE_MEDIASTORE : MODE_PUBLIC_LEGACY;
    }

    private String nextFileName() {
        return "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new Date()) + ".mp4";
    }

    /** 创建输出目标并返回可写文件（fd 或路径）。 */
    private void openOutput(String name) throws Exception {
        ContentResolver resolver = appContext.getContentResolver();
        if (outputMode == MODE_SAF && safTreeUri != null && Build.VERSION.SDK_INT >= 26) {
            String treeDocId = DocumentsContract.getTreeDocumentId(safTreeUri);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(safTreeUri, treeDocId);
            outputUri = DocumentsContract.createDocument(resolver, parentUri, "video/mp4", name);
            if (outputUri == null) {
                throw new IllegalStateException("无法在所选文件夹创建录像文件");
            }
            outputPfd = resolver.openFileDescriptor(outputUri, "rw");
            return;
        }
        if (outputMode == MODE_MEDIASTORE) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, DEFAULT_PUBLIC_SUBDIR);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) {
                throw new IllegalStateException("无法写入公共媒体库");
            }
            outputPfd = resolver.openFileDescriptor(outputUri, "rw");
            return;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "DroidScreenTimer");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建公共录像目录");
        }
        legacyOutputFile = new File(dir, name);
        outputUri = Uri.fromFile(legacyOutputFile);
    }

    private MediaMuxer createMuxer() throws Exception {
        int[] degrees = {0, 90, 180, 270};
        int orientation = degrees[Math.max(0, Math.min(3, Prefs.getRecordOrientationIndex(appContext)))];
        MediaMuxer mediaMuxer;
        if (outputPfd != null && Build.VERSION.SDK_INT >= 26) {
            mediaMuxer = new MediaMuxer(outputPfd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            File file = legacyOutputFile != null ? legacyOutputFile
                    : new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES + "/DroidScreenTimer"), nextFileName());
            mediaMuxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        mediaMuxer.setOrientationHint(orientation);
        return mediaMuxer;
    }

    /** 段结束后收尾：关闭输出目标、在媒体库中发布文件。 */
    private void finalizeOutput() {
        if (outputPfd != null) {
            try {
                outputPfd.close();
            } catch (Exception ignored) {
            }
            outputPfd = null;
        }
        if (outputUri != null && outputMode == MODE_MEDIASTORE && Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                appContext.getContentResolver().update(outputUri, values, null, null);
            } catch (Exception ignored) {
            }
        }
        outputUri = null;
        legacyOutputFile = null;
    }

    /** 初始化编码链路：muxer + MediaCodec(H264) + ImageReader 抓帧流。 */
    private void prepareEncoder() throws Exception {
        openOutput(nextFileName());
        muxer = createMuxer();
        muxerTrack = -1;
        muxerStarted = false;
        encodedFrameCount = 0;
        frameCount = 0;
        firstFramePtsNs = -1;

        MediaCodecInfo codecInfo = selectAvcEncoder();
        if (codecInfo == null) {
            throw new IllegalStateException("设备没有可用的 H.264 编码器");
        }
        encoder = MediaCodec.createByCodecName(codecInfo.getName());
        MediaFormat format = MediaFormat.createVideoFormat(MIME_AVC,
                recordSize.getWidth(), recordSize.getHeight());
        int colorFormat = selectColorFormat(codecInfo);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        frameReader = ImageReader.newInstance(recordSize.getWidth(), recordSize.getHeight(),
                ImageFormat.YUV_420_888, 3);
        frameReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        frameCount++;
                        lastFrameLuma = sampleYPlaneLuma(image);
                        feedEncoder(image);
                    }
                } catch (Exception e) {
                    lastError = "编码帧失败：" + e.getMessage();
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }
        }, handler);

        List<Surface> targets = new ArrayList<>();
        targets.add(frameReader.getSurface());
        if (previewSurface != null) {
            targets.add(previewSurface);
        }
        if (streamReader != null) {
            targets.add(streamReader.getSurface());
        }
        request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        for (Surface target : targets) {
            request.addTarget(target);
        }
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);

        cameraDevice.createCaptureSession(targets, sessionCallback, handler);
    }

    private MediaCodecInfo selectAvcEncoder() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (MIME_AVC.equals(type)) {
                    return info;
                }
            }
        }
        return null;
    }

    private int selectColorFormat(MediaCodecInfo codecInfo) {
        MediaCodecInfo.CodecCapabilities capabilities =
                codecInfo.getCapabilitiesForType(MIME_AVC);
        int fallback = -1;
        for (int format : capabilities.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return format;
            }
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                fallback = format;
            }
        }
        return fallback >= 0 ? fallback
                : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession cameraSession) {
                    session = cameraSession;
                    try {
                        session.setRepeatingRequest(request.build(), null, handler);
                        if (!recording) {
                            recording = true;
                            pendingStart = false;
                            if (handler != null) {
                                handler.postDelayed(segmentTick, segmentMs);
                            }
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    notifyStateChanged(true);
                                }
                            });
                        }
                    } catch (Exception e) {
                        notifyError("录像开始失败：" + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraSession) {
                    notifyError("摄像头会话配置失败");
                }
            };

    private void feedEncoder(Image image) {
        if (encoder == null || muxer == null) {
            return;
        }
        byte[] nv12 = imageToNv12(image);
        long ptsNs = image.getTimestamp();
        if (firstFramePtsNs < 0) {
            firstFramePtsNs = ptsNs;
        }
        long ptsUs = (ptsNs - firstFramePtsNs) / 1000L;

        int inIndex = encoder.dequeueInputBuffer(10_000);
        if (inIndex >= 0) {
            ByteBuffer input = encoder.getInputBuffer(inIndex);
            if (input != null) {
                input.clear();
                input.put(nv12);
                encoder.queueInputBuffer(inIndex, 0, nv12.length, ptsUs, 0);
            }
        }
        drainEncoder();
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (encoder != null) {
            int outIndex = encoder.dequeueOutputBuffer(info, 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxer != null && muxerTrack == -1) {
                    muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
                continue;
            }
            if (outIndex < 0) {
                continue;
            }
            ByteBuffer output = encoder.getOutputBuffer(outIndex);
            boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!isConfig && muxerStarted && output != null && info.size > 0) {
                output.position(info.offset);
                output.limit(info.offset + info.size);
                try {
                    muxer.writeSampleData(muxerTrack, output, info);
                    encodedFrameCount++;
                } catch (Exception e) {
                    lastError = "写帧失败：" + e.getMessage();
                }
            }
            encoder.releaseOutputBuffer(outIndex, false);
        }
    }

    /** YUV_420_888 转 NV12（H.264 软编码器输入布局）。 */
    private byte[] imageToNv12(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int uRowStride = uPlane.getRowStride();
        int uPixelStride = uPlane.getPixelStride();
        int vRowStride = vPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();

        byte[] nv12 = new byte[width * height * 3 / 2];
        int pos = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                nv12[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride);
            }
        }
        int uvRows = height / 2;
        int uvCols = width / 2;
        for (int row = 0; row < uvRows; row++) {
            for (int col = 0; col < uvCols; col++) {
                nv12[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
                nv12[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
            }
        }
        return nv12;
    }

    /** 抽样统计 Y 平面平均亮度（0-255），全黑帧接近 0-16。 */
    private int sampleYPlaneLuma(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            int size = buffer.remaining();
            if (size <= 0) {
                return -1;
            }
            long sum = 0;
            int count = 0;
            int step = Math.max(1, size / 2000);
            for (int i = 0; i < size; i += step) {
                sum += buffer.get(i) & 0xFF;
                count++;
            }
            return count == 0 ? -1 : (int) (sum / count);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 按配置时长分段：结束当前文件并立即开始下一段。 */
    private final Runnable segmentTick = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            closeCapture();
            if (!ensureFreeSpace()) {
                shutdown();
                notifyError("存储空间不足且无旧录像可清理，录像停止");
                return;
            }
            pendingStart = true;
            openCamera();
        }
    };

    /** 关闭录制相关资源（保留相机线程），供分段重启使用。 */
    private void closeCapture() {
        if (handler != null) {
            handler.removeCallbacks(segmentTick);
        }
        if (session != null) {
            session.close();
            session = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (frameReader != null) {
            try {
                frameReader.close();
            } catch (Exception ignored) {
            }
            frameReader = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            try {
                encoder.release();
            } catch (Exception ignored) {
            }
            encoder = null;
        }
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
        muxerTrack = -1;
        muxerStarted = false;
        recording = false;
        finalizeOutput();
    }

    /** 完全停止：释放录制资源并退出相机线程。 */
    private void shutdown() {
        closeCapture();
        pendingStart = false;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                notifyStateChanged(false);
            }
        });
        if (thread != null) {
            HandlerThread old = thread;
            thread = null;
            handler = null;
            old.quitSafely();
        }
        // 流媒体仍有客户端时改回自开相机继续推流
        CameraStream.getInstance(appContext).onRecorderIdle();
    }

    /** 当前存储卷的剩余空间。 */
    private long freeSpace() {
        try {
            return Environment.getExternalStorageDirectory().getFreeSpace();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 检查剩余空间，不足 2GB 时按时间从旧到新删除本应用的录像文件（循环录入）。
     *
     * @return false 表示无法腾出空间
     */
    private boolean ensureFreeSpace() {
        long free = freeSpace();
        if (free >= MIN_FREE_BYTES) {
            return true;
        }
        List<Object> oldEntries = listOldVideos();
        for (Object entry : oldEntries) {
            long freed;
            boolean ok;
            if (entry instanceof File) {
                File file = (File) entry;
                freed = file.length();
                ok = file.delete();
            } else if (entry instanceof Uri) {
                Uri uri = (Uri) entry;
                freed = querySize(uri);
                ok = deleteVideoEntry(uri);
            } else {
                continue;
            }
            if (!ok) {
                return false;
            }
            free += freed;
            if (free >= MIN_FREE_BYTES) {
                return true;
            }
        }
        return free >= MIN_FREE_BYTES;
    }

    /** 列出当前模式下所有旧录像，按时间从旧到新排序。元素为 File 或 Uri。 */
    private List<Object> listOldVideos() {
        List<Object> result = new ArrayList<>();
        ContentResolver resolver = appContext.getContentResolver();
        if (outputMode == MODE_SAF && safTreeUri != null) {
            try {
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        safTreeUri, DocumentsContract.getTreeDocumentId(safTreeUri));
                Cursor cursor = resolver.query(childrenUri,
                        new String[]{
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                        }, null, null, null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String mime = cursor.getString(1);
                        if (mime != null && mime.startsWith("video/")) {
                            result.add(DocumentsContract.buildDocumentUriUsingTree(
                                    safTreeUri, cursor.getString(0)));
                        }
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {
            }
        } else if (outputMode == MODE_MEDIASTORE && Build.VERSION.SDK_INT >= 29) {
            try {
                Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                Cursor cursor = resolver.query(collection,
                        new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_MODIFIED},
                        MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                        new String[]{DEFAULT_PUBLIC_SUBDIR + "%"},
                        MediaStore.Video.Media.DATE_MODIFIED + " ASC");
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        result.add(android.content.ContentUris.withAppendedId(collection, id));
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "DroidScreenTimer");
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp4")) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }

    private long querySize(Uri uri) {
        try {
            ParcelFileDescriptor pfd = appContext.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                long size = pfd.getStatSize();
                pfd.close();
                return size;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private boolean deleteVideoEntry(Uri uri) {
        try {
            if (outputMode == MODE_SAF) {
                return DocumentsContract.deleteDocument(appContext.getContentResolver(), uri);
            }
            return appContext.getContentResolver().delete(uri, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyStateChanged(final boolean state) {
        Listener current = listener;
        if (current != null) {
            current.onRecordingStateChanged(state);
        }
    }

    private void notifyError(final String message) {
        lastError = message;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onError(message);
                }
            }
        });
    }
}
