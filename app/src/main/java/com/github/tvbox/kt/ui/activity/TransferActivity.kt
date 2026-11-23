package com.github.tvbox.kt.ui.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.server.RemoteServer
import com.github.tvbox.kt.ui.tv.widget.QRCodeGen
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 网页传文件页(二维码 + 进度 + 文件管理),Kotlin 移植(与旧版 TransferActivity 一致)。 */
class TransferActivity : BaseActivity() {
    private var fileList: LinearLayout? = null
    private var address: String? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null
    private var progressName: String? = null
    private var fileSignature: String? = null
    private val handler = Handler()
    private val refresh = object : Runnable {
        override fun run() {
            loadFiles()
            handler.postDelayed(this, 2000)
        }
    }
    private val progressRefresh = object : Runnable {
        override fun run() {
            ProgressTask().execute()
            handler.postDelayed(this, 800)
        }
    }

    override val layoutResID: Int
        get() = R.layout.activity_transfer

    override fun init() {
        val qr = findViewById<ImageView>(R.id.ivTransferQr)
        val addressView = findViewById<TextView>(R.id.tvTransferAddress)
        fileList = findViewById(R.id.transferFileList)
        progress = findViewById(R.id.transferProgress)
        status = findViewById(R.id.tvTransferStatus)
        address = ControlManager.get().getAddress(false)
        val page = address + "transfer.html"
        addressView.text = "手机或电脑扫描二维码，或访问：\n" + page
        qr.setImageBitmap(QRCodeGen.generateBitmap(page, 420, 420))
        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        handler.post(progressRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(progressRefresh)
        super.onPause()
    }

    private inner class ProgressTask : AsyncTask<Void, Void, String>() {
        override fun doInBackground(vararg ignored: Void?): String? {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(address + "transfer/progress").openConnection() as HttpURLConnection
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val line = reader.readLine()
                reader.close()
                return line
            } catch (ignoredError: Throwable) {
                return null
            } finally {
                if (connection != null) connection.disconnect()
            }
        }

        override fun onPostExecute(value: String?) {
            if (value == null) return
            try {
                val jsonObject = JSONObject(value)
                val percent = jsonObject.optInt("progress", 0)
                val name = jsonObject.optString("name", "")
                progressName = name
                val fileReady = percent >= 100 && hasTransferFile(name)
                val displayPercent = if (percent >= 100 && !fileReady) 99 else percent
                progress?.progress = displayPercent
                if (displayPercent > 0 && displayPercent < 100 && name.length > 0) {
                    this@TransferActivity.status!!.text = "正在上传 " + name + "（" + displayPercent + "%）"
                } else if (fileReady) {
                    this@TransferActivity.status!!.text = "最近一次上传完成"
                } else if (percent >= 100) {
                    this@TransferActivity.status!!.text = "正在确认 " + (if (name.length > 0) name else "文件")
                } else {
                    this@TransferActivity.status!!.text = "等待网页上传"
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun hasTransferFile(name: String): Boolean {
        if (name.isEmpty()) return false
        val fs = RemoteServer.getTransferDirectory().listFiles() ?: return false
        for (f in fs) if (f.isFile && name == f.name) return true
        return false
    }

    private fun loadFiles() {
        val dir = RemoteServer.getTransferDirectory()
        val fs = dir.listFiles()
        if (progress!!.progress > 0 && progressName != null && progressName!!.isNotEmpty() && fs != null) {
            for (f in fs) {
                if (f.isFile && progressName == f.name) {
                    progressName = ""
                    progress!!.progress = 0
                    this@TransferActivity.status!!.text = "等待网页上传"
                    break
                }
            }
        }
        val signature = StringBuilder()
        if (fs != null) for (f in fs) if (f.isFile) signature.append(f.name).append(':').append(f.length()).append(';')
        if (fileSignature != null && signature.toString() == fileSignature) return
        fileSignature = signature.toString()
        fileList!!.removeAllViews()
        if (fs == null || fs.isEmpty()) {
            val empty = TextView(this)
            empty.text = "暂无文件"
            empty.setTextColor(0xffb0bec5.toInt())
            empty.textSize = 20f
            empty.setPadding(8, 18, 8, 18)
            fileList!!.addView(empty)
            return
        }
        for (f in fs) if (f.isFile) addFileRow(f)
    }

    private fun addFileRow(file: File) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(8, 5, 8, 5)
        val name = TextView(this)
        name.id = View.generateViewId()
        name.text = file.name + "  " + readable(file.length())
        name.setTextColor(0xffffffff.toInt())
        name.textSize = 20f
        name.setSingleLine(true)
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        name.gravity = Gravity.CENTER_VERTICAL
        name.isFocusable = true
        name.isClickable = true
        name.setBackgroundResource(R.drawable.shape_setting_model_focus)
        name.setPadding(12, 4, 12, 4)
        row.addView(name, LinearLayout.LayoutParams(0, resources.getDimensionPixelSize(R.dimen.vs_60), 1f))
        val delete = Button(this)
        delete.id = View.generateViewId()
        delete.text = "删除"
        delete.textSize = 18f
        delete.setTextColor(0xffffffff.toInt())
        delete.setBackgroundResource(R.drawable.button_danmu_setting)
        delete.isFocusable = true
        delete.contentDescription = "删除 " + file.name
        val deleteParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.vs_100),
            resources.getDimensionPixelSize(R.dimen.vs_60)
        )
        deleteParams.leftMargin = resources.getDimensionPixelSize(R.dimen.vs_8)
        row.addView(delete, deleteParams)
        name.nextFocusRightId = delete.id
        delete.nextFocusLeftId = name.id
        name.setOnClickListener { _ -> openFile(file) }
        delete.setOnClickListener { _ -> if (file.delete()) loadFiles() }
        fileList!!.addView(row)
        if (fileList!!.childCount == 1) name.requestFocus()
    }

    private fun openFile(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 24) {
            uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else uri = Uri.fromFile(file)
        intent.setDataAndType(uri, mimeType(file.name))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有找到可以打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeType(name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive"
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")) return "video/*"
        if (lower.endsWith(".mp3") || lower.endsWith(".flac")) return "audio/*"
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) return "image/*"
        return "*/*"
    }

    private fun readable(n: Long): String {
        return if (n < 1024) "$n B" else if (n < 1048576) (n / 1024).toString() + " KB" else (n / 1048576).toString() + " MB"
    }
}
