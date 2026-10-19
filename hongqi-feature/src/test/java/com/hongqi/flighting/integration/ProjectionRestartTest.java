package com.hongqi.flighting.integration;

import android.app.Service;
import android.content.Intent;
import com.hongqi.flighting.service.ScreenCaptureService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class ProjectionRestartTest {
    @Test public void processRestartDoesNotReusePersistedProjectionPermission() {
        org.robolectric.android.controller.ServiceController<ScreenCaptureService> controller =
            Robolectric.buildService(ScreenCaptureService.class);
        try {
            ScreenCaptureService service = controller.create().get();
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
            assertFalse(ScreenCaptureService.isMirrorRunning());
        } finally { controller.destroy(); }
    }
    @Test public void oldSilentStartIntentCannotCreateNewProjectionSession() {
        org.robolectric.android.controller.ServiceController<ScreenCaptureService> controller =
            Robolectric.buildService(ScreenCaptureService.class);
        try {
            ScreenCaptureService service = controller.create().get();
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(ScreenCaptureService.ACTION_START), 0, 1));
            assertFalse(ScreenCaptureService.isMirrorRunning());
        } finally { controller.destroy(); }
    }
}
