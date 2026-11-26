package com.github.tvbox.kt.ui.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.ui.compose.LocalFileItem
import com.github.tvbox.kt.ui.compose.LocalFileScreen
import com.github.tvbox.kt.ui.compose.TvTheme
import java.io.File
import java.text.DecimalFormat
import java.util.Arrays
import java.util.Collections

/** 本地文件选择页(直播/点播配置文件),UI 由 Jetpack Compose 承载。 */
class LocalFileActivity : BaseActivity() {
    private val fileItems = mutableStateListOf<LocalFileItem>()
    private val titleText = mutableStateOf("")
    private val pathText = mutableStateOf("")
    private var files = ArrayList<File>()
    private var currentDir: File? = null
    private var rootDir: File? = null

    override val layoutResID: Int
        get() = R.layout.activity_simple_compose

    override fun init() {
        initRoot()
        val live = intent != null && intent.getBooleanExtra(EXTRA_LIVE, false)
        titleText.value = if (live) "选择直播配置文件" else "选择点播配置文件"
        loadDir(rootDir)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeView).setContent {
            TvTheme {
                LocalFileScreen(
                    title = titleText.value,
                    path = pathText.value,
                    files = fileItems,
                    onFileClick = ::openAt
                )
            }
        }
    }

    private fun initRoot() {
        val storage = Environment.getExternalStorageDirectory()
        val tvBox = File(storage, "TVBox")
        if (!tvBox.exists()) tvBox.mkdirs()
        rootDir = if (tvBox.exists() && tvBox.isDirectory) tvBox else storage
    }

    private fun loadDir(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "无法打开目录", Toast.LENGTH_SHORT).show()
            return
        }
        currentDir = dir
        pathText.value = dir.absolutePath
        val list = ArrayList<File>()
        val parent = dir.parentFile
        val isRoot = isStorageRoot(dir)
        if (!isRoot && parent != null) list.add(parent)
        val children = dir.listFiles()
        if (children != null) list.addAll(Arrays.asList(*children))
        Collections.sort(list) { left, right ->
            if (left == currentDir?.parentFile) return@sort -1
            if (right == currentDir?.parentFile) return@sort 1
            if (left.isDirectory != right.isDirectory) return@sort if (left.isDirectory) -1 else 1
            (left.name ?: "").compareTo(right.name ?: "", ignoreCase = true)
        }
        files = list
        val newItems = list.map { f ->
            val isParent = !isRoot && parent != null && f == parent
            LocalFileItem(
                name = if (isParent) ".." else f.name,
                type = if (f.isDirectory) "目录" else "文件",
                info = if (f.isDirectory) "进入" else formatSize(f.length())
            )
        }
        fileItems.clear(); fileItems.addAll(newItems)
    }

    private fun openAt(position: Int) {
        if (position !in files.indices) return
        open(files[position])
    }

    private fun open(item: File) {
        if (item.isDirectory) {
            loadDir(item)
            return
        }
        val data = Intent()
        data.data = Uri.fromFile(item)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) {
            val parent = currentDir?.parentFile
            if (currentDir != null && !isStorageRoot(currentDir!!) && parent != null) {
                loadDir(parent)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isStorageRoot(dir: File): Boolean {
        return try {
            Environment.getExternalStorageDirectory().canonicalPath == dir.canonicalPath
        } catch (ignored: Throwable) {
            Environment.getExternalStorageDirectory().absolutePath == dir.absolutePath
        }
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return SIZE_FORMAT.format(kb) + " KB"
        val mb = kb / 1024.0
        if (mb < 1024) return SIZE_FORMAT.format(mb) + " MB"
        return SIZE_FORMAT.format(mb / 1024.0) + " GB"
    }

    companion object {
        @JvmField
        val EXTRA_LIVE = "live"
        private val SIZE_FORMAT = DecimalFormat("0.#")
    }
}
