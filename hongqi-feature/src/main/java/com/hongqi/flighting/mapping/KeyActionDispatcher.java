package com.hongqi.flighting.mapping;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * 按键动作派发。完整复刻参考方控 App（com.hzsoft.sidebar.SteeringActionDispatcher）：
 * 动作只有三种——上一曲 / 播放暂停 / 下一曲，均映射为媒体键并定向派发给目标 App。
 */
public final class KeyActionDispatcher {
    public static final String NEXT = "media_next";
    public static final String PLAY_PAUSE = "media_play_pause";
    public static final String PREVIOUS = "media_previous";

    private static volatile long syntheticMediaUntil;

    private KeyActionDispatcher() {
    }

    /** 执行动作；成功派发返回 true，并在短时间内屏蔽合成媒体键回环。 */
    public static boolean execute(Context context, String action, String packageName) {
        int keyCode;
        if (PREVIOUS.equals(action)) {
            keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        } else if (PLAY_PAUSE.equals(action)) {
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        } else if (!NEXT.equals(action)) {
            return false;
        } else {
            keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
        }
        if (!com.hongqi.flighting.util.ActivationGuard.isSecureRunning(context)) return false;
        boolean dispatched = context.getPackageName().equals(packageName)
            ? com.hongqi.flighting.integration.FeatureNavigation.sendMediaKey(context, keyCode)
            : TargetMediaSessionService.dispatch(packageName, keyCode);
        if (dispatched) {
            syntheticMediaUntil = SystemClock.elapsedRealtime() + 300;
        }
        return dispatched;
    }

    /** 动作的人读文案。 */
    public static String label(Context context, String action) {
        if (PREVIOUS.equals(action)) {
            return "上一曲";
        }
        if (PLAY_PAUSE.equals(action)) {
            return "播放 / 暂停";
        }
        if (NEXT.equals(action)) {
            return "下一曲";
        }
        return "";
    }

    /** 合成媒体键派发后的短暂窗口内忽略后续按键，避免无限回环。 */
    static void resetSyntheticMediaGuard() { syntheticMediaUntil = 0; }

    public static boolean shouldIgnoreSyntheticMedia() {
        return SystemClock.elapsedRealtime() < syntheticMediaUntil;
    }
}