package com.droidscreentimer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 仅在设置页显示的调试预览：只打开摄像头取景，不启动录像编码，
 * 用于确认摄像头朝向、分辨率等参数是否正确。
 * 录像进行中（摄像头被 CameraRecorder 单例占用）时，改为复用录像会话的预览面。
 */
public class CameraDebugPreview implements TextureView.SurfaceTextureListener {

    private final Context appContext;
    private final TextureView view;
    private final TextView statusText;
    private final CameraRecorder recorder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder requestBuilder;
    private String chosenId;
    private Size chosenSize;
    private boolean usingRecorderPreview;

    public CameraDebugPreview(Context context, TextureView view, TextView statusText) {
        this.appContext = context.getApplicationContext();
        this.view = view;
        this.statusText = statusText;
        this.recorder = CameraRecorder.getInstance(context);
        view.setSurfaceTextureListener(this);
    }

    /** 恢复预览（页面可见时调用）。Surface 未就绪时由回调触发。 */
    public void start() {
        if (usingRecorderPreview) {
            return;
        }
        if (view.isAvailable()) {
            open();
        }
    }

    /** 暂停预览并释放资源（页面不可见时调用）。 */
    public void stop() {
        if (usingRecorderPreview) {
            recorder.detachPreview();
            usingRecorderPreview = false;
            setStatus("预览已停止");
            return;
        }
        closeCamera();
    }

    /** 摄像头朝向配置变化后重开预览。 */
    public void restart() {
        stop();
        start();
    }

    private void open() {
        if (recorder.isBusy()) {
            // 摄像头正被录像会话占用，直接复用其预览输出
            usingRecorderPreview = true;
            recorder.attachPreview(view);
            setStatus("录像进行中：预览来自当前录像会话");
            return;
        }
        if (handler == null) {
            thread = new HandlerThread("droid-debug-preview");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                openOnBackground();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void openOnBackground() {
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
            chosenId = pickedId;

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(pickedId);
            chosenSize = choosePreviewSize(characteristics);
            SurfaceTexture texture = view.getSurfaceTexture();
            if (texture == null) {
                throw new IllegalStateException("预览 Surface 未就绪");
            }
            texture.setDefaultBufferSize(chosenSize.getWidth(), chosenSize.getHeight());
            Surface surface = new Surface(texture);
            manager.openCamera(pickedId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    createSession(surface, chosenSize);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    closeCamera();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    setStatus("打开摄像头失败：error=" + error);
                    closeCamera();
                }
            }, handler);
        } catch (Exception e) {
            setStatus("调试预览失败：" + e.getMessage());
            closeCamera();
        }
    }

    private void createSession(Surface surface, Size size) {
        try {
            List<Surface> targets = new ArrayList<>();
            targets.add(surface);
            requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(surface);
            device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, handler);
                        setStatus("调试预览中 · " + facingLabel() + " · id=" + chosenId
                                + " · " + size.getWidth() + "x" + size.getHeight());
                    } catch (Exception e) {
                        setStatus("预览请求失败：" + e.getMessage());
                        closeCamera();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    setStatus("预览会话配置失败");
                    closeCamera();
                }
            }, handler);
        } catch (Exception e) {
            setStatus("预览会话创建失败：" + e.getMessage());
            closeCamera();
        }
    }

    private void closeCamera() {
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
        requestBuilder = null;
        if (handler != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private String facingLabel() {
        return Prefs.getCameraFacingIndex(appContext) == 0 ? "后置" : "前置";
    }

    private Size choosePreviewSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        if (map != null) {
            long bestScore = Long.MAX_VALUE;
            for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
                long score = Math.abs(s.getWidth() - 640L) * 1000L + Math.abs(s.getHeight() - 480L);
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
        }
        return best != null ? best : new Size(640, 480);
    }

    private void setStatus(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusText != null) {
                    statusText.setText(text);
                }
            }
        });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (!usingRecorderPreview) {
            closeCamera();
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
