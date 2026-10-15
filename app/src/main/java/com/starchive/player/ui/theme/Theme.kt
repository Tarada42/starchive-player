package com.starchive.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.starchive.player.R

// Fonts: Syne for display/headlines, DM Sans for body (bundled in res/font)
private val DisplayFont = FontFamily(Font(R.font.syne_variable))
private val BodyFont = FontFamily(Font(R.font.dm_sans_variable))

// Colors scraped from the Vercel web app's CSS
private val Background = Color(0xFF0A0A0F)
private val Surface = Color(0xFF11111C)
private val SurfaceVariant = Color(0xFF1A1A24)
private val Primary = Color(0xFF7C3AED)      // violet-600
private val PrimaryLight = Color(0xFFA855F7) // purple-500
private val Secondary = Color(0xFF22C55E)    // green-500
private val AccentRed = Color(0xFFEF4444)    // red-500
private val AccentAmber = Color(0xFFF59E0B)  // amber-500
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF9090B0)
private val TextMuted = Color(0xFF6B7280)

private val DarkScheme = darkColorScheme(
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    primary = Primary,
    onPrimary = TextPrimary,
    secondary = PrimaryLight,
    onSecondary = TextPrimary,
    tertiary = Secondary,
    onTertiary = TextPrimary,
    error = AccentRed,
    onError = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = TextMuted,
)

private val LightScheme = lightColorScheme(
    background = Color(0xFFF0F0F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEAEAEF),
    primary = Primary,
    onPrimary = Color.White,
    secondary = PrimaryLight,
    onSecondary = Color.White,
    tertiary = Secondary,
    onTertiary = Color.White,
    error = AccentRed,
    onError = Color.White,
    onBackground = Color(0xFF11111C),
    onSurface = Color(0xFF11111C),
    onSurfaceVariant = Color(0xFF5A5A7A),
    outline = Color(0xFF9090B0),
)

private val StArchiveTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFont,
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFont,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFont,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFont,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun StArchiveTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = StArchiveTypography,
        content = content,
    )
}
