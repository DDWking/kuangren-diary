package com.ddw.kuangren.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Holo {
    val Background = Color(0xFF05070D)
    val Surface = Color(0xFF0B1220)
    val SurfaceAlt = Color(0xFF111B2E)
    val Border = Color(0xFF1E3A5F)
    val Primary = Color(0xFF38F2E8)
    val Secondary = Color(0xFF8B5CF6)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF4D5E)
    val Text = Color(0xFFE8F1FF)
    val TextMuted = Color(0xFF7C8CA6)
    val Success = Color(0xFF4ADE80)
    val Mono = FontFamily.Monospace

    fun categoryColor(category: String): Color = when (category) {
        "study" -> Primary
        "body" -> Success
        "life" -> Warning
        "mind" -> Secondary
        else -> TextMuted
    }

    fun attributeLabel(key: String): String = when (key) {
        "intelligence" -> "智力"
        "stamina" -> "体力"
        "willpower" -> "意志"
        "focus" -> "专注"
        "order" -> "秩序"
        else -> key
    }
}

private val scheme = darkColorScheme(
    primary = Holo.Primary,
    onPrimary = Holo.Background,
    secondary = Holo.Secondary,
    onSecondary = Holo.Text,
    tertiary = Holo.Warning,
    background = Holo.Background,
    onBackground = Holo.Text,
    surface = Holo.Surface,
    onSurface = Holo.Text,
    surfaceVariant = Holo.SurfaceAlt,
    onSurfaceVariant = Holo.TextMuted,
    outline = Holo.Border,
    error = Holo.Danger,
    onError = Holo.Text
)

private val typography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, color = Holo.Text),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, color = Holo.Text),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = Holo.TextMuted),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Holo.Text),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Holo.Text),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Holo.Text),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.TextMuted)
)

@Composable
fun KuangrenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
