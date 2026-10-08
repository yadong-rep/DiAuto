package com.hongqi.flighting.integration;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import com.hongqi.flighting.util.ActivationGuard;
import com.hongqi.flighting.ui.HongqiFeatureActivity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class AuthorizationTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        ActivationGuard.clearActivation(context);
    }
    @Test public void freshInstallCannotRunProductionFeatures() {
        assertFalse(FeatureIntegration.isAuthorized(context));
    }
    @Test public void activeGrantAllowsFeaturesButDisabledGrantRevokesImmediately() {
        ActivationGuard.saveActivationToken(context, "ACTIVE", "1234567890abcdef");
        assertTrue(FeatureIntegration.isAuthorized(context));
        ActivationGuard.saveActivationToken(context, "DISABLED", "1234567890abcdef");
        assertFalse(FeatureIntegration.isAuthorized(context));
    }
    @Test public void expiredOrFutureDatedOfflineGrantIsRejected() {
        ActivationGuard.saveActivationToken(context, "ACTIVE", "1234567890abcdef");
        context.getSharedPreferences("carlife_mirror_activation", 0).edit()
            .putLong("last_verify_time", System.currentTimeMillis() - 8L * 24 * 3600 * 1000).commit();
        assertFalse(FeatureIntegration.isAuthorized(context));
        context.getSharedPreferences("carlife_mirror_activation", 0).edit()
            .putLong("last_verify_time", System.currentTimeMillis() + 100_000).commit();
        assertFalse(FeatureIntegration.isAuthorized(context));
    }
    @Test public void changingStoredStatusCannotForgeAnActiveGrant() {
        ActivationGuard.saveActivationToken(context, "PENDING", "1234567890abcdef");
        android.content.SharedPreferences preferences = context.getSharedPreferences("carlife_mirror_activation", 0);
        preferences.edit().putString("activation_token",
            preferences.getString("activation_token", "").replace("PENDING", "ACTIVE")).commit();
        assertFalse(FeatureIntegration.isAuthorized(context));
    }
    @Test public void unauthorizedFeatureActivityCanResumeWithoutUninitializedViewCrash() {
        try (org.robolectric.android.controller.ActivityController<HongqiFeatureActivity> controller =
            Robolectric.buildActivity(HongqiFeatureActivity.class)) {
            controller.create().start().resume();
            assertTrue(controller.get().isFinishing());
        }
    }
}
