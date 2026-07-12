package com.cpu.seamlessloopmobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DefaultTextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    letterSpacing = 0.sp
)

val SeamlessLoopTypography = Typography(
    displayLarge = DefaultTextStyle.copy(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    displayMedium = DefaultTextStyle.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    displaySmall = DefaultTextStyle.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = DefaultTextStyle.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = DefaultTextStyle.copy(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = DefaultTextStyle.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = DefaultTextStyle.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = DefaultTextStyle.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = DefaultTextStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = DefaultTextStyle.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = DefaultTextStyle.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = DefaultTextStyle.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = DefaultTextStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = DefaultTextStyle.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = DefaultTextStyle.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)
