package com.hongqi.flighting.service;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import com.hongqi.flighting.util.InAppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 将渲染器输出（编码器输入 Surface）编码为 H.264 并封装成 MP4，写入系统 Download 目录。
 * 通过 MediaMuxer 接收 MediaCodec 的编码结果，渲染器直接在输入 Surface 上绘制即可。
 */
final class ScreenVideoRecorder {
    private static final String TAG = "ScreenVideoRecorder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int IFRAME_INTERVAL_SEC = 1;

    private final int width;
    private final int height;
    private final int fps;

    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private Thread drainThread;
    private int trackIndex = -1;
    private boolean muxerStarted;

    private String outputPath;
    private ParcelFileDescriptor outputParcelFd;
    private File outputFile;

    ScreenVideoRecorder(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
    }

    String getOutputPath() {
        return outputPath != null ? outputPath : "";
    }

    Surface start(Context context) throws IOException {
        openOutput(context);

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, estimateBitRate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        muxer = createMuxer();
        encoder.start();

        drainThread = new Thread(this::drainLoop, "ScreenVideoRecorder");
        drainThread.start();
        return inputSurface;
    }

    void stop() {
        if (encoder != null) {
            try {
                encoder.signalEndOfInputStream();
            } catch (Exception e) {
                InAppLogger.w(TAG, "signalEndOfInputStream failed", e);
            }
        }
        if (drainThread != null) {
            try {
                drainThread.join(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        releaseCodec();
        closeOutput();
    }

    private void openOutput(Context context) throws IOException {
        String fileName = "mirror_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("create MediaStore download entry failed");
            }
            outputPath = "Download/" + fileName;
            outputParcelFd = resolver.openFileDescriptor(uri, "w");
            if (outputParcelFd == null) {
                throw new IOException("open download file descriptor failed");
            }
        } else {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads == null || (!downloads.exists() && !downloads.mkdirs())) {
                downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            }
            if (downloads == null) {
                throw new IOException("resolve download directory failed");
            }
            outputFile = new File(downloads, fileName);
            outputPath = outputFile.getAbsolutePath();
            if (Build.VERSION.SDK_INT >= 26) {
                outputParcelFd = ParcelFileDescriptor.open(outputFile,
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
            }
        }
    }

    private MediaMuxer createMuxer() throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            return new MediaMuxer(outputParcelFd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        return new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void drainLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean eos = false;
        while (!eos) {
            int outputIndex;
            try {
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
            } catch (Exception e) {
                InAppLogger.w(TAG, "dequeueOutputBuffer failed", e);
                break;
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = encoder.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
            } else if (outputIndex >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                if (encodedData != null) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    eos = true;
                }
            }
        }
        InAppLogger.i(TAG, "drain loop finished, muxerStarted=" + muxerStarted);
    }

    private void releaseCodec() {
        if (muxer != null) {
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    InAppLogger.w(TAG, "muxer.stop failed", e);
                }
            }
            try {
                muxer.release();
            } catch (Exception e) {
                InAppLogger.w(TAG, "muxer.release failed", e);
            }
            muxer = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                InAppLogger.w(TAG, "encoder.stop failed", e);
            }
            try {
                encoder.release();
            } catch (Exception e) {
                InAppLogger.w(TAG, "encoder.release failed", e);
            }
            encoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        muxerStarted = false;
        trackIndex = -1;
    }

    private void closeOutput() {
        if (outputParcelFd != null) {
            try {
                outputParcelFd.close();
            } catch (IOException e) {
                InAppLogger.w(TAG, "close output fd failed", e);
            }
            outputParcelFd = null;
        }
    }

    private int estimateBitRate() {
        return Math.max(2_000_000, (int) ((long) width * height * fps * 3 / 10));
    }
}