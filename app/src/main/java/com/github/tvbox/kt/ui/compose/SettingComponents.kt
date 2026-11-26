package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.kt.R

/**
 * 单个设置行:标题在左、当前值在右、末尾 ">" 箭头。
 * TV 焦点:获得焦点时显示青色渐变背景 + 描边并轻微放大;上下左右导航由 Compose 焦点系统处理,
 * 遥控器确认键由 clickable 自动响应。
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
    val shape = RoundedCornerShape(10.dp)
    val rowHeight = dimensionResource(R.dimen.vs_60)
    val padH = dimensionResource(R.dimen.vs_20)
    val labelSize = 24.sp
    val bg = if (focused) {
        Modifier.background(
            brush = Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd)),
            shape = shape
        )
    } else {
        Modifier.background(color = TvColors.PanelIdle, shape = shape)
    }
    val border = Modifier.border(
        width = if (focused) 2.dp else 1.dp,
        color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
        shape = shape
    )
    Row(
        modifier = Modifier
            .weight(weight)
            .height(rowHeight)
            .then(if (focused) Modifier.scale(1.03f) else Modifier)
            .then(bg)
            .then(border)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = padH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TvColors.RowText,
            fontSize = labelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (value != null) {
            Text(
                text = value,
                color = TvColors.ValueText,
                fontSize = labelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).widthIn(max = 220.dp)
            )
        }
        if (showArrow) {
            Text(
                text = ">",
                color = TvColors.RowText,
                fontSize = 30.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}

/** 一行中并列 1~3 个设置项(与原布局的多列行一致)。 */
@Composable
fun SettingRowGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = dimensionResource(R.dimen.vs_10)),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_5))
    ) {
        content()
    }
}

/** 分组标题,可点击折叠;折叠状态用 ▾/▸ 表示。 */
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
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_50))
            .then(bg)
            .border(
                width = 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.vs_10)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (if (collapsed) "▸  " else "▾  ") + title,
            color = TvColors.GroupTitle,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
