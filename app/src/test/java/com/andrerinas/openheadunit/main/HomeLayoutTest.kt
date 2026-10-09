package com.andrerinas.openheadunit.main

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

// Layout tests use no TLS; the app's Conscrypt library targets Android, not desktop JVMs.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class HomeLayoutTest {
    @Test
    @Config(qualifiers = "port")
    fun portraitHomeContainsAllInteractiveViews() = checkHomeViews()

    @Test
    @Config(qualifiers = "land")
    fun landscapeHomeContainsAllInteractiveViews() = checkHomeViews()

    private fun checkHomeViews() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val home = LayoutInflater.from(context).inflate(R.layout.fragment_home, null)
        for (id in intArrayOf(R.id.hongqi_features_button, R.id.wifi_button,
                R.id.usb_button, R.id.settings_button, R.id.exit_button, R.id.connection_status)) {
            assertNotNull(context.resources.getResourceEntryName(id), home.findViewById<View>(id))
        }
    }
}
