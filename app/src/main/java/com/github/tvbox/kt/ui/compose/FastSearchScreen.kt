package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

data class FastVideoUi(
    val name: String,
    val pic: String?,
    val siteName: String,
    val note: String? = null
)

data class FastSearchUiSnapshot(
    val statusLine1: String = "准备搜索",
    val statusLine2: String = "结果 0",
    val words: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
    val selectedSite: String = SEARCH_ALL,
    val mainResults: List<FastVideoUi> = emptyList(),
    val filterResults: List<FastVideoUi> = emptyList(),
    val showMainGrid: Boolean = true,
    val showResults: Boolean = true,
    val siteFocusToken: Int = 0
)

private const val SEARCH_ALL = "全部"

@Composable
fun FastSearchScreen(
    state: FastSearchUiSnapshot,
    onWordClick: (String) -> Unit,
    onSiteFocus: (String) -> Unit,
    onSiteClick: (String) -> Unit,
    onResultClick: (Int, Boolean) -> Unit
) {
    TvAdaptiveLayout { metrics ->
        val siteFocusRequester = remember { FocusRequester() }
        LaunchedEffect(state.siteFocusToken) {
            if (state.siteFocusToken > 0) runCatching { siteFocusRequester.requestFocus() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(metrics.contentPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SearchStatus(state.statusLine1, state.statusLine2)
                WordRow(
                    words = state.words,
                    onWordClick = onWordClick,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SiteColumn(
                    sites = state.sites,
                    selectedSite = state.selectedSite,
                    firstFocusRequester = siteFocusRequester,
                    onFocus = onSiteFocus,
                    onClick = onSiteClick,
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .padding(end = 16.dp)
                )
                val sideWidth = 200.dp
                val resultWidth = (
                    metrics.width - metrics.horizontalGutter * 2 - sideWidth - metrics.contentGap
                    ).coerceAtLeast(240.dp)
                val columns = (
                    (resultWidth + metrics.contentGap) /
                        (metrics.cardWidth + metrics.contentGap)
                    ).toInt().coerceIn(2, 6)
                val cardWidth = (
                    (resultWidth - metrics.contentGap * (columns - 1)) / columns
                    ).coerceIn(96.dp, 168.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (state.showResults) {
                        ResultGrid(
                            results = if (state.showMainGrid) state.mainResults else state.filterResults,
                            columns = columns,
                            cardWidth = cardWidth,
                            gap = metrics.contentGap,
                            onResultClick = { index ->
                                onResultClick(index, !state.showMainGrid)
                            }
                        )
                    } else {
                        TvEmptyState(
                            title = "准备搜索",
                            message = "选择关键词或站点开始搜索",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchStatus(line1: String, line2: String) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .height(56.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.borderVariant,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = line1,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = line2,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WordRow(
    words: List<String>,
    onWordClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(words.size, key = { index -> "${words[index]}\u0001$index" }) { index ->
            TvFilterChip(
                text = words[index],
                selected = false,
                onClick = { onWordClick(words[index]) }
            )
        }
    }
}

@Composable
private fun SiteColumn(
    sites: List<String>,
    selectedSite: String,
    firstFocusRequester: FocusRequester,
    onFocus: (String) -> Unit,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sites.size, key = { index -> "${sites[index]}\u0001$index" }) { index ->
            val name = sites[index]
            TvFilterChip(
                text = name,
                selected = name == selectedSite,
                onFocus = { onFocus(name) },
                focusRequester = if (index == 0) firstFocusRequester else null,
                onClick = { onClick(name) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ResultGrid(
    results: List<FastVideoUi>,
    columns: Int,
    cardWidth: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    onResultClick: (Int) -> Unit
) {
    if (results.isEmpty()) {
        TvEmptyState(
            title = "暂无结果",
            message = "请尝试其他关键词或站点",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        itemsIndexed(
            items = results,
            key = { index, item -> "${item.siteName}\u0001${item.name}\u0001${item.pic}\u0001$index" },
            contentType = { _, _ -> "search-poster" }
        ) { index, item ->
            TvMediaCard(
                name = item.name,
                pic = item.pic,
                badge = item.siteName,
                note = item.note,
                cardWidth = cardWidth,
                onClick = { onResultClick(index) }
            )
        }
    }
}
