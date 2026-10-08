package com.andrerinas.openheadunit.hongqi

import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.main.MainActivity
import com.hongqi.flighting.integration.FeatureIntegration
import com.hongqi.flighting.integration.FeatureNavigation

object HongqiBridge {
    fun install(app: App) {
        FeatureIntegration.install(app, object : FeatureNavigation.Host {
            override fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)
            override fun sendMediaKey(context: Context, keyCode: Int): Boolean {
                val manager = App.provide(context).commManager
                if (!manager.isConnected) return false
                // A distinct origin bypasses remapping of the generated click back into itself.
                manager.sendKey(keyCode, true, null, "hongqi-mapped")
                manager.sendKey(keyCode, false, null, "hongqi-mapped")
                return true
            }
        })
    }
}
