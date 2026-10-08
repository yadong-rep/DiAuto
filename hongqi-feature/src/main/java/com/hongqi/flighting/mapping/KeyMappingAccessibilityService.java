package com.hongqi.flighting.mapping;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 按键映射无障碍服务。完整复刻参考方控 App
 * （com.hzsoft.sidebar.SteeringAccessibilityService）：onKeyEvent 交给
 * KeyMappingManager.handleKey 处理，并在连接时开启 flagRequestFilterKeyEvents。
 */
public class KeyMappingAccessibilityService extends AccessibilityService {
    private static final String TAG = "SidebarSteering";

    private static volatile KeyMappingAccessibilityService instance;

    public static KeyMappingAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** 执行系统全局动作（返回/桌面/最近任务等），供侧边栏固定控件调用。 */
    public static boolean performSystemAction(int action) {
        KeyMappingAccessibilityService service = instance;
        return service != null && service.performGlobalAction(action);
    }

    public static boolean isEnabledInSettings(Context context) {
        String enabled;
        if (context == null
            || (enabled = Settings.Secure.getString(context.getContentResolver(),
                "enabled_accessibility_services")) == null) {
            return false;
        }
        return enabled.toLowerCase().contains(
            new ComponentName(context, KeyMappingAccessibilityService.class)
                .flattenToString().toLowerCase());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        boolean handled = KeyMappingManager.handleKey(this, event, this);
        if (handled && event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "key=" + event.getKeyCode() + " scan=" + event.getScanCode());
        }
        return handled || super.onKeyEvent(event);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }
}