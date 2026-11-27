package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 主题:复用项目既有的科技风配色(colors.xml),保证与原生页面视觉一致。
 * 全部使用 Material2(与 compose-bom 2024.06.00 / compiler 1.5.14 匹配)。
 */
/**
 * 全局 UI 规范:三页(首页/详情/设置)共用的字号阶梯与圆角,保证视觉协调统一。
 * 与 TvColors 同包,各页面无需 import 即可引用。
 */
object TvSpec {
    // ===== 字号阶梯:8 档覆盖全 App,远距离观看整体偏大 =====
    val Hero = 34.sp        // 一级大标题(收藏/历史页标题)
    val Title = 28.sp       // 页面标题
    val Heading = 24.sp     // 二级标题/按钮/空态主文案
    val Section = 22.sp     // 分区标题、chip、海报名
    val Body = 20.sp        // 正文、角标
    val Value = 18.sp       // 值/次要信息
    val Caption = 16.sp     // 辅助文字
    val Micro = 13.sp       // 极小状态副行

    // ===== 圆角阶梯:语义化,替代散落的 2/4/6/8/10/12/14dp =====
    val CornerXs = 4.dp     // 文字角标
    val CornerSm = 8.dp     // 小按钮/文件行
    val CornerMd = 10.dp    // 中等组件
    val CornerCard = 12.dp  // 卡片/行/入口底板圆角
    val CornerChip = 12.dp  // chip/按钮圆角
    val CornerLg = 14.dp    // 大面板
}

object TvDivider {
    val Line = Color(0x33FFFFFF)  // 半透明分隔线
}

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
    val HintText = Color(0xBBFFFFFF)        // 次要/提示文字(color_CCFFFFFF)
}

private val TvShapes = Shapes(
    small = RoundedCornerShape(TvSpec.CornerSm),
    medium = RoundedCornerShape(TvSpec.CornerMd),
    large = RoundedCornerShape(TvSpec.CornerLg)
)

private val TvTypography = Typography(
    body1 = TextStyle(fontSize = TvSpec.Heading, color = TvColors.RowText),
    subtitle1 = TextStyle(fontSize = TvSpec.Heading, color = TvColors.RowText),
    h6 = TextStyle(fontSize = TvSpec.Title, color = TvColors.GroupTitle)
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

/**
 * Compose 统一主题。
 *
 * 根因：原生页面用 AndroidAutoSize 的 mm 子单位（design_width=1280，vs_50 即设计稿 50px，
 * 在 2772px 屏上渲染为 108px），而 Compose 的 .sp/.dp 走的是设备原始 density，
 * 与 mm 的缩放比例不一致 —— 字号相对容器忽大忽小，在框里显示不全。
 *
 * 修复：把 Compose 的 LocalDensity 设为「屏幕宽度 / 设计宽度(1280)」，
 * 使 .dp/.sp 与 mm 走同一缩放比例（今日头条方案在 Compose 的标准落地，Google 官方推荐）。
 * dimensionResource(vs_xx) 不受影响：它取资源的 px 再除 density 转 Dp，渲染时再乘回，density 抵消。
 * 保留系统 fontScale，尊重用户的字体大小设置。
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    val widthPixels = context.resources.displayMetrics.widthPixels
    val designWidthPx = 1280f
    val adaptedDensity = (widthPixels / designWidthPx).coerceAtLeast(0.1f)
    MaterialTheme(
        colors = TvColorsMaterial,
        typography = TvTypography,
        shapes = TvShapes,
        content = {
            CompositionLocalProvider(
                LocalDensity provides Density(density = adaptedDensity, fontScale = fontScale)
            ) {
                content()
            }
        }
    )
}
