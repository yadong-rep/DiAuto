package com.hongqi.flighting.mapping;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.hongqi.flighting.service.ScreenCaptureService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 按键映射存储与按键处理。
 *
 * <p>完整复刻参考方控 App（com.hzsoft.sidebar.SteeringMapper）的逻辑：
 * 映射以「keyCode:scanCode」指纹为键、以「动作 + 目标包名」为值存于 SharedPreferences；
 * 学习态通过 SharedPreferences 中的 pending_action / pending_app / captured_key 跨组件传递，
 * 由界面侧轮询 consumeCaptured() 获取刚学到的按键，从而避免内存态学习在车机上失效。
 */
public final class KeyMappingManager {
    private static final String TAG = "KeyMappingManager";

    private static final String CAPTURED = "captured_key";
    private static final String MAP_PREFIX = "map:";
    private static final String PENDING_ACTION = "pending_action";
    private static final String PENDING_APP = "pending_app";
    private static final String PREFS = "steering_control_v4";
    private static final String SEPARATOR = "\u001f";

    // 双击投屏快捷键
    private static final String DOUBLE_TAP_PENDING = "double_tap_pending";
    private static final String DOUBLE_TAP_CAPTURED = "double_tap_captured";
    private static final String DOUBLE_TAP_FINGERPRINT = "double_tap_cast_fingerprint";

    private static String consumedFingerprint;
    private static long lastEventAt;
    private static String lastFingerprint;

    /** 一条按键映射：方向盘按键指纹 -> 目标动作 + 目标 App。 */
    public static final class Mapping implements Comparable<Mapping> {
        public final String action;
        public final String fingerprint;
        public final String packageName;

        Mapping(String fingerprint, String action, String packageName) {
            this.fingerprint = fingerprint;
            this.action = action;
            this.packageName = packageName;
        }

        @Override
        public int compareTo(Mapping o) {
            return this.fingerprint.compareTo(o.fingerprint);
        }
    }

    private KeyMappingManager() {
    }

    /** 读取全部已保存映射。 */
    public static List<Mapping> all(Context context) {
        List<Mapping> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences(context).getAll().entrySet()) {
            if (entry.getValue() instanceof String) {
                Mapping mapping = decodeEntry(entry.getKey(), (String) entry.getValue());
                if (mapping != null) {
                    result.add(mapping);
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    /** 目标 App 的展示文案。 */
    public static String appLabel(Context context, String packageName) {
        try {
            return "绑定：" + context.getPackageManager()
                .getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0))
                + "（前台/后台均可）";
        } catch (Exception e) {
            return "绑定：" + packageName;
        }
    }

    /** 进入学习态，等待方向盘按键捕获。 */
    public static void beginLearning(Context context, String action, String app) {
        preferences(context).edit()
            .putString(PENDING_ACTION, action)
            .putString(PENDING_APP, app)
            .remove(CAPTURED)
            .commit();
    }

    /** 取消学习态。 */
    public static void cancelLearning(Context context) {
        preferences(context).edit()
            .remove(PENDING_ACTION)
            .remove(PENDING_APP)
            .remove(CAPTURED)
            .commit();
    }

    /** 进入双击投屏键学习态。 */
    public static void beginLearningDoubleTapCast(Context context) {
        preferences(context).edit()
            .putBoolean(DOUBLE_TAP_PENDING, true)
            .remove(DOUBLE_TAP_CAPTURED)
            .commit();
    }

    /** 取消双击投屏键学习态。 */
    public static void cancelLearningDoubleTapCast(Context context) {
        preferences(context).edit()
            .remove(DOUBLE_TAP_PENDING)
            .remove(DOUBLE_TAP_CAPTURED)
            .commit();
    }

    /** 读取当前设置的双击投屏键指纹；未设置返回 null。 */
    public static String getDoubleTapCastFingerprint(Context context) {
        return preferences(context).getString(DOUBLE_TAP_FINGERPRINT, null);
    }

    /** 清除双击投屏键。 */
    public static void clearDoubleTapCast(Context context) {
        preferences(context).edit().remove(DOUBLE_TAP_FINGERPRINT).commit();
    }

    /** 学习完成后轮询：返回并持久化刚学到的双击投屏键，无结果返回 null。 */
    public static String consumeDoubleTapCastCaptured(Context context) {
        SharedPreferences prefs = preferences(context);
        String fingerprint = prefs.getString(DOUBLE_TAP_CAPTURED, null);
        if (fingerprint != null) {
            prefs.edit()
                .putString(DOUBLE_TAP_FINGERPRINT, fingerprint)
                .remove(DOUBLE_TAP_CAPTURED)
                .commit();
        }
        return fingerprint;
    }

    /** 清空映射。 */
    public static void clearMappings(Context context) {
        preferences(context).edit().clear().commit();
        consumedFingerprint = null;
        lastFingerprint = null;
        lastEventAt = 0;
        KeyActionDispatcher.resetSyntheticMediaGuard();
    }

    /** 取出并清除刚学到的按键指纹；未学到时返回 null。 */
    public static String consumeCaptured(Context context) {
        SharedPreferences prefs = preferences(context);
        String captured = prefs.getString(CAPTURED, null);
        if (captured != null) {
            prefs.edit().remove(CAPTURED).commit();
        }
        return captured;
    }

    private static Mapping decode(String fingerprint, String raw) {
        int separator;
        if (raw != null && (separator = raw.indexOf(SEPARATOR)) >= 0) {
            String packageName = raw.substring(SEPARATOR.length() + separator);
            if (packageName.length() == 0) {
                return null;
            }
            return new Mapping(fingerprint, raw.substring(0, separator), packageName);
        }
        return null;
    }

    private static Mapping decodeEntry(String key, String value) {
        if (key == null || !key.startsWith(MAP_PREFIX)) {
            return null;
        }
        String fingerprint = key.substring(MAP_PREFIX.length());
        int pipe = fingerprint.indexOf('|');
        if (pipe >= 0) {
            fingerprint = fingerprint.substring(0, pipe);
        }
        return decode(fingerprint, value);
    }

    private static String encode(String action, String packageName) {
        return action + SEPARATOR + packageName;
    }

    /**
     * 处理按键事件：学习态捕获；否则命中映射则派发动作，并消费事件。
     * 服务与前台 Activity 均调用此静态方法，通过静态去重状态 + SharedPreferences 学习态协同。
     */
    public static boolean handleKey(Context context, KeyEvent keyEvent,
                                    AccessibilityService accessibilityService) {
        if (!com.hongqi.flighting.util.ActivationGuard.isSecureRunning(context)) return false;
        Mapping selected;
        if (keyEvent == null) {
            return false;
        }
        String fingerprint = keyEvent.getKeyCode() + ":" + keyEvent.getScanCode();
        SharedPreferences prefs = preferences(context);
        boolean learning = prefs.contains(PENDING_ACTION);
        boolean learningDoubleTap = prefs.getBoolean(DOUBLE_TAP_PENDING, false);
        if (!learning && !learningDoubleTap && KeyActionDispatcher.shouldIgnoreSyntheticMedia()) {
            return fingerprint.equals(consumedFingerprint);
        }
        List<Mapping> mappings = mappingsForFingerprint(prefs, fingerprint);
        if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
            if (learning || learningDoubleTap) {
                return true;
            }
            boolean equals = fingerprint.equals(consumedFingerprint);
            return equals;
        } else if (keyEvent.getAction() != KeyEvent.ACTION_DOWN || keyEvent.getRepeatCount() > 0) {
            return learning || learningDoubleTap || fingerprint.equals(consumedFingerprint);
        } else {
            long now = SystemClock.elapsedRealtime();
            if (!fingerprint.equals(lastFingerprint) || now - lastEventAt >= 180) consumedFingerprint = null;

            // 双击投屏键学习：捕获按键
            if (learningDoubleTap) {
                prefs.edit()
                    .putString(DOUBLE_TAP_CAPTURED, fingerprint)
                    .remove(DOUBLE_TAP_PENDING)
                    .commit();
                lastFingerprint = fingerprint;
                lastEventAt = now;
                consumedFingerprint = fingerprint;
                return true;
            }

            if (!learning && fingerprint.equals(lastFingerprint) && now - lastEventAt < 180) {
                return fingerprint.equals(consumedFingerprint);
            }

            // 投屏快捷键检测：单击切换投屏
            String castFingerprint = prefs.getString(DOUBLE_TAP_FINGERPRINT, null);
            if (castFingerprint != null && castFingerprint.equals(fingerprint)) {
                Log.i(TAG, "投屏键单击 fp=" + fingerprint + "，切换投屏");
                consumedFingerprint = fingerprint;
                lastFingerprint = fingerprint;
                lastEventAt = now;
                ScreenCaptureService.toggleCast(context);
                return true;
            }

            if (!fingerprint.equals(lastFingerprint) || now - lastEventAt >= 180) {
                String action = prefs.getString(PENDING_ACTION, null);
                if (action == null) {
                    if (mappings.isEmpty()
                        || (selected = TargetMediaSessionService.selectMapping(context, mappings)) == null) {
                        return false;
                    }
                    boolean executed = KeyActionDispatcher.execute(context, selected.action, selected.packageName);
                    if (executed) {
                        lastFingerprint = fingerprint;
                        lastEventAt = now;
                        consumedFingerprint = fingerprint;
                    } else {
                        consumedFingerprint = null;
                    }
                    return executed;
                }
                String app = prefs.getString(PENDING_APP, "");
                SharedPreferences.Editor editor = prefs.edit();
                String plainKey = MAP_PREFIX + fingerprint;
                Mapping existing = decode(fingerprint, prefs.getString(plainKey, null));
                if (existing != null) {
                    editor.putString(mappingKey(fingerprint, existing.packageName),
                        encode(existing.action, existing.packageName));
                    editor.remove(plainKey);
                }
                editor.putString(mappingKey(fingerprint, app), encode(action, app))
                    .remove(PENDING_ACTION)
                    .remove(PENDING_APP)
                    .putString(CAPTURED, fingerprint)
                    .commit();
                lastFingerprint = fingerprint;
                lastEventAt = now;
                consumedFingerprint = fingerprint;
                return true;
            }
            return fingerprint.equals(consumedFingerprint);
        }
    }

    /** 指纹的人读文案。 */
    public static String keyLabel(String fingerprint) {
        String[] parts = fingerprint.split(":");
        try {
            int keyCode = Integer.parseInt(parts[0]);
            return KeyEvent.keyCodeToString(keyCode)
                + "  (键值 " + keyCode
                + ", 扫描码 " + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0) + ")";
        } catch (Exception e) {
            return fingerprint;
        }
    }

    private static String mappingKey(String fingerprint, String packageName) {
        return MAP_PREFIX + fingerprint + "|" + packageName;
    }

    private static List<Mapping> mappingsForFingerprint(SharedPreferences prefs, String fingerprint) {
        List<Mapping> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof String) {
                Mapping mapping = decodeEntry(entry.getKey(), (String) entry.getValue());
                if (mapping != null && fingerprint.equals(mapping.fingerprint)) {
                    result.add(mapping);
                }
            }
        }
        return result;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 删除指定指纹 + 包名的映射。 */
    public static void removeMapping(Context context, String fingerprint, String packageName) {
        SharedPreferences prefs = preferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof String) {
                Mapping mapping = decodeEntry(entry.getKey(), (String) entry.getValue());
                if (mapping != null && fingerprint.equals(mapping.fingerprint)
                    && packageName.equals(mapping.packageName)) {
                    editor.remove(entry.getKey());
                }
            }
        }
        editor.commit();
    }
}