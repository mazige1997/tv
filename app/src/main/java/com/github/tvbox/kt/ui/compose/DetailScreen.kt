package com.github.tvbox.kt.ui.compose

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.github.tvbox.kt.R

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
            .padding(
                start = dimensionResource(R.dimen.vs_40),
                end = dimensionResource(R.dimen.vs_40),
                top = dimensionResource(R.dimen.vs_15),
                bottom = dimensionResource(R.dimen.vs_20)
            ),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
    ) {
        if (!state.hasPlayData) {
            EmptyPlaylist()
            return@Column
        }

        // ===== 顶部信息区:海报 + 文字 + 操作按钮 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.vs_300)),
            verticalAlignment = Alignment.Top
        ) {
            // 海报 / 预览占位
            Box(
                modifier = Modifier
                    .width(dimensionResource(R.dimen.vs_280))
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopStart
            ) {
                if (!state.showPreview) {
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        },
                        update = { iv -> bindPoster(iv, state.posterUrl, state.posterName) },
                        modifier = Modifier.size(
                            width = dimensionResource(R.dimen.vs_230),
                            height = dimensionResource(R.dimen.vs_300)
                        )
                    )
                }
            }

            // 文字信息 + 按钮
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = dimensionResource(R.dimen.vs_20)),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = TvSpec.Title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = dimensionResource(R.dimen.vs_8))
                )
                InfoLine(state.site, state.year, state.area, state.lang, state.type)
                InfoText(state.actor)
                InfoText(state.director)
                Box(modifier = Modifier.clickable { onPlayUrlClick() }) {
                    Text(
                        text = state.playUrl,
                        color = TvColors.ValueText,
                        fontSize = TvSpec.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.vs_5))
                    )
                }
                Text(
                    text = state.des,
                    color = TvColors.HintText,
                    fontSize = TvSpec.Value,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 24.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                ActionButtons(
                    playText = state.playText,
                    collectText = state.collectText,
                    onPlay = onPlay,
                    onQuickSearch = onQuickSearch,
                    onDesc = onDesc,
                    onCollect = onCollect,
                    onChangeSource = onChangeSource
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
                    .height(dimensionResource(R.dimen.vs_45)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
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
                    .height(dimensionResource(R.dimen.vs_45)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FocusChip(
                    text = state.sortText,
                    selected = false,
                    onClick = onSort,
                    modifier = Modifier
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.vs_10)))
                val groupListState = rememberLazyListState()
                LazyRow(
                    state = groupListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
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
                    .height(dimensionResource(R.dimen.vs_45)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
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
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10)),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
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
    Row(modifier = Modifier.padding(bottom = dimensionResource(R.dimen.vs_5))) {
        InfoSegment(site)
        InfoSegment(year)
        InfoSegment(area)
        InfoSegment(lang)
        InfoSegment(type)
    }
}

@Composable
private fun InfoSegment(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        color = TvColors.HintText,
        fontSize = TvSpec.Value,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = dimensionResource(R.dimen.vs_10))
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
        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.vs_5))
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
    onChangeSource: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
    ) {
        ActionButton(playText, onPlay)
        ActionButton("快速搜索", onQuickSearch)
        ActionButton("简介", onDesc)
        ActionButton(collectText, onCollect)
        ActionButton("切源", onChangeSource)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ActionButton(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(dimensionResource(R.dimen.vs_40))
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .background(color = TvColors.PanelIdle, shape = shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = if (focused) TvColors.Accent else Color.White, fontSize = TvSpec.Caption)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    Box(
        modifier = modifier
            .height(dimensionResource(R.dimen.vs_45))
            .then(if (focused) Modifier.scale(1.04f) else Modifier)
            .background(color = TvColors.PanelIdle, shape = shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused || selected) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.vs_10)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected || focused) TvColors.Accent else Color.White,
            fontSize = TvSpec.Value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlagChip(
    text: String,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) {
        if (focused) onFocus()
    }
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    Column(
        modifier = Modifier
            .height(dimensionResource(R.dimen.vs_45))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (focused) Modifier.scale(1.04f) else Modifier)
            .background(color = TvColors.PanelIdle, shape = shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused || selected) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.vs_10)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = if (selected || focused) TvColors.Accent else Color.White,
            fontSize = TvSpec.Value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .width(dimensionResource(R.dimen.vs_30))
                    .height(dimensionResource(R.dimen.vs_4))
                    .background(TvColors.Accent)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesCell(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_50))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .background(color = TvColors.PanelIdle, shape = shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else if (selected) TvColors.GroupTitle else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected || focused) TvColors.Accent else Color.White,
            fontSize = TvSpec.Value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyPlaylist() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂无播放数据",
            color = Color.White,
            fontSize = 24.sp
        )
    }
}
