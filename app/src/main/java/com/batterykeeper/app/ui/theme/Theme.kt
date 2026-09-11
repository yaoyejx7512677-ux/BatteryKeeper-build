package com.batterykeeper.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFFF8A3D)
val OrangeLight = Color(0xFFFFB066)
val Green = Color(0xFF4ADE80)
val Red = Color(0xFFF87171)
val Blue = Color(0xFF60A5FA)
val TxtPrimary = Color(0xFFF5F7FA)
val TxtSecondary = Color(0xFFB0B8C2)
val TxtTertiary = Color(0xFF929BA7)
val CardBg = Color(0xFF1C2024)
val CardBorder = Color(0x17FFFFFF)
val ScreenBg = Color(0xFF101214)

private val DarkScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1A0E00),
    secondary = Green,
    onSecondary = Color(0xFF00290F),
    tertiary = Blue,
    background = ScreenBg,
    onBackground = TxtPrimary,
    surface = ScreenBg,
    onSurface = TxtPrimary,
    surfaceVariant = Color(0xFF161B2A),
    onSurfaceVariant = TxtSecondary,
    error = Red,
)

@Composable
fun BatteryKeeperTheme(content: @Composable () -> Unit) {
    // 电池监控工具以暗色沉浸为主，统一使用暗色方案
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
