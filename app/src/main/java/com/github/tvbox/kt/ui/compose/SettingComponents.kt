package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    TvListItem(
        title = label,
        value = value,
        showArrow = showArrow,
        modifier = Modifier.weight(weight),
        onClick = onClick
    )
}

@Composable
fun SettingRowGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
fun SettingGroupHeader(
    title: String,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    TvActionButton(
        text = if (collapsed) "▸  $title" else "▾  $title",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    )
}
