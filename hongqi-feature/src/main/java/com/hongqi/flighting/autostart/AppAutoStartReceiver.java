package com.hongqi.flighting.autostart;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机启动其他应用的闹钟接收器。完整复刻参考方控 App
 * （com.hzsoft.sidebar.AppAutoStartReceiver）：交给 AppAutoStartManager.handleAlarm 处理。
 */
public class AppAutoStartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppAutoStartManager.handleAlarm(context, intent);
    }
}