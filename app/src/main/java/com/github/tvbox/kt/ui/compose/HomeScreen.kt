package com.github.tvbox.kt.ui.compose

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.util.ImgUtil
import me.jessyan.autosize.utils.AutoSizeUtils

/**
 * 首页 Compose UI(整页):状态栏 + 横排分类条 + 当前分类页(网格/主页)。
 * 分类切换由 Activity 依据 currentSelected 决定渲染哪个 pageState,不用 ViewPager。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    ui: HomeUiSnapshot,
    gridPage: HomeGridPageState?,
    userPage: HomeUserPageState,
    showUserPage: Boolean,
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
    ) {
        TopBar(
            siteName = ui.siteName,
            dateTime = ui.dateTime,
            hidden = ui.topHidden,
            onSiteClick = onSiteClick,
            onSiteLongClick = onSiteLongClick
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_15)))
        CategoryBar(
            tabs = ui.tabs,
            selected = ui.selected,
            focusToken = ui.tabFocusToken,
            onFocus = onTabFocus,
            onClick = onTabClick,
            onUp = onTabUp
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_10)))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showUserPage) {
                UserHomePage(
                    page = userPage,
                    onEntryClick = onEntryClick,
                    onItemClick = onHotItemClick,
                    onItemLongClick = onHotItemLongClick
                )
            } else if (gridPage != null) {
                GridHomePage(
                    page = gridPage,
                    onItemClick = onGridItemClick,
                    onItemLongClick = onGridItemLongClick,
                    onLoadMore = onLoadMore
                )
            }
        }
    }
}

/** 顶部状态栏:站点名(点击清缓存/长按设置) + 时钟。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(
    siteName: String,
    dateTime: String,
    hidden: Boolean,
    onSiteClick: () -> Unit,
    onSiteLongClick: () -> Unit
) {
    val height = if (hidden) 1.dp else dimensionResource(R.dimen.vs_60)
    val alpha = if (hidden) 0f else 1f
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .alpha(alpha)
            .padding(
                start = dimensionResource(R.dimen.vs_50),
                end = dimensionResource(R.dimen.vs_50),
                top = dimensionResource(R.dimen.vs_5)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = siteName.ifBlank { stringResource(R.string.app_name) },
            color = Color.White,
            fontSize = TvSpec.Title,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onSiteClick,
                    onLongClick = onSiteLongClick
                )
        )
        Text(
            text = dateTime,
            color = TvColors.HintText,
            fontSize = TvSpec.Section,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(end = dimensionResource(R.dimen.vs_50)),
            textAlign = TextAlign.End
        )
    }
}

/** 横排分类条:焦点缩放 + 选中高亮 + 筛选圆点。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryBar(
    tabs: List<HomeTabUi>,
    selected: Int,
    focusToken: Int,
    onFocus: (Int) -> Unit,
    onClick: (Int) -> Unit,
    onUp: () -> Unit
) {
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusToken) {
        if (focusToken > 0) runCatching { firstFocus.requestFocus() }
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_45))
            .padding(horizontal = dimensionResource(R.dimen.vs_50)),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
    ) {
        repeat(tabs.size) { index ->
            item {
                TabChip(
                    text = tabs[index].name,
                    isSelected = index == selected,
                    filterCount = tabs[index].filterCount,
                    focusRequester = if (index == 0) firstFocus else null,
                    onFocus = { onFocus(index) },
                    onClick = { onClick(index) },
                    onUp = onUp
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    text: String,
    isSelected: Boolean,
    filterCount: Int,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onUp: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    var mod = Modifier
        .height(dimensionResource(R.dimen.vs_45))
        .background(
            brush = if (focused || isSelected) {
                Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd))
            } else {
                Brush.horizontalGradient(listOf(TvColors.PanelIdle, TvColors.PanelIdle))
            },
            shape = RoundedCornerShape(TvSpec.CornerChip)
        )
        .border(
            width = if (focused || isSelected) 2.dp else 1.dp,
            color = if (focused || isSelected) TvColors.Accent else TvColors.StrokeIdle,
            shape = RoundedCornerShape(TvSpec.CornerChip)
        )
        .then(if (focused) Modifier.scale(1.1f) else Modifier)
        .focusable(interactionSource = interactionSource)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        .padding(horizontal = dimensionResource(R.dimen.vs_20))
    if (focusRequester != null) mod = mod.focusRequester(focusRequester)
    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            color = if (focused || isSelected) TvColors.Accent else TvColors.HintText,
            fontSize = TvSpec.Section,
            fontWeight = if (focused || isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (filterCount > 0) {
            Box(
                modifier = Modifier
                    .padding(start = dimensionResource(R.dimen.vs_5))
                    .width(dimensionResource(R.dimen.vs_10))
                    .height(dimensionResource(R.dimen.vs_10))
                    .background(TvColors.Accent, RoundedCornerShape(50))
            )
        }
    }
}

/** 分类网格页:焦点网格 + 滚动/分页状态由 pageState 保活。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridHomePage(
    page: HomeGridPageState,
    onItemClick: (Movie.Video) -> Unit,
    onItemLongClick: (Movie.Video) -> Unit,
    onLoadMore: () -> Unit
) {
    val context = LocalContext.current
    val columns = if (page.isFolderMode) 1 else page.columns.coerceIn(1, 8)
    val listState = page.gridState
    // 滚动到底部触发加载更多
    LaunchedEffect(listState.firstVisibleItemIndex, page.videos.size) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (page.videos.isNotEmpty() && last >= page.videos.size - 2 && !page.isRequesting) {
            onLoadMore()
        }
    }
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = dimensionResource(R.dimen.vs_50))) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
        ) {
            repeat(page.videos.size) { index ->
                item {
                    val video = page.videos[index]
                    MediaPosterCard(
                        name = video.name ?: "",
                        pic = video.pic,
                        badge = if (video.year > 0) video.year.toString() else "",
                        note = video.note,
                        context = context,
                        onFocus = { page.focusIndex = index },
                        onClick = { onItemClick(video) },
                        onLongClick = { onItemLongClick(video) }
                    )
                }
            }
        }
    }
}

/** “主页”页:7 个功能入口 + 热播横排/网格。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserHomePage(
    page: HomeUserPageState,
    onEntryClick: (String) -> Unit,
    onItemClick: (Movie.Video) -> Unit,
    onItemLongClick: (Movie.Video) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        EntryRow(onEntryClick = onEntryClick)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (page.useGridStyle) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(page.columns.coerceIn(1, 8)),
                    state = page.gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = dimensionResource(R.dimen.vs_50)),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10)),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
                ) {
                    repeat(page.videos.size) { index ->
                        item {
                            val video = page.videos[index]
                            HotCard(
                                video = video,
                                tvRate = page.tvRate,
                                deleteMode = page.deleteMode,
                                context = context,
                                onClick = { onItemClick(video) },
                                onLongClick = { onItemLongClick(video) }
                            )
                        }
                    }
                }
            } else {
                LazyRow(
                    state = page.listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_20)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = dimensionResource(R.dimen.vs_50),
                        vertical = dimensionResource(R.dimen.vs_10)
                    )
                ) {
                    repeat(page.videos.size) { index ->
                        item {
                            val video = page.videos[index]
                            Box(modifier = Modifier.width(dimensionResource(R.dimen.vs_214))) {
                                HotCard(
                                    video = video,
                                    tvRate = page.tvRate,
                                    deleteMode = page.deleteMode,
                                    context = context,
                                    onClick = { onItemClick(video) },
                                    onLongClick = { onItemLongClick(video) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 主页 7 个功能入口。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(onEntryClick: (String) -> Unit) {
    val entries = listOf(
        "live" to "直播", "search" to "搜索", "setting" to "设置",
        "history" to "历史", "push" to "推送", "favorite" to "收藏", "transfer" to "传输"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimensionResource(R.dimen.vs_50))
            .background(TvColors.PanelIdle, RoundedCornerShape(TvSpec.CornerCard))
            .padding(
                horizontal = dimensionResource(R.dimen.vs_10),
                vertical = dimensionResource(R.dimen.vs_10)
            ),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
    ) {
        repeat(entries.size) { index ->
            EntryChip(text = entries[index].second, onClick = { onEntryClick(entries[index].first) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.EntryChip(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerChip)
    Text(
        text = text,
        color = Color.White,
        fontSize = TvSpec.Section,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(1f)
            .height(dimensionResource(R.dimen.vs_45))
            .background(
                brush = if (focused) Brush.horizontalGradient(listOf(TvColors.FocusStart, TvColors.FocusEnd)) else Brush.horizontalGradient(listOf(TvColors.PanelIdle, TvColors.PanelIdle)),
                shape = shape
            )
            .border(width = if (focused) 2.dp else 1.dp, color = if (focused) TvColors.Accent else TvColors.StrokeIdle, shape = shape)
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.vs_5))
    )
}

/** 分类网格海报卡片。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPosterCard(
    name: String,
    pic: String?,
    badge: String,
    note: String?,
    context: android.content.Context,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerCard)
    LaunchedEffect(focused) { if (focused) onFocus() }
    val radiusPx = AutoSizeUtils.mm2px(context, 10f)
    val targetW = AutoSizeUtils.mm2px(context, ImgUtil.defaultWidth.toFloat())
    val targetH = AutoSizeUtils.mm2px(context, ImgUtil.defaultHeight.toFloat())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_280))
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .background(TvColors.PanelIdle, shape)
            .border(width = if (focused) 2.dp else 1.dp, color = if (focused) TvColors.Accent else TvColors.StrokeIdle, shape = shape)
            .focusable(interactionSource = interactionSource)
            .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
    ) {
        PosterImage(name, pic, context, radiusPx, targetW, targetH, Modifier.fillMaxSize())
        if (badge.isNotEmpty()) {
            Text(
                text = badge, color = Color.White, fontSize = TvSpec.Body, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(dimensionResource(R.dimen.vs_5))
                    .background(TvColors.BadgeBg, RoundedCornerShape(TvSpec.CornerXs)).padding(horizontal = dimensionResource(R.dimen.vs_5))
            )
        }
        BottomTitle(name, note)
    }
}

/** 热播卡片(带角标与删除遮罩)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HotCard(
    video: Movie.Video,
    tvRate: String,
    deleteMode: Boolean,
    context: android.content.Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerCard)
    val radiusPx = AutoSizeUtils.mm2px(context, 10f)
    val targetW = AutoSizeUtils.mm2px(context, ImgUtil.defaultWidth.toFloat())
    val targetH = AutoSizeUtils.mm2px(context, ImgUtil.defaultHeight.toFloat())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_280))
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .background(TvColors.PanelIdle, shape)
            .border(width = if (focused) 2.dp else 1.dp, color = if (focused) TvColors.Accent else TvColors.StrokeIdle, shape = shape)
            .focusable(interactionSource = interactionSource)
            .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
    ) {
        PosterImage(video.name ?: "", video.pic, context, radiusPx, targetW, targetH, Modifier.fillMaxSize())
        if (tvRate.isNotEmpty()) {
            Text(
                text = tvRate, color = Color.White, fontSize = TvSpec.Body, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(dimensionResource(R.dimen.vs_5))
                    .background(TvColors.BadgeBg, RoundedCornerShape(TvSpec.CornerXs)).padding(horizontal = dimensionResource(R.dimen.vs_5))
            )
        }
        BottomTitle(video.name ?: "", video.note)
        if (deleteMode) {
            Box(
                modifier = Modifier.fillMaxSize().background(TvColors.DeleteMask),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "删除", color = TvColors.Accent, fontSize = TvSpec.Title, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PosterImage(
    name: String,
    pic: String?,
    context: android.content.Context,
    radiusPx: Int,
    targetW: Int,
    targetH: Int,
    modifier: Modifier
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
            if (!pic.isNullOrEmpty()) {
                ImgUtil.loadCenterCrop(pic, iv, radiusPx, targetW, targetH, name)
            } else {
                iv.setImageDrawable(ImgUtil.createTextDrawable(name))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun BoxScope.BottomTitle(name: String, note: String?) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(TvColors.BadgeBg)
            .padding(dimensionResource(R.dimen.vs_5)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!note.isNullOrEmpty()) {
            Text(text = note, color = TvColors.ValueText, fontSize = TvSpec.Value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(text = name, color = Color.White, fontSize = TvSpec.Section, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
