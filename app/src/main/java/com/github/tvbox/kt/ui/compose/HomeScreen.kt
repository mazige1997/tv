package com.github.tvbox.kt.ui.compose

import android.util.DisplayMetrics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.tvbox.kt.bean.Movie

private data class HomeEntry(
    val key: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val HomeEntries = listOf(
    HomeEntry("live", "直播", Icons.Rounded.LiveTv),
    HomeEntry("search", "搜索", Icons.Rounded.Search),
    HomeEntry("setting", "设置", Icons.Rounded.Settings),
    HomeEntry("history", "历史", Icons.Rounded.History),
    HomeEntry("push", "推送", Icons.Rounded.Send),
    HomeEntry("favorite", "收藏", Icons.Rounded.FavoriteBorder),
    HomeEntry("transfer", "传输", Icons.Rounded.SwapHoriz)
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    ui: HomeUiSnapshot,
    gridPage: HomeGridPageState?,
    userPage: HomeUserPageState,
    showUserPage: Boolean,
    clock: State<String>,
    onTabFocus: (Int) -> Unit,
    onTabClick: (Int) -> Unit,
    onTabUp: () -> Unit,
    onSiteClick: () -> Unit,
    onSiteLongClick: () -> Unit,
    onGridItemClick: (Movie.Video) -> Unit,
    onGridItemLongClick: (Movie.Video) -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (String) -> Unit,
    onHotItemClick: (Movie.Video) -> Unit,
    onHotItemLongClick: (Movie.Video) -> Unit
) {
    val view = LocalView.current
    val currentDensity = LocalDensity.current
    // AutoSize 会改写 Activity 密度，首页改用显示器密度计算真实 dp 尺寸。
    val displayDensity = remember(view, currentDensity.density) {
        val metrics = DisplayMetrics()
        view.display?.getRealMetrics(metrics)
        metrics.density.takeIf { it > 0f } ?: currentDensity.density
    }
    CompositionLocalProvider(
        LocalDensity provides Density(displayDensity, currentDensity.fontScale)
    ) {
        TvAdaptiveLayout { metrics ->
            val useSideActions = showUserPage &&
                metrics.width >= 720.dp &&
                metrics.height >= 360.dp &&
                metrics.width / metrics.height >= 1.9f
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                key("home-status") {
                    if (!ui.topHidden) {
                        TvHomeStatusBar(
                            siteName = ui.siteName,
                            clock = clock,
                            onSiteClick = onSiteClick,
                            onSiteLongClick = onSiteLongClick
                        )
                    }
                }
                key("home-categories") {
                    if (ui.topHidden) {
                        Spacer(modifier = Modifier.height(metrics.verticalGutter))
                    }
                    TvCategoryBar(
                        tabs = ui.tabs,
                        selected = ui.selected,
                        focusToken = ui.tabFocusToken,
                        onTabFocus = onTabFocus,
                        onTabClick = onTabClick,
                        onTabUp = onTabUp
                    )
                }
                key("home-actions") {
                    if (showUserPage && !useSideActions) {
                        QuickActionBar(onEntryClick = onEntryClick)
                    }
                }
                key("home-content") {
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            showUserPage -> key("user-home") {
                                if (useSideActions) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = metrics.horizontalGutter)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        ) {
                                            UserHomePage(
                                                page = userPage,
                                                metrics = metrics,
                                                sideLayout = true,
                                                onItemClick = onHotItemClick,
                                                onItemLongClick = onHotItemLongClick,
                                                onRefresh = onTabUp
                                            )
                                        }
                                        QuickActionPanel(onEntryClick = onEntryClick)
                                    }
                                } else {
                                    UserHomePage(
                                        page = userPage,
                                        metrics = metrics,
                                        onItemClick = onHotItemClick,
                                        onItemLongClick = onHotItemLongClick,
                                        onRefresh = onTabUp
                                    )
                                }
                            }

                            gridPage != null -> key(gridPage) {
                                GridHomePage(
                                    page = gridPage,
                                    metrics = metrics,
                                    screenLoading = ui.screenLoading,
                                    onItemClick = onGridItemClick,
                                    onItemLongClick = onGridItemLongClick,
                                    onLoadMore = onLoadMore,
                                    onRefresh = onTabUp
                                )
                            }

                            else -> key("home-empty") {
                                TvEmptyState(
                                    title = "尚未配置影视源",
                                    message = "请进入设置配置订阅后使用",
                                    actionText = "打开设置",
                                    onAction = { onEntryClick("setting") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun QuickActionBar(onEntryClick: (String) -> Unit) {
    val metrics = LocalTvLayoutMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .horizontalScroll(rememberScrollState())
            .padding(metrics.contentPadding)
            .focusRestorer()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeEntries.forEach { entry ->
            key(entry.key) {
                TvQuickAction(
                    icon = entry.icon,
                    label = entry.label,
                    onClick = { onEntryClick(entry.key) }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(if (metrics.compactHeight) 4.dp else 8.dp))
}

@Composable
private fun QuickActionPanel(onEntryClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .width(184.dp)
            .fillMaxHeight()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
                RoundedCornerShape(16.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HomeEntries.chunked(2).forEachIndexed { index, rowEntries ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowEntries.forEach { entry ->
                    key(entry.key) {
                        TvQuickAction(
                            icon = entry.icon,
                            label = entry.label,
                            onClick = { onEntryClick(entry.key) }
                        )
                    }
                }
                if (rowEntries.size == 1) {
                    Spacer(modifier = Modifier.width(72.dp))
                }
            }
            if (index < HomeEntries.lastIndex / 2) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun UserHomePage(
    page: HomeUserPageState,
    metrics: TvLayoutMetrics,
    onItemClick: (Movie.Video) -> Unit,
    onItemLongClick: (Movie.Video) -> Unit,
    onRefresh: () -> Unit,
    sideLayout: Boolean = false
) {
    val videos = page.videos
    if (videos.isEmpty()) {
        TvEmptyState(
            title = "暂无推荐内容",
            message = "可刷新推荐，或进入设置切换影视源",
            actionText = "刷新",
            onAction = onRefresh
        )
        return
    }
    val sectionTitle = page.tvRate.ifBlank { "热门推荐" }
    val horizontalPadding = if (sideLayout) 0.dp else metrics.horizontalGutter
    val availableWidth = if (sideLayout) {
        (metrics.width - metrics.horizontalGutter * 2 - 184.dp).coerceAtLeast(240.dp)
    } else {
        metrics.width - metrics.horizontalGutter * 2
    }
    val columns = if (sideLayout) {
        ((availableWidth + metrics.contentGap) / (metrics.cardWidth + metrics.contentGap))
            .toInt()
            .coerceIn(2, 6)
    } else {
        metrics.gridColumns
    }
    val cardWidth = if (sideLayout) {
        ((availableWidth - metrics.contentGap * (columns - 1)) / columns)
            .coerceIn(96.dp, 168.dp)
    } else {
        metrics.cardWidth
    }
    if (page.useGridStyle || page.deleteMode) {
        val content = if (page.deleteMode) videos else videos.drop(1)
        val contentKeys = rememberVideoKeys(videos, if (page.deleteMode) 0 else 1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = page.gridState,
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 40.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(metrics.contentGap),
            verticalArrangement = Arrangement.spacedBy(metrics.contentGap)
        ) {
            if (!page.deleteMode) {
                item(
                    key = "featured",
                    contentType = "featured",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    TvFeaturedContent(
                        video = videos.first(),
                        badge = sectionTitle,
                        onClick = { onItemClick(videos.first()) },
                        onLongClick = { onItemLongClick(videos.first()) }
                    )
                }
                item(
                    key = "section",
                    contentType = "section",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    TvSectionHeader(
                        title = sectionTitle,
                        subtitle = "${videos.size} 部内容",
                        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
                    )
                }
            } else {
                item(
                    key = "delete-section",
                    contentType = "section",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    TvSectionHeader(
                        title = "管理观看记录",
                        subtitle = "选择要删除的记录",
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
            gridItemsIndexed(
                items = content,
                key = { index, _ -> contentKeys[index] },
                contentType = { _, _ -> "poster" }
            ) { index, video ->
                TvPosterCard(
                    video = video,
                    cardWidth = cardWidth,
                    cardHeight = cardWidth * 1.5f,
                    deleteMode = page.deleteMode,
                    onClick = { onItemClick(video) },
                    onLongClick = { onItemLongClick(video) }
                )
            }
            if (content.isEmpty()) {
                item(
                    key = "single-item",
                    contentType = "section",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    Text(
                        text = "更多精彩内容即将上线",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
        }
    } else {
        val remaining = videos.drop(1)
        val remainingKeys = rememberVideoKeys(videos, drop = 1)
        LazyColumn(
            state = page.catalogState,
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(key = "featured", contentType = "featured") {
                TvFeaturedContent(
                    video = videos.first(),
                    badge = sectionTitle,
                    onClick = { onItemClick(videos.first()) },
                    onLongClick = { onItemLongClick(videos.first()) }
                )
            }
            if (remaining.isNotEmpty() || sideLayout) {
                item(key = "section", contentType = "section") {
                    TvSectionHeader(
                        title = sectionTitle,
                        subtitle = "${videos.size} 部内容"
                    )
                }
                item(key = "posters", contentType = "poster-row") {
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.contentGap)
                    ) {
                        itemsIndexed(
                            items = remaining,
                            key = { index, _ -> remainingKeys[index] },
                            contentType = { _, _ -> "poster" }
                        ) { _, video ->
                            TvPosterCard(
                                video = video,
                                cardWidth = cardWidth,
                                onClick = { onItemClick(video) },
                                onLongClick = { onItemLongClick(video) }
                            )
                        }
                    }
                }
            }
            item(key = "hint", contentType = "section") {
                Text(
                    text = "长按影片可快速搜索",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun GridHomePage(
    page: HomeGridPageState,
    metrics: TvLayoutMetrics,
    screenLoading: Boolean,
    onItemClick: (Movie.Video) -> Unit,
    onItemLongClick: (Movie.Video) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit
) {
    val videos = page.videos
    if (videos.isEmpty() && screenLoading) {
        TvLoadingState()
        return
    }
    if (videos.isEmpty()) {
        TvEmptyState(
            title = if (page.isFolderMode) "此文件夹为空" else "暂无内容",
            message = "请刷新当前分类，或切换其他影视源",
            actionText = "刷新",
            onAction = onRefresh
        )
        return
    }

    val listState = page.gridState
    val videoKeys = rememberVideoKeys(videos)
    LaunchedEffect(listState, videos.size, page.pendingScrollIndex, page.isFolderMode) {
        val index = page.pendingScrollIndex ?: return@LaunchedEffect
        if (videos.isNotEmpty()) {
            listState.scrollToItem(index.coerceIn(0, videos.lastIndex))
            page.pendingScrollIndex = null
        }
    }
    LaunchedEffect(listState.firstVisibleItemIndex, videos.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= videos.size - 2 && !page.isRequesting) {
            onLoadMore()
        }
    }

    val style = page.style
    val styleRatio = style?.ratio
    val wideStyle = style?.type == "rect" && (styleRatio ?: 0f) >= 1.3f
    val columns = when {
        page.isFolderMode -> 1
        style?.type == "list" -> 1
        wideStyle -> metrics.gridColumns.coerceAtMost(if ((styleRatio ?: 1.5f) >= 1.7f) 3 else 4)
        else -> metrics.gridColumns
    }
    val contentWidth = metrics.width - metrics.horizontalGutter * 2
    val itemWidth = (
        (contentWidth - metrics.contentGap * (columns - 1)) / columns
    ).coerceAtLeast(96.dp)
    val aspectRatio = when {
        page.isFolderMode -> 16f / 9f
        wideStyle -> (styleRatio ?: 1.5f).coerceIn(0.8f, 2.2f)
        style?.type == "list" -> (styleRatio ?: (2f / 3f)).coerceIn(0.8f, 2.2f)
        else -> 2f / 3f
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer(),
        contentPadding = PaddingValues(
            start = metrics.horizontalGutter,
            end = metrics.horizontalGutter,
            top = 8.dp,
            bottom = 40.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(metrics.contentGap),
        verticalArrangement = Arrangement.spacedBy(metrics.contentGap)
    ) {
        gridItemsIndexed(
            items = videos,
            key = { index, _ -> videoKeys[index] },
            contentType = { _, _ -> "poster" }
        ) { index, video ->
            TvPosterCard(
                video = video,
                cardWidth = itemWidth,
                cardHeight = itemWidth / aspectRatio,
                badge = if (video.year > 0) video.year.toString() else "",
                onFocus = { page.focusIndex = index },
                onClick = { onItemClick(video) },
                onLongClick = { onItemLongClick(video) }
            )
        }
        if (page.isRequesting) {
            item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvProgressIndicator(
                        color = TvColors.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = "正在加载更多",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberVideoKeys(videos: List<Movie.Video>, drop: Int = 0): List<String> =
    remember(videos, drop) {
        derivedStateOf { buildVideoKeys(if (drop == 0) videos else videos.drop(drop)) }
    }.value

private fun buildVideoKeys(videos: List<Movie.Video>): List<String> {
    val baseKeys = ArrayList<String>(videos.size)
    val totals = HashMap<String, Int>()
    videos.forEach { video ->
        val base = listOf(
            video.sourceKey.orEmpty(),
            video.id.orEmpty(),
            video.name.orEmpty(),
            video.pic.orEmpty()
        ).joinToString("\u0001")
        baseKeys.add(base)
        totals[base] = (totals[base] ?: 0) + 1
    }
    val seen = HashMap<String, Int>()
    return baseKeys.map { base ->
        val occurrence = seen[base] ?: 0
        seen[base] = occurrence + 1
        if ((totals[base] ?: 0) > 1) "$base\u0002$occurrence" else base
    }
}
