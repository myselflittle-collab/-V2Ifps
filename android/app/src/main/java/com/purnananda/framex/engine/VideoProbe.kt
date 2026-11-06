package com.purnananda.framex.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

object VideoProbe {

    fun findVideoTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    fun probe(context: Context, uri: Uri): VideoInfo {
        val (name, size) = nameAndSize(context, uri)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw ExtractionException("This file can't be read as a video.", e)
        }
        try {
            val track = findVideoTrack(ex)
            if (track < 0) throw ExtractionException("No video stream found in this file.")
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0) throw ExtractionException("Video stream has no valid resolution.")
            val nominal = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching {
                fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
            }.getOrElse { runCatching { fmt.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f) } else 0f

            // Count samples without decoding: for video, one sample decodes to one frame.
            ex.selectTrack(track)
            val times = ArrayList<Long>(4096)
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (ex.sampleTrackIndex == track) times.add(t)
                if (!ex.advance()) break
            }
            if (times.isEmpty()) throw ExtractionException("The video stream contains no frames.")
            times.sort()

            var duration = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (duration <= 0 && times.size > 1) {
                duration = times.last() - times.first() + (times.last() - times.first()) / (times.size - 1)
            }

            // Variable frame rate: frame intervals differ noticeably from each other.
            var vfr = false
            if (times.size > 3) {
                val deltas = (1 until times.size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
                if (deltas.isNotEmpty()) {
                    val median = deltas[deltas.size / 2].toDouble()
                    val outliers = deltas.count { kotlin.math.abs(it - median) > median * 0.25 }
                    vfr = outliers > deltas.size * 0.05
                }
            }

            val rotation = MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(context, uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                    if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
                } finally {
                    r.release()
                }
            }

            return VideoInfo(
                filename = name ?: "video",
                mime = mime,
                width = width, height = height,
                rotation = ((rotation % 360) + 360) % 360,
                durationUs = duration,
                frameCount = times.size,
                nominalFps = nominal,
                isVfr = vfr,
                sizeBytes = size,
            )
        } finally {
            ex.release()
        }
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = uri.lastPathSegment
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        if (size < 0) runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        }
        return name to size.coerceAtLeast(0)
    }
}
