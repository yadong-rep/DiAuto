package com.andrerinas.openheadunit.hongqi

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.andrerinas.openheadunit.decoder.SoftwareYuvFrameSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One decoder input, two EGL consumers. Its thread does not belong to an Activity. */
class DiAutoVideoHub private constructor() : SoftwareYuvFrameSink {
    companion object {
        private val lazyHub = lazy { DiAutoVideoHub() }
        val instance: DiAutoVideoHub get() = lazyHub.value
        val current: DiAutoVideoHub? get() = if(lazyHub.isInitialized()) lazyHub.value else null
        @Volatile var requested = false
        fun fit(videoW: Int, videoH: Int, outputW: Int, outputH: Int): Pair<Float, Float> {
            if (minOf(videoW, videoH, outputW, outputH) <= 0) return 1f to 1f
            val video = videoW.toFloat() / videoH
            val output = outputW.toFloat() / outputH
            return if (video > output) 1f to output / video else video / output to 1f
        }
    }
    private val thread = HandlerThread("DiAuto-video-outputs").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var display: EGLDisplay
    private lateinit var config: EGLConfig
    private lateinit var context: EGLContext
    private lateinit var offscreen: EGLSurface
    private lateinit var texture: SurfaceTexture
    lateinit var input: Surface
        private set
    private var oes = 0
    private var oesProgram = 0
    private var yuvProgram = 0
    private val yuvTextures = IntArray(3)
    private val transform = FloatArray(16)
    private val vertices = buffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val coords = buffer(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f))
    private val yuvCoords = buffer(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))
    private var videoW = 1280
    private var videoH = 720
    @Volatile private var mainMirror = 1f
    fun mirrorMain(scaleX: Float) { mainMirror = if(scaleX < 0) -1f else 1f }
    private data class Output(val native: Surface, val egl: EGLSurface, var w: Int, var h: Int)
    private val outputs = mutableMapOf<String, Output>()
    @Volatile var instrumentActive = false
        private set
    @Volatile var mainActive = false
        private set
    @Volatile var lastFrameMs = 0L
        private set
    @Volatile var longFrames = 0L
        private set
    @Volatile var onOutputFailure: ((String) -> Unit)? = null
    private val pendingYuv = AtomicBoolean(false)
    private val packedPlanes = arrayOfNulls<ByteBuffer>(3)

    init {
        try { sync { initialize() } }
        catch(error: RuntimeException) {
            handler.post {
                if(::input.isInitialized) input.release()
                if(::texture.isInitialized) { texture.setOnFrameAvailableListener(null);texture.release() }
                if(::display.isInitialized) {
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                    if(::offscreen.isInitialized) EGL14.eglDestroySurface(display,offscreen)
                    if(::context.isInitialized) EGL14.eglDestroyContext(display,context)
                    EGL14.eglTerminate(display)
                }
                thread.quitSafely()
            }
            throw error
        }
    }
    private fun sync(block: () -> Unit) {
        if (Thread.currentThread() === thread) { block(); return }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post { try { block() } catch (t: Throwable) { failure = t } finally { done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS)) { "Video renderer did not respond" }
        failure?.let { throw IllegalStateException("Video renderer unavailable", it) }
    }
    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0] > 0)
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT)
        offscreen = EGL14.eglCreatePbufferSurface(display,config,
            intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        current(offscreen)
        oesProgram = program("""#extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 uv; uniform samplerExternalOES image;
            void main(){gl_FragColor=texture2D(image,uv);}""")
        yuvProgram = program("""precision mediump float; varying vec2 uv;
            uniform sampler2D y; uniform sampler2D u; uniform sampler2D v;
            void main(){float Y=1.164383*(texture2D(y,uv).r-0.0625);
            float U=texture2D(u,uv).r-0.5;float V=texture2D(v,uv).r-0.5;
            gl_FragColor=vec4(Y+1.792741*V,Y-0.213249*U-0.532909*V,Y+2.112402*U,1.0);}""")
        val ids = IntArray(1); GLES20.glGenTextures(1, ids,0); oes = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes)
        parameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glGenTextures(3,yuvTextures,0)
        texture = SurfaceTexture(oes).apply {
            setDefaultBufferSize(videoW,videoH)
            setOnFrameAvailableListener({
                try {
                    current(offscreen)
                    updateTexImage(); getTransformMatrix(transform)
                    draw(oesProgram, false)
                } catch(e: RuntimeException) { renderingFailed(e) }
            }, handler)
        }
        input = Surface(texture)
    }
    fun size(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        handler.post { videoW=w;videoH=h;texture.setDefaultBufferSize(w,h) }
    }
    fun attach(name: String, surface: Surface, w: Int, h: Int) = sync {
        remove(name)
        check(surface.isValid)
        val egl = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
        check(egl != EGL14.EGL_NO_SURFACE) { "Cannot open $name video output" }
        outputs[name] = Output(surface,egl,w,h)
        instrumentActive = outputs.containsKey("instrument"); mainActive = outputs.containsKey("main")
    }
    fun detach(name: String, expected: Surface? = null) = sync {
        if(expected == null || outputs[name]?.native === expected) remove(name)
    }
    private fun remove(name: String) {
        current(offscreen)
        outputs.remove(name)?.let { EGL14.eglDestroySurface(display,it.egl) }
        instrumentActive = outputs.containsKey("instrument"); mainActive = outputs.containsKey("main")
    }
    private fun current(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "Cannot bind video output" }
    }
    private fun draw(p: Int, yuv: Boolean) {
        val invalid = mutableListOf<String>()
        outputs.forEach { (name, out) ->
            if (!out.native.isValid || !EGL14.eglMakeCurrent(display,out.egl,out.egl,context)) {
                invalid.add(name); return@forEach
            }
            EGL14.eglSwapInterval(display,0)
            GLES20.glViewport(0,0,out.w,out.h)
            GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(p)
            // The instrument always fits the full stream; no source-region crop exists.
            val scale = fit(videoW,videoH,out.w,out.h)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p,"scale"),
                scale.first * if(name == "main") mainMirror else 1f,scale.second)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p,"transform"),1,false,transform,0)
            val a=GLES20.glGetAttribLocation(p,"position"); val b=GLES20.glGetAttribLocation(p,"coord")
            vertices.position(0); val tc=if(yuv)yuvCoords else coords;tc.position(0)
            GLES20.glEnableVertexAttribArray(a);GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,vertices)
            GLES20.glEnableVertexAttribArray(b);GLES20.glVertexAttribPointer(b,2,GLES20.GL_FLOAT,false,0,tc)
            if (yuv) {
                listOf("y","u","v").forEachIndexed { i,n ->
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0+i);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,yuvTextures[i])
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(p,n),i)
                }
            } else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(p,"image"),0)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            if (!EGL14.eglSwapBuffers(display,out.egl)) invalid.add(name)
            else if (name == "main") {
                val now=SystemClock.elapsedRealtime()
                if(lastFrameMs>0 && now-lastFrameMs>1200) longFrames++
                lastFrameMs=now
            }
        }
        current(offscreen); invalid.forEach { remove(it); onOutputFailure?.invoke(it) }
    }
    override fun renderYuv420Frame(width: Int, height: Int, yPlane: ByteBuffer, yStride: Int,
        uPlane: ByteBuffer, uStride: Int, vPlane: ByteBuffer, vStride: Int): Boolean {
        if(!pendingYuv.compareAndSet(false,true)) return true // Keep latency bounded.
        try {
            val cw=(width+1)/2;val ch=(height+1)/2
            val planes=listOf(pack(yPlane,yStride,width,height,0),pack(uPlane,uStride,cw,ch,1),pack(vPlane,vStride,cw,ch,2))
            handler.post {
                try {
                    current(offscreen); videoW=width;videoH=height; Matrix.setIdentityM(transform,0)
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1)
                    planes.forEachIndexed { i,bytes ->
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,yuvTextures[i]);parameters(GLES20.GL_TEXTURE_2D)
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_LUMINANCE,
                            if(i==0)width else cw,if(i==0)height else ch,0,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,bytes)
                    }
                    draw(yuvProgram,true)
                } catch(e: RuntimeException) { renderingFailed(e) }
                finally { pendingYuv.set(false) }
            }
            return true
        } catch (e: RuntimeException) { pendingYuv.set(false); return false }
    }
    private fun renderingFailed(error: RuntimeException) {
        android.util.Log.e("DiAutoVideoHub","Video output failed",error)
        if(instrumentActive) onOutputFailure?.invoke("instrument")
    }
    private fun pack(source: ByteBuffer, stride: Int, w: Int, h: Int, index: Int): ByteBuffer {
        require(stride>=w && w>0 && h>0)
        val data=source.duplicate();val base=data.position()
        require(data.remaining().toLong() >= (h-1L)*stride+w)
        val result=packedPlanes[index]?.takeIf { it.capacity() == w*h }
            ?: ByteBuffer.allocateDirect(w*h).also { packedPlanes[index]=it }
        result.clear()
        for(row in 0 until h) { data.limit(base+row*stride+w);data.position(base+row*stride);result.put(data) }
        result.flip();return result
    }
    private fun parameters(target: Int) {
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
    }
    private fun program(fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s)
            val ok=IntArray(1);GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0]!=0) { GLES20.glGetShaderInfoLog(s) };return s
        }
        val v=shader(GLES20.GL_VERTEX_SHADER,"""attribute vec2 position;attribute vec2 coord;
            uniform vec2 scale;uniform mat4 transform;varying vec2 uv;
            void main(){gl_Position=vec4(position*scale,0.0,1.0);uv=(transform*vec4(coord,0.0,1.0)).xy;}""")
        val f=shader(GLES20.GL_FRAGMENT_SHADER,fragment);val p=GLES20.glCreateProgram()
        GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p)
        val ok=IntArray(1);GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0)
        check(ok[0]!=0) { GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p
    }
    private fun buffer(data: FloatArray) = ByteBuffer.allocateDirect(data.size*4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data);position(0) }
}
