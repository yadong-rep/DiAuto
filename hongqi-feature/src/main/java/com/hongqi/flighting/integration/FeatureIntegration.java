package com.hongqi.flighting.integration;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import com.hongqi.flighting.mapping.KeyMappingManager;
import com.hongqi.flighting.mapping.KeyMappingAccessibilityService;
import com.hongqi.flighting.sidebar.SidebarPrefs;
import com.hongqi.flighting.sidebar.SidebarService;
import com.hongqi.flighting.ui.ActivationActivity;
import com.hongqi.flighting.ui.HongqiFeatureActivity;
import com.hongqi.flighting.util.ActivationGuard;

public final class FeatureIntegration {
    private FeatureIntegration() {}
    public static void install(Application application, FeatureNavigation.Host host) {
        FeatureNavigation.install(host);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (!(activity instanceof ActivationActivity) && !isAuthorized(activity)) {
                    // Check all host deep links and activities, not only the launcher.
                    ActivationGuard.requireActivated(activity);
                }
            }
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof ActivationActivity || activity.isFinishing()) return;
                if (!isAuthorized(activity)) { ActivationGuard.requireActivated(activity); return; }
                if (SidebarPrefs.isEnabled(activity) && !SidebarService.isRunning()
                    && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(activity))) {
                    SidebarService.start(activity, SidebarService.ACTION_SHOW);
                }
            }
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
    public static boolean isAuthorized(Context context) {
        return ActivationGuard.isSecureRunning(context);
    }
    public static boolean handleKey(Context context, KeyEvent event) {
        return isAuthorized(context) && KeyMappingManager.handleKey(context, event, KeyMappingAccessibilityService.getInstance());
    }
    public static boolean handleKey(Context context, int code, boolean down) {
        return handleKey(context, new KeyEvent(down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code));
    }
    public static void openFeatures(Context context) {
        Intent intent = new Intent(context, HongqiFeatureActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
