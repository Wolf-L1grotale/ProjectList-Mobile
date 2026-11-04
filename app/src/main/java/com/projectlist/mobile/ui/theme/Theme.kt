package com.projectlist.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo950,
    secondary = Sky500,
    onSecondary = Color.White,
    secondaryContainer = Sky100,
    onSecondaryContainer = Sky900,
    tertiary = Emerald600,
    onTertiary = Color.White,
    tertiaryContainer = Emerald100,
    onTertiaryContainer = Emerald900,
    error = Rose500,
    onError = Color.White,
    errorContainer = Rose100,
    onErrorContainer = Rose900,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate500,
    outline = Slate200,
    outlineVariant = Slate200,
    surfaceTint = Indigo600,
)

private val DarkScheme = darkColorScheme(
    primary = Indigo400,
    onPrimary = Indigo950,
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Indigo100,
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Sky900,
    onSecondaryContainer = Sky100,
    tertiary = Color(0xFF34D399),
    onTertiary = Emerald900,
    tertiaryContainer = Emerald900,
    onTertiaryContainer = Emerald100,
    error = Color(0xFFFB7185),
    onError = Rose900,
    errorContainer = Rose900,
    onErrorContainer = Rose100,
    background = NightBg,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Slate400,
    outline = NightOutline,
    outlineVariant = NightOutline,
    surfaceTint = Indigo400,
)

private val PLTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PLTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = PLTypography,
        content = content,
    )
}
