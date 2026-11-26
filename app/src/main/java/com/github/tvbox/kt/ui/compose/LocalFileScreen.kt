package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.kt.R

/** 文件列表项数据（type=目录/文件，info=进入/大小，isParent=返回上级）。 */
data class LocalFileItem(
    val name: String,
    val type: String,
    val info: String
)

/** 本地文件选择页：标题 + 路径 + 垂直焦点文件列表 + 空提示。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalFileScreen(
    title: String,
    path: String,
    files: List<LocalFileItem>,
    onFileClick: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(files.size) {
        if (files.isNotEmpty()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
            .padding(
                horizontal = dimensionResource(R.dimen.vs_40),
                vertical = dimensionResource(R.dimen.vs_30)
            )
    ) {
        Text(text = title, color = Color.White, fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_10)))
        Text(
            text = path,
            color = TvColors.HintText,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_20)))

        if (files.isEmpty()) {
            Text(
                text = "当前目录为空",
                color = TvColors.HintText,
                fontSize = 22.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.vs_8))
            ) {
                items(files.size) { index ->
                    val file = files[index]
                    LocalFileRow(
                        file = file,
                        focusRequester = if (index == 0) focusRequester else null,
                        onClick = { onFileClick(index) }
                    )
                }
            }
        }
    }
}

/** 单个文件行：类型 / 名称 / 信息，TV 焦点缩放描边。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalFileRow(
    file: LocalFileItem,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_60))
            .then(if (focused) Modifier.scale(1.03f) else Modifier)
            .background(color = if (focused) TvColors.FocusEnd else TvColors.PanelIdle, shape = shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                shape = shape
            )
            .then(focusModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.vs_20)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = file.type,
            color = Color.White,
            fontSize = 22.sp,
            maxLines = 1,
            modifier = Modifier.width(dimensionResource(R.dimen.vs_80))
        )
        Text(
            text = file.name,
            color = Color.White,
            fontSize = 24.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = file.info,
            color = TvColors.HintText,
            fontSize = 20.sp,
            maxLines = 1,
            modifier = Modifier.width(dimensionResource(R.dimen.vs_220)),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
