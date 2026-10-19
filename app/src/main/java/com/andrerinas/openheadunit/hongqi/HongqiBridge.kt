package com.andrerinas.openheadunit.hongqi

import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.main.MainActivity
import com.hongqi.flighting.integration.FeatureIntegration
import com.hongqi.flighting.integration.FeatureNavigation

object HongqiBridge {
    fun install(app: App) {
        App.provide(app).videoDecoder.instrumentDimensionsListener = { width, height ->
            DiAutoVideoHub.current?.size(width,height)
        }
        FeatureIntegration.install(app, object : FeatureNavigation.Host {
            override fun videoConnected(context: Context) = App.provide(context).commManager.isConnected
            override fun attachVideo(context: Context, surface: android.view.Surface, width: Int, height: Int) {
                val component = App.provide(context)
                check(component.commManager.isConnected) { "DiAuto is disconnected" }
                val hub = DiAutoVideoHub.instance
                lateinit var failureCallback: (String) -> Unit
                failureCallback = { name ->
                    if(name == "instrument") android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if(hub.onOutputFailure !== failureCallback) return@post
                        context.startService(Intent(context, com.hongqi.flighting.service.ScreenCaptureService::class.java)
                            .setAction(com.hongqi.flighting.service.ScreenCaptureService.ACTION_STOP))
                        android.widget.Toast.makeText(context,"DiAuto 视频输出失败，请查看日志",android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                hub.onOutputFailure = failureCallback
                val dimensions = component.videoDecoder.streamDimensions()
                hub.size(dimensions.first, dimensions.second)
                hub.attach("instrument", surface, width, height)
                DiAutoVideoHub.requested = true
                component.videoDecoder.softwareYuvFrameSink = hub
                component.videoDecoder.setSurface(hub.input)
                component.commManager.send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain=true, unsolicited=false))
            }
            override fun detachVideo(context: Context, completion: Runnable) {
                val hub = DiAutoVideoHub.current
                hub?.onOutputFailure = null
                try { hub?.detach("instrument") }
                catch(error: RuntimeException) {
                    android.util.Log.e("DiAutoVideoHub","Cannot release instrument output",error)
                    DiAutoVideoHub.requested = false
                    if(hub != null) App.provide(context).videoDecoder.stopIfCurrentSurface(hub.input,
                        com.andrerinas.openheadunit.decoder.DecoderStopPolicy.REASON_SURFACE_DESTROYED)
                }
                if (hub != null && !hub.mainActive) {
                    val component = App.provide(context)
                    if(component.commManager.isConnected) component.commManager.send(
                        com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain=false, unsolicited=false))
                    component.videoDecoder.stopIfCurrentSurface(hub.input,
                        com.andrerinas.openheadunit.decoder.DecoderStopPolicy.REASON_SURFACE_DESTROYED)
                }
                completion.run()
            }
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
