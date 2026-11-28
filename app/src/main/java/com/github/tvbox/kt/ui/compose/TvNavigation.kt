package com.github.tvbox.kt.ui.compose

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.Text
import com.github.tvbox.kt.R

@Composable
fun TvHomeStatusBar(
    siteName: String,
    clock: State<String>,
    onSiteClick: () -> Unit,
    onSiteLongClick: () -> Unit
) {
    val metrics = LocalTvLayoutMetrics.current
    val dateTime by clock
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(
                start = metrics.horizontalGutter,
                end = metrics.horizontalGutter,
                top = metrics.verticalGutter,
                bottom = if (metrics.compactHeight) 4.dp else 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onSiteClick,
            onLongClick = onSiteLongClick,
            modifier = Modifier.widthIn(max = 360.dp),
            shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp,
                vertical = if (metrics.compactHeight) 4.dp else 6.dp
            )
        ) {
            Box(
                modifier = Modifier
                    .size(if (metrics.compactHeight) 30.dp else 34.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(if (metrics.compactHeight) 20.dp else 22.dp)
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = siteName.ifBlank { stringResource(R.string.app_name) },
                    style = if (metrics.compactHeight) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!metrics.compactHeight) {
                    Text(
                        text = "当前影视源",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = dateTime,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvCategoryBar(
    tabs: List<HomeTabUi>,
    selected: Int,
    focusToken: Int,
    onTabFocus: (Int) -> Unit,
    onTabClick: (Int) -> Unit,
    onTabUp: () -> Unit
) {
    val metrics = LocalTvLayoutMetrics.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusToken) {
        if (focusToken > 0 && tabs.isNotEmpty()) {
            runCatching { firstFocus.requestFocus() }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = metrics.horizontalGutter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tabs.isEmpty()) {
            Text(
                text = "暂无分类，请先配置订阅",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            val selectedIndex = selected.coerceIn(tabs.indices)
            TabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier
                    .wrapContentWidth()
                    .height(52.dp),
                indicator = { positions, hasFocus ->
                    TabRowDefaults.PillIndicator(
                        currentTabPosition = positions[selectedIndex],
                        doesTabRowHaveFocus = hasFocus,
                        activeColor = MaterialTheme.colorScheme.primaryContainer,
                        inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                    )
                },
                separator = { Spacer(modifier = Modifier.size(6.dp)) }
            ) {
                tabs.forEachIndexed { index, tab ->
                    key(tab.id, index) {
                        Tab(
                            selected = index == selectedIndex,
                            onFocus = { onTabFocus(index) },
                            onClick = { onTabClick(index) },
                            modifier = Modifier
                                .height(48.dp)
                                .widthIn(min = 96.dp)
                                .then(
                                    if (index == 0) {
                                        Modifier.focusRequester(firstFocus)
                                    } else {
                                        Modifier
                                    }
                                )
                                .onPreviewKeyEvent { event ->
                                    val keyCode = event.nativeKeyEvent.keyCode
                                    if (keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                                        false
                                    } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        true
                                    } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                        onTabUp()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            colors = androidx.tv.material3.TabDefaults.pillIndicatorTabColors(
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                focusedSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text(
                                text = tab.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 18.dp)
                            )
                            if (tab.filterCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 7.dp)
                                        .size(6.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(50)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.borderVariant.copy(alpha = 0.45f))
    )
}
