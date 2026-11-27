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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.util.ImgUtil
import me.jessyan.autosize.utils.AutoSizeUtils

/** 搜索结果海报项快照。 */
data class FastVideoUi(
    val name: String,
    val pic: String?,
    val siteName: String,
    val note: String? = null
)

/** 极速搜索页整体 UI 快照,业务侧每次数据变化构造新对象驱动重组。 */
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

/**
 * 极速搜索页 Compose UI。
 * - 状态栏(双行) + 分词横排 + 站名列 + 结果网格/单站过滤网格
 * - 图片走 ImgUtil.loadCenterCrop(AndroidView 承载原生 ImageView),复用 Glide 缓存与文字 fallback
 * - 焦点用 collectIsFocusedAsState + 瞬时 scale(1.05),与已迁移页面写法一致
 * - 业务流程(多源并发/token/线程池/EventBus/文件夹栈)完全在 Activity 侧,本组件只做展示与点击回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FastSearchScreen(
    state: FastSearchUiSnapshot,
    onWordClick: (String) -> Unit,
    onSiteFocus: (String) -> Unit,
    onSiteClick: (String) -> Unit,
    onResultClick: (Int, Boolean) -> Unit
) {
    val context = LocalContext.current
    val siteFocusRequester = remember { FocusRequester() }

    // 结果就绪后把焦点交给站名列首项,复刻原 requestWordListFirstFocus()
    LaunchedEffect(state.siteFocusToken) {
        if (state.siteFocusToken > 0) {
            runCatching { siteFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
    ) {
        // ===== 顶部:状态条 + 分词横排 =====
        // 高度需容纳 vs_50 的 chip + 上下 padding,否则 chip 顶部被裁切
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.vs_70))
                .padding(
                    start = dimensionResource(R.dimen.vs_20),
                    top = dimensionResource(R.dimen.vs_10),
                    bottom = dimensionResource(R.dimen.vs_10)
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchStatus(state.statusLine1, state.statusLine2)
            WordRow(words = state.words, onWordClick = onWordClick)
        }

        // ===== 主体:左侧站名列 + 右侧结果区 =====
        // 必须用 weight 而不是 fillMaxSize:后者会把 70dp 的状态条高度重复计入,
        // 使结果网格底部被裁掉,看起来像图片互相挤压。
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SiteColumn(
                sites = state.sites,
                selectedSite = state.selectedSite,
                firstFocusRequester = siteFocusRequester,
                onFocus = onSiteFocus,
                onClick = onSiteClick,
                modifier = Modifier
                    .width(dimensionResource(R.dimen.vs_240))
                    .fillMaxHeight()
                    .padding(start = TvSpec.SpaceXl, bottom = TvSpec.SpaceXl)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(
                        start = TvSpec.ContentGutter,
                        end = TvSpec.PageGutter,
                        bottom = TvSpec.SpaceXl
                    )
            ) {
                if (state.showResults) {
                    if (state.showMainGrid) {
                        ResultGrid(
                            results = state.mainResults,
                            context = context,
                            onResultClick = { index -> onResultClick(index, false) }
                        )
                    } else {
                        ResultGrid(
                            results = state.filterResults,
                            context = context,
                            onResultClick = { index -> onResultClick(index, true) }
                        )
                    }
                }
            }
        }
    }
}

/** 双行搜索状态条。 */
@Composable
private fun SearchStatus(line1: String, line2: String) {
    Column(
        modifier = Modifier
            .width(dimensionResource(R.dimen.vs_220))
            .height(dimensionResource(R.dimen.vs_50))
            .padding(end = dimensionResource(R.dimen.vs_40))
            .background(TvColors.PanelIdle, RoundedCornerShape(TvSpec.CornerChip))
            .border(1.dp, TvColors.StrokeIdle, RoundedCornerShape(TvSpec.CornerChip)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = line1,
            color = Color.White,
            fontSize = TvSpec.Caption,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = line2,
            color = TvColors.HintText,
            fontSize = TvSpec.Micro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 分词横排。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordRow(words: List<String>, onWordClick: (String) -> Unit) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(TvSpec.SpaceSm)
    ) {
        repeat(words.size) { index ->
            item {
                WordChip(text = words[index], onClick = { onWordClick(words[index]) })
            }
        }
    }
}

/** 左侧站名列。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteColumn(
    sites: List<String>,
    selectedSite: String,
    firstFocusRequester: FocusRequester,
    onFocus: (String) -> Unit,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(sites.size) {
        if (sites.isNotEmpty()) {
            runCatching { listState.scrollToItem(0) }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvSpec.SpaceSm)
    ) {
        repeat(sites.size) { index ->
            item {
                val name = sites[index]
                val selected = name == selectedSite
                SiteChip(
                    text = name,
                    selected = selected,
                    focusRequester = if (index == 0) firstFocusRequester else null,
                    onFocus = { onFocus(name) },
                    onClick = { onClick(name) }
                )
            }
        }
    }
}

/** 结果/过滤海报网格(4 列)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultGrid(
    results: List<FastVideoUi>,
    context: android.content.Context,
    onResultClick: (Int) -> Unit
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(results.size) {
        if (results.isEmpty()) runCatching { gridState.scrollToItem(0) }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = TvSpec.SpaceSm,
                end = TvSpec.SpaceSm,
                top = TvSpec.SpaceMd,
                bottom = TvSpec.SpaceMd
            ),
        // 焦点卡片会放大 3%,间距必须大于放大后的外扩量,否则视觉上会重叠。
        horizontalArrangement = Arrangement.spacedBy(TvSpec.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(TvSpec.SpaceXl)
    ) {
        repeat(results.size) { index ->
            item {
                SearchPosterCard(
                    item = results[index],
                    context = context,
                    onClick = { onResultClick(index) }
                )
            }
        }
    }
}

/** 分词 chip:聚焦青色渐变 + 描边。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordChip(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    Text(
        text = text,
        color = Color.White,
        fontSize = TvSpec.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .height(dimensionResource(R.dimen.vs_50))
            .background(
                brush = if (focused) {
                    Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd))
                } else {
                    Brush.horizontalGradient(listOf(TvColors.PanelIdle, TvColors.PanelIdle))
                },
                shape = shape
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .then(if (focused) Modifier.scale(1.03f) else Modifier)
            .padding(horizontal = dimensionResource(R.dimen.vs_10))
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.vs_5))
    )
}

/** 站名 chip:选中态粗体高亮,聚焦缩放。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteChip(
    text: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    LaunchedEffect(focused) {
        if (focused) onFocus()
    }
    var mod = Modifier
        .fillMaxWidth()
        .height(dimensionResource(R.dimen.vs_50))
        .background(
            brush = if (focused || selected) {
                Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd))
            } else {
                Brush.horizontalGradient(listOf(TvColors.PanelIdle, TvColors.PanelIdle))
            },
            shape = shape
        )
        .border(
            width = if (focused || selected) 2.dp else 1.dp,
            color = if (focused || selected) TvColors.Accent else TvColors.StrokeIdle,
            shape = shape
        )
        .then(if (focused) Modifier.scale(1.05f) else Modifier)
        .focusable(interactionSource = interactionSource)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    if (focusRequester != null) mod = mod.focusRequester(focusRequester)
    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (selected) TvColors.Accent else Color.White,
            fontSize = TvSpec.Body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 搜索结果海报卡片:复用 ImgUtil,焦点缩放 1.05(与原 setPosterFocusScale 等价)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchPosterCard(
    item: FastVideoUi,
    context: android.content.Context,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerCard)
    val radiusPx = AutoSizeUtils.mm2px(context, 10f)
    val targetW = AutoSizeUtils.mm2px(context, 214f)
    val targetH = AutoSizeUtils.mm2px(context, 280f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvSpec.PosterHeight)
            .then(if (focused) Modifier.scale(1.03f) else Modifier)
            .background(TvColors.PanelIdle, shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        AndroidView(
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { iv ->
                if (!item.pic.isNullOrEmpty()) {
                    ImgUtil.loadCenterCrop(item.pic, iv, radiusPx, targetW, targetH, item.name)
                } else {
                    iv.setImageDrawable(ImgUtil.createTextDrawable(item.name))
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (item.siteName.isNotEmpty()) {
            Text(
                text = item.siteName,
                color = Color.White,
                fontSize = TvSpec.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(dimensionResource(R.dimen.vs_5))
                    .background(TvColors.BadgeBg, RoundedCornerShape(TvSpec.CornerChip))
                    .padding(horizontal = dimensionResource(R.dimen.vs_5))
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(TvColors.BadgeBg)
                .padding(dimensionResource(R.dimen.vs_5)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!item.note.isNullOrEmpty()) {
                Text(
                    text = item.note,
                    color = TvColors.ValueText,
                    fontSize = TvSpec.Value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = item.name,
                color = Color.White,
                fontSize = TvSpec.Section,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
