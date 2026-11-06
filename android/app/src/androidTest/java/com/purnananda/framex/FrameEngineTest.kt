package com.purnananda.framex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purnananda.framex.engine.ExtractResult
import com.purnananda.framex.engine.ExtractSettings
import com.purnananda.framex.engine.FrameExtractor
import com.purnananda.framex.engine.ImageFormat
import com.purnananda.framex.engine.VideoProbe
import com.purnananda.framex.engine.ZipStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Runs the real engine on an emulator against videos made with FFmpeg (see assets/).
 *
 * Each test video has a white vertical bar at x = (sourceFrameIndex * 5) mod 320 below y = 40,
 * and a solid red box in the top-left corner. Checking the bar in every extracted image proves
 * no frame was skipped, duplicated or shifted; the red box proves the image isn't flipped.
 */
@RunWith(AndroidJUnit4::class)
class FrameEngineTest {
    private val testCtx: Context = InstrumentationRegistry.getInstrumentation().context
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expected = JSONObject(testCtx.assets.open("expected.json").bufferedReader().readText())

    private fun asset(name: String): Uri {
        val f = File(ctx.cacheDir, "test_$name")
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun run(name: String, settings: ExtractSettings = ExtractSettings()): Pair<ExtractResult, com.purnananda.framex.engine.VideoInfo> {
        val uri = asset(name)
        val info = VideoProbe.probe(ctx, uri)
        val dir = File(ctx.cacheDir, "job_${name}_${System.nanoTime()}").apply { mkdirs() }
        val result = FrameExtractor(ctx).extract(uri, info, settings, dir, AtomicBoolean(false)) {}
        return result to info
    }

    private fun bitmap(f: File): Bitmap = BitmapFactory.decodeFile(f.path)!!

    private fun isWhite(p: Int) = (p shr 16 and 0xFF) > 200 && (p shr 8 and 0xFF) > 200 && (p and 0xFF) > 200
    private fun isRed(p: Int) = (p shr 16 and 0xFF) > 170 && (p shr 8 and 0xFF) < 90 && (p and 0xFF) < 90

    /**
     * The bar for source frame k fills columns [x0, x0+9]. Both inner edges must be white and the
     * pixels just outside both edges must not be: a neighbouring frame (bar 5 px away) fails this.
     * Validated against FFmpeg's own decode: every real frame passes, every ±1 shift fails.
     */
    private fun assertBar(bmp: Bitmap, k: Int, label: String) {
        val rows = (60 until 175 step 5)
        val x0 = (k * 5) % 320
        val xr = minOf(x0 + 8, 319)
        val inside = rows.count { isWhite(bmp.getPixel(x0 + 1, it)) && isWhite(bmp.getPixel(xr, it)) }
        assertTrue("$label: bar for source frame $k missing at x=$x0", inside >= 20)
        if (x0 - 2 >= 0) assertTrue("$label: bar extends left of x=$x0 (wrong frame?)", rows.count { isWhite(bmp.getPixel(x0 - 2, it)) } <= 4)
        if (x0 + 11 < 320) assertTrue("$label: bar extends right of x=${x0 + 9} (wrong frame?)", rows.count { isWhite(bmp.getPixel(x0 + 11, it)) } <= 4)
    }

    private fun assertUpright(bmp: Bitmap, label: String) {
        assertTrue("$label: red box not in the top-left (image flipped?)", isRed(bmp.getPixel(20, 15)))
        assertTrue("$label: red box found at the bottom-left (image flipped)", !isRed(bmp.getPixel(20, bmp.height - 15)))
    }

    private fun meanDiff(a: Bitmap, b: Bitmap): Double {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var sum = 0L
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            sum += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
        }
        return sum.toDouble() / ((a.width / 2) * (a.height / 2) * 3)
    }

    private fun assertNames(r: ExtractResult, ext: String = "png", pad: Int = 0) {
        val want = (1..r.frames.size).map { if (pad > 0) it.toString().padStart(pad, '0') + ".$ext" else "$it.$ext" }
        assertEquals(want, r.frames.map { it.name })
    }

    @Test fun cfr30_everyFrameExactAndInOrder() {
        val (r, info) = run("cfr30.mp4")
        assertEquals(expected.getInt("cfr30.mp4"), info.frameCount)
        assertEquals(60, r.frames.size)
        assertEquals(320 to 180, r.width to r.height)
        assertNames(r)
        r.frames.forEachIndexed { i, f -> val b = bitmap(f.file); assertBar(b, i, f.name); if (i == 0) assertUpright(b, f.name) }
        val ref = BitmapFactory.decodeStream(testCtx.assets.open("ref_cfr30_1.png"))
        val d = meanDiff(bitmap(r.frames[0].file), ref)
        assertTrue("frame 1 differs from FFmpeg's decode by $d", d < 12)
        val d31 = meanDiff(bitmap(r.frames[30].file), BitmapFactory.decodeStream(testCtx.assets.open("ref_cfr30_31.png")))
        assertTrue("frame 31 differs from FFmpeg's frame 31 by $d31", d31 < 12)
        android.util.Log.i("FrameXTest", "cfr30 colour difference vs FFmpeg: frame1=%.2f frame31=%.2f".format(d, d31))
    }

    @Test fun cfr120_gives120Frames() {
        val (r, info) = run("cfr120.mp4")
        assertEquals(120, info.frameCount)
        assertEquals(120.0, info.fps, 0.5)
        assertEquals(120, r.frames.size)
        r.frames.forEachIndexed { i, f -> assertBar(bitmap(f.file), i, f.name) }
    }

    @Test fun vfr_extractsTrueDecodedFrames() {
        val (r, info) = run("vfr.mp4")
        val sourceIdx = (0 until 120).filter { it % 7 != 3 && it % 5 != 1 }
        assertEquals(expected.getInt("vfr.mp4"), sourceIdx.size)
        assertTrue("VFR not detected", info.isVfr)
        assertEquals(sourceIdx.size, info.frameCount)
        assertEquals(sourceIdx.size, r.frames.size)
        r.frames.forEachIndexed { i, f -> assertBar(bitmap(f.file), sourceIdx[i], f.name) }
    }

    @Test fun rotation_isApplied() {
        val (r, info) = run("rot90.mp4")
        assertEquals(180 to 320, info.displayWidth to info.displayHeight)
        assertEquals(180 to 320, r.width to r.height)
        val ref = BitmapFactory.decodeStream(testCtx.assets.open("ref_rot90_1.png"))
        val d = meanDiff(bitmap(r.frames[0].file), ref)
        assertTrue("rotated frame differs from FFmpeg's auto-rotated frame by $d", d < 12)
    }

    @Test fun range_jpegAndZeroPadding() {
        val (r, _) = run("cfr30.mp4", ExtractSettings(format = ImageFormat.JPEG, quality = 95, zeroPad = true, startFrame = 31, endFrame = 45))
        assertEquals(15, r.frames.size)
        assertNames(r, "jpg", 4)
        r.frames.forEachIndexed { i, f -> assertBar(bitmap(f.file), 30 + i, f.name) }
    }

    @Test fun webp_lossless() {
        val (r, _) = run("cfr30.mp4", ExtractSettings(format = ImageFormat.WEBP, quality = 100, endFrame = 5))
        assertNames(r, "webp")
        val ref = BitmapFactory.decodeStream(testCtx.assets.open("ref_cfr30_1.png"))
        assertTrue(meanDiff(bitmap(r.frames[0].file), ref) < 12)
    }

    @Test fun zip_storedAndVerified() {
        val (r, _) = run("cfr30.mp4")
        val z = File(ctx.cacheDir, "frames.zip")
        z.outputStream().use { out -> ZipStore.write(r.frames.map { ZipStore.Entry(it.name, it.file, it.crc32, it.size) }, out) }
        RandomAccessFile(z, "r").use { assertEquals(60, ZipStore.verify(it.channel, r.frames.map { f -> f.name })) }
        ZipFile(z).use { zf ->
            assertEquals((1..60).map { "$it.png" }, zf.entries().toList().map { it.name })
            assertTrue(zf.entries().toList().all { it.method == java.util.zip.ZipEntry.STORED })
            assertTrue(zf.getInputStream(zf.getEntry("1.png")).readBytes().contentEquals(r.frames[0].file.readBytes()))
        }
    }
}
