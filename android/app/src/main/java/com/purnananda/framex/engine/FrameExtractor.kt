package com.purnananda.framex.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/**
 * Extracts every decoded frame of a video into [workDir]/frames as 1.png … N.png
 * (or zero-padded), at the video's display resolution, then validates the result.
 */
class FrameExtractor(private val context: Context) {

    fun extract(
        uri: Uri,
        info: VideoInfo,
        settings: ExtractSettings,
        workDir: File,
        cancel: AtomicBoolean,
        onProgress: (Progress) -> Unit,
    ): ExtractResult {
        val start = (settings.startFrame ?: 1).coerceAtLeast(1)
        val end = settings.endFrame?.coerceAtMost(info.frameCount) ?: info.frameCount
        if (start > info.frameCount) throw ExtractionException("Start frame is beyond the last frame (${info.frameCount}).")
        if (end < start) throw ExtractionException("The end frame must come after the start frame.")
        val expected = end - start + 1

        val raw = File(workDir, "raw").apply { deleteRecursively(); mkdirs() }
        val framesDir = File(workDir, "frames").apply { deleteRecursively() }

        checkDisk(workDir, estimateFrameBytes(uri, info, settings) * expected.toLong())

        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
        val pool: ExecutorService = Executors.newFixedThreadPool(threads)
        val inFlight = Semaphore(threads + 1) // bounds memory: at most this many frames held at once
        val futures = ArrayList<Future<Frame>>(expected)
        val done = AtomicInteger(0)
        val t0 = System.nanoTime()
        val matrix = if (info.rotation != 0) Matrix().apply { postRotate(info.rotation.toFloat()) } else null
        var lastReport = 0L

        fun report() {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val n = done.get()
            val speed = if (elapsed > 0) n / elapsed else 0.0
            val eta = if (speed > 0) (expected - n) / speed else null
            onProgress(Progress(Stage.EXTRACTING, n, expected, speed, eta))
        }

        try {
            FrameDecoder(context, uri).decode(cancel) { index, _, bmp ->
                val number = index + 1
                if (number < start) return@decode true
                if (number > end) return@decode false
                inFlight.acquire()
                if (cancel.get()) { inFlight.release(); throw CancelledException() }
                // Copy (and rotate) on the decode thread; the decoder reuses its bitmap.
                val frame = if (matrix != null) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, false)
                else bmp.copy(Bitmap.Config.ARGB_8888, false)
                val seq = number - start + 1
                futures += pool.submit<Frame> {
                    try {
                        encode(frame, settings, File(raw, String.format(java.util.Locale.ROOT, "%09d.%s", seq, settings.format.ext)))
                    } finally {
                        frame.recycle()
                        inFlight.release()
                        done.incrementAndGet()
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastReport > 200) { lastReport = now; report() }
                number < end
            }
            val written = futures.map { f ->
                try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
                    throw ExtractionException("Could not save a frame: ${e.cause?.message}", e.cause)
                }
            }
            if (cancel.get()) throw CancelledException()
            report()
            if (written.isEmpty()) throw ExtractionException("The decoder produced no frames.")

            onProgress(Progress(Stage.VALIDATING, written.size, expected, 0.0, null))
            return finish(written, raw, framesDir, info, settings, expected)
        } catch (e: Throwable) {
            pool.shutdownNow()
            raw.deleteRecursively()
            framesDir.deleteRecursively()
            throw e
        } finally {
            pool.shutdown()
        }
    }

    private fun encode(bmp: Bitmap, s: ExtractSettings, out: File): Frame {
        val buf = ByteArrayOutputStream(bmp.width * bmp.height)
        val ok = when (s.format) {
            ImageFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, buf)
            ImageFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, s.quality, buf)
            ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                bmp.compress(if (s.quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY, s.quality, buf)
            } else {
                @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, s.quality, buf)
            }
        }
        if (!ok) throw ExtractionException("Image encoding failed for ${out.name}.")
        val bytes = buf.toByteArray()
        out.writeBytes(bytes)
        val crc = CRC32().apply { update(bytes) }.value
        return Frame(out.name, out, crc, bytes.size.toLong())
    }

    /** Rename to final sequential names and validate everything before the ZIP is built. */
    private fun finish(written: List<Frame>, raw: File, framesDir: File, info: VideoInfo, s: ExtractSettings, expected: Int): ExtractResult {
        val warnings = mutableListOf<String>()
        val total = written.size
        val pad = if (s.zeroPad) maxOf(4, total.toString().length) else 0
        if (!raw.renameTo(framesDir)) throw ExtractionException("Could not organise the extracted frames.")

        var w = 0; var h = 0; var bytes = 0L
        val frames = written.mapIndexed { i, f ->
            val n = i + 1
            if (f.name != String.format(java.util.Locale.ROOT, "%09d.%s", n, s.format.ext)) throw ExtractionException("Frame sequence broken at image $n.")
            val name = if (pad > 0) String.format(java.util.Locale.ROOT, "%0${pad}d.%s", n, s.format.ext) else "$n.${s.format.ext}"
            val src = File(framesDir, f.name)
            val dst = File(framesDir, name)
            if (src != dst && !src.renameTo(dst)) throw ExtractionException("Could not name frame $name.")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dst.path, opts)
            if (opts.outWidth <= 0) throw ExtractionException("Validation failed: $name is not a readable image.")
            if (i == 0) { w = opts.outWidth; h = opts.outHeight }
            else if (opts.outWidth != w || opts.outHeight != h) throw ExtractionException("Validation failed: $name has a different size.")
            if (dst.length() != f.size) throw ExtractionException("Validation failed: $name was not written completely.")
            bytes += f.size
            Frame(name, dst, f.crc32, f.size)
        }
        val onDisk = framesDir.list()?.size ?: 0
        if (onDisk != total) throw ExtractionException("Validation failed: expected $total images, found $onDisk.")
        if (w != info.displayWidth || h != info.displayHeight) {
            throw ExtractionException("Validation failed: frames are ${w}×$h, the video is ${info.displayWidth}×${info.displayHeight}.")
        }
        if (total != expected) {
            warnings += "Decoded $total frames; the stream index lists $expected. The decoded count is the true number of frames."
        }
        return ExtractResult(frames, w, h, bytes, warnings)
    }

    /** Encodes one real frame from the middle of the video to estimate output size. */
    fun estimateFrameBytes(uri: Uri, info: VideoInfo, s: ExtractSettings): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bmp = r.getFrameAtTime(info.durationUs / 2, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return info.displayWidth.toLong() * info.displayHeight * 2
            val buf = ByteArrayOutputStream()
            when (s.format) {
                ImageFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, buf)
                ImageFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, s.quality, buf)
                ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                    bmp.compress(if (s.quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY, s.quality, buf)
                } else {
                    @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, s.quality, buf)
                }
            }
            bmp.recycle()
            buf.size().toLong()
        } catch (_: Exception) {
            info.displayWidth.toLong() * info.displayHeight * 2
        } finally {
            r.release()
        }
    }

    private fun checkDisk(dir: File, needed: Long) {
        val free = StatFs(dir.path).availableBytes
        if (needed * 2.2 > free) {
            throw ExtractionException(
                "Not enough free storage: about %.2f GB is needed for the frames and ZIP, %.2f GB is free."
                    .format(needed * 2.2 / 1e9, free / 1e9))
        }
    }
}
