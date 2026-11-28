package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.tvbox.kt.R

/** 收藏/历史共用的列表项数据。 */
data class GridMediaItem(
    val name: String,
    val pic: String?,
    val sourceName: String,
    val note: String? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridMediaScreen(
    title: String,
    mediaItems: List<GridMediaItem>,
    delMode: Boolean,
    columnCount: Int,
    onToggleDelMode: () -> Unit,
    onClear: () -> Unit,
    onItemClick: (Int) -> Unit,
    onItemLongClick: (Int) -> Unit,
    onItemDelete: (Int) -> Unit
) {
    TvAdaptiveLayout { metrics ->
        val gridState = rememberLazyGridState()
        var focusedIndex by remember { mutableStateOf(-1) }
        val focusRequesters = remember(mediaItems.size) {
            List(mediaItems.size) { FocusRequester() }
        }
        val itemHeightPx = with(LocalDensity.current) { metrics.cardHeight.toPx().toInt() }

        LaunchedEffect(mediaItems.size) {
            if (focusRequesters.isNotEmpty()) {
                runCatching { focusRequesters[0].requestFocus() }
            }
        }

        LaunchedEffect(focusedIndex) {
            if (focusedIndex in mediaItems.indices) {
                val info = gridState.layoutInfo
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                val offset = ((viewport - itemHeightPx) / 2).coerceAtLeast(0)
                runCatching { gridState.animateScrollToItem(focusedIndex, scrollOffset = -offset) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(metrics.contentPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (delMode) {
                    Text(
                        text = "确定键删除当前选中项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TvIconButton(
                    painter = painterResource(R.drawable.icon_delete),
                    contentDescription = "切换删除模式",
                    onClick = onToggleDelMode
                )
                TvIconButton(
                    painter = painterResource(R.drawable.icon_clear),
                    contentDescription = "清空列表",
                    onClick = onClear
                )
            }

            if (mediaItems.isEmpty()) {
                TvEmptyState(
                    title = "暂无内容",
                    message = "收藏或播放记录会显示在这里",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(metrics.gridColumns),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.contentGap),
                    verticalArrangement = Arrangement.spacedBy(metrics.contentGap)
                ) {
                    itemsIndexed(
                        items = mediaItems,
                        key = { index, item -> "${item.sourceName}\u0001${item.name}\u0001$index" },
                        contentType = { _, _ -> "media-card" }
                    ) { index, item ->
                        TvMediaCard(
                            name = item.name,
                            pic = item.pic,
                            badge = item.sourceName,
                            note = item.note,
                            cardWidth = metrics.cardWidth,
                            cardHeight = metrics.cardHeight,
                            deleteMode = delMode,
                            focusRequester = focusRequesters.getOrNull(index),
                            onFocus = { focusedIndex = index },
                            onClick = { if (delMode) onItemDelete(index) else onItemClick(index) },
                            onLongClick = { onItemLongClick(index) }
                        )
                    }
                }
            }
        }
    }
}
