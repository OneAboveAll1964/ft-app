package app.ft.projection

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import app.ft.core.DiagLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrameGate(
    private val inWidth: Int,
    private val inHeight: Int,
    private val width: Int,
    private val height: Int,
    private val output: Surface
) {
    private val tag = "Gate"
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var program = 0
    private var textureName = 0
    private var position = -1
    private var coord = -1
    private var transform = -1
    private val matrix = FloatArray(16)
    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var ready = false
    private var fresh = false
    private var empty = 0
    private var lastNs = 0L
    private var newFrames = 0
    private var repeats = 0
    private var skipped = 0
    private var reportedNs = 0L
    @Volatile private var intervalNs = 33_333_333L
    var input: Surface? = null
        private set

    private val tick = Runnable { onTick() }

    fun start(fps: Int): Boolean {
        setRate(fps)
        val t = HandlerThread("FT-FrameGate", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val done = CountDownLatch(1)
        var ok = false
        h.post {
            ok = runCatching { setUp(h) }
                .onFailure { DiagLog.e(tag, "frame gate unavailable, the car gets every frame", it) }
                .isSuccess
            done.countDown()
        }
        if (!done.await(3, TimeUnit.SECONDS)) ok = false
        if (!ok) stop()
        return ok
    }

    fun setRate(fps: Int) {
        intervalNs = 1_000_000_000L / fps.coerceIn(1, 60)
    }

    private fun setUp(h: Handler) {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(d != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(d, version, 0, version, 1)) { "EGL did not start" }
        display = d
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(d, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config the encoder can take" }
        val config = configs[0] ?: error("no EGL config")
        val ctx = EGL14.eglCreateContext(d, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(ctx != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
        context = ctx
        val s = EGL14.eglCreateWindowSurface(d, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "no EGL surface on the encoder" }
        surface = s
        check(EGL14.eglMakeCurrent(d, s, s, ctx)) { "EGL context could not be made current" }
        program = link()
        position = GLES20.glGetAttribLocation(program, "aPosition")
        coord = GLES20.glGetAttribLocation(program, "aCoord")
        transform = GLES20.glGetUniformLocation(program, "uTransform")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureName = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(textureName)
        st.setDefaultBufferSize(inWidth, inHeight)
        st.setOnFrameAvailableListener({ onFrame() }, h)
        texture = st
        input = Surface(st)
        reportedNs = System.nanoTime()
        DiagLog.i(tag, "frames to the car follow the rate the head unit asks for" + if (inWidth != width || inHeight != height) ", ${inWidth}x$inHeight drawn into ${width}x$height" else "")
    }

    private fun onFrame() {
        val st = texture ?: return
        if (runCatching { st.updateTexImage() }.isFailure) return
        if (fresh) skipped++
        fresh = true
        empty = 0
        ready = true
        val h = handler ?: return
        val wait = lastNs + intervalNs - System.nanoTime()
        if (wait <= SLACK_NS) {
            h.removeCallbacks(tick)
            draw()
        } else if (!h.hasCallbacks(tick)) {
            h.postDelayed(tick, (wait + 999_999) / 1_000_000)
        }
    }

    private fun onTick() {
        val h = handler ?: return
        if (!ready) return
        if (!fresh) {
            empty++
            if (empty > IDLE_TICKS && empty % IDLE_EVERY != 0) {
                h.removeCallbacks(tick)
                h.postDelayed(tick, intervalNs / 1_000_000)
                return
            }
        }
        draw()
    }

    private fun draw() {
        val h = handler ?: return
        val st = texture ?: return
        if (!ready) return
        val now = System.nanoTime()
        try {
            st.getTransformMatrix(matrix)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8, quad)
            GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 8, uv)
            GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, surface, now)
            EGL14.eglSwapBuffers(display, surface)
        } catch (t: Throwable) {
            DiagLog.e(tag, "could not pass a frame on", t)
        }
        if (fresh) newFrames++ else repeats++
        fresh = false
        lastNs = now
        h.removeCallbacks(tick)
        h.postDelayed(tick, intervalNs / 1_000_000)
        if (now - reportedNs >= REPORT_NS) {
            DiagLog.i(tag, "last ${(now - reportedNs) / 1_000_000_000}s at ${1_000_000_000L / intervalNs} fps: $newFrames new frames sent, $repeats repeats, $skipped skipped")
            newFrames = 0
            repeats = 0
            skipped = 0
            reportedNs = now
        }
    }

    private fun link(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    fun stop() {
        val h = handler ?: run {
            thread?.quitSafely()
            thread = null
            return
        }
        handler = null
        val done = CountDownLatch(1)
        h.post {
            h.removeCallbacks(tick)
            runCatching { texture?.setOnFrameAvailableListener(null) }
            runCatching { input?.release() }
            runCatching { texture?.release() }
            input = null
            texture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (textureName != 0) GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                }
            }
            program = 0
            textureName = 0
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread?.quitSafely()
        thread = null
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val SLACK_NS = 4_000_000L
        private const val IDLE_TICKS = 8
        private const val IDLE_EVERY = 20
        private const val REPORT_NS = 30_000_000_000L

        private val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aCoord;
            uniform mat4 uTransform;
            varying vec2 vCoord;
            void main() {
                gl_Position = aPosition;
                vCoord = (uTransform * aCoord).xy;
            }
        """.trimIndent()

        private val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vCoord);
            }
        """.trimIndent()

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }
    }
}
