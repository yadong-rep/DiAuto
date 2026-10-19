package com.hongqi.flighting.autostart;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * 开机广播接收器。完整复刻参考方控 App（com.hzsoft.sidebar.BootReceiver）：
 * 根据「boot」开关自启本应用，并触发 AppAutoStartManager 按顺序启动其他应用。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SidebarBoot";

    public static boolean bootBroadcastActive;

    public static void startIfEnabled(Context context) {
        if (AppAutoStartManager.preferences(context).getBoolean("boot", false)) {
            try {
                Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                    Log.i(TAG, "app launcher activity started");
                }
            } catch (Exception e) {
                Log.e(TAG, "failed to start app", e);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!com.hongqi.flighting.util.ActivationGuard.isSecureRunning(context)) return;
        bootBroadcastActive = true;
        startIfEnabled(context);
        if (!"android.intent.action.MY_PACKAGE_REPLACED".equals(intent.getAction())) {
            AppAutoStartManager.scheduleFromBoot(context);
        }
        bootBroadcastActive = false;
    }
}