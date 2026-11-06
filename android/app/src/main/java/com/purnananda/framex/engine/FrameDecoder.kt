package com.purnananda.framex.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes every frame of a video, in presentation order, into full-resolution RGBA bitmaps.
 *
 * MediaCodec renders each decoded frame to a SurfaceTexture; the frame is drawn into an
 * offscreen EGL surface of the video's exact size and read back with glReadPixels. Every
 * output buffer is rendered and waited for before the next is released, so no frame is
 * skipped or duplicated. Rotation metadata is not applied here (see [FrameExtractor]).
 */
class FrameDecoder(private val context: Context, private val uri: Uri) {

    /**
     * Calls [onFrame] for each decoded frame with its 0-based index. The bitmap is reused
     * between calls: copy it if it must outlive the callback. Return false to stop early.
     */
    fun decode(cancel: AtomicBoolean, onFrame: (index: Int, ptsUs: Long, bitmap: Bitmap) -> Boolean): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var output: OutputSurface? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = VideoProbe.findVideoTrack(extractor)
            if (track < 0) throw ExtractionException("No video stream found.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            // Keep the decoder from applying rotation itself; we rotate the bitmap explicitly.
            if (format.containsKey(MediaFormat.KEY_ROTATION)) format.setInteger(MediaFormat.KEY_ROTATION, 0)

            output = OutputSurface(width, height)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw ExtractionException("This phone has no decoder for ${mime.removePrefix("video/").uppercase()} video.", e)
            }
            codec.configure(format, output.surface, null, 0)
            codec.start()
            return loop(extractor, codec, output, cancel, onFrame)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            output?.release()
            extractor.release()
        }
    }

    private fun loop(
        extractor: MediaExtractor, codec: MediaCodec, output: OutputSurface,
        cancel: AtomicBoolean, onFrame: (Int, Long, Bitmap) -> Boolean,
    ): Int {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var index = 0
        var idleOutputs = 0
        while (!outputDone) {
            if (cancel.get()) throw CancelledException()
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone && ++idleOutputs > 600) {  // ~6 s with no output after EOS
                        throw ExtractionException("The decoder stopped responding.")
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIdx < 0 -> Unit
                else -> {
                    idleOutputs = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size != 0
                    codec.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        output.awaitNewImage()
                        output.drawImage()
                        val bmp = output.readBitmap()
                        if (!onFrame(index, info.presentationTimeUs, bmp)) return index + 1
                        index++
                    }
                    if (eos) outputDone = true
                }
            }
        }
        return index
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}

/** Offscreen EGL pbuffer + SurfaceTexture that MediaCodec decodes into. */
private class OutputSurface(private val width: Int, private val height: Int) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val handlerThread = HandlerThread("framex-surface").apply { start() }
    private val lock = Object()
    private var frameAvailable = false
    private val texture: SurfaceTexture
    val surface: Surface
    private val renderer = TextureRenderer()
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    init {
        setupEgl()
        renderer.setup()
        texture = SurfaceTexture(renderer.textureId)
        texture.setOnFrameAvailableListener({
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }, Handler(handlerThread.looper))
        surface = Surface(texture)
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No EGL config" }
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context failed" }
        eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "Could not create a ${width}x$height drawing surface (the video may be larger than this phone's GPU supports)."
        }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    fun awaitNewImage() {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!frameAvailable) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) throw ExtractionException("Timed out waiting for a decoded frame.")
                lock.wait(wait)
            }
            frameAvailable = false
        }
        texture.updateTexImage()
    }

    fun drawImage() {
        GLES20.glViewport(0, 0, width, height)
        renderer.draw(texture)
    }

    /** Reads the frame. The quad is drawn upside down, so glReadPixels' bottom-up rows come out top-down. */
    fun readBitmap(): Bitmap {
        pixels.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        return bitmap
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { texture.release() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        handlerThread.quitSafely()
        bitmap.recycle()
    }
}

/** Draws an external OES texture as a full-viewport quad, flipped vertically. */
private class TextureRenderer {
    var textureId = 0
        private set
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uSt = 0
    private val stMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    // x, y, u, v — texture v is inverted so the image lands upside down in the framebuffer.
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        ))
        position(0)
    }

    fun setup() {
        program = link(
            """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }
            """.trimIndent(),
        )
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSt = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(st: SurfaceTexture) {
        st.getTransformMatrix(stMatrix)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, identity, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader error: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link error: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }
}
