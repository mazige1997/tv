package com.github.tvbox.kt.ui.compose

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.util.ImgUtil
import me.jessyan.autosize.utils.AutoSizeUtils

/** 收藏/历史共用的列表项数据。 */
data class GridMediaItem(
    val name: String,
    val pic: String?,
    val sourceName: String,
    val note: String? = null
)

/**
 * 收藏/历史共用的 TV 焦点网格页。
 * - 图片走 ImgUtil.loadCenterCrop（复用 Glide 内存/磁盘缓存与文字 fallback），不引入新图片库
 * - LazyVerticalGrid + focusable 实现方向键导航；选中项居中、缩放描边、上边界回跳顶部按钮
 * - 删除模式、清空弹窗、点击/长按跳转由外部回调处理
 */
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
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    var focusedIndex by remember { mutableStateOf(-1) }
    val focusRequesters = remember(mediaItems.size) { List(mediaItems.size) { FocusRequester() } }
    // 在 Composable 作用域预先计算 item 高度(px)，避免在 LaunchedEffect 的非 Composable lambda 中调用 @Composable API
    val itemHeightPx = with(LocalDensity.current) {
        dimensionResource(R.dimen.vs_280).toPx().toInt()
    }

    // 进入页面默认聚焦第一个 item（首个可聚焦项），保证遥控器立即可操作
    LaunchedEffect(mediaItems.size) {
        if (focusRequesters.isNotEmpty()) {
            runCatching { focusRequesters[0].requestFocus() }
        }
    }

    // 选中项居中：跟随焦点平滑滚动，复刻 TvRecyclerView 的 tv_selectedItemIsCentered
    LaunchedEffect(focusedIndex) {
        val idx = focusedIndex
        if (idx in mediaItems.indices) {
            val info = gridState.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val offset = ((viewport - itemHeightPx) / 2).coerceAtLeast(0)
            try {
                gridState.animateScrollToItem(idx, scrollOffset = -offset)
            } catch (t: Throwable) {
                // 列表正在重组或索引暂时失效时忽略，下次焦点变化会重试
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.vs_50),
                    vertical = dimensionResource(R.dimen.vs_20)
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = TvSpec.Hero
            )
            Spacer(modifier = Modifier.weight(1f))
            if (delMode) {
                Text(
                    text = "确定键删除当前选中项",
                    color = Color.White,
                    fontSize = TvSpec.Heading,
                    modifier = Modifier.padding(end = dimensionResource(R.dimen.vs_10))
                )
            }
            TvIconButton(
                iconRes = R.drawable.icon_delete,
                onClick = onToggleDelMode
            )
            TvIconButton(
                iconRes = R.drawable.icon_clear,
                onClick = onClear
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimensionResource(R.dimen.vs_60)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_10))
        ) {
            items(mediaItems.size) { index ->
                val item = mediaItems[index]
                GridMediaCard(
                    item = item,
                    delMode = delMode,
                    focusRequester = focusRequesterFor(index, focusRequesters),
                    onFocused = { focusedIndex = index },
                    onClick = { if (delMode) onItemDelete(index) else onItemClick(index) },
                    onLongClick = { onItemLongClick(index) },
                    context = context
                )
            }
        }
    }
}

/** 安全取 FocusRequester（item 数量变化时 remember 重建，索引越界返回新建实例不影响功能）。 */
private fun focusRequesterFor(index: Int, list: List<FocusRequester>): FocusRequester =
    list.getOrNull(index) ?: FocusRequester()

/** 顶部图标按钮：焦点高亮 + 确认键点击。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvIconButton(iconRes: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerMd)
    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.vs_50))
            .padding(horizontal = dimensionResource(R.dimen.vs_5))
            .background(
                color = if (focused) TvColors.Accent.copy(alpha = 0.35f) else TvColors.PanelIdle,
                shape = shape
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.vs_35))
        )
    }
}

/** 单个海报 item：Glide 图片 + 角标 + 标题 + 删除遮罩，焦点缩放描边。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridMediaCard(
    item: GridMediaItem,
    delMode: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    context: android.content.Context
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerMd)
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    val radiusPx = AutoSizeUtils.mm2px(context, 10f)
    val targetW = AutoSizeUtils.mm2px(context, ImgUtil.defaultWidth.toFloat())
    val targetH = AutoSizeUtils.mm2px(context, ImgUtil.defaultHeight.toFloat())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_280))
            .then(if (focused) Modifier.scale(1.05f) else Modifier)
            .background(color = TvColors.PanelIdle, shape = shape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongClick() }
            )
    ) {
        // 图片：复用原生 ImageView + ImgUtil（保留 Glide 缓存/fallback）
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

        // 左上角来源角标
        if (item.sourceName.isNotEmpty()) {
            Text(
                text = item.sourceName,
                color = Color.White,
                fontSize = TvSpec.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(dimensionResource(R.dimen.vs_5))
                    .background(TvColors.BadgeBg, RoundedCornerShape(TvSpec.CornerXs))
                    .padding(horizontal = dimensionResource(R.dimen.vs_5))
            )
        }

        // 底部：上次看到 + 标题
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

        // 删除模式遮罩
        if (delMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvColors.DeleteMask),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.icon_delete),
                    contentDescription = null,
                    modifier = Modifier.size(dimensionResource(R.dimen.vs_80))
                )
            }
        }
    }
}
