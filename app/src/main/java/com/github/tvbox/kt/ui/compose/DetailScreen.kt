package com.github.tvbox.kt.ui.compose

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlin.math.roundToInt

/** 剧集 UI 快照(从 VodInfo 的可变列表拷贝,保证 Compose 重组可靠)。 */
data class DetailSeriesUi(val name: String, val selected: Boolean)
data class DetailFlagUi(val name: String, val selected: Boolean)

/** 详情页整体 UI 快照,业务侧每次刷新构造新对象赋值,驱动重组。 */
data class DetailUiSnapshot(
    val title: String = "",
    val site: String = "",
    val year: String = "",
    val area: String = "",
    val lang: String = "",
    val type: String = "",
    val actor: String = "",
    val director: String = "",
    val playUrl: String = "",
    val des: String = "",
    val collectText: String = "加入收藏",
    val sortText: String = "正序",
    val playText: String = "立即播放",
    val posterUrl: String = "",
    val posterName: String = "",
    val hasPlayData: Boolean = false,
    val showPreview: Boolean = true,
    val fullScreen: Boolean = false,
    val flags: List<DetailFlagUi> = emptyList(),
    val series: List<DetailSeriesUi> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: Int = 0,
    val showGroups: Boolean = false,
    val qualities: List<String> = emptyList(),
    val selectedQuality: Int = 0,
    val showQualities: Boolean = false,
    val columns: Int = 6,
    val selectedFlag: Int = 0
)

/** 主动滚动请求,token 递增确保相同 index 也能再次触发。 */
data class DetailScrollTarget(val index: Int = -1, val token: Long = 0L)

/**
 * 详情页 Compose 界面:信息区 + 线路/分组/清晰度 + 剧集网格。
 * 播放器浮层(previewPlayer/previewPlayerBlock)仍为原生 View,覆盖在本 Composable 之上。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    state: DetailUiSnapshot,
    seriesScroll: DetailScrollTarget,
    flagScroll: DetailScrollTarget,
    flagFocusRequester: FocusRequester,
    seriesFocusRequester: FocusRequester,
    bindPoster: (ImageView, String, String) -> Unit,
    onPreviewBounds: (Int, Int, Int, Int) -> Unit,
    onPlay: () -> Unit,
    onQuickSearch: () -> Unit,
    onDesc: () -> Unit,
    onCollect: () -> Unit,
    onChangeSource: () -> Unit,
    onPlayUrlClick: () -> Unit,
    onSort: () -> Unit,
    onFlagSelected: (Int) -> Unit,
    onSeriesSelected: (Int) -> Unit,
    onGroupSelected: (Int) -> Unit,
    onQualitySelected: (Int) -> Unit
) {
    val metrics = LocalTvLayoutMetrics.current
    val infoHeight = if (metrics.compactHeight) {
        200.dp
    } else {
        (metrics.height * 0.40f).coerceIn(220.dp, 320.dp)
    }
    val posterWidth = (metrics.width * 0.28f).coerceIn(200.dp, 400.dp)
    val chipHeight = if (metrics.compactHeight) 40.dp else TvSpec.ChipHeight
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
            .padding(
                start = metrics.horizontalGutter,
                end = metrics.horizontalGutter,
                top = metrics.verticalGutter,
                bottom = metrics.verticalGutter
            ),
        verticalArrangement = Arrangement.spacedBy(metrics.contentGap)
    ) {
        if (!state.hasPlayData) {
            EmptyPlaylist()
            return@Column
        }

        // ===== 顶部信息区:海报 + 文字 + 操作按钮 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(infoHeight),
            verticalAlignment = Alignment.Top
        ) {
            // 海报始终铺满信息区,原生预览播放器覆盖在其上,避免空黑框和 mm 尺寸溢出。
            Box(
                modifier = Modifier
                    .width(posterWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(TvSpec.CornerCard))
                    .background(TvColors.PanelIdle, RoundedCornerShape(TvSpec.CornerCard))
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        val left = position.x.roundToInt()
                        val top = position.y.roundToInt()
                        onPreviewBounds(
                            left,
                            top,
                            left + coordinates.size.width,
                            top + coordinates.size.height
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = { iv -> bindPoster(iv, state.posterUrl, state.posterName) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 文字信息 + 按钮
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = metrics.contentGap + 8.dp),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = TvSpec.Title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                InfoLine(state.site, state.year, state.area, state.lang, state.type)
                if (!metrics.compactHeight && metrics.height >= 600.dp) {
                    InfoText(state.actor)
                    InfoText(state.director)
                }
                Box(modifier = Modifier.clickable { onPlayUrlClick() }) {
                    Text(
                        text = state.playUrl,
                        color = TvColors.ValueText,
                        fontSize = TvSpec.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = state.des,
                    color = TvColors.HintText,
                    fontSize = TvSpec.Value,
                    maxLines = if (metrics.compactHeight) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = if (metrics.compactHeight) 20.sp else 24.sp
                )
                ActionButtons(
                    playText = state.playText,
                    collectText = state.collectText,
                    onPlay = onPlay,
                    onQuickSearch = onQuickSearch,
                    onDesc = onDesc,
                    onCollect = onCollect,
                    onChangeSource = onChangeSource,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        // ===== 线路(flag)条 =====
        if (state.flags.isNotEmpty()) {
            val flagListState = rememberLazyListState()
            LaunchedEffect(flagScroll.token) {
                if (flagScroll.index >= 0) {
                    try {
                        flagListState.scrollToItem(flagScroll.index)
                    } catch (_: Throwable) {
                    }
                }
            }
            LazyRow(
                state = flagListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chipHeight),
                horizontalArrangement = Arrangement.spacedBy(TvSpec.SpaceMd)
            ) {
                repeat(state.flags.size) { index ->
                    item {
                        val flag = state.flags[index]
                        FlagChip(
                            text = flag.name,
                            selected = flag.selected,
                            onFocus = { onFlagSelected(index) },
                            onClick = { onFlagSelected(index) },
                            focusRequester = if (index == 0) flagFocusRequester else null
                        )
                    }
                }
            }
        }

        // ===== 分组条(排序按钮 + 分组) =====
        if (state.showGroups && state.groups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chipHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FocusChip(
                    text = state.sortText,
                    selected = false,
                    onClick = onSort,
                    modifier = Modifier
                )
                Spacer(modifier = Modifier.width(12.dp))
                val groupListState = rememberLazyListState()
                LazyRow(
                    state = groupListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(TvSpec.SpaceMd)
                ) {
                    repeat(state.groups.size) { index ->
                        item {
                            FlagChip(
                                text = state.groups[index],
                                selected = index == state.selectedGroup,
                                onFocus = { onGroupSelected(index) },
                                onClick = { onGroupSelected(index) },
                                focusRequester = null
                            )
                        }
                    }
                }
            }
        }

        // ===== 清晰度条 =====
        if (state.showQualities && state.qualities.isNotEmpty()) {
            val qualityListState = rememberLazyListState()
            LazyRow(
                state = qualityListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chipHeight),
                horizontalArrangement = Arrangement.spacedBy(TvSpec.SpaceMd)
            ) {
                repeat(state.qualities.size) { index ->
                    item {
                        FlagChip(
                            text = state.qualities[index],
                            selected = index == state.selectedQuality,
                            onFocus = { onQualitySelected(index) },
                            onClick = { onQualitySelected(index) },
                            focusRequester = null
                        )
                    }
                }
            }
        }

        // ===== 剧集网格 =====
        if (state.series.isNotEmpty()) {
            val gridState = rememberLazyGridState()
            LaunchedEffect(seriesScroll.token) {
                if (seriesScroll.index >= 0) {
                    try {
                        gridState.animateScrollToItem(seriesScroll.index)
                    } catch (_: Throwable) {
                    }
                }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(state.columns.coerceIn(2, 8)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TvSpec.SpaceMd),
                horizontalArrangement = Arrangement.spacedBy(TvSpec.SpaceMd)
            ) {
                repeat(state.series.size) { index ->
                    item {
                        val series = state.series[index]
                        SeriesCell(
                            text = series.name,
                            selected = series.selected,
                            onClick = { onSeriesSelected(index) },
                            focusRequester = if (index == 0) seriesFocusRequester else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(site: String, year: String, area: String, lang: String, type: String) {
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoSegment(site)
        InfoSegment(year)
        InfoSegment(area)
        InfoSegment(lang)
        InfoSegment(type)
    }
}

@Composable
private fun RowScope.InfoSegment(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        color = TvColors.HintText,
        fontSize = TvSpec.Value,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false)
    )
}

@Composable
private fun InfoText(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        color = TvColors.HintText,
        fontSize = TvSpec.Value,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun ActionButtons(
    playText: String,
    collectText: String,
    onPlay: () -> Unit,
    onQuickSearch: () -> Unit,
    onDesc: () -> Unit,
    onCollect: () -> Unit,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TvActionButton(playText, onPlay, modifier = Modifier.weight(1f))
        TvActionButton("快速搜索", onQuickSearch, modifier = Modifier.weight(1f))
        TvActionButton("简介", onDesc, modifier = Modifier.weight(1f))
        TvActionButton(collectText, onCollect, modifier = Modifier.weight(1f))
        TvActionButton("切源", onChangeSource, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FocusChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvFilterChip(
        text = text,
        selected = selected,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun FlagChip(
    text: String,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    TvFilterChip(
        text = text,
        selected = selected,
        onFocus = onFocus,
        focusRequester = focusRequester,
        onClick = onClick
    )
}

@Composable
private fun SeriesCell(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    TvFilterChip(
        text = text,
        selected = selected,
        focusRequester = focusRequester,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EmptyPlaylist() {
    TvEmptyState(
        title = "暂无播放数据",
        message = "请切换线路或返回重试",
        modifier = Modifier.fillMaxSize()
    )
}
