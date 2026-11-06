package com.purnananda.framex.ui

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.purnananda.framex.engine.ExtractSettings
import com.purnananda.framex.engine.Frame
import com.purnananda.framex.engine.ImageFormat
import com.purnananda.framex.engine.Stage
import com.purnananda.framex.engine.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

fun fmtBytes(n: Long): String {
    if (n <= 0) return "0 B"
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    var v = n.toDouble()
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return if (i <= 1) "${v.roundToInt()} ${u[i]}" else String.format(Locale.US, "%.2f %s", v, u[i])
}

fun fmtDuration(sec: Double): String {
    val h = (sec / 3600).toInt(); val m = ((sec % 3600) / 60).toInt(); val s = sec % 60
    val ss = String.format(Locale.US, "%05.2f", s)
    return if (h > 0) String.format(Locale.US, "%d:%02d:%s", h, m, ss) else "$m:$ss"
}

fun fmtFps(fps: Double): String =
    if (kotlin.math.abs(fps - fps.roundToInt()) < 0.001) fps.roundToInt().toString() else String.format(Locale.US, "%.3f", fps)

fun fmtEta(sec: Double?): String = when {
    sec == null -> "—"
    sec < 1 -> "under a second"
    sec < 60 -> "${kotlin.math.ceil(sec).toInt()} s"
    else -> "${(sec / 60).toInt()} min ${(sec % 60).roundToInt()} s"
}

@Composable
fun FrameXScreen(vm: FrameXViewModel, state: UiState, onPick: () -> Unit) {
    var thumbDp by remember { mutableStateOf(150) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val done = state.phase as? Phase.Done

    LazyVerticalGrid(
        columns = GridCells.Adaptive(thumbDp.dp),
        modifier = Modifier.fillMaxSize().background(Fx.Graphite).systemBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        full { Header(onHome = { if (state.phase !is Phase.Running) vm.reset() }) }
        full { Steps(state.phase) }
        state.error?.let { msg -> full { Banner(msg, Fx.Err) { vm.dismissError() } } }
        state.notice?.let { msg -> full { Banner(msg, Fx.Muted) { vm.dismissNotice() } } }

        when (state.phase) {
            Phase.Empty -> {
                full { PickCard(onPick) }
                full { Explainer() }
            }
            Phase.Probing -> full { PickCard(onPick, busy = true) }
            else -> Unit
        }

        val info = state.info
        if (info != null) {
            full { VideoSheet(info, state.poster, state, vm.plannedFrames(state), canReplace = state.phase !is Phase.Running, onReplace = onPick) }
            when (val p = state.phase) {
                Phase.Ready -> {
                    full { SettingsSection(state.settings, info.frameCount) { vm.updateSettings(it) } }
                    full {
                        val bad = state.settings.startFrame != null && state.settings.endFrame != null &&
                            state.settings.endFrame < state.settings.startFrame
                        Column(Modifier.padding(top = 8.dp)) {
                            Button(
                                onClick = { vm.extract() }, enabled = !bad,
                                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Fx.Amber, contentColor = Fx.AmberInk),
                            ) { Text("Extract ${nf.format(vm.plannedFrames(state))} frames", style = Fx.Title.copy(color = Fx.AmberInk)) }
                            if (bad) Text("The end frame must come after the start frame.", style = Fx.Small.copy(color = Fx.Err), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                is Phase.Running -> full { ProgressSection(p, onStop = { vm.stop() }) }
                is Phase.Done -> full { ResultSection(p, onAgain = { vm.extractAgain() }) }
                else -> Unit
            }
        }

        if (done != null) {
            full { GalleryHeader(done.result.frames.size, thumbDp) { thumbDp = it } }
            items(done.result.frames.size, key = { done.result.frames[it].name }) { i ->
                Tile(vm, done.result.frames[i], thumbDp) { viewer = i }
            }
        }
        full { Text("Frames are processed on this phone. Nothing is uploaded.", style = Fx.Tiny, modifier = Modifier.padding(top = 32.dp)) }
    }

    val v = viewer
    if (done != null && v != null) {
        FrameViewer(vm, done.result.frames, v, onIndex = { viewer = it }, onClose = { viewer = null })
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

// ---------------------------------------------------------------------------- header
@Composable
private fun Header(onHome: () -> Unit) {
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onHome)) {
            Mark(Modifier.size(width = 26.dp, height = 22.dp))
            Spacer(Modifier.width(10.dp))
            Text("FrameX", style = Fx.Title.copy(fontWeight = FontWeight.Bold))
        }
        Text("Every decoded frame of a video, at full resolution, numbered from 1.", style = Fx.Small, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun Mark(modifier: Modifier) = Canvas(modifier) {
    val bw = size.width / 4.4f
    val gap = (size.width - 3 * bw) / 2
    listOf(1f, .6f, .3f).forEachIndexed { i, a ->
        drawRoundRect(Fx.Amber.copy(alpha = a), Offset(i * (bw + gap), 0f), Size(bw, size.height), CornerRadius(2f, 2f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Steps(phase: Phase) {
    val step = when (phase) {
        Phase.Empty, Phase.Probing -> 1
        Phase.Ready -> 2
        is Phase.Running -> 3
        is Phase.Done -> 4
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 8.dp)) {
        listOf("Upload", "Settings", "Extract", "Download").forEachIndexed { i, label ->
            val n = i + 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (n == step) Fx.Amber else Color.Transparent)
                        .border(1.dp, if (n < step) Fx.Ok else if (n == step) Fx.Amber else Fx.Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (n < step) "✓" else "$n", style = Fx.Tiny.copy(
                        color = if (n == step) Fx.AmberInk else if (n < step) Fx.Ok else Fx.Muted,
                        fontWeight = if (n == step) FontWeight.Bold else FontWeight.Normal))
                }
                Spacer(Modifier.width(7.dp))
                Text(label, style = Fx.Small.copy(color = if (n == step) Fx.Text else Fx.Muted))
            }
        }
    }
}

@Composable
private fun Banner(msg: String, color: Color, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .1f))
            .border(1.dp, color, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, style = Fx.Body, modifier = Modifier.weight(1f))
        Text("Dismiss", style = Fx.Small.copy(color = Fx.Text), modifier = Modifier.clickable(onClick = onDismiss).padding(start = 12.dp))
    }
}

// ---------------------------------------------------------------------------- empty state
@Composable
private fun PickCard(onPick: () -> Unit, busy: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Fx.Panel)
            .dashedBorder().clickable(enabled = !busy, onClick = onPick).padding(horizontal = 24.dp, vertical = 36.dp),
    ) {
        Filmstrip(Modifier.size(width = 84.dp, height = 52.dp))
        Spacer(Modifier.height(16.dp))
        if (busy) {
            Text("Reading video details…", style = Fx.Title)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp), color = Fx.Amber, trackColor = Fx.Line)
        } else {
            Text("Choose a video", style = Fx.Title)
            Text("From your gallery or files. MP4, MOV, MKV, WebM and anything else this phone can play.",
                style = Fx.Small, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun Modifier.dashedBorder() = this.drawBehind {
    drawRoundRect(Fx.Line, size = size, cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
}

@Composable
private fun Filmstrip(modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    drawRoundRect(Fx.Muted, cornerRadius = CornerRadius(6f, 6f), style = Stroke(3f))
    val hole = w / 14
    for (i in 0 until 7) {
        val x = w * 0.08f + i * (w * 0.84f / 7)
        drawRect(Fx.Muted, Offset(x, h * 0.1f), Size(hole, h * 0.08f))
        drawRect(Fx.Muted, Offset(x, h * 0.82f), Size(hole, h * 0.08f))
    }
    val fw = w * 0.24f
    for (i in 0 until 3) drawRoundRect(Fx.Line, Offset(w * 0.1f + i * (fw + w * 0.04f), h * 0.3f), Size(fw, h * 0.4f), CornerRadius(3f, 3f))
}

@Composable
private fun Explainer() {
    Column(Modifier.padding(top = 28.dp)) {
        Text("What you get", style = Fx.Title)
        Text("A 10-second clip at 30 fps becomes 300 images. At 120 fps, 1,200. FrameX decodes the video with this phone's own decoder and saves each frame exactly once, in order, without resizing or re-timing it.",
            style = Fx.Body.copy(color = Fx.Muted), modifier = Modifier.padding(top = 8.dp))
        listOf(
            "Original resolution" to "4K stays 4K. No scaling, cropping or enhancement.",
            "True frame count" to "Variable frame rate phone videos are counted frame by frame, not by duration × fps.",
            "Checked before saving" to "File names, image sizes and the ZIP's checksums are verified before the ZIP appears in Downloads.",
        ).forEach { (t, d) ->
            Column(Modifier.padding(top = 18.dp)) {
                Text(t, style = Fx.Body.copy(fontWeight = FontWeight.SemiBold))
                Text(d, style = Fx.Body.copy(color = Fx.Muted))
            }
        }
    }
}

// ---------------------------------------------------------------------------- video sheet
@Composable
private fun VideoSheet(info: VideoInfo, poster: Bitmap?, state: UiState, planned: Int, canReplace: Boolean, onReplace: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Fx.Panel).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (poster != null) Image(poster.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text("No preview available", style = Fx.Small)
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info.filename, style = Fx.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (canReplace) {
                OutlinedButton(onClick = onReplace, border = BorderStroke(1.dp, Fx.Line), contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(6.dp), modifier = Modifier.height(34.dp).padding(start = 8.dp)) {
                    Text("Choose another", style = Fx.Small.copy(color = Fx.Text))
                }
            }
        }
        Text(fmtBytes(info.sizeBytes), style = Fx.Small)
        FrameRuler(info.fps, info.isVfr)
        val facts = listOf(
            "Duration" to fmtDuration(info.durationSec),
            "Frame rate" to fmtFps(info.fps) + " fps" + if (info.isVfr) "  (variable)" else "",
            "Resolution" to "${info.displayWidth} × ${info.displayHeight}" + if (info.rotation != 0) "  (rotated ${info.rotation}°)" else "",
            "Codec" to info.codec,
            "Total frames" to nf.format(info.frameCount),
            "Output images" to nf.format(planned),
            "Estimated size" to (state.estimateBytes?.let { "≈ " + fmtBytes(it) } ?: "measuring…"),
            "Image format" to when (state.settings.format) { ImageFormat.PNG -> "PNG (lossless)"; else -> state.settings.format.label },
        )
        facts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Fx.Line))
                        Text(k, style = Fx.Tiny, modifier = Modifier.padding(top = 8.dp))
                        Text(v, style = Fx.Body)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (info.isVfr) {
            Row(Modifier.padding(top = 14.dp)) {
                Box(Modifier.width(2.dp).height(54.dp).background(Fx.Amber))
                Text("This video has a variable frame rate. FrameX saves every decoded frame as-is, so the count is the true number of frames, which can differ from duration × fps.",
                    style = Fx.Small, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** One second of the video, one cell per frame; a playhead sweeps it once at the video's own rate. */
@Composable
private fun FrameRuler(fps: Double, vfr: Boolean) {
    val cells = fps.roundToInt().coerceIn(1, 240)
    val context = LocalContext.current
    val anim = remember(cells) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    LaunchedEffect(cells) {
        if (reduced) anim.snapTo(1f) else { anim.snapTo(0f); anim.animateTo(1f, tween(1000, easing = LinearEasing)) }
    }
    Column(Modifier.padding(top = 14.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)).background(Fx.Graphite)
                .clickable { if (!reduced) scope.launchReplay(anim) }.padding(6.dp),
        ) {
            val gap = if (cells > 120) 1f else 2f
            val cw = (size.width - gap * (cells - 1)) / cells
            val lit = (anim.value * cells).toInt()
            for (i in 0 until cells) {
                val major = (i + 1) % 10 == 0
                drawRect(if (i < lit) Fx.Amber else Fx.Line, Offset(i * (cw + gap), 0f),
                    Size(cw.coerceAtLeast(0.5f), if (major) size.height else size.height * 0.86f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 s", style = Fx.Tiny); Text("1 s", style = Fx.Tiny)
        }
        Text(buildAnnotatedString {
            append("One second of this video is ")
            withStyle(SpanStyle(color = Fx.Amber, fontWeight = FontWeight.SemiBold)) { append((if (vfr) "about " else "") + fmtFps(fps) + " images") }
            append(", not one.")
        }, style = Fx.Body, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun CoroutineScope.launchReplay(a: Animatable<Float, *>) = launch {
    a.snapTo(0f); a.animateTo(1f, tween(1000, easing = LinearEasing))
}

// ---------------------------------------------------------------------------- settings
@Composable
private fun SettingsSection(s: ExtractSettings, total: Int, onChange: (ExtractSettings) -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column {
            Label("Image format")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ImageFormat.PNG to "Lossless. Exact pixels, largest files.",
                    ImageFormat.JPEG to "Smallest files. Adjustable quality.",
                    ImageFormat.WEBP to "Lossless at 100, smaller below it.",
                ).forEach { (f, note) ->
                    val on = s.format == f
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Fx.AmberSoft else Fx.Panel)
                            .border(1.dp, if (on) Fx.Amber else Fx.Line, RoundedCornerShape(8.dp))
                            .clickable { onChange(s.copy(format = f, quality = if (f == ImageFormat.PNG) 100 else s.quality)) }
                            .padding(10.dp),
                    ) {
                        Text(f.label, style = Fx.Body.copy(fontWeight = FontWeight.SemiBold))
                        Text(note, style = Fx.Tiny)
                    }
                }
            }
        }
        if (s.format != ImageFormat.PNG) {
            Column {
                Label(buildString {
                    append("Quality ${s.quality}")
                    if (s.format == ImageFormat.WEBP && s.quality == 100) append(" (lossless)")
                })
                Slider(value = s.quality.toFloat(), onValueChange = { onChange(s.copy(quality = it.roundToInt())) },
                    valueRange = 50f..100f, steps = 49,
                    colors = SliderDefaults.colors(thumbColor = Fx.Amber, activeTrackColor = Fx.Amber, inactiveTrackColor = Fx.Line,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            }
        }
        Column {
            Label("File names")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(s.copy(zeroPad = !s.zeroPad)) }) {
                Checkbox(checked = s.zeroPad, onCheckedChange = { onChange(s.copy(zeroPad = it)) },
                    colors = CheckboxDefaults.colors(checkedColor = Fx.Amber, checkmarkColor = Fx.AmberInk, uncheckedColor = Fx.Muted))
                Text("Pad with zeros so files sort correctly everywhere", style = Fx.Body)
            }
            val pad = maxOf(4, total.toString().length)
            fun n(i: Int) = if (s.zeroPad) i.toString().padStart(pad, '0') else i.toString()
            Text("${n(1)}.${s.format.ext}   ${n(2)}.${s.format.ext}   …   ${n(total)}.${s.format.ext}", style = Fx.Mono.copy(color = Fx.Text),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Fx.Panel).padding(10.dp))
        }
        Column {
            Label("Frames")
            RadioRow("Every frame (${nf.format(total)})", !s.isRange) { onChange(s.copy(startFrame = null, endFrame = null)) }
            RadioRow("A range of frames", s.isRange) { if (!s.isRange) onChange(s.copy(startFrame = 1, endFrame = total)) }
            if (s.isRange) {
                Row(Modifier.padding(start = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("From frame", s.startFrame, total, Modifier.weight(1f)) { onChange(s.copy(startFrame = it)) }
                    NumberField("To frame", s.endFrame, total, Modifier.weight(1f)) { onChange(s.copy(endFrame = it)) }
                }
                Text("The first frame of the range is saved as 1.${s.format.ext}.", style = Fx.Small, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            }
        }
    }
}

@Composable private fun Label(t: String) = Text(t, style = Fx.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Fx.Amber, unselectedColor = Fx.Muted))
        Text(label, style = Fx.Body)
    }
}

@Composable
private fun NumberField(label: String, value: Int?, max: Int, modifier: Modifier, onValue: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { t -> onValue(t.filter(Char::isDigit).take(9).toIntOrNull()?.coerceIn(1, maxOf(1, max))) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fx.Amber, unfocusedBorderColor = Fx.Line,
            focusedLabelColor = Fx.Amber, unfocusedLabelColor = Fx.Muted, cursorColor = Fx.Amber),
    )
}

// ---------------------------------------------------------------------------- progress / result
@Composable
private fun ProgressSection(p: Phase.Running, onStop: () -> Unit) {
    val pr = p.progress
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.US, "%.1f%%", pr.percent), style = Fx.Big)
            Text(pr.stage.label, style = Fx.Body.copy(color = Fx.Muted), modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        LinearProgressIndicator(progress = { (pr.percent / 100).toFloat() }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            color = Fx.Amber, trackColor = Fx.Line, drawStopIndicator = {})
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val idx = listOf(Stage.EXTRACTING, Stage.VALIDATING, Stage.ZIPPING).indexOf(pr.stage)
            listOf("Extract", "Validate", "Save ZIP").forEachIndexed { i, s ->
                Text(s, style = Fx.Small.copy(color = when { i < idx -> Fx.Ok; i == idx -> Fx.Text; else -> Fx.Muted }))
            }
        }
        if (pr.stage == Stage.EXTRACTING) {
            Row { Stat("Frames extracted", "${nf.format(pr.framesDone)} / ${nf.format(pr.expected)}", Modifier.weight(1f)); Stat("Current frame", if (pr.framesDone > 0) nf.format(pr.framesDone) else "—", Modifier.weight(1f)) }
            Row { Stat("Speed", if (pr.speedFps > 0) String.format(Locale.US, "%.1f frames/s", pr.speedFps) else "—", Modifier.weight(1f)); Stat("Time left", fmtEta(pr.etaSec), Modifier.weight(1f)) }
        }
        Text("Keep FrameX open until it finishes. The screen stays on while it works.", style = Fx.Small)
        OutlinedButton(onClick = onStop, border = BorderStroke(1.dp, Fx.Line), shape = RoundedCornerShape(8.dp)) {
            Text("Stop extraction", style = Fx.Body)
        }
    }
}

@Composable
private fun Stat(k: String, v: String, modifier: Modifier) = Column(modifier) {
    Text(k, style = Fx.Tiny); Text(v, style = Fx.Title.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"))
}

@Composable
private fun ResultSection(d: Phase.Done, onAgain: () -> Unit) {
    val context = LocalContext.current
    val r = d.result
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(nf.format(r.frames.size), style = Fx.Big)
            Text("images ready", style = Fx.Title.copy(color = Fx.Muted, fontWeight = FontWeight.Normal), modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
        }
        Text("Saved to Download/FrameX/${d.zipName} (${fmtBytes(d.zipBytes)})", style = Fx.Body)
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, d.zipUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Share ZIP"))
            },
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Fx.Amber, contentColor = Fx.AmberInk),
        ) { Text("Share ZIP", style = Fx.Title.copy(color = Fx.AmberInk)) }
        OutlinedButton(
            onClick = { runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Fx.Line), shape = RoundedCornerShape(8.dp),
        ) { Text("Open Downloads", style = Fx.Body) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Check("${nf.format(r.frames.size)} images, named ${r.frames.first().name} to ${r.frames.last().name} with no gaps")
            Check("Every image is ${r.width} × ${r.height}, the video's resolution")
            Check("ZIP verified: ${nf.format(d.verifiedEntries)} entries, all checksums match")
        }
        r.warnings.forEach { Text(it, style = Fx.Small) }
        OutlinedButton(onClick = onAgain, border = BorderStroke(1.dp, Fx.Line), shape = RoundedCornerShape(8.dp)) {
            Text("Change settings and extract again", style = Fx.Body)
        }
    }
}

@Composable
private fun Check(t: String) = Row {
    Text("✓", style = Fx.Small.copy(color = Fx.Ok)); Spacer(Modifier.width(8.dp)); Text(t, style = Fx.Small.copy(color = Fx.Text))
}

// ---------------------------------------------------------------------------- gallery
@Composable
private fun GalleryHeader(total: Int, thumbDp: Int, onSize: (Int) -> Unit) {
    Column(Modifier.padding(top = 40.dp, bottom = 4.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Fx.Line))
        Text("Frames", style = Fx.Title, modifier = Modifier.padding(top = 24.dp))
        Text("${nf.format(total)} images. Tap a frame to view it at full size.", style = Fx.Small)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Thumbnail size", style = Fx.Tiny)
            Slider(value = thumbDp.toFloat(), onValueChange = { onSize(it.roundToInt()) }, valueRange = 90f..260f,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
                colors = SliderDefaults.colors(thumbColor = Fx.Amber, activeTrackColor = Fx.Amber, inactiveTrackColor = Fx.Line))
        }
    }
}

@Composable
private fun Tile(vm: FrameXViewModel, frame: Frame, thumbDp: Int, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { thumbDp.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, frame.file, px) { value = vm.thumbnail(frame.file, px) }
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Fx.Panel).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            bmp?.let { Image(it.asImageBitmap(), "Frame ${frame.name}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text(frame.name, style = Fx.Mono, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun FrameViewer(vm: FrameXViewModel, frames: List<Frame>, index: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val frame = frames[index]
    var toast by remember { mutableStateOf<String?>(null) }
    val bmp by produceState<Bitmap?>(null, frame.file) { value = vm.fullImage(frame.file, 2560) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xF00A0B0E)).systemBarsPadding().padding(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), "Frame ${frame.name}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            Text(frame.name, style = Fx.Mono.copy(color = Fx.Text), modifier = Modifier.padding(top = 10.dp))
            Text("Frame ${nf.format(index + 1)} of ${nf.format(frames.size)}", style = Fx.Small)
            toast?.let { Text(it, style = Fx.Small.copy(color = Fx.Ok)) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewerButton("Previous", index > 0, Modifier.weight(1f)) { onIndex(index - 1) }
                ViewerButton("Next", index < frames.lastIndex, Modifier.weight(1f)) { onIndex(index + 1) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewerButton("Save image", true, Modifier.weight(1f)) { vm.saveFrameToPictures(frame.file) { toast = it } }
                ViewerButton("Close", true, Modifier.weight(1f), onClose)
            }
        }
    }
}

@Composable
private fun ViewerButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, border = BorderStroke(1.dp, Fx.Line), shape = RoundedCornerShape(8.dp)) {
        Text(label, style = Fx.Body.copy(color = if (enabled) Fx.Text else Fx.Muted))
    }
}
