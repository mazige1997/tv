package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.kt.R

/**
 * 单个设置行:标题在左、当前值在右、末尾箭头。
 * TV 焦点:青色渐变背景 + 2dp 描边 + 1.05 倍放大,与首页卡片焦点风格统一。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScope.SettingRow(
    label: String,
    value: String? = null,
    showArrow: Boolean = true,
    visible: Boolean = true,
    weight: Float = 1f,
    onClick: () -> Unit
) {
    if (!visible) return
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerCard)
    val rowHeight = dimensionResource(R.dimen.vs_50)
    val bg = if (focused) {
        Modifier.background(
            brush = Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd)),
            shape = shape
        )
    } else {
        Modifier.background(color = TvColors.PanelIdle, shape = shape)
    }
    Row(
        modifier = Modifier
            .weight(weight)
            .height(rowHeight)
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .then(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.vs_15)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TvColors.RowText,
            fontSize = TvSpec.Section,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (value != null) {
            Text(
                text = value,
                color = TvColors.ValueText,
                fontSize = TvSpec.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 200.dp)
            )
        }
        if (showArrow) {
            Text(
                text = "›",
                color = if (focused) TvColors.Accent else TvColors.HintText,
                fontSize = 30.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** 一行中并列 1~3 个设置项,等宽且间距统一。 */
@Composable
fun SettingRowGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_8))
    ) {
        content()
    }
}

/** 分组标题:左侧青色竖条 + 标题 + 折叠箭头,与设置行形成明确层级区分。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingGroupHeader(
    title: String,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_45))
            .then(
                if (focused) Modifier.background(
                    brush = Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd)),
                    shape = shape
                ) else Modifier
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = TvColors.Accent,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.vs_12)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(22.dp)
                .background(TvColors.Accent, RoundedCornerShape(2.dp))
        )
        Text(
            text = title,
            color = TvColors.GroupTitle,
            fontSize = TvSpec.Section,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f)
        )
        Text(
            text = if (collapsed) "▸" else "▾",
            color = TvColors.GroupTitle,
            fontSize = TvSpec.Body,
            fontWeight = FontWeight.Bold
        )
    }
}
