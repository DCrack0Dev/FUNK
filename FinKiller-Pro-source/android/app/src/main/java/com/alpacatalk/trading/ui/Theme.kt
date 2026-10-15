package com.alpacatalk.trading.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fin Killer Pro visual tokens: obsidian terminal + liquid gold + teal data + danger red.
val Obsidian = Color(0xFF05070A)
val Onyx = Color(0xFF0D1117)
val Graphite = Color(0xFF151B24)
val LiquidGold = Color(0xFFD4AF37)
val LiquidGoldLight = Color(0xFFF5D77A)
val DeepTeal = Color(0xFF14B8A6)
val ProfitGreen = Color(0xFF00E08A)
val DangerRed = Color(0xFFFF2E4D)
val Ivory = Color(0xFFF2EFE6)
val MutedSlate = Color(0xFFA7B0BE)

// Compatibility names used by the existing account badges.
val PaperBlue = DeepTeal
val PaperContainer = Color(0xFF10302D)
val LiveRed = DangerRed
val LiveContainer = Color(0xFF3A101A)
val OfflineAmber = LiquidGold
val OfflineContainer = Color(0xFF332A0D)

private val AppScheme = darkColorScheme(
    primary = LiquidGoldLight,
    onPrimary = Color(0xFF1B1607),
    primaryContainer = Color(0xFF493B12),
    onPrimaryContainer = Color(0xFFFFE9A6),
    secondary = DeepTeal,
    onSecondary = Color(0xFF001F1B),
    secondaryContainer = Color(0xFF10302D),
    onSecondaryContainer = Color(0xFF8CF8E9),
    tertiary = LiquidGold,
    background = Obsidian,
    surface = Onyx,
    surfaceVariant = Graphite,
    onSurface = Ivory,
    onSurfaceVariant = MutedSlate,
    error = DangerRed,
    errorContainer = LiveContainer,
    onErrorContainer = Color(0xFFFFD9DE),
    outline = Color(0xFF536070),
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun AlpacaTradingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppScheme, typography = AppTypography, content = content)
}
