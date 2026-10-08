package com.hongqi.flighting.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import android.provider.Settings;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.hongqi.flighting.display.ClusterMirrorCapabilityPolicy;
import com.hongqi.flighting.display.InstrumentDisplayMetadata;
import com.hongqi.flighting.display.InstrumentDisplayPolicy;
import com.hongqi.flighting.display.InstrumentDisplaySelection;
import com.hongqi.flighting.display.InstrumentDisplayState;
import com.hongqi.flighting.display.MirrorDisplayLifecycle;
import com.hongqi.flighting.display.MirrorResourceLifecycle;
import com.hongqi.flighting.ui.HongqiFeatureActivity;
import com.hongqi.flighting.util.ActivationGuard;
import com.hongqi.flighting.util.ConfigManager;
import com.hongqi.flighting.util.InAppLogger;

import java.util.ArrayList;
import java.util.List;

public class ScreenCaptureService extends Service {
    public static final String ACTION_TOGGLE =
        "com.hongqi.flighting.ACTION_CAST_TOGGLE";
    public static final String ACTION_STOP =
        "com.hongqi.flighting.ACTION_CAST_STOP";
    public static final String ACTION_START =
        "com.hongqi.flighting.ACTION_CAST_START";
    public static final String ACTION_QUERY =
        "com.hongqi.flighting.ACTION_CAST_QUERY";
    public static final String EXTRA_AUTO_START =
        "com.hongqi.flighting.EXTRA_AUTO_START_CAST";

    private static final String TAG = "ScreenCaptureService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "carlife_mirror_channel";

    /** 进程内单例状态，供 SidebarService 等跨组件读。 */
    private static volatile boolean sMirrorRunning = false;
    public static boolean isMirrorRunning() {
        return sMirrorRunning;
    }

    /** Every start requests a fresh projection grant; stop uses the existing foreground service. */
    public static void toggleCast(Context context) {
        if (context == null || !ActivationGuard.isSecureRunning(context)) return;
        if (sMirrorRunning) {
            context.startService(new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP));
        } else {
            context.startActivity(com.hongqi.flighting.integration.FeatureNavigation.castIntent(context));
        }
    }
    public static final String ACTION_START_GRANTED = "com.hongqi.flighting.ACTION_CAST_START_GRANTED";
    public static final String EXTRA_RESULT_CODE = "hongqi_projection_result_code";
    public static final String EXTRA_RESULT_DATA = "hongqi_projection_result_data";
    public static final String EXTRA_SESSION_TOKEN = "hongqi_projection_session_token";
    private static final String DISPLAY_PREREQUISITE_MESSAGE = "请先开启原车导航仪表投屏，再启动 CarlifeMirror";
    private static final int INSTRUMENT_OUTPUT_WIDTH = 1920;
    private static final int INSTRUMENT_OUTPUT_HEIGHT = 720;
    private static final int MIRROR_FPS = 30;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object resourceLock = new Object();
    private final ClusterMirrorCapabilityPolicy capabilityPolicy =
        new ClusterMirrorCapabilityPolicy();
    private final MirrorResourceLifecycle resourceLifecycle = new MirrorResourceLifecycle();
    private final InstrumentDisplayPolicy displayPolicy = new InstrumentDisplayPolicy();
    private final MirrorDisplayLifecycle displayLifecycle = new MirrorDisplayLifecycle(
        displayPolicy
    );

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private MirrorPresentation presentation;
    private Surface activeSurface;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private int displayListenerToken = -1;
    private ConfigManager configManager;
    private ScreenStretchRenderer stretchRenderer;
    private ScreenVideoRecorder videoRecorder;
    // EGL startup and codec/renderer joins must never block the car's UI thread.
    private final java.util.concurrent.ExecutorService renderWorker =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "HongqiMirrorLifecycle"));

    public class LocalBinder extends Binder {
        public ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        configManager = new ConfigManager(this);
        LyricsProvider.configure(configManager.getLyricProviderOrder());
        LyricsProvider.initCache(this);
        createNotificationChannel();
        InAppLogger.i(TAG, "本地镜像服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMirror();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START_GRANTED.equals(intent.getAction())) {
            // A system restart never resurrects an expired capture permission.
            stopMirror();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent.class);
        int token = intent.getIntExtra(EXTRA_SESSION_TOKEN, -1);
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopMirror();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
            if (!ActivationGuard.isSecureRunning(this) || token < 0 || token != getActiveToken()) {
                stopMirror();
                return START_NOT_STICKY;
            }
            onProjectionResult(token, resultCode, data);
        } catch (RuntimeException e) {
            InAppLogger.e(TAG, "无法启动投屏前台服务", e);
            stopMirror();
        }
        return START_NOT_STICKY;
    }

public boolean beginStart() {
        if (!ActivationGuard.isSecureRunning(this)) return false;
        ClusterMirrorCapabilityPolicy.Decision capability = capabilityPolicy.evaluate();
        if (capability.getOutcome() == ClusterMirrorCapabilityPolicy.Outcome.UNSUPPORTED) {
            InAppLogger.w(TAG, "Cluster mirror unsupported: " + capability.getReason());
            return false;
        }
        boolean saveToFile = configManager != null && configManager.isSimulatorPreview();
        if (!saveToFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            InAppLogger.w(TAG, "缺少悬浮窗权限（SYSTEM_ALERT_WINDOW），拒绝启动镜像");
            return false;
        }

        int token = resourceLifecycle.begin();
        if (token < 0) {
            InAppLogger.w(TAG, "镜像已在启动、运行或停止中：" + buildDebugState());
            return false;
        }

        displayPolicy.setAllowDefaultDisplay(configManager != null && configManager.isSimulatorPreview());
        if (!displayLifecycle.begin(token)) {
            failStart(token, DISPLAY_PREREQUISITE_MESSAGE, null);
            return false;
        }
        if (!registerDisplayListener(token)) {
            failStart(token, DISPLAY_PREREQUISITE_MESSAGE, null);
            return false;
        }
        if (resolveTargetDisplay(token) == null) {
            failStart(token, DISPLAY_PREREQUISITE_MESSAGE, null);
            return false;
        }
        InAppLogger.i(TAG, "开始本地镜像流程 token=" + token);
        return true;
    }

    public int getActiveToken() {
        return resourceLifecycle.getActiveToken();
    }

    public boolean isRunning() {
        return resourceLifecycle.getState() == MirrorResourceLifecycle.State.RUNNING;
    }

    public boolean isBusy() {
        return resourceLifecycle.getState() != MirrorResourceLifecycle.State.IDLE;
    }

    public void cancelPendingStart(int token) {
        stopMirrorInternal(token);
        InAppLogger.w(TAG, "屏幕录制授权被取消 token=" + token);
    }

    public void onProjectionResult(int resultCode, Intent data) {
        onProjectionResult(getActiveToken(), resultCode, data);
    }

    public void onProjectionResult(int token, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            cancelPendingStart(token);
            return;
        }
        if (!isCurrentStart(token)) {
            InAppLogger.w(TAG, "忽略过期的屏幕录制授权结果 token=" + token);
            return;
        }

        Display display = resolveTargetDisplay(token);
        if (display == null) {
            failStart(token, DISPLAY_PREREQUISITE_MESSAGE, null);
            return;
        }
        continueProjectionStart(token, resultCode, data, display);
    }

    public void stopMirror() {
        stopMirrorInternal();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    public String getDebugState() {
        return buildDebugState();
    }

    private void continueProjectionStart(int token, int resultCode, Intent data, Display display) {
        if (!isCurrentStart(token)) {
            return;
        }

        try {
            if (projectionManager == null) {
                throw new IllegalStateException("无法获取 MediaProjectionManager");
            }

            MediaProjection projection = projectionManager.getMediaProjection(resultCode, data);
            if (projection == null) {
                failStart(token, "无法获取 MediaProjection", null);
                return;
            }
            MediaProjection.Callback callback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> stopMirrorInternal(token));
                }
            };
            try {
                projection.registerCallback(callback, mainHandler);
            } catch (RuntimeException exception) {
                releaseProjection(projection, callback);
                failStart(token, "启动本地镜像失败", exception);
                return;
            }
            if (!publishProjection(token, projection, callback)) {
                releaseProjection(projection, callback);
                return;
            }

            DisplayMetrics sourceMetrics = getSourceDisplayMetrics();
            int sourceWidth = Math.max(1, sourceMetrics.widthPixels);
            int sourceHeight = Math.max(1, sourceMetrics.heightPixels);
            int sourceDensity = Math.max(1, sourceMetrics.densityDpi);
            InAppLogger.i(TAG, "屏幕录制已授权，目标屏 ID=" + display.getDisplayId()
                + "，源尺寸=" + sourceWidth + "x" + sourceHeight + " dpi=" + sourceDensity);

            if (configManager.isSimulatorPreview()) {
                startFileRecording(token, sourceWidth, sourceHeight, sourceDensity);
            } else {
                int[] instrumentSize = resolveInstrumentOutputSize(display);
                int outputWidth = instrumentSize[0];
                int outputHeight = instrumentSize[1];

                InAppLogger.i(TAG, "准备显示多区域合并镜像 output=" + outputWidth + "x" + outputHeight);
                showMirrorPresentation(token, display, sourceWidth, sourceHeight, sourceDensity, outputWidth, outputHeight);
            }
        } catch (Exception e) {
            failStart(token, "启动本地镜像失败", e);
        }
    }

    private void showMirrorPresentation(
        int token,
        Display display,
        int sourceWidth,
        int sourceHeight,
        int sourceDensity,
        int outputWidth,
        int outputHeight
    ) {
        if (!isCurrentStart(token)) {
            return;
        }
        if (!isResolvedTargetDisplay(token, display)) {
            failStart(token, DISPLAY_PREREQUISITE_MESSAGE, null);
            return;
        }

        MirrorPresentation mirrorPresentation = null;
        try {
            mirrorPresentation = new MirrorPresentation(this, display, outputWidth, outputHeight);
            mirrorPresentation.setSurfaceReadyListener(surface -> mainHandler.post(
                () -> startVirtualDisplay(token, surface, display, sourceWidth, sourceHeight, sourceDensity, outputWidth, outputHeight)
            ));
            mirrorPresentation.setSurfaceDestroyedListener(() -> mainHandler.post(
                () -> stopMirrorInternal(token)
            ));
mirrorPresentation.setOnDismissListener(dialog -> stopMirrorInternal(token));
            // 方案A实验：镜像窗口提升为 TYPE_APPLICATION_OVERLAY(2038)，与原车导航同层覆盖
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mirrorPresentation.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            mirrorPresentation.show();
            if (!publishPresentation(token, mirrorPresentation)) {
                dismissPresentation(mirrorPresentation);
            }
        } catch (RuntimeException exception) {
            if (mirrorPresentation != null) {
                dismissPresentation(mirrorPresentation);
            }
            failStart(token, "创建镜像窗口失败", exception);
        }
    }

    private void startFileRecording(int token, int sourceWidth, int sourceHeight, int sourceDensity) {
        prepareRenderer(token, null, null, sourceWidth, sourceHeight, sourceDensity,
            INSTRUMENT_OUTPUT_WIDTH, INSTRUMENT_OUTPUT_HEIGHT, true);
    }

    private void startVirtualDisplay(int token, Surface renderTargetSurface, Display display,
        int sourceWidth, int sourceHeight, int sourceDensity, int outputWidth, int outputHeight) {
        MirrorResourceLifecycle.SurfaceDecision decision;
        synchronized (resourceLock) {
            decision = resourceLifecycle.onSurfaceReady(token);
            if (!decision.shouldReleaseSurface()) activeSurface = renderTargetSurface;
        }
        if (decision.shouldReleaseSurface()) { releaseSurface(renderTargetSurface); return; }
        if (!decision.shouldCreateVirtualDisplay()) return;
        if (mediaProjection == null || !renderTargetSurface.isValid()) {
            failStart(token, "镜像 Surface 或 MediaProjection 不可用", null);
            return;
        }
        prepareRenderer(token, renderTargetSurface, display, sourceWidth, sourceHeight,
            sourceDensity, outputWidth, outputHeight, false);
    }

    private void prepareRenderer(int token, Surface targetSurface, Display display,
        int sourceWidth, int sourceHeight, int sourceDensity, int outputWidth,
        int outputHeight, boolean recording) {
        ScreenStretchRenderer.RegionConfig first = buildRegionConfig(1);
        ScreenStretchRenderer.RegionConfig second = buildRegionConfig(2);
        ScreenStretchRenderer.RegionConfig third = buildRegionConfig(3);
        renderWorker.execute(() -> {
            ScreenVideoRecorder recorder = recording
                ? new ScreenVideoRecorder(outputWidth, outputHeight, MIRROR_FPS) : null;
            ScreenStretchRenderer renderer = null;
            boolean published = false;
            try {
                Surface output = recorder != null ? recorder.start(this) : targetSurface;
                renderer = new ScreenStretchRenderer(output, getAssets(), sourceWidth, sourceHeight,
                    outputWidth, outputHeight, first, second, third, MIRROR_FPS);
                synchronized (resourceLock) {
                    if (resourceLifecycle.isStarting(token)) {
                        if (recording) resourceLifecycle.acquireSurface(token);
                        stretchRenderer = renderer;
                        videoRecorder = recorder;
                        published = true;
                    }
                }
                if (!published) {
                    renderer.stop();
                    if (recorder != null) recorder.stop();
                    return;
                }
                Surface input = renderer.startAndWaitForInputSurface();
                ScreenVideoRecorder completedRecorder = recorder;
                mainHandler.post(() -> finishRendererStart(token, input, display, sourceWidth,
                    sourceHeight, sourceDensity, outputWidth, outputHeight, completedRecorder));
            } catch (Exception error) {
                // Published resources belong to stopMirrorInternal; local failures release locally.
                if (!published) {
                    if (renderer != null) renderer.stop();
                    if (recorder != null) recorder.stop();
                }
                mainHandler.post(() -> failStart(token, "创建镜像渲染器失败", error));
            }
        });
    }

    private void finishRendererStart(int token, Surface input, Display display,
        int sourceWidth, int sourceHeight, int sourceDensity, int outputWidth,
        int outputHeight, ScreenVideoRecorder recorder) {
        if (!isCurrentStart(token)) return;
        try {
            MediaProjection projection;
            synchronized (resourceLock) { projection = mediaProjection; }
            if (projection == null) throw new IllegalStateException("MediaProjection 不可用");
            VirtualDisplay created = projection.createVirtualDisplay("HongQiMirror",
                Math.max(1, sourceWidth), Math.max(1, sourceHeight), Math.max(1, sourceDensity),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, input, null, mainHandler);
            if (created == null) throw new IllegalStateException("无法创建 HongQiMirror VirtualDisplay");
            if (!publishVirtualDisplay(token, created)) { created.release(); return; }
            if (!resourceLifecycle.markRunning(token) || !displayLifecycle.markRunning(token)) {
                stopMirrorInternal(token); return;
            }
            sMirrorRunning = true;
            sendBroadcast(new Intent(ACTION_QUERY).setPackage(getPackageName()).putExtra("running", true));
            if (recorder != null) {
                Toast.makeText(this, "开始录制，保存到 " + recorder.getOutputPath(), Toast.LENGTH_LONG).show();
            } else {
                InAppLogger.i(TAG, "多区域镜像已启动：source=" + sourceWidth + "x" + sourceHeight
                    + " output=" + outputWidth + "x" + outputHeight + " target=" + display.getDisplayId());
            }
        } catch (Exception error) {
            failStart(token, "创建虚拟显示失败", error);
        }
    }

    private ScreenStretchRenderer.RegionConfig buildRegionConfig(int index) {
        if (index == 1) {
            return new ScreenStretchRenderer.RegionConfig(
                true, "screen", "",
                configManager.getStretchMode(),
                configManager.getCropStartX(), configManager.getCropEndX(),
                configManager.getCropStartY(), configManager.getCropEndY(),
                configManager.getOutputWidthPercent(), configManager.getOutputHorizontalAlign());
        }
        if (index == 2) {
            return new ScreenStretchRenderer.RegionConfig(
                configManager.isRegion2Enabled(), configManager.getRegion2SourceType(),
                resolveImageSource(configManager.getRegion2ImageLocalPath(), configManager.getRegion2ImageUrl()),
                configManager.getRegion2StretchMode(),
                configManager.getRegion2CropStartX(), configManager.getRegion2CropEndX(),
                configManager.getRegion2CropStartY(), configManager.getRegion2CropEndY(),
                configManager.getRegion2OutputWidthPercent(), configManager.getRegion2OutputHorizontalAlign());
        }
        return new ScreenStretchRenderer.RegionConfig(
            configManager.isRegion3Enabled(), configManager.getRegion3SourceType(),
            resolveImageSource(configManager.getRegion3ImageLocalPath(), configManager.getRegion3ImageUrl()),
            configManager.getRegion3StretchMode(),
            configManager.getRegion3CropStartX(), configManager.getRegion3CropEndX(),
            configManager.getRegion3CropStartY(), configManager.getRegion3CropEndY(),
            configManager.getRegion3OutputWidthPercent(), configManager.getRegion3OutputHorizontalAlign());
    }

    /** 本地缓存存在则优先使用本地文件，否则回退到服务端 URL。 */
    private String resolveImageSource(String localPath, String url) {
        return (localPath != null && !localPath.isEmpty()) ? localPath : url;
    }

    private int[] resolveInstrumentOutputSize(Display display) {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return new int[]{metrics.widthPixels, metrics.heightPixels};
            }
        } catch (RuntimeException exception) {
            InAppLogger.w(TAG, "读取仪表屏尺寸失败，使用默认输出尺寸", exception);
        }
        return new int[]{INSTRUMENT_OUTPUT_WIDTH, INSTRUMENT_OUTPUT_HEIGHT};
    }

    private void failStart(int token, String message, Exception error) {
        if (!isCurrentStart(token)) {
            return;
        }
        if (error == null) {
            InAppLogger.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
            InAppLogger.e(TAG, message, error);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        stopMirrorInternal(token);
    }

    private boolean isCurrentStart(int token) {
        return resourceLifecycle.isStarting(token);
    }

    private Display resolveTargetDisplay(int token) {
        if (displayManager == null) {
            return null;
        }
        final Display[] displays;
        final InstrumentDisplaySelection selection;
        try {
            displays = displayManager.getDisplays();
            selection = displayLifecycle.resolveStart(token, toMetadata(displays));
        } catch (RuntimeException exception) {
            InAppLogger.w(TAG, "读取仪表显示屏失败", exception);
            return null;
        }
        if (selection.getOutcome() != InstrumentDisplaySelection.Outcome.FOUND) {
            return null;
        }
        int selectedDisplayId = selection.getDisplay().getDisplayId();
        for (Display display : displays) {
            if (display != null && display.getDisplayId() == selectedDisplayId) {
                return display;
            }
        }
        return null;
    }

    private boolean isResolvedTargetDisplay(int token, Display display) {
        try {
            InstrumentDisplaySelection selection = displayPolicy.select(
                toMetadata(new Display[]{display})
            );
            return displayLifecycle.isActive(token)
                && selection.getOutcome() == InstrumentDisplaySelection.Outcome.FOUND
                && selection.getDisplay().getDisplayId() == displayLifecycle.getTargetDisplayId();
        } catch (RuntimeException exception) {
            InAppLogger.w(TAG, "复核仪表显示屏失败", exception);
            return false;
        }
    }

    private List<InstrumentDisplayMetadata> toMetadata(Display[] displays) {
        List<InstrumentDisplayMetadata> metadata = new ArrayList<>();
        for (Display display : displays) {
            if (display != null) {
                metadata.add(new InstrumentDisplayMetadata(
                    display.getDisplayId(),
                    display.getName(),
                    display.getDisplayId() == Display.DEFAULT_DISPLAY,
                    display.isValid(),
                    toInstrumentDisplayState(display.getState()),
                    display.getFlags(),
                    Build.VERSION.SDK_INT >= 23 ? display.getMode().getModeId() : 0
                ));
            }
        }
        return metadata;
    }

    private InstrumentDisplayState toInstrumentDisplayState(int displayState) {
        if (displayState == Display.STATE_ON) {
            return InstrumentDisplayState.ON;
        }
        if (displayState == Display.STATE_OFF) {
            return InstrumentDisplayState.OFF;
        }
        return InstrumentDisplayState.UNKNOWN;
    }

    private boolean registerDisplayListener(int token) {
        if (displayManager == null || displayListenerToken == token) {
            return displayListenerToken == token;
        }
        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                onDisplayEvent(token);
            }

            @Override
            public void onDisplayChanged(int displayId) {
                onDisplayEvent(token);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                onDisplayEvent(token);
            }
        };
        try {
            displayManager.registerDisplayListener(listener, mainHandler);
        } catch (RuntimeException exception) {
            displayLifecycle.listenerRegistrationFailed(token);
            InAppLogger.w(TAG, "注册仪表显示屏监听失败", exception);
            return false;
        }
        if (!displayLifecycle.listenerRegistered(token)) {
            try {
                displayManager.unregisterDisplayListener(listener);
            } catch (RuntimeException exception) {
                InAppLogger.w(TAG, "注销过期仪表显示屏监听失败", exception);
            }
            return false;
        }
        displayListener = listener;
        displayListenerToken = token;
        return true;
    }

    private void onDisplayEvent(int token) {
        mainHandler.post(() -> {
            try {
                if (displayManager != null && displayLifecycle.onDisplayEvent(
                    token,
                    toMetadata(displayManager.getDisplays())
                )) {
                    stopMirrorInternal(token);
                }
            } catch (RuntimeException exception) {
                InAppLogger.w(TAG, "处理仪表显示屏变化失败", exception);
                stopMirrorInternal(token);
            }
        });
    }

    private void unregisterDisplayListener(int expectedToken) {
        if (expectedToken >= 0 && displayListenerToken != expectedToken) {
            return;
        }
        DisplayManager.DisplayListener listener = displayListener;
        int listenerToken = displayListenerToken;
        displayListener = null;
        displayListenerToken = -1;
        displayLifecycle.detachListener(listenerToken);
        if (displayManager == null || listener == null) {
            return;
        }
        try {
            displayManager.unregisterDisplayListener(listener);
        } catch (RuntimeException exception) {
            InAppLogger.w(TAG, "注销仪表显示屏监听失败", exception);
        }
    }

    private DisplayMetrics getSourceDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            Display defaultDisplay = windowManager.getDefaultDisplay();
            if (defaultDisplay != null) {
                defaultDisplay.getRealMetrics(metrics);
            }
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics = getResources().getDisplayMetrics();
        }
        return metrics;
    }

    private void stopMirrorInternal() {
        stopMirrorInternal(-1);
    }

    private void stopMirrorInternal(int expectedToken) {
        int token = expectedToken >= 0 ? expectedToken : resourceLifecycle.getActiveToken();
        MirrorResourceLifecycle.ReleaseActions actions;
        VirtualDisplay releasedVirtualDisplay;
        MirrorPresentation dismissedPresentation;
        MediaProjection releasedProjection;
        MediaProjection.Callback releasedCallback;
        Surface releasedSurface;
        ScreenStretchRenderer releasedRenderer;
        ScreenVideoRecorder releasedRecorder;
        synchronized (resourceLock) {
            actions = resourceLifecycle.stop(token);
            if (!actions.ownsStop()) {
                return;
            }
            releasedVirtualDisplay = virtualDisplay;
            virtualDisplay = null;
            dismissedPresentation = presentation;
            presentation = null;
            releasedProjection = mediaProjection;
            releasedCallback = projectionCallback;
            mediaProjection = null;
            projectionCallback = null;
            releasedSurface = activeSurface;
            activeSurface = null;
            releasedRenderer = stretchRenderer;
            stretchRenderer = null;
            releasedRecorder = videoRecorder;
            videoRecorder = null;
        }
        unregisterDisplayListener(token);
        displayLifecycle.stop(token);
        if (configManager != null) configManager.setEnabled(false);
        stopForeground(true);
        stopSelf();

        if (actions.shouldDismissPresentation() && dismissedPresentation != null) {
            try {
                dismissedPresentation.setSurfaceReadyListener(null);
                dismissedPresentation.setSurfaceDestroyedListener(null);
                dismissedPresentation.setOnDismissListener(null);
                dismissedPresentation.dismiss();
            } catch (Exception e) {
                Log.w(TAG, "关闭镜像窗口失败", e);
            }
        }
        sMirrorRunning = false;
        sendBroadcast(new Intent(ACTION_QUERY).setPackage(getPackageName()).putExtra("running", false));
        renderWorker.execute(() -> {
            if (releasedRenderer != null) {
                try {
                    releasedRenderer.stop();
                } catch (Exception e) {
                    Log.w(TAG, "停止多区域渲染器失败", e);
                }
            }
            if (actions.shouldReleaseVirtualDisplay() && releasedVirtualDisplay != null) {
                try {
                    releasedVirtualDisplay.release();
                } catch (Exception e) {
                    Log.w(TAG, "释放 VirtualDisplay 失败", e);
                }
            }
            if (releasedRecorder != null) {
                try {
                    releasedRecorder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "结束视频录制失败", e);
                }
            }
            if (actions.shouldUnregisterProjectionCallback() && releasedProjection != null && releasedCallback != null) {
                try {
                    releasedProjection.unregisterCallback(releasedCallback);
                } catch (Exception e) {
                    Log.w(TAG, "注销 MediaProjection 回调失败", e);
                }
            }
            if (actions.shouldStopProjection() && releasedProjection != null) {
                try {
                    releasedProjection.stop();
                } catch (Exception e) {
                    Log.w(TAG, "停止 MediaProjection 失败", e);
                }
            }
            if (actions.shouldReleaseSurface() && releasedSurface != null) {
                try {
                    releasedSurface.release();
                } catch (Exception e) {
                    Log.w(TAG, "释放镜像 Surface 失败", e);
                }
            }
            InAppLogger.i(TAG, "镜像停止，已释放本地镜像资源");
            mainHandler.post(() -> {
                resourceLifecycle.finishStop(actions);
                sendBroadcast(new Intent(ACTION_QUERY).setPackage(getPackageName()).putExtra("running", false));
            });
        });
    }


    private boolean publishProjection(
        int token,
        MediaProjection projection,
        MediaProjection.Callback callback
    ) {
        synchronized (resourceLock) {
            if (!resourceLifecycle.acceptProjectionTransfer(token)) {
                return false;
            }
            mediaProjection = projection;
            projectionCallback = callback;
            return true;
        }
    }

    private boolean publishPresentation(int token, MirrorPresentation mirrorPresentation) {
        synchronized (resourceLock) {
            if (!resourceLifecycle.acceptPresentationTransfer(token)) {
                return false;
            }
            presentation = mirrorPresentation;
            return true;
        }
    }

    private boolean publishVirtualDisplay(int token, VirtualDisplay createdVirtualDisplay) {
        synchronized (resourceLock) {
            if (!resourceLifecycle.acceptVirtualDisplayTransfer(token)) {
                return false;
            }
            virtualDisplay = createdVirtualDisplay;
            return true;
        }
    }

    private void releaseProjection(MediaProjection projection, MediaProjection.Callback callback) {
        try {
            projection.unregisterCallback(callback);
        } catch (Exception e) {
            Log.w(TAG, "注销 MediaProjection 回调失败", e);
        }
        try {
            projection.stop();
        } catch (Exception e) {
            Log.w(TAG, "停止 MediaProjection 失败", e);
        }
    }

    private void dismissPresentation(MirrorPresentation mirrorPresentation) {
        try {
            mirrorPresentation.setSurfaceReadyListener(null);
            mirrorPresentation.setSurfaceDestroyedListener(null);
            mirrorPresentation.setOnDismissListener(null);
            mirrorPresentation.dismiss();
        } catch (RuntimeException exception) {
            Log.w(TAG, "关闭镜像窗口失败", exception);
        }
    }

    private String buildDebugState() {
        synchronized (resourceLock) {
            return "state=" + resourceLifecycle.getState()
                + ", token=" + resourceLifecycle.getActiveToken()
                + ", virtualDisplay=" + (virtualDisplay != null)
                + ", presentation=" + (presentation != null)
                + ", mediaProjection=" + (mediaProjection != null)
                + ", activeSurface=" + (activeSurface != null);
        }
    }

    private void releaseSurface(Surface surface) {
        try {
            surface.release();
        } catch (Exception e) {
            Log.w(TAG, "释放镜像 Surface 失败", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "主屏镜像服务",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, HongqiFeatureActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder
            .setContentTitle("HongQiMirror")
            .setContentText("正在使用主屏本地镜像")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        stopMirrorInternal();
        unregisterDisplayListener(-1);
        renderWorker.shutdown();
        super.onDestroy();
    }

    private static final class MirrorPresentation extends Presentation {
        private final int outputWidth;
        private final int outputHeight;
        private SurfaceReadyListener surfaceReadyListener;
        private Runnable surfaceDestroyedListener;

        MirrorPresentation(Context context, Display display, int outputWidth, int outputHeight) {
            super(context, display);
            this.outputWidth = Math.max(1, outputWidth);
            this.outputHeight = Math.max(1, outputHeight);
        }

        void setSurfaceReadyListener(SurfaceReadyListener listener) {
            surfaceReadyListener = listener;
        }

        void setSurfaceDestroyedListener(Runnable listener) {
            surfaceDestroyedListener = listener;
        }

        @Override
        protected void onStart() {
            super.onStart();
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.black);
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                );
            }
        }

        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);

            TextureView textureView = new TextureView(getContext());
            root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            setContentView(root);

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    texture.setDefaultBufferSize(outputWidth, outputHeight);
                    if (surfaceReadyListener != null) {
                        surfaceReadyListener.onSurfaceReady(new Surface(texture));
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    texture.setDefaultBufferSize(outputWidth, outputHeight);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    if (surfaceDestroyedListener != null) {
                        surfaceDestroyedListener.run();
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            });
        }
    }

    private interface SurfaceReadyListener {
        void onSurfaceReady(Surface surface);
    }
}
