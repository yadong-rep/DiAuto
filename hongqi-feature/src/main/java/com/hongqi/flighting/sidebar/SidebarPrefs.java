package com.hongqi.flighting.sidebar;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 侧边栏配置的持久化。参考 com.hzsoft.sidebar 中基于 PreferenceManager 的运行时配置，
 * 这里独立使用名为 "sidebar" 的 SharedPreferences 文件，避免与投屏/按键映射配置混用。
 */
public final class SidebarPrefs {

    private static final String NAME = "sidebar";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SIDE = "side";          // 0=左，1=右
    public static final String KEY_APPS = "apps";          // 换行分隔 "pkg:activity" / "widget:code"
    public static final String KEY_VIBRATE = "vibrate";
    public static final String KEY_ITEM_SIZE = "s_width";  // 单项尺寸 dp，默认 60
    public static final String KEY_BG_COLOR = "bg_color";  // 面板背景色 ARGB int
    public static final String KEY_BLUR_RADIUS = "blur";    // 虚化半径 0-25，0=关闭
    public static final String KEY_CORNER_RADIUS = "corner"; // 圆角 dp，默认 16
    public static final String KEY_ANIM_SPEED = "anim";    // 动画速度 ms，默认 200
    public static final String KEY_PANEL_ALPHA = "p_alpha"; // 面板不透明度 0-255，默认 221(0xDD)

    public static final int SIDE_LEFT = 0;
    public static final int SIDE_RIGHT = 1;

    /** 预设背景色方案，供 UI 选择。 */
    public static final int[] PRESET_COLORS = {
        0xFF08090D,  // 深空黑
        0xFF0D1B2A,  // 午夜蓝
        0xFF0D1F17,  // 墨绿
        0xFF1A0D2A,  // 暗紫
        0xFF1C1C1C,  // 炭灰
        0xFF2A1A0D,  // 暖棕
        0xFF131820,  // 科技灰
        0xFF1E1410,  // 红木
    };
    public static final String[] PRESET_NAMES = {
        "深空黑", "午夜蓝", "墨绿", "暗紫", "炭灰", "暖棕", "科技灰", "红木"
    };

    private SidebarPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, false);
    }

    public static int side(Context context) {
        return get(context).getInt(KEY_SIDE, SIDE_LEFT);
    }

    public static String apps(Context context) {
        return get(context).getString(KEY_APPS, "");
    }

    public static boolean vibrate(Context context) {
        return get(context).getBoolean(KEY_VIBRATE, true);
    }

    public static int itemSize(Context context) {
        return get(context).getInt(KEY_ITEM_SIZE, 60);
    }

    /** 面板背景色（不含透明度，透明度由 panelAlpha 控制）。 */
    public static int bgColor(Context context) {
        return get(context).getInt(KEY_BG_COLOR, 0xFF08090D);
    }

    /** 虚化半径 0-25，0=关闭。 */
    public static int blurRadius(Context context) {
        return get(context).getInt(KEY_BLUR_RADIUS, 0);
    }

    /** 圆角 dp。 */
    public static int cornerRadius(Context context) {
        return get(context).getInt(KEY_CORNER_RADIUS, 16);
    }

    /** 动画速度 ms。 */
    public static int animSpeed(Context context) {
        return get(context).getInt(KEY_ANIM_SPEED, 200);
    }

    /** 面板不透明度 0-255。 */
    public static int panelAlpha(Context context) {
        return get(context).getInt(KEY_PANEL_ALPHA, 221);
    }

    /** 根据预设索引匹配当前背景色。 */
    public static int colorIndex(Context context) {
        int c = bgColor(context);
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == c) {
                return i;
            }
        }
        return 0;
    }
}