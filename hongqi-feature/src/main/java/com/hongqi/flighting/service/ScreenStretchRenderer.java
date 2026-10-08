package com.hongqi.flighting.service;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Surface;

import com.hongqi.flighting.mapping.TargetMediaSessionService;
import com.hongqi.flighting.util.InAppLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ScreenStretchRenderer {
    private static final String TAG = "ScreenStretchRenderer";
    private static final int MAX_REGIONS = 3;
    private static final int IMAGE_TEXTURE_UNIT_BASE = 1; // unit 0 = OES

    // 音乐区域渲染参数
    private static final float MUSIC_ROTATION_DEG_PER_SEC = 20f;          // 封面转速（度/秒）
    private static final long MUSIC_METADATA_REFRESH_NANOS = 500_000_000L; // 元数据拉取间隔 500ms
    private static final long MUSIC_FRAME_REFRESH_NANOS = 33_333_333L;     // 帧重绘间隔（约 30fps）
    private static final int MUSIC_ARTWORK_SIZE = 512;                     // 封面缩放尺寸
    private static final int MUSIC_FRAME_MAX_HEIGHT = 720;                 // 音乐帧位图高度

    // OES 顶点着色器（屏幕采集用）
    private static final String VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform mat4 uCropMatrix;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = uMVPMatrix * aPosition;\n" +
        "  vTexCoord = (uCropMatrix * uTexMatrix * aTexCoord).xy;\n" +
        "}\n";

    // OES 片段着色器（屏幕采集）
    private static final String FRAGMENT_SHADER_OES =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
        "}\n";

    // 2D 片段着色器（图片用）
    private static final String FRAGMENT_SHADER_2D =
        "precision mediump float;\n" +
        "uniform sampler2D sTexture;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
        "}\n";

    private static final float[] FULL_RECTANGLE = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    private static final float[] FULL_TEX_COORDS = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    // 2D 位图（图片/音乐）纹理坐标需垂直翻转，补偿 Bitmap 自上而下的行序
    private static final float[] FULL_TEX_COORDS_FLIPPED = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };

    public static class RegionConfig {
        public final boolean enabled;
        public final String sourceType; // "screen", "image", or "music"
        public final String imageName;  // 图片模式下为服务器 URL，兼容旧 assets/builtin/ 文件名
        public final String stretchMode;
        public final int cropStartX, cropEndX, cropStartY, cropEndY;
        public final int outputWidthPercent;
        public final String outputHorizontalAlign;

        public RegionConfig(boolean enabled, String sourceType, String imageName, String stretchMode,
                            int cropStartX, int cropEndX, int cropStartY, int cropEndY,
                            int outputWidthPercent, String outputHorizontalAlign) {
            this.enabled = enabled;
            this.sourceType = sourceType != null ? sourceType : "screen";
            this.imageName = imageName != null ? imageName : "";
            this.stretchMode = stretchMode != null ? stretchMode : "stretch_fill";
            this.cropStartX = cropStartX;
            this.cropEndX = cropEndX;
            this.cropStartY = cropStartY;
            this.cropEndY = cropEndY;
            this.outputWidthPercent = clamp(outputWidthPercent, 0, 100);
            this.outputHorizontalAlign = outputHorizontalAlign != null ? outputHorizontalAlign : "center";
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static class PerRegionState {
        RegionConfig config;
        float[] mvpMatrix = new float[16];
        float[] cropMatrix = new float[16];
        int imageTextureId = 0; // non-zero if image mode
        int musicTextureId = 0; // non-zero if music mode
        Bitmap musicFrameBitmap = null; // 复用的音乐帧位图缓冲
        long lastMusicRefreshNanos = 0;

        // GIF 动图逐帧播放状态
        Movie gifMovie;              // GIF 解码器
        long gifDurationMs;          // 总时长（毫秒）
        long gifStartNanos;          // 播放起点
        long lastGifFrameMs = -1;    // 上次上传的帧时间点，用于去重
        Bitmap gifFrameBitmap;       // 复用的 GIF 帧位图缓冲
    }

    private final Surface encoderSurface;
    private final AssetManager assetManager;
    private final int sourceWidth;
    private final int sourceHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final PerRegionState[] regions = new PerRegionState[MAX_REGIONS];
    private final long frameIntervalNanos;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final Object frameLock = new Object();

    private Thread renderThread;
    private volatile boolean running;
    private volatile boolean frameAvailable;
    private volatile Surface inputSurface;
    private volatile RuntimeException startupException;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture surfaceTexture;
    private int oesTextureId;
    private int programOes;
    private int program2D;
    private int aPositionLoc;
    private int aTexCoordLoc;
    private int uMvpMatrixLoc;
    private int uTexMatrixLoc;
    private int uCropMatrixLoc;
    // 2D program locations (may differ from OES)
    private int aPositionLoc2D;
    private int aTexCoordLoc2D;
    private int uMvpMatrixLoc2D;
    private int uTexMatrixLoc2D;
    private int uCropMatrixLoc2D;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private FloatBuffer texCoordBufferFlipped;
    private final float[] texMatrix = new float[16];
    private final float[] identityMatrix = new float[16];

    // 音乐区域动态元数据与旋转状态
    private TargetMediaSessionService.MediaInfo currentMedia;
    private Bitmap currentArtworkScaled;
    private String lastMediaSignature = "";
    private long lastMediaFetchNanos = 0;
    private long rotationStartNanos = 0;

    // 歌词与播放进度
    private volatile List<LyricsProvider.LrcLine> currentLyrics;
    private volatile String currentLyricsSource;   // "session" = 来自 MediaSession, "lrclib" = 来自网络
    private volatile String lyricsFetchSignature = "";
    private volatile String lastSessionLyricsText;  // 上次从 MediaSession 读到的歌词原文
    private long currentPositionMs = 0;
    private boolean isPlaying = false;
    private long lastPositionFetchNanos = 0;

    ScreenStretchRenderer(Surface encoderSurface, AssetManager assetManager,
                          int sourceWidth, int sourceHeight,
                          int outputWidth, int outputHeight,
                          RegionConfig r1, RegionConfig r2, RegionConfig r3,
                          int targetFps) {
        this.encoderSurface = encoderSurface;
        this.assetManager = assetManager;
        this.sourceWidth = Math.max(1, sourceWidth);
        this.sourceHeight = Math.max(1, sourceHeight);
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.frameIntervalNanos = 1_000_000_000L / Math.max(1, targetFps);

        for (int i = 0; i < MAX_REGIONS; i++) {
            regions[i] = new PerRegionState();
        }
        regions[0].config = r1 != null ? r1 : new RegionConfig(true, "screen", "", "stretch_fill", -100, 100, -100, 100, 100, "center");
        regions[1].config = r2 != null ? r2 : new RegionConfig(false, "screen", "", "fit_center", -100, 100, -100, 100, 50, "center");
        regions[2].config = r3 != null ? r3 : new RegionConfig(false, "screen", "", "fit_center", -100, 100, -100, 100, 30, "center");
    }

    void updateRegionConfig(int index, RegionConfig config) {
        if (index < 0 || index >= MAX_REGIONS) return;
        if (config != null) {
            regions[index].config = config;
            recomputeLayout();
        }
    }

    Surface startAndWaitForInputSurface() throws InterruptedException {
        running = true;
        renderThread = new Thread(this::renderLoop, "ScreenStretchRenderer");
        renderThread.start();
        if (!readyLatch.await(5, TimeUnit.SECONDS)) {
            stop();
            throw new IllegalStateException("OpenGL renderer startup timed out");
        }
        if (startupException != null) {
            throw startupException;
        }
        return inputSurface;
    }

    void stop() {
        running = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (renderThread != null) {
            try {
                renderThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InAppLogger.w(TAG, "stop interrupted", e);
            }
            renderThread = null;
        }
    }

    private void recomputeLayout() {
        int enabledCount = 0;
        for (int i = 0; i < MAX_REGIONS; i++) {
            if (regions[i].config.enabled) enabledCount++;
        }

        int[] regionWidths = computeRegionWidths(enabledCount);
        int[] regionXOffsets = computeRegionXOffsets(enabledCount, regionWidths);
        for (int i = 0; i < MAX_REGIONS; i++) {
            RegionConfig cfg = regions[i].config;
            if (!cfg.enabled) {
                Matrix.setIdentityM(regions[i].mvpMatrix, 0);
                Matrix.setIdentityM(regions[i].cropMatrix, 0);
                continue;
            }

            int regionWidth = regionWidths[i];
            int regionHeight = outputHeight;

            if ("music".equals(cfg.sourceType)) {
                // 音乐区域按本区域视口比例铺满，无需屏幕采集的裁剪/拉伸矩阵
                Matrix.setIdentityM(regions[i].mvpMatrix, 0);
                Matrix.setIdentityM(regions[i].cropMatrix, 0);
            } else {
                computeMvpMatrix(regions[i].mvpMatrix, cfg, regionWidth, regionHeight, enabledCount == 1);
                computeCropMatrix(regions[i].cropMatrix, cfg);
            }

            InAppLogger.i(TAG, "Region[" + i + "] layout: viewport=" + regionXOffsets[i] + ",0," + regionWidth + "x" + regionHeight
                + " source=" + cfg.sourceType + (cfg.sourceType.equals("image") ? " img=" + cfg.imageName : "")
                + " stretch=" + cfg.stretchMode + " crop=x" + cfg.cropStartX + ".." + cfg.cropEndX
                + " y" + cfg.cropStartY + ".." + cfg.cropEndY
                + " width%=" + cfg.outputWidthPercent + " align=" + cfg.outputHorizontalAlign);
        }
    }

    private int[] computeRegionWidths(int enabledCount) {
        int[] widths = new int[MAX_REGIONS];
        for (int i = 0; i < MAX_REGIONS; i++) {
            if (!regions[i].config.enabled) continue;
            if (enabledCount == 1) {
                widths[i] = outputWidth;
            } else {
                widths[i] = Math.max(1, regions[i].config.outputWidthPercent * outputWidth / 100);
            }
        }
        return widths;
    }

    private int[] computeRegionXOffsets(int enabledCount, int[] widths) {
        int[] offsets = new int[MAX_REGIONS];
        boolean[] assigned = new boolean[MAX_REGIONS];
        if (enabledCount <= 1) return offsets;

        int left = findAlignedRegion("left");
        int center = findAlignedRegion("center");
        int right = findAlignedRegion("right");

        if (left >= 0) {
            offsets[left] = 0;
            assigned[left] = true;
        }
        if (right >= 0) {
            offsets[right] = clamp(outputWidth - widths[right], 0, outputWidth - widths[right]);
            assigned[right] = true;
        }
        if (center >= 0) {
            int centerOffset;
            if (left >= 0) {
                centerOffset = widths[left];
            } else if (right >= 0) {
                centerOffset = outputWidth - widths[right] - widths[center];
            } else {
                centerOffset = (outputWidth - widths[center]) / 2;
            }
            offsets[center] = clamp(centerOffset, 0, outputWidth - widths[center]);
            assigned[center] = true;
        }

        int sequentialOffset = 0;
        for (int i = 0; i < MAX_REGIONS; i++) {
            if (!regions[i].config.enabled) continue;
            if (!assigned[i]) {
                offsets[i] = clamp(sequentialOffset, 0, outputWidth - widths[i]);
            }
            sequentialOffset += widths[i];
        }
        return offsets;
    }

    private int findAlignedRegion(String align) {
        for (int i = 0; i < MAX_REGIONS; i++) {
            if (regions[i].config.enabled && align.equals(regions[i].config.outputHorizontalAlign)) {
                return i;
            }
        }
        return -1;
    }

    private void computeMvpMatrix(float[] matrix, RegionConfig config,
                                  int regionWidth, int regionHeight, boolean applyWidthScale) {
        Matrix.setIdentityM(matrix, 0);

        float cropWidth = (toUnit(config.cropEndX) - toUnit(config.cropStartX)) * sourceWidth;
        float cropHeight = (toUnit(config.cropEndY) - toUnit(config.cropStartY)) * sourceHeight;
        float sourceAspect = cropWidth / cropHeight;
        float targetAspect = regionWidth / (float) regionHeight;
        float scaleX = 1f;
        float scaleY = 1f;

        if ("fit_center".equals(config.stretchMode) || "center_inside".equals(config.stretchMode)) {
            if (sourceAspect > targetAspect) {
                scaleY = targetAspect / sourceAspect;
            } else {
                scaleX = sourceAspect / targetAspect;
            }
        } else if ("center_crop".equals(config.stretchMode)) {
            if (sourceAspect > targetAspect) {
                scaleX = sourceAspect / targetAspect;
            } else {
                scaleY = targetAspect / sourceAspect;
            }
        } else if ("fit_width".equals(config.stretchMode)) {
            scaleY = targetAspect / sourceAspect;
        } else if ("fit_height".equals(config.stretchMode)) {
            scaleX = sourceAspect / targetAspect;
        }

        if (applyWidthScale) {
            float widthScale = config.outputWidthPercent / 100f;
            float offsetX = 0f;
            if ("left".equals(config.outputHorizontalAlign)) {
                offsetX = -(1f - widthScale);
            } else if ("right".equals(config.outputHorizontalAlign)) {
                offsetX = 1f - widthScale;
            }
            Matrix.translateM(matrix, 0, offsetX, 0f, 0f);
            Matrix.scaleM(matrix, 0, scaleX * widthScale, scaleY, 1f);
        } else {
            Matrix.scaleM(matrix, 0, scaleX, scaleY, 1f);
        }
    }

    private void computeCropMatrix(float[] matrix, RegionConfig config) {
        float left = toUnit(config.cropStartX);
        float right = toUnit(config.cropEndX);
        float top = toUnit(config.cropStartY);
        float bottom = toUnit(config.cropEndY);
        Matrix.setIdentityM(matrix, 0);
        Matrix.translateM(matrix, 0, left, top, 0f);
        Matrix.scaleM(matrix, 0, right - left, bottom - top, 1f);
    }

    private float toUnit(int value) {
        return (clamp(value, -100, 100) + 100) / 200f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void renderLoop() {
        try {
            initGl();
            recomputeLayout();
            readyLatch.countDown();
            loadImageTextures();
            InAppLogger.i(TAG, "Multi-region renderer started: source=" + sourceWidth + "x" + sourceHeight
                + " output=" + outputWidth + "x" + outputHeight
                + " targetFps=" + (1_000_000_000L / frameIntervalNanos));

            boolean hasTextureFrame = false;
            long nextFrameTimeNanos = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                long waitNanos = Math.max(1_000_000L, nextFrameTimeNanos - now);
                boolean hasNewFrame = waitForFrame(waitNanos);
                if (!running) break;
                if (hasNewFrame) hasTextureFrame = true;
                if (hasTextureFrame) {
                    long presentationTimeNanos = System.nanoTime();
                    drawFrame(hasNewFrame, presentationTimeNanos);
                    nextFrameTimeNanos = presentationTimeNanos + frameIntervalNanos;
                }
            }
        } catch (RuntimeException e) {
            startupException = e;
            readyLatch.countDown();
            InAppLogger.e(TAG, "Renderer runtime error", e);
        } finally {
            releaseGl();
            InAppLogger.i(TAG, "Renderer stopped");
        }
    }

    private boolean waitForFrame(long timeoutNanos) {
        synchronized (frameLock) {
            if (!running) return false;
            if (!frameAvailable) {
                try {
                    long timeoutMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
                    frameLock.wait(timeoutMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                    return false;
                }
            }
            boolean hasNewFrame = frameAvailable;
            frameAvailable = false;
            return hasNewFrame;
        }
    }

    private void initGl() {
        vertexBuffer = createFloatBuffer(FULL_RECTANGLE);
        texCoordBuffer = createFloatBuffer(FULL_TEX_COORDS);
        texCoordBufferFlipped = createFloatBuffer(FULL_TEX_COORDS_FLIPPED);
        Matrix.setIdentityM(identityMatrix, 0);

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize failed");
        }

        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw new IllegalStateException("eglChooseConfig failed");
        }

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        checkEgl("eglCreateContext");

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0);
        checkEgl("eglCreateWindowSurface");
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("eglMakeCurrent failed");
        }

        // 创建两个着色器程序
        programOes = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        program2D = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);

        aPositionLoc = GLES20.glGetAttribLocation(programOes, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(programOes, "aTexCoord");
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programOes, "uMVPMatrix");
        uTexMatrixLoc = GLES20.glGetUniformLocation(programOes, "uTexMatrix");
        uCropMatrixLoc = GLES20.glGetUniformLocation(programOes, "uCropMatrix");

        aPositionLoc2D = GLES20.glGetAttribLocation(program2D, "aPosition");
        aTexCoordLoc2D = GLES20.glGetAttribLocation(program2D, "aTexCoord");
        uMvpMatrixLoc2D = GLES20.glGetUniformLocation(program2D, "uMVPMatrix");
        uTexMatrixLoc2D = GLES20.glGetUniformLocation(program2D, "uTexMatrix");
        uCropMatrixLoc2D = GLES20.glGetUniformLocation(program2D, "uCropMatrix");

        // 创建 OES 纹理（屏幕采集）
        oesTextureId = createOesTexture();
        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notifyAll();
            }
        });
        inputSurface = new Surface(surfaceTexture);

        rotationStartNanos = System.nanoTime();

        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    /** 在 GL 上下文就绪后、渲染循环开始前加载图片纹理；失败仅记录日志，不拖垮投屏。 */
    private void loadImageTextures() {
        for (int i = 1; i < MAX_REGIONS; i++) {
            try {
                regions[i].imageTextureId = loadImageTexture(regions[i]);
            } catch (Throwable t) {
                InAppLogger.w(TAG, "Failed to preload image region " + i, t);
                regions[i].imageTextureId = 0;
            }
        }
    }

    private int loadImageTexture(PerRegionState state) {
        RegionConfig config = state.config;
        if (config == null || !"image".equals(config.sourceType)
            || config.imageName == null || config.imageName.isEmpty()) {
            return 0;
        }
        byte[] bytes = readImageBytes(config.imageName);
        if (bytes == null) {
            InAppLogger.w(TAG, "Failed to load image: " + config.imageName);
            return 0;
        }
        if (isGif(bytes)) {
            return loadGifTexture(state, bytes);
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            InAppLogger.w(TAG, "Failed to decode image: " + config.imageName);
            return 0;
        }
        int textureId = uploadBitmapTexture(bitmap);
        InAppLogger.i(TAG, "Loaded image texture: " + config.imageName
            + " " + bitmap.getWidth() + "x" + bitmap.getHeight());
        bitmap.recycle();
        return textureId;
    }

    private byte[] readImageBytes(String imageSource) {
        if (imageSource.startsWith("http://") || imageSource.startsWith("https://")) {
            return ImageApiClient.downloadBytes(imageSource);
        }
        // 本地缓存文件优先
        File localFile = new File(imageSource);
        if (localFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(localFile)) {
                return readStreamToBytes(fis);
            } catch (IOException e) {
                InAppLogger.w(TAG, "Failed to read local image: " + imageSource, e);
                return null;
            }
        }
        // 兼容本地内置图片（assets/builtin/）
        String assetPath = "hongqi/builtin/" + imageSource;
        try (InputStream is = assetManager.open(assetPath)) {
            return readStreamToBytes(is);
        } catch (IOException e) {
            InAppLogger.w(TAG, "Failed to load asset image: " + assetPath, e);
            return null;
        }
    }

    private byte[] readStreamToBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private boolean isGif(byte[] bytes) {
        return bytes != null && bytes.length >= 6
            && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
            && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
    }

    private int loadGifTexture(PerRegionState state, byte[] bytes) {
        Movie movie = Movie.decodeByteArray(bytes, 0, bytes.length);
        if (movie == null || movie.width() <= 0 || movie.height() <= 0 || movie.duration() <= 0) {
            // 无法作为动图解析，回退为静态首帧
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                InAppLogger.w(TAG, "Failed to decode GIF fallback: " + state.config.imageName);
                return 0;
            }
            int textureId = uploadBitmapTexture(bitmap);
            bitmap.recycle();
            return textureId;
        }

        state.gifMovie = movie;
        state.gifDurationMs = movie.duration();
        state.gifStartNanos = System.nanoTime();
        state.lastGifFrameMs = -1;
        state.gifFrameBitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888);

        canvasGifFrame(state, 0);
        uploadGifFrame(state);
        InAppLogger.i(TAG, "Loaded GIF: " + state.config.imageName
            + " " + movie.width() + "x" + movie.height() + " duration=" + state.gifDurationMs + "ms");
        return state.imageTextureId;
    }

    /** 在渲染循环内推进 GIF 到当前时间点并更新纹理。 */
    private void updateGifFrame(PerRegionState state, long nowNanos) {
        if (state.gifMovie == null || state.gifDurationMs <= 0 || state.gifFrameBitmap == null) {
            return;
        }
        long elapsedMs = (nowNanos - state.gifStartNanos) / 1_000_000L;
        long frameMs = elapsedMs % state.gifDurationMs;
        if (frameMs == state.lastGifFrameMs) {
            return;
        }
        state.lastGifFrameMs = frameMs;
        canvasGifFrame(state, (int) frameMs);
        uploadGifFrame(state);
    }

    private void canvasGifFrame(PerRegionState state, int frameMs) {
        Canvas canvas = new Canvas(state.gifFrameBitmap);
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        state.gifMovie.setTime(frameMs);
        state.gifMovie.draw(canvas, 0f, 0f);
    }

    private void uploadGifFrame(PerRegionState state) {
        Bitmap frame = state.gifFrameBitmap;
        if (frame == null) {
            return;
        }
        if (state.imageTextureId == 0) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            state.imageTextureId = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, state.imageTextureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                frame.getWidth(), frame.getHeight(), 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(frame.getWidth() * frame.getHeight() * 4)
            .order(ByteOrder.nativeOrder());
        frame.copyPixelsToBuffer(buffer);
        buffer.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, state.imageTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
            frame.getWidth(), frame.getHeight(),
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
    }

    private int uploadBitmapTexture(Bitmap bitmap) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            bitmap.getWidth(), bitmap.getHeight(), 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        ByteBuffer buffer = ByteBuffer.allocateDirect(bitmap.getWidth() * bitmap.getHeight() * 4);
        bitmap.copyPixelsToBuffer(buffer);
        buffer.position(0);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
            bitmap.getWidth(), bitmap.getHeight(),
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        return textures[0];
    }

    private void drawFrame(boolean updateTexture, long presentationTimeNanos) {
        if (updateTexture) {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        int enabledCount = 0;
        boolean hasMusicRegion = false;
        for (int i = 0; i < MAX_REGIONS; i++) {
            if (regions[i].config.enabled) {
                enabledCount++;
                if ("music".equals(regions[i].config.sourceType)) {
                    hasMusicRegion = true;
                }
            }
        }
        long musicNowNanos = System.nanoTime();
        if (hasMusicRegion) {
            refreshMediaIfNeeded(musicNowNanos);
        }
        float musicAngleDeg = (musicNowNanos - rotationStartNanos) / 1_000_000_000f * MUSIC_ROTATION_DEG_PER_SEC;

        int[] regionWidths = computeRegionWidths(enabledCount);
        int[] regionXOffsets = computeRegionXOffsets(enabledCount, regionWidths);
        boolean anyEnabled = false;
        for (int i = 0; i < MAX_REGIONS; i++) {
            RegionConfig cfg = regions[i].config;
            if (!cfg.enabled) continue;

            int xOffset = regionXOffsets[i];
            int regionWidth = regionWidths[i];

            GLES20.glViewport(xOffset, 0, regionWidth, outputHeight);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(xOffset, 0, regionWidth, outputHeight);

            boolean isImageMode = "image".equals(cfg.sourceType) && regions[i].imageTextureId != 0;
            boolean isMusicMode = "music".equals(cfg.sourceType);

            int curProg, curAPos, curATex, curUMvp, curUTex, curUCrop;
            FloatBuffer drawTexCoordBuffer = texCoordBuffer;
            float[] texMatrixForDraw = texMatrix;

            if (isMusicMode) {
                prepareMusicFrame(i, regionWidth, outputHeight);
                PerRegionState musicState = regions[i];
                if (musicState.musicFrameBitmap != null
                    && (musicNowNanos - musicState.lastMusicRefreshNanos) >= MUSIC_FRAME_REFRESH_NANOS) {
                    long estPos = getEstimatedPositionMs(musicNowNanos);
                    drawMusicFrame(musicState.musicFrameBitmap, currentMedia,
                        currentArtworkScaled, musicAngleDeg, estPos);
                    uploadMusicTexture(musicState, musicState.musicFrameBitmap);
                    musicState.lastMusicRefreshNanos = musicNowNanos;
                }
                GLES20.glUseProgram(program2D);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, musicState.musicTextureId);
                curProg = program2D;
                curAPos = aPositionLoc2D;
                curATex = aTexCoordLoc2D;
                curUMvp = uMvpMatrixLoc2D;
                curUTex = uTexMatrixLoc2D;
                curUCrop = uCropMatrixLoc2D;
                drawTexCoordBuffer = texCoordBufferFlipped;
                texMatrixForDraw = identityMatrix;
            } else if (isImageMode) {
                if (regions[i].gifMovie != null) {
                    updateGifFrame(regions[i], musicNowNanos);
                }
                GLES20.glUseProgram(program2D);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, regions[i].imageTextureId);
                curProg = program2D;
                curAPos = aPositionLoc2D;
                curATex = aTexCoordLoc2D;
                curUMvp = uMvpMatrixLoc2D;
                curUTex = uTexMatrixLoc2D;
                curUCrop = uCropMatrixLoc2D;
                drawTexCoordBuffer = texCoordBufferFlipped;
                texMatrixForDraw = identityMatrix;
            } else {
                GLES20.glUseProgram(programOes);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
                curProg = programOes;
                curAPos = aPositionLoc;
                curATex = aTexCoordLoc;
                curUMvp = uMvpMatrixLoc;
                curUTex = uTexMatrixLoc;
                curUCrop = uCropMatrixLoc;
            }

            GLES20.glUniform1i(GLES20.glGetUniformLocation(curProg, "sTexture"), 0);
            GLES20.glUniformMatrix4fv(curUMvp, 1, false, regions[i].mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(curUTex, 1, false, texMatrixForDraw, 0);
            GLES20.glUniformMatrix4fv(curUCrop, 1, false, regions[i].cropMatrix, 0);

            GLES20.glEnableVertexAttribArray(curAPos);
            GLES20.glVertexAttribPointer(curAPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(curATex);
            GLES20.glVertexAttribPointer(curATex, 2, GLES20.GL_FLOAT, false, 0, drawTexCoordBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(curAPos);
            GLES20.glDisableVertexAttribArray(curATex);

            anyEnabled = true;
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        // Fallback: no region enabled, show black
        if (!anyEnabled) {
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNanos);
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw new IllegalStateException("eglSwapBuffers failed");
        }
    }

    private void refreshMediaIfNeeded(long nowNanos) {
        if (nowNanos - lastMediaFetchNanos < MUSIC_METADATA_REFRESH_NANOS) {
            return;
        }
        lastMediaFetchNanos = nowNanos;
        TargetMediaSessionService.MediaInfo info = TargetMediaSessionService.getCurrentPlayingMedia();

        // 始终更新播放进度（用于歌词同步 + 倒计时）
        currentMedia = info;
        currentPositionMs = info != null ? info.positionMs : 0;
        isPlaying = info != null && info.playing;
        lastPositionFetchNanos = nowNanos;

        // ===== 每帧检查 MediaSession 是否直接提供了歌词 =====
        String sessionLyrics = info != null ? info.lyrics : null;
        if (sessionLyrics != null && sessionLyrics.length() > 10) {
            // 歌词文本变化时重新解析
            if (!sessionLyrics.equals(lastSessionLyricsText)) {
                lastSessionLyricsText = sessionLyrics;
                List<LyricsProvider.LrcLine> parsed = LyricsProvider.parse(sessionLyrics);
                if (parsed != null && !parsed.isEmpty()) {
                    currentLyrics = parsed;
                    currentLyricsSource = "session";
                    lyricsFetchSignature = "";
                } else {
                    // 非 LRC 格式（车机通知/副标题提供的纯文本或单句歌词），按行等间隔兜底显示
                    List<LyricsProvider.LrcLine> plain = LyricsProvider.parsePlain(sessionLyrics);
                    if (plain != null && !plain.isEmpty()) {
                        currentLyrics = plain;
                        currentLyricsSource = "session";
                        lyricsFetchSignature = "";
                    }
                }
            }
        } else {
            // MediaSession 本次没有歌词
            lastSessionLyricsText = null;
            // 如果之前是 session 歌词，现在没了，清空
            if ("session".equals(currentLyricsSource)) {
                currentLyrics = null;
                currentLyricsSource = null;
            }
        }

        // 仅在歌曲变化时重新拉取封面 + LRCLIB 回退歌词
        String signature = info == null ? "" : (info.title + "\u0001" + info.artist);
        if (signature.equals(lastMediaSignature)) {
            return;
        }
        lastMediaSignature = signature;

        // 封面
        Bitmap artwork = info == null ? null : info.artwork;
        Bitmap newScaled = artwork == null
            ? null
            : Bitmap.createScaledBitmap(artwork, MUSIC_ARTWORK_SIZE, MUSIC_ARTWORK_SIZE, true);
        if (currentArtworkScaled != null) {
            currentArtworkScaled.recycle();
        }
        currentArtworkScaled = newScaled;

        // 歌词回退：MediaSession 没歌词时异步调 LRCLIB
        if (info != null && info.title != null && !info.title.isEmpty()
            && (info.lyrics == null || info.lyrics.length() <= 10)) {
            final String title = info.title;
            final String artist = info.artist != null ? info.artist : "";
            final String album = info.album != null ? info.album : "";
            final String fetchSig = title + "|" + artist;
            if (!fetchSig.equals(lyricsFetchSignature)) {
                lyricsFetchSignature = fetchSig;
                LyricsProvider.fetchAsync(title, artist, album, lyrics -> {
                    if (!"session".equals(currentLyricsSource)) {
                        currentLyrics = lyrics;
                        currentLyricsSource = lyrics != null ? "lrclib" : null;
                    }
                });
            }
        }
    }

    /** 估算当前播放位置（在两次 fetch 之间用经过时间推算，使歌词平滑滚动）。 */
    private long getEstimatedPositionMs(long nowNanos) {
        if (!isPlaying || currentPositionMs <= 0) {
            return currentPositionMs;
        }
        long elapsed = (nowNanos - lastPositionFetchNanos) / 1_000_000L;
        if (elapsed < 0) {
            elapsed = 0;
        }
        return currentPositionMs + elapsed;
    }

    private void prepareMusicFrame(int regionIndex, int regionWidth, int regionHeight) {
        PerRegionState state = regions[regionIndex];
        float aspect = regionWidth / (float) Math.max(1, regionHeight);
        int frameHeight = MUSIC_FRAME_MAX_HEIGHT;
        int frameWidth = Math.max(64, Math.round(frameHeight * aspect));
        if (state.musicFrameBitmap != null
            && state.musicFrameBitmap.getWidth() == frameWidth
            && state.musicFrameBitmap.getHeight() == frameHeight) {
            return;
        }
        if (state.musicFrameBitmap != null) {
            state.musicFrameBitmap.recycle();
        }
        state.musicFrameBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
    }

    private void drawMusicFrame(Bitmap frame,
                                TargetMediaSessionService.MediaInfo info,
                                Bitmap artwork,
                                float angleDeg,
                                long positionMs) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        float shortSide = Math.min(w, h);
        Canvas canvas = new Canvas(frame);

        Paint background = new Paint();
        background.setShader(new LinearGradient(0f, 0f, 0f, h, 0xFF1B1E27, 0xFF0B0D13, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, w, h, background);

        // ===== 圆饼（缩小，靠顶部） =====
        float cx = w / 2f;
        float cy = h * 0.13f;
        float discRadius = shortSide * 0.12f;

        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(0xFF090A0F);
        canvas.drawCircle(cx, cy, discRadius + shortSide * 0.012f, plate);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, shortSide * 0.004f));
        ring.setColor(0xFFC9A24B);

        if (artwork != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, discRadius, Path.Direction.CW);
            int saved = canvas.save();
            canvas.clipPath(clip);
            canvas.rotate(angleDeg, cx, cy);
            RectF dst = new RectF(cx - discRadius, cy - discRadius, cx + discRadius, cy + discRadius);
            Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(artwork, null, dst, coverPaint);
            canvas.restoreToCount(saved);
            canvas.drawCircle(cx, cy, discRadius, ring);
            Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
            hub.setColor(0xFF0B0D13);
            canvas.drawCircle(cx, cy, discRadius * 0.18f, hub);
        } else {
            Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
            placeholder.setColor(0xFF2A2E3A);
            canvas.drawCircle(cx, cy, discRadius, placeholder);
            canvas.drawCircle(cx, cy, discRadius, ring);
            Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
            note.setColor(0xFF8A92A5);
            note.setTypeface(Typeface.DEFAULT_BOLD);
            note.setTextSize(discRadius * 1.0f);
            note.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("\u266A", cx, cy + note.getTextSize() * 0.33f, note);
        }

        // ===== 标题 + 歌手 =====
        String title  = info != null && info.title  != null && info.title.length()  > 0 ? info.title  : "\u672a\u68c0\u6d4b\u5230\u64ad\u653e";
        String artist = info != null && info.artist != null && info.artist.length() > 0 ? info.artist : "";
        long durationMs = info != null ? info.durationMs : 0;

        float maxTextWidth = w * 0.88f;
        float y = cy + discRadius + shortSide * 0.05f;

        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setTextSize(shortSide * 0.062f);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        CharSequence titleText = TextUtils.ellipsize(title, titlePaint, maxTextWidth, TextUtils.TruncateAt.END);
        y += titlePaint.getTextSize();
        canvas.drawText(titleText.toString(), cx, y, titlePaint);
        y += shortSide * 0.005f;

        if (!artist.isEmpty()) {
            TextPaint artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            artistPaint.setColor(0xFF9AA3B2);
            artistPaint.setTypeface(Typeface.DEFAULT);
            artistPaint.setTextSize(shortSide * 0.042f);
            artistPaint.setTextAlign(Paint.Align.CENTER);
            CharSequence artistText = TextUtils.ellipsize(artist, artistPaint, maxTextWidth, TextUtils.TruncateAt.END);
            y += artistPaint.getTextSize();
            canvas.drawText(artistText.toString(), cx, y, artistPaint);
        }

        // ===== 歌词区（3~5行，当前行高亮） =====
        List<LyricsProvider.LrcLine> lyrics = currentLyrics;
        if (lyrics != null && !lyrics.isEmpty() && positionMs > 0) {
            int currentLine = LyricsProvider.findCurrentLine(lyrics, positionMs);
            if (currentLine >= 0) {
                // 显示当前行前后各2行，共最多5行
                int visibleCount = Math.min(5, lyrics.size());
                int startIdx = Math.max(0, currentLine - 2);
                int endIdx = Math.min(lyrics.size() - 1, startIdx + visibleCount - 1);
                if (endIdx - startIdx + 1 < visibleCount) {
                    startIdx = Math.max(0, endIdx - visibleCount + 1);
                }

                float lyricSize = shortSide * 0.044f;
                float lineSpacing = lyricSize * 0.35f;
                float lyricTop = h * 0.40f;

                for (int i = startIdx; i <= endIdx; i++) {
                    LyricsProvider.LrcLine line = lyrics.get(i);
                    int offset = i - startIdx;
                    float ly = lyricTop + offset * (lyricSize + lineSpacing);

                    TextPaint lp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                    lp.setTextAlign(Paint.Align.CENTER);

                    if (i == currentLine) {
                        // 当前行：金色粗体，放大
                        lp.setColor(0xFFD9B46A);
                        lp.setTypeface(Typeface.DEFAULT_BOLD);
                        lp.setTextSize(lyricSize * 1.12f);
                    } else {
                        // 上下行：灰色，越远越淡
                        int distance = Math.abs(i - currentLine);
                        int alpha = 0xFF - distance * 0x50;
                        if (alpha < 0x30) alpha = 0x30;
                        lp.setColor((alpha << 24) | 0x9AA3B2);
                        lp.setTypeface(Typeface.DEFAULT);
                        lp.setTextSize(lyricSize);
                    }

                    CharSequence text = TextUtils.ellipsize(line.text, lp, maxTextWidth, TextUtils.TruncateAt.END);
                    canvas.drawText(text.toString(), cx, ly + lp.getTextSize(), lp);
                }
            }
        } else if (lyrics != null && !lyrics.isEmpty() && positionMs <= 0) {
            // 有歌词但还没有播放进度，显示前几行
            float lyricSize = shortSide * 0.044f;
            float lineSpacing = lyricSize * 0.35f;
            float lyricTop = h * 0.40f;
            int count = Math.min(5, lyrics.size());
            for (int i = 0; i < count; i++) {
                TextPaint lp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                lp.setColor(0xFF6B7280);
                lp.setTypeface(Typeface.DEFAULT);
                lp.setTextSize(lyricSize);
                lp.setTextAlign(Paint.Align.CENTER);
                CharSequence text = TextUtils.ellipsize(lyrics.get(i).text, lp, maxTextWidth, TextUtils.TruncateAt.END);
                float ly = lyricTop + i * (lyricSize + lineSpacing);
                canvas.drawText(text.toString(), cx, ly + lp.getTextSize(), lp);
            }
        } else {
            // 无歌词：显示专辑名
            String album = info != null && info.album != null && info.album.length() > 0 ? info.album : "";
            if (!album.isEmpty()) {
                TextPaint albumPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                albumPaint.setColor(0xFFC9A24B);
                albumPaint.setTypeface(Typeface.DEFAULT);
                albumPaint.setTextSize(shortSide * 0.04f);
                albumPaint.setTextAlign(Paint.Align.CENTER);
                CharSequence albumText = TextUtils.ellipsize(album, albumPaint, maxTextWidth, TextUtils.TruncateAt.END);
                y = h * 0.42f + albumPaint.getTextSize();
                canvas.drawText(albumText.toString(), cx, y, albumPaint);
            } else if (info == null) {
                TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                hintPaint.setColor(0xFF6B7280);
                hintPaint.setTextSize(shortSide * 0.035f);
                hintPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("\u8bf7\u5f00\u542f\u901a\u77e5\u4f7f\u7528\u6743", cx, h * 0.45f, hintPaint);
            }
        }

        // ===== 底部倒计时 =====
        if (durationMs > 0) {
            long remaining = durationMs - positionMs;
            if (remaining < 0) remaining = 0;
            String remainStr = "-" + formatDuration(remaining);
            TextPaint cdPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            cdPaint.setColor(0xFFC9A24B);
            cdPaint.setTypeface(Typeface.DEFAULT_BOLD);
            cdPaint.setTextSize(shortSide * 0.038f);
            cdPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(remainStr, cx, h * 0.94f, cdPaint);
        } else if (info != null) {
            // 无时长信息，显示播放状态
            TextPaint stPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            stPaint.setColor(0xFF6B7280);
            stPaint.setTextSize(shortSide * 0.03f);
            stPaint.setTextAlign(Paint.Align.CENTER);
            String stateText = isPlaying ? "\u64ad\u653e\u4e2d" : "\u5df2\u6682\u505c";
            canvas.drawText(stateText, cx, h * 0.94f, stPaint);
        }
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min >= 60) {
            long hr = min / 60;
            min %= 60;
            return String.format("%d:%02d:%02d", hr, min, sec);
        }
        return String.format("%d:%02d", min, sec);
    }

    private void uploadMusicTexture(PerRegionState state, Bitmap frame) {
        if (state.musicTextureId == 0) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            state.musicTextureId = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, state.musicTextureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                frame.getWidth(), frame.getHeight(), 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(frame.getWidth() * frame.getHeight() * 4)
            .order(ByteOrder.nativeOrder());
        frame.copyPixelsToBuffer(buffer);
        buffer.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, state.musicTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
            frame.getWidth(), frame.getHeight(),
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
    }

    private void releaseGl() {
        for (int i = 1; i < MAX_REGIONS; i++) {
            if (regions[i].imageTextureId != 0) {
                int[] textures = {regions[i].imageTextureId};
                GLES20.glDeleteTextures(1, textures, 0);
                regions[i].imageTextureId = 0;
            }
            if (regions[i].musicTextureId != 0) {
                int[] textures = {regions[i].musicTextureId};
                GLES20.glDeleteTextures(1, textures, 0);
                regions[i].musicTextureId = 0;
            }
            if (regions[i].musicFrameBitmap != null) {
                regions[i].musicFrameBitmap.recycle();
                regions[i].musicFrameBitmap = null;
            }
            regions[i].gifMovie = null;
            if (regions[i].gifFrameBitmap != null) {
                regions[i].gifFrameBitmap.recycle();
                regions[i].gifFrameBitmap = null;
            }
        }
        if (currentArtworkScaled != null) {
            currentArtworkScaled.recycle();
            currentArtworkScaled = null;
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (programOes != 0) {
            GLES20.glDeleteProgram(programOes);
            programOes = 0;
        }
        if (program2D != 0) {
            GLES20.glDeleteProgram(program2D);
            program2D = 0;
        }
        if (oesTextureId != 0) {
            int[] textures = {oesTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            oesTextureId = 0;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private int createOesTexture() {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return texture[0];
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int createdProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(createdProgram, vertexShader);
        GLES20.glAttachShader(createdProgram, fragmentShader);
        GLES20.glLinkProgram(createdProgram);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(createdProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(createdProgram);
            GLES20.glDeleteProgram(createdProgram);
            throw new IllegalStateException("Could not link GL program: " + log);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return createdProgram;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile shader " + type + ": " + log);
        }
        return shader;
    }

    private FloatBuffer createFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void checkEgl(String op) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException(op + " EGL error 0x" + Integer.toHexString(error));
        }
    }
}
