package com.hongqi.flighting.autostart;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 开机启动其他应用的管理器。完整复刻参考方控 App
 * （com.hzsoft.sidebar.AppAutoStartManager）：配置存于默认 SharedPreferences，
 * 通过 AlarmManager + BroadcastReceiver 按「首次延迟 / 间隔」顺序启动应用，可选返回桌面。
 */
public final class AppAutoStartManager {
    private static final String TAG = "SidebarAutoStart";

    public static final String KEY_ENABLED = "app_autostart_enabled";
    public static final String KEY_INTERVAL = "app_autostart_interval";
    private static final String KEY_LAST_BOOT_TOKEN = "app_autostart_last_boot_token";
    public static final String KEY_PACKAGES = "app_autostart_packages";
    public static final String KEY_RETURN_HOME = "app_autostart_return_home";
    public static final String KEY_START_DELAY = "app_autostart_delay";
    private static final int REQUEST_BASE = 17300;

    private AppAutoStartManager() {
    }

    private static long getBootToken(Context context) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return Settings.Global.getInt(context.getContentResolver(), "boot_count");
            } catch (Exception e) {
                // 忽略，回退到基于开机时间的估算
            }
        }
        return ((System.currentTimeMillis() - SystemClock.elapsedRealtime()) + 30000) / 60000;
    }

    public static List<String> getPackages(Context context) {
        String raw = preferences(context).getString(KEY_PACKAGES, "");
        ArrayList<String> result = new ArrayList<>();
        for (String item : raw.split("\n")) {
            String pkg = item.trim();
            if (pkg.length() > 0 && !result.contains(pkg)) {
                result.add(pkg);
            }
        }
        return result;
    }

    public static void handleAlarm(Context context, Intent intent) {
        if (!com.hongqi.flighting.util.ActivationGuard.isSecureRunning(context)) return;
        SharedPreferences prefs = preferences(context);
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            return;
        }
        if (intent.getBooleanExtra("return_home", false)) {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
            return;
        }
        List<String> packages = getPackages(context);
        int index = intent.getIntExtra("index", 0);
        if (index < 0 || index >= packages.size()) {
            return;
        }
        String pkg = packages.get(index);
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                Log.i(TAG, "started " + pkg);
            }
        } catch (Exception e) {
            Log.e(TAG, "failed " + pkg, e);
        }
        int interval = prefs.getInt(KEY_INTERVAL, 3);
        int next = index + 1;
        if (next < packages.size()) {
            schedule(context, next, false, interval);
        } else if (prefs.getBoolean(KEY_RETURN_HOME, true)) {
            schedule(context, next, true, Math.max(1, interval));
        }
    }

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("hongqi_autostart", Context.MODE_PRIVATE);
    }

    public static void savePackages(Context context, List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (pkg != null && pkg.trim().length() > 0) {
                sb.append(pkg.trim()).append('\n');
            }
        }
        preferences(context).edit().putString(KEY_PACKAGES, sb.toString()).commit();
    }

    private static void schedule(Context context, int index, boolean returnHome, int seconds) {
        try {
            Intent intent = new Intent(context, AppAutoStartReceiver.class);
            intent.putExtra("index", index);
            intent.putExtra("return_home", returnHome);
            long delayMs = SystemClock.elapsedRealtime()
                + (Math.max(0, Math.min(300, seconds)) * 1000L) + 300L;
            PendingIntent pi = PendingIntent.getBroadcast(context,
                index + REQUEST_BASE + (returnHome ? 1000 : 0), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, delayMs, pi);
        } catch (Exception e) {
            Log.e(TAG, "schedule failed", e);
        }
    }

    public static synchronized void scheduleFromBoot(Context context) {
        if (!com.hongqi.flighting.util.ActivationGuard.isSecureRunning(context)) return;
        SharedPreferences prefs = preferences(context);
        List<String> packages = getPackages(context);
        if (prefs.getBoolean(KEY_ENABLED, false) && !packages.isEmpty()) {
            long bootToken = getBootToken(context);
            if (prefs.getLong(KEY_LAST_BOOT_TOKEN, Long.MIN_VALUE) == bootToken) {
                Log.i(TAG, "already scheduled for this boot");
                return;
            }
            if (!prefs.edit().putLong(KEY_LAST_BOOT_TOKEN, bootToken).commit()) {
                Log.w(TAG, "failed to persist boot token");
            }
            Log.i(TAG, "schedule boot apps count=" + packages.size());
            schedule(context, 0, false, prefs.getInt(KEY_START_DELAY, 5));
        }
    }
}