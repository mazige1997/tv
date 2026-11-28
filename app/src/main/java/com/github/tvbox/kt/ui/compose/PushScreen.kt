package com.github.tvbox.kt.ui.compose

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun PushScreen(
    qrBitmap: Bitmap?,
    address: String,
    onPushClipboard: () -> Unit
) {
    TvAdaptiveLayout { metrics ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(metrics.contentPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "推送二维码"
                    }
                },
                update = { it.setImageBitmap(qrBitmap) },
                modifier = Modifier.size((metrics.width * 0.28f).coerceAtMost(280.dp))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            TvActionButton(text = "推送剪贴板内容", onClick = onPushClipboard)
        }
    }
}
