package com.github.tvbox.kt.ui.compose

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.util.ImgUtil

private val PosterShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

// TV Material Surface 1.0.1 只处理按键，补上触摸与鼠标点击。
@Composable
internal fun Modifier.tvPointerClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = { currentOnClick() },
            onLongPress = { currentOnLongClick?.invoke() }
        )
    }
}

@Composable
fun TvPosterCard(
    video: Movie.Video,
    modifier: Modifier = Modifier,
    cardWidth: Dp = LocalTvLayoutMetrics.current.cardWidth,
    cardHeight: Dp = cardWidth * 1.5f,
    badge: String = video.year.takeIf { it > 0 }?.toString().orEmpty(),
    deleteMode: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val name = video.name.orEmpty()
    val note = video.note.orEmpty()
    val description = listOf(name, note).filter { it.isNotBlank() }.joinToString("，")
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .tvPointerClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardDefaults.shape(PosterShape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = Color.White,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = Color.White
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = PosterShape
            )
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                elevation = 8.dp
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TvPosterImage(
                name = name,
                pic = video.pic,
                targetSize = DpSize(cardWidth, cardHeight),
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight * 0.42f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                        )
                    )
            )
            if (badge.isNotBlank()) {
                TvBadge(
                    text = badge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (note.isNotBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelMedium,
                        color = TvColors.ValueText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = name.ifBlank { "未命名" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (deleteMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TvColors.DeleteMask),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.titleLarge,
                        color = TvColors.Accent
                    )
                }
            }
        }
    }
}

@Composable
fun TvFeaturedContent(
    video: Movie.Video,
    modifier: Modifier = Modifier,
    height: Dp = LocalTvLayoutMetrics.current.heroHeight,
    badge: String = "",
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val name = video.name.orEmpty()
    val compactHero = height < 200.dp
    val metadata = listOfNotNull(
        video.year.takeIf { it > 0 }?.toString(),
        video.area?.takeIf { it.isNotBlank() },
        video.type?.takeIf { it.isNotBlank() },
        video.note?.takeIf { it.isNotBlank() }
    ).take(3).joinToString("  ·  ")
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = name
                role = Role.Button
            }
            .tvPointerClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = Color.White,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = Color.White
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                elevation = 10.dp
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TvPosterImage(
                name = name,
                pic = video.pic,
                targetSize = DpSize(LocalTvLayoutMetrics.current.width, height),
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.88f),
                                Color.Black.copy(alpha = 0.46f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compactHero) 20.dp else 28.dp,
                        vertical = if (compactHero) 16.dp else 24.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(if (compactHero) 6.dp else 8.dp)
            ) {
                if (badge.isNotBlank()) {
                    TvBadge(text = badge)
                }
                Text(
                    text = name.ifBlank { "今日精选" },
                    style = if (compactHero) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.displaySmall
                    },
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = if (compactHero) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compactHero) 6.dp else 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.icon_play),
                        contentDescription = null,
                        tint = TvColors.Accent,
                        modifier = Modifier.size(if (compactHero) 18.dp else 22.dp)
                    )
                    Text(
                        text = "查看详情",
                        style = if (compactHero) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        color = TvColors.Accent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvQuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showLabel = LocalTvLayoutMetrics.current.showActionLabels
    val compactHeight = LocalTvLayoutMetrics.current.compactHeight
    Card(
        onClick = onClick,
        modifier = modifier
            .width(if (showLabel) 72.dp else 52.dp)
            .height(if (compactHeight) 52.dp else 64.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!isConfirm) {
                    false
                } else {
                    if (
                        event.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                        !event.nativeKeyEvent.isCanceled
                    ) {
                        onClick()
                    }
                    true
                }
            }
            .tvPointerClickable(onClick = onClick),
        shape = CardDefaults.shape(PosterShape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = PosterShape
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = if (compactHeight) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            if (showLabel) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun TvBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.68f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
fun TvLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TvColors.Accent, strokeWidth = 3.dp)
            Text(
                text = "正在加载…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun TvEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.icon_img_placeholder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionText != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .tvPointerClickable(onClick = onAction),
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(text = actionText)
                }
            }
        }
    }
}

@Composable
private fun TvPosterImage(
    name: String,
    pic: String?,
    modifier: Modifier,
    targetSize: DpSize? = null
) {
    val density = LocalDensity.current
    val metrics = LocalTvLayoutMetrics.current
    val loadWidth = targetSize?.width ?: metrics.cardWidth
    val loadHeight = targetSize?.height ?: metrics.cardHeight
    val targetWidth = with(density) { loadWidth.roundToPx().coerceIn(1, 2048) }
    val targetHeight = with(density) { loadHeight.roundToPx().coerceIn(1, 1536) }
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { view ->
            val key = PosterRequestKey(pic, targetWidth, targetHeight, name)
            if (view.tag != key) {
                view.tag = key
                if (!pic.isNullOrBlank()) {
                    ImgUtil.loadCenterCrop(pic, view, 1, targetWidth, targetHeight, name)
                } else {
                    view.setImageDrawable(ImgUtil.createTextDrawable(name.ifBlank { "TVBox" }))
                }
            }
        },
        modifier = modifier
    )
}

private data class PosterRequestKey(
    val pic: String?,
    val width: Int,
    val height: Int,
    val name: String
)
