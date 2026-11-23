package com.github.tvbox.kt.ui.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.ui.adapter.LocalFileAdapter
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import me.jessyan.autosize.utils.AutoSizeUtils
import java.io.File
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

/** 本地文件选择页(直播/点播配置文件),Kotlin 移植(与旧版 LocalFileActivity 一致)。 */
class LocalFileActivity : BaseActivity() {
    private var fileList: TvRecyclerView? = null
    private var tvTitle: TextView? = null
    private var tvPath: TextView? = null
    private var tvEmpty: TextView? = null
    private var adapter: LocalFileAdapter? = null
    private var currentDir: File? = null
    private var rootDir: File? = null

    override val layoutResID: Int
        get() = R.layout.activity_local_file

    override fun init() {
        tvTitle = findViewById(R.id.tvTitle)
        tvPath = findViewById(R.id.tvPath)
        tvEmpty = findViewById(R.id.tvEmpty)
        fileList = findViewById(R.id.fileList)
        adapter = LocalFileAdapter()
        fileList!!.setAdapter(adapter)
        fileList!!.layoutManager = V7LinearLayoutManager(this, 1, false)
        fileList!!.setSpacingWithMargins(0, AutoSizeUtils.mm2px(this, 8f))
        adapter!!.setOnItemClickListener { adapter0, view, position ->
            val item = adapter0.getItem(position) as File?
            if (item != null) open(item)
        }
        initRoot()
        val live = intent != null && intent.getBooleanExtra(EXTRA_LIVE, false)
        tvTitle?.text = if (live) "选择直播配置文件" else "选择点播配置文件"
        loadDir(rootDir)
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
        tvPath?.text = dir.absolutePath
        val files = ArrayList<File>()
        val parent = dir.parentFile
        if (!isStorageRoot(dir) && parent != null) files.add(parent)
        val children = dir.listFiles()
        if (children != null) files.addAll(Arrays.asList(*children))
        adapter?.setParentDir(if (isStorageRoot(dir)) null else parent)
        Collections.sort(files) { left, right ->
            if (left == currentDir?.parentFile) return@sort -1
            if (right == currentDir?.parentFile) return@sort 1
            if (left.isDirectory != right.isDirectory) return@sort if (left.isDirectory) -1 else 1
            (left.name ?: "").compareTo(right.name ?: "", ignoreCase = true)
        }
        adapter?.setNewData(files)
        tvEmpty?.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        fileList?.postDelayed({
            fileList?.requestFocus()
        }, 100)
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

    companion object {
        @JvmField
        val EXTRA_LIVE = "live"
    }
}
