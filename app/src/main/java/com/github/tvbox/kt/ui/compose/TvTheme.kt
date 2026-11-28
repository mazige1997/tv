package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TvSpec {
    val Hero = 34.sp
    val Title = 24.sp
    val Heading = 20.sp
    val Section = 18.sp
    val Body = 16.sp
    val Value = 14.sp
    val Caption = 13.sp
    val Micro = 11.sp

    val CornerXs = 4.dp
    val CornerSm = 8.dp
    val CornerMd = 10.dp
    val CornerCard = 12.dp
    val CornerChip = 12.dp
    val CornerLg = 14.dp

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp
    val ChipGap = 12.dp

    val TabMinWidth = 96.dp
    val TabMaxWidth = 160.dp
    val PageGutter = 48.dp
    val ContentGutter = 32.dp

    val PreviewWidth = 420.dp
    val PreviewHeight = 270.dp
    val PreviewInset = 20.dp
    val PosterWidth = 144.dp
    val PosterHeight = 216.dp
    val ChipHeight = 48.dp
    val ActionHeight = 48.dp
}

object TvDivider {
    val Line = Color(0x33FFFFFF)
}

object TvColors {
    val Accent = Color(0xFF02F8E1)
    val GroupTitle = Color(0xFF67E8F9)
    val ValueText = Color(0xFF9FE8FF)
    val RowText = Color(0xFFF5F7FA)
    val FocusStart = Color(0xFF123A4E)
    val FocusEnd = Color(0xFF0C1C28)
    val PanelIdle = Color(0xFF1B2732)
    val StrokeIdle = Color(0xFF34434F)
    val ScreenBg = Color(0xFF0B0F14)
    val BadgeBg = Color(0xCC0A1118)
    val DeleteMask = Color(0xAA000000)
    val HintText = Color(0xFFB8C4CF)
}

private val TvShapes = Shapes(
    small = RoundedCornerShape(TvSpec.CornerSm),
    medium = RoundedCornerShape(TvSpec.CornerMd),
    large = RoundedCornerShape(TvSpec.CornerLg)
)

private val LegacyTypography = Typography(
    h4 = TextStyle(fontSize = TvSpec.Hero, fontWeight = FontWeight.Bold),
    h5 = TextStyle(fontSize = TvSpec.Title, fontWeight = FontWeight.SemiBold),
    h6 = TextStyle(fontSize = TvSpec.Heading, fontWeight = FontWeight.SemiBold),
    subtitle1 = TextStyle(fontSize = TvSpec.Section, fontWeight = FontWeight.Medium),
    subtitle2 = TextStyle(fontSize = TvSpec.Value, fontWeight = FontWeight.Medium),
    body1 = TextStyle(fontSize = TvSpec.Body),
    body2 = TextStyle(fontSize = TvSpec.Value),
    button = TextStyle(fontSize = TvSpec.Value, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = TvSpec.Caption),
    overline = TextStyle(fontSize = TvSpec.Micro, fontWeight = FontWeight.Medium)
)

private val LegacyColors = darkColors(
    primary = TvColors.Accent,
    primaryVariant = TvColors.GroupTitle,
    secondary = TvColors.GroupTitle,
    background = TvColors.ScreenBg,
    surface = TvColors.PanelIdle,
    onPrimary = Color(0xFF003C36),
    onSecondary = Color(0xFF00363B),
    onBackground = TvColors.RowText,
    onSurface = TvColors.RowText,
    error = Color(0xFFFFB4AB)
)

private val TvColorScheme = androidx.tv.material3.darkColorScheme(
    primary = TvColors.Accent,
    onPrimary = Color(0xFF003C36),
    primaryContainer = Color(0xFF00594F),
    onPrimaryContainer = Color(0xFF8CFCEA),
    secondary = TvColors.GroupTitle,
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF004F57),
    onSecondaryContainer = Color(0xFFA6F3FF),
    tertiary = Color(0xFFB8C7FF),
    onTertiary = Color(0xFF1D2D61),
    tertiaryContainer = Color(0xFF344578),
    onTertiaryContainer = Color(0xFFDEE2FF),
    background = TvColors.ScreenBg,
    onBackground = TvColors.RowText,
    surface = TvColors.ScreenBg,
    onSurface = TvColors.RowText,
    surfaceVariant = TvColors.PanelIdle,
    onSurfaceVariant = TvColors.HintText,
    surfaceTint = TvColors.Accent,
    inverseSurface = Color(0xFFE3E8ED),
    inverseOnSurface = Color(0xFF131C22),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    border = Color(0xFF5A6B78),
    borderVariant = TvColors.StrokeIdle,
    scrim = Color.Black
)

private val TvTypography = androidx.tv.material3.Typography().let { default ->
    default.copy(
        displayLarge = default.displayLarge.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold),
        displayMedium = default.displayMedium.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold),
        displaySmall = default.displaySmall.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
        headlineLarge = default.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = default.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = default.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = default.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = default.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        titleSmall = default.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        bodyLarge = default.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = default.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = default.bodySmall.copy(fontSize = 12.sp),
        labelLarge = default.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = default.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = default.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    )
}

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = LegacyColors,
        typography = LegacyTypography,
        shapes = TvShapes
    ) {
        androidx.tv.material3.MaterialTheme(
            colorScheme = TvColorScheme,
            typography = TvTypography,
            content = content
        )
    }
}
