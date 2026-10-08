package com.hongqi.flighting.sidebar;

import com.hongqi.flighting.R;

/**
 * 侧边栏系统控件的枚举与元数据。参考 com.hzsoft.sidebar.Sidebar 中的 w() 系统功能控制。
 * 控件以整数 code 持久化（写入 prefs 的 "apps" 键，形如 "widget:&lt;code&gt;\n"），避免直接依赖资源 ID。
 */
public final class SidebarWidgets {

    public static final int WIFI = 0;
    public static final int BLUETOOTH = 1;
    public static final int BRIGHTNESS = 2;
    public static final int GPS = 3;
    public static final int ROTATION = 4;
    public static final int SOUND = 5;
    public static final int TORCH = 6;
    public static final int ALL_APPS = 7;
    public static final int RECENT = 8;
    public static final int PREV = 9;
    public static final int PLAY_PAUSE = 10;
    public static final int NEXT = 11;
    public static final int CAST = 12;

    /** 所有可选控件，用于“添加控件”选择弹框。 */
    public static final int[] ALL = {
        CAST, WIFI, BLUETOOTH, BRIGHTNESS, GPS, ROTATION, SOUND, TORCH,
        ALL_APPS, RECENT, PREV, PLAY_PAUSE, NEXT
    };

    private SidebarWidgets() {
    }

    public static int icon(int code) {
        switch (code) {
            case WIFI: return R.drawable.hongqi_ic_sidebar_wifi;
            case BLUETOOTH: return R.drawable.hongqi_ic_sidebar_bluetooth;
            case BRIGHTNESS: return R.drawable.hongqi_ic_sidebar_brightness;
            case GPS: return R.drawable.hongqi_ic_sidebar_gps;
            case ROTATION: return R.drawable.hongqi_ic_sidebar_rotation;
            case SOUND: return R.drawable.hongqi_ic_sidebar_sound;
            case TORCH: return R.drawable.hongqi_ic_sidebar_torch;
            case ALL_APPS: return R.drawable.hongqi_ic_sidebar_apps;
            case RECENT: return R.drawable.hongqi_ic_sidebar_recent;
            case PREV: return R.drawable.hongqi_ic_sidebar_prev;
            case PLAY_PAUSE: return R.drawable.hongqi_ic_sidebar_play;
            case NEXT: return R.drawable.hongqi_ic_sidebar_next;
            case CAST: return R.drawable.hongqi_ic_sidebar_cast;
            default: return R.drawable.hongqi_ic_sidebar_apps;
        }
    }

    public static String label(int code) {
        switch (code) {
            case WIFI: return "无线网络";
            case BLUETOOTH: return "蓝牙";
            case BRIGHTNESS: return "亮度";
            case GPS: return "定位";
            case ROTATION: return "旋转";
            case SOUND: return "音量";
            case TORCH: return "手电筒";
            case ALL_APPS: return "全部应用";
            case RECENT: return "最近应用";
            case PREV: return "上一曲";
            case PLAY_PAUSE: return "播放/暂停";
            case NEXT: return "下一曲";
            case CAST: return "投屏";
            default: return "控件";
        }
    }
}