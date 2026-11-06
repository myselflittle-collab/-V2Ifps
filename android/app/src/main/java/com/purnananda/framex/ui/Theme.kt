package com.purnananda.framex.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Fx {
    val Graphite = Color(0xFF1C1F25)
    val Panel = Color(0xFF252932)
    val Panel2 = Color(0xFF2C313B)
    val Line = Color(0xFF353A45)
    val Text = Color(0xFFE7E9EE)
    val Muted = Color(0xFF8E96A4)
    val Amber = Color(0xFFF2B544)
    val AmberInk = Color(0xFF2A1F07)
    val AmberSoft = Color(0x24F2B544)
    val Ok = Color(0xFF6FCF97)
    val Err = Color(0xFFF0716A)

    // Tabular figures so counters don't jitter as they change.
    private const val TNUM = "tnum"
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Text, fontFeatureSettings = TNUM)
    val Small = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = Muted, fontFeatureSettings = TNUM)
    val Tiny = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Muted, fontFeatureSettings = TNUM)
    val Title = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = Text)
    val Big = TextStyle(fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold, color = Text,
        letterSpacing = (-1.5).sp, fontFeatureSettings = TNUM)
    val Mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
}

@Composable
fun FrameXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fx.Amber, onPrimary = Fx.AmberInk,
            background = Fx.Graphite, onBackground = Fx.Text,
            surface = Fx.Panel, onSurface = Fx.Text, surfaceVariant = Fx.Panel2,
            outline = Fx.Line, error = Fx.Err,
        ),
        content = content,
    )
}
