package de.boz.sshkeepalive.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Bg = Color(0xFF0B1220)
    val Surface = Color(0xFF121A2B)
    val SurfaceHigh = Color(0xFF18233A)
    val Outline = Color(0xFF2A3654)
    val Blue = Color(0xFF3B82F6)
    val BlueLight = Color(0xFF60A5FA)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Text = Color(0xFFE5EDFF)
    val Muted = Color(0xFF8A97B8)
}

private val Scheme = darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    secondary = Palette.BlueLight,
    tertiary = Palette.Green,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerHigh = Palette.SurfaceHigh,
    outline = Palette.Outline,
    error = Palette.Red,
)

@Composable
fun SshKeepAliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
