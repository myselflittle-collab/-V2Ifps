package com.purnananda.framex.engine

enum class ImageFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
}

data class VideoInfo(
    val filename: String,
    val mime: String,
    val width: Int,              // decoded (stored) size
    val height: Int,
    val rotation: Int,           // 0, 90, 180, 270 (clockwise, from metadata)
    val durationUs: Long,
    val frameCount: Int,         // counted from the stream's samples (1 sample = 1 frame)
    val nominalFps: Float,       // container's declared rate, 0 if absent
    val isVfr: Boolean,
    val sizeBytes: Long,
) {
    val displayWidth get() = if (rotation % 180 == 90) height else width
    val displayHeight get() = if (rotation % 180 == 90) width else height
    val durationSec get() = durationUs / 1_000_000.0
    /** Average frame rate = frames / duration, true for VFR videos too. */
    val fps: Double
        get() = if (frameCount > 0 && durationUs > 0) frameCount / durationSec else nominalFps.toDouble()
    val codec: String get() = mime.removePrefix("video/").uppercase()
}

data class ExtractSettings(
    val format: ImageFormat = ImageFormat.PNG,
    val quality: Int = 100,
    val zeroPad: Boolean = false,
    val startFrame: Int? = null,   // 1-based, inclusive
    val endFrame: Int? = null,     // 1-based, inclusive
) {
    val isRange get() = startFrame != null || endFrame != null
}

data class Progress(
    val stage: Stage,
    val framesDone: Int,
    val expected: Int,
    val speedFps: Double,
    val etaSec: Double?,
) {
    /** Extraction 0–90 %, validation 90–92 %, ZIP 92–100 %. 100 only when done. */
    val percent: Double
        get() = when (stage) {
            Stage.EXTRACTING -> if (expected > 0) 90.0 * (framesDone.toDouble() / expected).coerceAtMost(1.0) else 0.0
            Stage.VALIDATING -> 90.0
            Stage.ZIPPING -> 92.0 + 7.9 * (framesDone.toDouble() / expected.coerceAtLeast(1)).coerceAtMost(1.0)
            Stage.DONE -> 100.0
        }
}

enum class Stage(val label: String) {
    EXTRACTING("Extracting frames"), VALIDATING("Validating frames"), ZIPPING("Packaging ZIP"), DONE("Complete")
}

data class Frame(val name: String, val file: java.io.File, val crc32: Long, val size: Long)

data class ExtractResult(
    val frames: List<Frame>,
    val width: Int,
    val height: Int,
    val totalBytes: Long,
    val warnings: List<String>,
)

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class CancelledException : Exception("Cancelled")
