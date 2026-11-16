package com.transvibe.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transvibe.app.R

/**
 * Central design tokens for TransVibe aligned with the official brand emblem:
 * Deep Midnight Navy, Electric Cyan glow, and Warm Amber/Gold accents.
 */
object TransVibeColors {
    // Light Theme
    val LightPrimary = Color(0xFF0284C7)
    val LightPrimaryContainer = Color(0xFFE0F2FE)
    val LightOnPrimaryContainer = Color(0xFF082F49)
    val LightSecondary = Color(0xFF2563EB)
    val LightSecondaryContainer = Color(0xFFDBEAFE)
    val LightTertiary = Color(0xFFD97706)
    val LightTertiaryContainer = Color(0xFFFEF3C7)
    val LightSurface = Color(0xFFF8FAFC)
    val LightSurfaceContainer = Color(0xFFF1F5F9)
    val LightSurfaceContainerLow = Color(0xFFFFFFFF)
    val LightSurfaceContainerHigh = Color(0xFFE2E8F0)
    val LightSurfaceContainerHighest = Color(0xFFCBD5E1)
    val LightOnSurface = Color(0xFF0F172A)
    val LightOnSurfaceVariant = Color(0xFF475569)
    val LightOutline = Color(0xFF64748B)
    val LightOutlineVariant = Color(0xFFCBD5E1)

    // Dark Theme
    val DarkPrimary = Color(0xFF38BDF8)
    val DarkPrimaryContainer = Color(0xFF0C4A6E)
    val DarkOnPrimaryContainer = Color(0xFFE0F2FE)
    val DarkSecondary = Color(0xFF60A5FA)
    val DarkSecondaryContainer = Color(0xFF1E3A8A)
    val DarkTertiary = Color(0xFFFBBF24)
    val DarkTertiaryContainer = Color(0xFF78350F)
    val DarkSurface = Color(0xFF090D16)
    val DarkSurfaceContainer = Color(0xFF111827)
    val DarkSurfaceContainerLow = Color(0xFF0F1623)
    val DarkSurfaceContainerHigh = Color(0xFF1E293B)
    val DarkSurfaceContainerHighest = Color(0xFF283548)
    val DarkOnSurface = Color(0xFFF1F5F9)
    val DarkOnSurfaceVariant = Color(0xFF94A3B8)
    val DarkOutline = Color(0xFF64748B)
    val DarkOutlineVariant = Color(0xFF1E293B)

    // Status Colors
    val Success = Color(0xFF16A34A)
    val SuccessContainer = Color(0xFFDCFCE7)
    val SuccessDark = Color(0xFF34D399)
    val SuccessContainerDark = Color(0xFF064E3B)
    val Warning = Color(0xFFD97706)
    val WarningContainer = Color(0xFFFEF3C7)
    val WarningDark = Color(0xFFFBBF24)
    val WarningContainerDark = Color(0xFF78350F)
    val Error = Color(0xFFDC2626)
    val ErrorContainer = Color(0xFFFEE2E2)
    val ErrorDark = Color(0xFFF87171)
    val ErrorContainerDark = Color(0xFF7F1D1D)
    val Info = Color(0xFF0284C7)
    val InfoContainer = Color(0xFFE0F2FE)
    val InfoDark = Color(0xFF38BDF8)
    val InfoContainerDark = Color(0xFF0C4A6E)
    val Download = Color(0xFF0891B2)
    val DownloadDark = Color(0xFF22D3EE)
}

private val LightColors = lightColorScheme(
    primary = TransVibeColors.LightPrimary,
    onPrimary = Color.White,
    primaryContainer = TransVibeColors.LightPrimaryContainer,
    onPrimaryContainer = TransVibeColors.LightOnPrimaryContainer,
    secondary = TransVibeColors.LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = TransVibeColors.LightSecondaryContainer,
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = TransVibeColors.LightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = TransVibeColors.LightTertiaryContainer,
    onTertiaryContainer = Color(0xFF451A03),
    background = TransVibeColors.LightSurface,
    onBackground = TransVibeColors.LightOnSurface,
    surface = TransVibeColors.LightSurface,
    onSurface = TransVibeColors.LightOnSurface,
    surfaceVariant = TransVibeColors.LightSurfaceContainerHigh,
    onSurfaceVariant = TransVibeColors.LightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = TransVibeColors.LightSurfaceContainerLow,
    surfaceContainer = TransVibeColors.LightSurfaceContainer,
    surfaceContainerHigh = TransVibeColors.LightSurfaceContainerHigh,
    surfaceContainerHighest = TransVibeColors.LightSurfaceContainerHighest,
    outline = TransVibeColors.LightOutline,
    outlineVariant = TransVibeColors.LightOutlineVariant,
    error = TransVibeColors.Error,
    onError = Color.White,
    errorContainer = TransVibeColors.ErrorContainer,
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = TransVibeColors.DarkPrimary
)

private val DarkColors = darkColorScheme(
    primary = TransVibeColors.DarkPrimary,
    onPrimary = Color(0xFF082F49),
    primaryContainer = TransVibeColors.DarkPrimaryContainer,
    onPrimaryContainer = TransVibeColors.DarkOnPrimaryContainer,
    secondary = TransVibeColors.DarkSecondary,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = TransVibeColors.DarkSecondaryContainer,
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = TransVibeColors.DarkTertiary,
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = TransVibeColors.DarkTertiaryContainer,
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = TransVibeColors.DarkSurface,
    onBackground = TransVibeColors.DarkOnSurface,
    surface = TransVibeColors.DarkSurface,
    onSurface = TransVibeColors.DarkOnSurface,
    surfaceVariant = TransVibeColors.DarkSurfaceContainerHigh,
    onSurfaceVariant = TransVibeColors.DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF060911),
    surfaceContainerLow = TransVibeColors.DarkSurfaceContainerLow,
    surfaceContainer = TransVibeColors.DarkSurfaceContainer,
    surfaceContainerHigh = TransVibeColors.DarkSurfaceContainerHigh,
    surfaceContainerHighest = TransVibeColors.DarkSurfaceContainerHighest,
    outline = TransVibeColors.DarkOutline,
    outlineVariant = TransVibeColors.DarkOutlineVariant,
    error = TransVibeColors.ErrorDark,
    onError = Color(0xFF690005),
    errorContainer = TransVibeColors.ErrorContainerDark,
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF0F172A),
    inversePrimary = TransVibeColors.LightPrimary
)

data class StatusColors(
    val ready: Color,
    val readyContainer: Color,
    val processing: Color,
    val processingContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val offline: Color,
    val offlineContainer: Color,
    val download: Color,
    val downloadContainer: Color,
    val info: Color,
    val infoContainer: Color
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        ready = Color.Unspecified,
        readyContainer = Color.Unspecified,
        processing = Color.Unspecified,
        processingContainer = Color.Unspecified,
        warning = Color.Unspecified,
        warningContainer = Color.Unspecified,
        error = Color.Unspecified,
        errorContainer = Color.Unspecified,
        offline = Color.Unspecified,
        offlineContainer = Color.Unspecified,
        download = Color.Unspecified,
        downloadContainer = Color.Unspecified,
        info = Color.Unspecified,
        infoContainer = Color.Unspecified
    )
}

object AppSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val card: Dp = 20.dp
    val screenPadding: Dp = 16.dp
}

// Cairo Font Family
private val CairoFontFamily = try {
    FontFamily(
        Font(R.font.cairo_regular, FontWeight.Normal),
        Font(R.font.cairo_regular, FontWeight.Light),
        Font(R.font.cairo_medium, FontWeight.Medium),
        Font(R.font.cairo_semibold, FontWeight.SemiBold),
        Font(R.font.cairo_bold, FontWeight.Bold),
        Font(R.font.cairo_extrabold, FontWeight.ExtraBold)
    )
} catch (e: Exception) {
    FontFamily.Default
}

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 39.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 35.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = CairoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.3.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TransVibeTheme(
    themeMode: String,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val status = if (darkTheme) {
        StatusColors(
            ready = TransVibeColors.SuccessDark,
            readyContainer = TransVibeColors.SuccessContainerDark,
            processing = TransVibeColors.DarkPrimary,
            processingContainer = TransVibeColors.DarkPrimaryContainer,
            warning = TransVibeColors.WarningDark,
            warningContainer = TransVibeColors.WarningContainerDark,
            error = TransVibeColors.ErrorDark,
            errorContainer = TransVibeColors.ErrorContainerDark,
            offline = Color(0xFF94A3B8),
            offlineContainer = Color(0xFF1E293B),
            download = TransVibeColors.DownloadDark,
            downloadContainer = Color(0xFF164E63),
            info = TransVibeColors.InfoDark,
            infoContainer = TransVibeColors.InfoContainerDark
        )
    } else {
        StatusColors(
            ready = TransVibeColors.Success,
            readyContainer = TransVibeColors.SuccessContainer,
            processing = TransVibeColors.LightPrimary,
            processingContainer = TransVibeColors.LightPrimaryContainer,
            warning = TransVibeColors.Warning,
            warningContainer = TransVibeColors.WarningContainer,
            error = TransVibeColors.Error,
            errorContainer = TransVibeColors.ErrorContainer,
            offline = Color(0xFF64748B),
            offlineContainer = Color(0xFFE2E8F0),
            download = TransVibeColors.Download,
            downloadContainer = TransVibeColors.LightPrimaryContainer,
            info = TransVibeColors.Info,
            infoContainer = TransVibeColors.InfoContainer
        )
    }

    val colorScheme = if (darkTheme) DarkColors else LightColors

    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides status
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}