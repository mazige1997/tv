package com.github.tvbox.kt.ui.compose

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.kt.R

/** 推送入口页：二维码 + 访问地址 + 剪贴板推送按钮。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PushScreen(
    qrBitmap: Bitmap?,
    address: String,
    onPushClipboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.ScreenBg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 二维码：复用原生 ImageView 显示已生成的 Bitmap
        AndroidView(
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "推送二维码"
                }
            },
            update = { it.setImageBitmap(qrBitmap) },
            modifier = Modifier.size(dimensionResource(R.dimen.vs_300))
        )

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_20)))

        Text(
            text = address,
            color = Color.White,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp,
            modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.vs_40))
        )

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.vs_20)))

        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .then(if (focused) Modifier.scale(1.05f) else Modifier)
                .background(
                    color = if (focused) TvColors.FocusEnd else TvColors.PanelIdle,
                    shape = shape
                )
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) TvColors.Accent else TvColors.StrokeIdle,
                    shape = shape
                )
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onPushClipboard)
                .padding(dimensionResource(R.dimen.vs_10)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "推送剪贴板内容", color = Color.White, fontSize = 24.sp)
        }
    }
}
