package com.catcher.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 与电脑端一致的品牌蓝（#4c6ef5） */
val BrandBlue = Color(0xFF4C6EF5)
val BrandBlueDeep = Color(0xFF3B55D9)
val BrandViolet = Color(0xFF7C5CFC)
/** 顶部汇总卡蓝→青渐变端点：清爽的青 */
val BrandCyan = Color(0xFF0EA5E9)

val Ink = Color(0xFF161A23)
val InkSecondary = Color(0xFF5B6274)
val InkTertiary = Color(0xFF8A90A2)
val Canvas = Color(0xFFF5F6FB)
val CardWhite = Color(0xFFFFFFFF)
val HairLine = Color(0xFFE6E9F2)

val SuccessGreen = Color(0xFF11A36B)
val WarnAmber = Color(0xFFE08A00)
val DangerRed = Color(0xFFDC3E42)

private val CatcherColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EAFF),
    onPrimaryContainer = Color(0xFF1A2A66),
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE8FF),
    onSecondaryContainer = Color(0xFF2B1D6B),
    tertiary = SuccessGreen,
    background = Canvas,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F2F9),
    onSurfaceVariant = InkSecondary,
    outline = Color(0xFFBFC5D6),
    outlineVariant = HairLine,
    error = DangerRed,
    onError = Color.White,
)

private val CatcherTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val CatcherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CatcherColors,
        typography = CatcherTypography,
        shapes = CatcherShapes,
        content = content,
    )
}
