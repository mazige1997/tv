package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 主题:复用项目既有的科技风配色(colors.xml),保证与原生页面视觉一致。
 * 全部使用 Material2(与 compose-bom 2024.06.00 / compiler 1.5.14 匹配)。
 */
object TvColors {
    val Accent = Color(0xFF02F8E1)          // tech_accent
    val GroupTitle = Color(0xFF67E8F9)      // tech_group_title
    val ValueText = Color(0xFF9FE8FF)       // tech_value_text
    val RowText = Color(0xFFFFFFFF)
    val FocusStart = Color(0xFF123A4E)      // tech_focus_start
    val FocusEnd = Color(0xFF0C1C28)        // tech_focus_end
    val PanelIdle = Color(0x991D202A)       // tech_panel_idle
    val StrokeIdle = Color(0x26FFFFFF)      // tech_stroke_idle
    val ScreenBg = Color(0xFF12151C)        // 页面背景
    val BadgeBg = Color(0xCC0C1C28)         // 角标/标题底条半透明背景
    val DeleteMask = Color(0xAA000000)      // 删除模式遮罩
}

private val TvShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp)
)

private val TvTypography = Typography(
    body1 = TextStyle(fontSize = 24.sp, color = TvColors.RowText),
    subtitle1 = TextStyle(fontSize = 26.sp, color = TvColors.RowText),
    h6 = TextStyle(fontSize = 30.sp, color = TvColors.GroupTitle)
)

private val TvColorsMaterial = lightColors(
    primary = TvColors.Accent,
    primaryVariant = TvColors.Accent,
    secondary = TvColors.GroupTitle,
    background = Color(0xFF12151C),
    surface = TvColors.PanelIdle,
    onPrimary = Color.Black,
    onBackground = TvColors.RowText,
    onSurface = TvColors.RowText
)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = TvColorsMaterial,
        typography = TvTypography,
        shapes = TvShapes,
        content = content
    )
}
