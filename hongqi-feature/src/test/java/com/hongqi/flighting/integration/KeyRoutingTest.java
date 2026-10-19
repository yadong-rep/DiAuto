package com.hongqi.flighting.integration;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.view.KeyEvent;
import com.hongqi.flighting.mapping.KeyMappingManager;
import com.hongqi.flighting.mapping.KeyActionDispatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class KeyRoutingTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        KeyMappingManager.clearMappings(context);
    }
    @Test public void learningCapturesScancodeAndConsumesBothObserverCopies() {
        KeyMappingManager.beginLearning(context, KeyActionDispatcher.NEXT, "test.music");
        KeyEvent down = new KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F3, 0, 0, 1, 44);
        KeyEvent up = new KeyEvent(100, 101, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F3, 0, 0, 1, 44);
        assertTrue(FeatureIntegration.handleKey(context, down));
        assertTrue(FeatureIntegration.handleKey(context, down));
        assertTrue(FeatureIntegration.handleKey(context, up));
        assertTrue(FeatureIntegration.handleKey(context, up));
        assertEquals("133:44", KeyMappingManager.consumeCaptured(context));
        assertEquals(1, KeyMappingManager.all(context).size());
        assertEquals("test.music", KeyMappingManager.all(context).get(0).packageName);
    }
    @Test public void castShortcutRequestsFreshConsentOnlyOnceAcrossDuplicateObservers() {
        KeyMappingManager.beginLearningDoubleTapCast(context);
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5);
        assertTrue(FeatureIntegration.handleKey(context, down));
        assertNotNull(KeyMappingManager.consumeDoubleTapCastCaptured(context));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
        assertTrue(FeatureIntegration.handleKey(context, down));
        assertTrue(FeatureIntegration.handleKey(context, down));
        Intent start = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull(start);
        assertTrue(start.getBooleanExtra(com.hongqi.flighting.service.ScreenCaptureService.EXTRA_AUTO_START, false));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
    @Test public void ownPackageTargetRoutesToHostWithoutMediaSessionRecursion() {
        int[] calls = {0};
        FeatureNavigation.install(new FeatureNavigation.Host() {
            @Override public Intent homeIntent(Context c) { return new Intent(); }
            @Override public boolean sendMediaKey(Context c, int code) {
                assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, code); calls[0]++; return true;
            }
        });
        assertTrue(KeyActionDispatcher.execute(context, KeyActionDispatcher.NEXT, context.getPackageName()));
        assertEquals(1, calls[0]);
    }
    @Test public void unmappedKeysFallThroughToOriginalDiAutoHandler() {
        assertFalse(FeatureIntegration.handleKey(context, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F12)));
    }
}
