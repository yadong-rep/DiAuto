package com.andrerinas.openheadunit.hongqi

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.andrerinas.openheadunit.view.IProjectionView

class DiAutoProjectionView(context: Context) : SurfaceView(context), IProjectionView, SurfaceHolder.Callback {
    private val hub=DiAutoVideoHub.instance
    private val callbacks=mutableSetOf<IProjectionView.Callbacks>()
    private var ready=false
    init { holder.addCallback(this) }
    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        if(ready) { callback.onSurfaceCreated(hub.input);callback.onSurfaceChanged(hub.input,width,height) }
    }
    override fun removeCallback(callback: IProjectionView.Callbacks) { callbacks.remove(callback) }
    override fun surfaceCreated(holder: SurfaceHolder) { callbacks.toList().forEach { it.onSurfaceCreated(hub.input) } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        hub.attach("main",holder.surface,width,height);ready=true
        callbacks.toList().forEach { it.onSurfaceChanged(hub.input,width,height) }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready=false;hub.detach("main",holder.surface)
        callbacks.toList().forEach { it.onSurfaceDestroyed(hub.input) }
    }
    override fun setVideoSize(width: Int,height: Int) { hub.size(width,height) }
    override fun setVideoScale(scaleX: Float,scaleY: Float) { hub.mirrorMain(scaleX) }
    override fun lastFrameDrawnMs()=hub.lastFrameMs
    override fun longFrameEvents()=hub.longFrames
}
