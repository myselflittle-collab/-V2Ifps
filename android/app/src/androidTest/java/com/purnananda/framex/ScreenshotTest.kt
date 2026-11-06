package com.purnananda.framex

import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purnananda.framex.ui.FrameXViewModel
import com.purnananda.framex.ui.Phase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drives the real UI through a full extraction and saves screenshots for review (CI pulls them). */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    /** Runs a shell command as the shell user and waits for it to finish. */
    private fun sh(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    /** Screenshots go to /data/local/tmp so they survive the test APK being uninstalled. */
    private fun shot(name: String) {
        inst.waitForIdleSync()
        Thread.sleep(1200)
        sh("screencap -p /data/local/tmp/framex_$name.png")
    }

    private fun swipeUp(times: Int = 1) = repeat(times) {
        sh("input swipe 540 1700 540 500 300")
        Thread.sleep(700)
    }

    @Test fun fullFlow() {
        val video = File(ctx.cacheDir, "Holi_clip_120fps.mp4")
        inst.context.assets.open("cfr120.mp4").use { i -> video.outputStream().use { i.copyTo(it) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shot("1_start")
            lateinit var vm: FrameXViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[FrameXViewModel::class.java] }
            inst.runOnMainSync { vm.open(Uri.fromFile(video)) }
            waitFor(20_000) { vm.state.value.phase == Phase.Ready && vm.state.value.estimateBytes != null }
            shot("2_loaded")
            swipeUp(2)
            shot("3_settings")
            inst.runOnMainSync { vm.extract() }
            waitFor(60_000) { vm.state.value.phase is Phase.Done || vm.state.value.error != null }
            assertTrue("extraction failed: ${vm.state.value.error}", vm.state.value.phase is Phase.Done)
            sh("input swipe 540 600 540 1900 200")
            swipeUp(1)
            shot("4_result")
            swipeUp(3)
            shot("5_gallery")
        }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(200)
        }
    }
}
