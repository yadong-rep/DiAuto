package com.hongqi.flighting.ui;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.hongqi.flighting.integration.FeatureNavigation;
import com.hongqi.flighting.util.ActivationGuard;

/** Shared authorization entrance for DiAuto and every Hongqi feature. */
public final class ActivationActivity extends AppCompatActivity {
    public static final String EXTRA_DESTINATION = "hongqi_activation_destination";
    private ActivationController controller;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (ActivationGuard.isSecureRunning(this)) { openHome(); return; }
        controller = new ActivationController(this, this::openHome);
        controller.start();
    }
    private void openHome() {
        android.content.Intent destination = androidx.core.content.IntentCompat.getParcelableExtra(
            getIntent(), EXTRA_DESTINATION, android.content.Intent.class);
        if (destination == null || destination.getComponent() == null
            || !getPackageName().equals(destination.getComponent().getPackageName())
            || getClass().getName().equals(destination.getComponent().getClassName())) {
            destination = FeatureNavigation.homeIntent(this);
        }
        destination.setFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(destination);
        finish();
    }
    @Override protected void onDestroy() {
        if (controller != null) controller.close();
        super.onDestroy();
    }
}
