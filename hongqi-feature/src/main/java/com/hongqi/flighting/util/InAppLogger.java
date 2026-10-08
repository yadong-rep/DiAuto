package com.hongqi.flighting.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small in-memory log buffer for viewing important diagnostics inside the app.
 */
public final class InAppLogger {
    private static final int MAX_CHARS = 24000;
    private static final Object LOCK = new Object();
    private static final StringBuilder BUFFER = new StringBuilder();
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private InAppLogger() {
    }

    public static void d(String tag, String message) {
        Log.d(tag, message);
        append("D", tag, message, null);
    }

    public static void i(String tag, String message) {
        Log.i(tag, message);
        append("I", tag, message, null);
    }

    public static void w(String tag, String message) {
        Log.w(tag, message);
        append("W", tag, message, null);
    }

    public static void w(String tag, String message, Throwable throwable) {
        Log.w(tag, message, throwable);
        append("W", tag, message, throwable);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
        append("E", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        append("E", tag, message, throwable);
    }

    public static String dump() {
        synchronized (LOCK) {
            return BUFFER.length() == 0 ? "暂无 App 内部关键日志" : BUFFER.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUFFER.setLength(0);
        }
    }

    private static void append(String level, String tag, String message, Throwable throwable) {
        synchronized (LOCK) {
            String time = TIME_FORMAT.format(new Date());
            BUFFER.append(time)
                .append(' ')
                .append(level)
                .append('/')
                .append(tag)
                .append(": ")
                .append(message)
                .append('\n');
            if (throwable != null) {
                BUFFER.append(throwable.getClass().getSimpleName())
                    .append(": ")
                    .append(throwable.getMessage())
                    .append('\n');
            }
            trimIfNeeded();
        }
    }

    private static void trimIfNeeded() {
        if (BUFFER.length() <= MAX_CHARS) {
            return;
        }
        int removeTo = BUFFER.length() - MAX_CHARS;
        int nextLine = BUFFER.indexOf("\n", removeTo);
        BUFFER.delete(0, nextLine >= 0 ? nextLine + 1 : removeTo);
    }
}
