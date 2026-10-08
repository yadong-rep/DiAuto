package com.hongqi.flighting.integration;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import com.hongqi.flighting.ui.HongqiFeatureActivity;

/** All references to the host live behind this interface; the feature has no app dependency. */
public final class FeatureNavigation {
    public interface Host {
        Intent homeIntent(Context context);
        boolean sendMediaKey(Context context, int keyCode);
    }
    private static volatile Host host;
    private FeatureNavigation() {}
    public static void install(Host value) { host = value; }
    public static Intent homeIntent(Context context) {
        Host current = host;
        if (current != null) return current.homeIntent(context);
        return new Intent(context, HongqiFeatureActivity.class);
    }
    public static Intent castIntent(Context context) {
        return new Intent(context, HongqiFeatureActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.hongqi.flighting.service.ScreenCaptureService.EXTRA_AUTO_START, true);
    }
    public static boolean sendMediaKey(Context context, int keyCode) {
        Host current = host;
        return current != null && current.sendMediaKey(context, keyCode);
    }
}
