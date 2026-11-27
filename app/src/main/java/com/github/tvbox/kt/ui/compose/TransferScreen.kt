package com.github.tvbox.kt.ui.compose

import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.ProgressBar
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.kt.R
import java.io.File

/**
 * 网页传文件页：左侧二维码 + 地址 + 上传进度，右侧已上传文件列表。
 * 二维码/进度条沿用原生控件（AndroidView），保证视觉与原页面一致且无 API 风险。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferScreen(
    qrBitmap: Bitmap?,
    address: String,
    progress: Int,
    status: String,
    files: List<File>,
    onOpenFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg)
            .padding(
                start = dimensionResource(R.dimen.vs_40),
                top = dimensionResource(R.dimen.vs_24),
                end = dimensionResource(R.dimen.vs_40),
                bottom = dimensionResource(R.dimen.vs_24)
            )
    ) {
        // 左栏：二维码 + 地址 + 进度 + 状态
        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundResource(R.drawable.shape_dialog_bg_main)
                        val p = 12
                        setPadding(p, p, p, p)
                        contentDescription = "传文件二维码"
                    }
                },
                update = { it.setImageBitmap(qrBitmap) },
                modifier = Modifier
                    .size(dimensionResource(R.dimen.vs_220))
                    .padding(bottom = dimensionResource(R.dimen.vs_14))
            )

            Text(
                text = address,
                color = Color.White,
                fontSize = TvSpec.Body,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.vs_10))
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_14)))

            AndroidView(
                factory = { ctx ->
                    ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                    }
                },
                update = { it.progress = progress },
                modifier = Modifier
                    .width(dimensionResource(R.dimen.vs_320))
                    .height(dimensionResource(R.dimen.vs_8))
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_8)))

            Text(
                text = status,
                color = TvColors.HintText,
                fontSize = TvSpec.Value,
                textAlign = TextAlign.Center
            )
        }

        // 中间竖分隔线
        Box(
            modifier = Modifier
                .width(dimensionResource(R.dimen.vs_1))
                .fillMaxHeight()
                .background(TvDivider.Line)
        )

        // 右栏：已上传文件列表
        Column(
            modifier = Modifier
                .weight(3f)
                .fillMaxHeight()
                .padding(
                    start = dimensionResource(R.dimen.vs_30),
                    top = dimensionResource(R.dimen.vs_8),
                    bottom = dimensionResource(R.dimen.vs_10)
                )
        ) {
            Text(
                text = "已上传文件",
                color = Color.White,
                fontSize = TvSpec.Heading,
                modifier = Modifier.padding(bottom = dimensionResource(R.dimen.vs_12))
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvColors.PanelIdle)
            ) {
                if (files.isEmpty()) {
                    item {
                        Text(
                            text = "暂无文件",
                            color = TvColors.HintText,
                            fontSize = TvSpec.Body,
                            modifier = Modifier.padding(
                                horizontal = dimensionResource(R.dimen.vs_10),
                                vertical = dimensionResource(R.dimen.vs_20)
                            )
                        )
                    }
                } else {
                    items(files) { file ->
                        TransferFileRow(
                            file = file,
                            onOpen = { onOpenFile(file) },
                            onDelete = { onDeleteFile(file) }
                        )
                    }
                }
            }
        }
    }
}

/** 单个文件行：文件名(点击打开) + 删除按钮，横向焦点自然导航。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferFileRow(
    file: File,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.vs_60))
            .padding(vertical = dimensionResource(R.dimen.vs_5)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusPill(
            text = file.name + "  " + readable(file.length()),
            weight = 1f,
            onClick = onOpen,
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.vs_8)))
        FocusPill(
            text = "删除",
            weight = 0f,
            fixedWidth = dimensionResource(R.dimen.vs_100),
            onClick = onDelete,
            textAlign = TextAlign.Center
        )
    }
}

/** 可聚焦的操作块：焦点时放大 + 青色描边。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.FocusPill(
    text: String,
    weight: Float,
    onClick: () -> Unit,
    textAlign: TextAlign,
    fixedWidth: androidx.compose.ui.unit.Dp? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvSpec.CornerSm)
    val base = Modifier
        .height(dimensionResource(R.dimen.vs_60))
        .then(if (fixedWidth != null) Modifier.width(fixedWidth) else Modifier.weight(weight))
        .then(if (focused) Modifier.scale(1.04f) else Modifier)
        .background(color = TvColors.PanelIdle, shape = shape)
        .border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
            shape = shape
        )
        .focusable(interactionSource = interactionSource)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)

    Box(
        modifier = base.padding(horizontal = dimensionResource(R.dimen.vs_10)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = TvSpec.Value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    }
}

private fun readable(n: Long): String {
    return if (n < 1024) "$n B"
    else if (n < 1048576) (n / 1024).toString() + " KB"
    else (n / 1048576).toString() + " MB"
}
