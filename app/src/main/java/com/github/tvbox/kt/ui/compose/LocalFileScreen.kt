package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

data class LocalFileItem(
    val name: String,
    val type: String,
    val info: String
)

@Composable
fun LocalFileScreen(
    title: String,
    path: String,
    files: List<LocalFileItem>,
    onFileClick: (Int) -> Unit
) {
    TvAdaptiveLayout { metrics ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(files.size) {
            if (files.isNotEmpty()) runCatching { focusRequester.requestFocus() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(metrics.contentPadding)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (files.isEmpty()) {
                TvEmptyState(
                    title = "当前目录为空",
                    message = "请选择其他目录",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files, key = { "${it.type}\u0001${it.name}\u0001${it.info}" }) { file ->
                        val index = files.indexOf(file)
                        TvListItem(
                            title = "${file.type}  ${file.name}",
                            value = file.info,
                            focusRequester = if (index == 0) focusRequester else null,
                            onClick = { onFileClick(index) }
                        )
                    }
                }
            }
        }
    }
}
