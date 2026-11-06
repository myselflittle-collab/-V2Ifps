package com.purnananda.framex.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purnananda.framex.engine.CancelledException
import com.purnananda.framex.engine.ExtractResult
import com.purnananda.framex.engine.ExtractSettings
import com.purnananda.framex.engine.ExtractionException
import com.purnananda.framex.engine.FrameExtractor
import com.purnananda.framex.engine.Progress
import com.purnananda.framex.engine.Stage
import com.purnananda.framex.engine.VideoInfo
import com.purnananda.framex.engine.VideoProbe
import com.purnananda.framex.engine.ZipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

sealed interface Phase {
    data object Empty : Phase
    data object Probing : Phase
    data object Ready : Phase
    data class Running(val progress: Progress) : Phase
    data class Done(val result: ExtractResult, val zipUri: Uri, val zipName: String, val zipBytes: Long, val verifiedEntries: Int) : Phase
}

data class UiState(
    val phase: Phase = Phase.Empty,
    val uri: Uri? = null,
    val info: VideoInfo? = null,
    val poster: Bitmap? = null,
    val settings: ExtractSettings = ExtractSettings(),
    val estimateBytes: Long? = null,
    val error: String? = null,
    val notice: String? = null,
)

class FrameXViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val extractor = FrameExtractor(app)
    private val jobsRoot = File(app.cacheDir, "jobs")
    private var workDir: File? = null
    private var cancel = AtomicBoolean(false)
    private var running: Job? = null
    private var estimating: Job? = null

    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        // Leftovers from a previous session are never needed again.
        viewModelScope.launch(Dispatchers.IO) { jobsRoot.deleteRecursively() }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun open(uri: Uri) {
        if (_state.value.phase is Phase.Running) return
        reset()
        _state.update { it.copy(phase = Phase.Probing, uri = uri, error = null) }
        viewModelScope.launch {
            try {
                val (info, poster) = withContext(Dispatchers.IO) {
                    val info = VideoProbe.probe(getApplication(), uri)
                    val poster = runCatching {
                        MediaMetadataRetriever().run {
                            try {
                                setDataSource(getApplication(), uri)
                                getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
                            } finally { release() }
                        }
                    }.getOrNull()
                    info to poster
                }
                _state.update { it.copy(phase = Phase.Ready, info = info, poster = poster) }
                refreshEstimate()
            } catch (e: Exception) {
                _state.update { UiState(error = e.message ?: "This file can't be read as a video.") }
            }
        }
    }

    fun updateSettings(s: ExtractSettings) {
        _state.update { it.copy(settings = s) }
        refreshEstimate()
    }

    private fun refreshEstimate() {
        val st = _state.value
        val uri = st.uri ?: return
        val info = st.info ?: return
        estimating?.cancel()
        estimating = viewModelScope.launch {
            delay(250)
            val per = withContext(Dispatchers.IO) { extractor.estimateFrameBytes(uri, info, st.settings) }
            _state.update { it.copy(estimateBytes = per * plannedFrames(it)) }
        }
    }

    fun plannedFrames(st: UiState = _state.value): Int {
        val info = st.info ?: return 0
        val start = (st.settings.startFrame ?: 1).coerceAtLeast(1)
        val end = (st.settings.endFrame ?: info.frameCount).coerceAtMost(info.frameCount)
        return (end - start + 1).coerceAtLeast(0)
    }

    fun extract() {
        val st = _state.value
        val uri = st.uri ?: return
        val info = st.info ?: return
        if (st.phase is Phase.Running) return
        cleanupWork()
        cancel = AtomicBoolean(false)
        val flag = cancel
        val dir = File(jobsRoot, "job_${System.currentTimeMillis()}").apply { mkdirs() }
        workDir = dir
        _state.update { it.copy(phase = Phase.Running(Progress(Stage.EXTRACTING, 0, plannedFrames(it), 0.0, null)), error = null, notice = null) }

        running = viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.Default) {
                    val result = extractor.extract(uri, info, st.settings, dir, flag) { p ->
                        _state.update { s -> if (s.phase is Phase.Running) s.copy(phase = Phase.Running(p)) else s }
                    }
                    saveZip(result, flag)
                }
                _state.update { it.copy(phase = done) }
            } catch (e: CancelledException) {
                cleanupWork()
                _state.update { it.copy(phase = Phase.Ready, notice = "Extraction stopped. Temporary files were removed.") }
            } catch (e: Throwable) {
                cleanupWork()
                val msg = when (e) {
                    is ExtractionException -> e.message
                    is OutOfMemoryError -> "The phone ran out of memory. Try JPEG, or a shorter frame range."
                    else -> "Extraction failed: ${e.message ?: e.javaClass.simpleName}"
                }
                _state.update { it.copy(phase = Phase.Ready, error = msg) }
            }
        }
    }

    /** Writes the ZIP to Download/FrameX. It stays hidden (IS_PENDING) until it passes verification. */
    private fun saveZip(result: ExtractResult, flag: AtomicBoolean): Phase.Done {
        val resolver = getApplication<Application>().contentResolver
        val name = "FrameX_Extracted_Frames.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FrameX")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ExtractionException("Could not create the ZIP in Downloads.")
        val total = result.frames.size
        try {
            _state.update { it.copy(phase = Phase.Running(Progress(Stage.ZIPPING, 0, total * 2, 0.0, null))) }
            resolver.openOutputStream(uri, "w")!!.use { out ->
                ZipStore.write(result.frames.map { ZipStore.Entry(it.name, it.file, it.crc32, it.size) }, out,
                    onEntry = { n -> if (n % 25 == 0) _state.update { it.copy(phase = Phase.Running(Progress(Stage.ZIPPING, n, total * 2, 0.0, null))) } },
                    isCancelled = { flag.get() })
            }
            val verified = resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    ZipStore.verify(ch, result.frames.map { it.name }) { n ->
                        if (n % 25 == 0) _state.update { it.copy(phase = Phase.Running(Progress(Stage.ZIPPING, total + n, total * 2, 0.0, null))) }
                    }
                }
            }
            val size = resolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
            return Phase.Done(result, uri, finalName, size, verified)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            if (e is CancelledException) throw e
            throw if (e is ExtractionException) e else ExtractionException("The ZIP could not be saved: ${e.message}", e)
        }
    }

    fun stop() { cancel.set(true) }

    fun extractAgain() {
        if (_state.value.phase is Phase.Done) {
            cleanupWork()
            _state.update { it.copy(phase = Phase.Ready) }
            refreshEstimate()
        }
    }

    fun reset() {
        cancel.set(true)
        running?.cancel()
        cleanupWork()
        _state.value = UiState()
    }

    private fun cleanupWork() {
        thumbs.evictAll()
        workDir?.let { d -> Thread { d.deleteRecursively() }.start() }
        workDir = null
    }

    /** Saves one frame to Pictures/FrameX. */
    fun saveFrameToPictures(file: File, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val mime = when (file.extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "FrameX_${file.name}")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FrameX")
            }
            val msg = runCatching {
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                "Saved to Pictures/FrameX"
            }.getOrElse { "Could not save the image: ${it.message}" }
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    suspend fun thumbnail(file: File, targetPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${file.path}@$targetPx"
        thumbs.get(key) ?: run {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { thumbs.put(key, it) }
        }
    }

    suspend fun fullImage(file: File, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    override fun onCleared() {
        cancel.set(true)
        cleanupWork()
    }
}
