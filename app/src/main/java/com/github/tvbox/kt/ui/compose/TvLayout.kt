package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor

@Immutable
data class TvLayoutMetrics(
    val width: Dp,
    val height: Dp,
    val widthSizeClass: WindowWidthSizeClass,
    val horizontalGutter: Dp,
    val verticalGutter: Dp,
    val contentGap: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val gridColumns: Int,
    val heroHeight: Dp,
    val showActionLabels: Boolean,
    val compactHeight: Boolean
) {
    val contentPadding: PaddingValues
        get() = PaddingValues(start = horizontalGutter, end = horizontalGutter)
}

val LocalTvLayoutMetrics = compositionLocalOf {
    TvLayoutMetrics(
        width = 960.dp,
        height = 540.dp,
        widthSizeClass = WindowWidthSizeClass.Expanded,
        horizontalGutter = 48.dp,
        verticalGutter = 20.dp,
        contentGap = 16.dp,
        cardWidth = 132.dp,
        cardHeight = 198.dp,
        gridColumns = 6,
        heroHeight = 216.dp,
        showActionLabels = true,
        compactHeight = false
    )
}

@Composable
fun TvAdaptiveLayout(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(TvLayoutMetrics) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = rememberTvLayoutMetrics(maxWidth, maxHeight)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTvLayoutMetrics provides metrics
        ) {
            content(metrics)
        }
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun rememberTvLayoutMetrics(width: Dp, height: Dp): TvLayoutMetrics {
    val windowSizeClass = remember(width, height) {
        WindowSizeClass.calculateFromSize(DpSize(width, height))
    }
    return remember(width, height, windowSizeClass) {
        val horizontalGutter = (width * 0.05f).coerceIn(24.dp, 72.dp)
        val verticalGutter = (height * 0.04f).coerceIn(12.dp, 32.dp)
        val contentWidth = (width - horizontalGutter * 2).coerceAtLeast(240.dp)
        val compactHeight = height < 480.dp
        val contentGap = if (contentWidth < 720.dp) 12.dp else 16.dp
        val minimumCardWidth = when {
            contentWidth < 600.dp -> 104.dp
            contentWidth < 1_000.dp -> 120.dp
            else -> 136.dp
        }
        val minimumColumns = if (contentWidth < 480.dp) 2 else 3
        val columns = floor(
            (contentWidth + contentGap) / (minimumCardWidth + contentGap)
        ).toInt().coerceIn(minimumColumns, 10)
        val cardWidth = (
            (contentWidth - contentGap * (columns - 1)) / columns
        ).coerceIn(96.dp, 168.dp)
        val heroHeight = when {
            compactHeight -> (height * 0.34f).coerceIn(144.dp, 184.dp)
            height < 700.dp -> (height * 0.38f).coerceIn(216.dp, 280.dp)
            else -> (height * 0.42f).coerceIn(280.dp, 360.dp)
        }
        TvLayoutMetrics(
            width = width,
            height = height,
            widthSizeClass = windowSizeClass.widthSizeClass,
            horizontalGutter = horizontalGutter,
            verticalGutter = verticalGutter,
            contentGap = contentGap,
            cardWidth = cardWidth,
            cardHeight = cardWidth * 1.5f,
            gridColumns = columns,
            heroHeight = heroHeight,
            showActionLabels = width >= 640.dp,
            compactHeight = compactHeight
        )
    }
}
