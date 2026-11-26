package com.github.tvbox.kt.ui.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.server.RemoteServer
import com.github.tvbox.kt.ui.compose.TransferScreen
import com.github.tvbox.kt.ui.compose.TvTheme
import com.github.tvbox.kt.ui.tv.widget.QRCodeGen
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 网页传文件页(二维码 + 进度 + 文件管理),UI 由 Jetpack Compose 承载。 */
class TransferActivity : BaseActivity() {
    private var address: String = ""
    private var progressName: String? = null
    private var fileSignature: String? = null
    private val handler = Handler()

    // Compose UI state
    private val qrBitmap = mutableStateOf<Bitmap?>(null)
    private val addressText = mutableStateOf("")
    private val progressValue = mutableStateOf(0)
    private val statusText = mutableStateOf("请在扫码后的网页查看上传进度")
    private val fileItems = mutableStateListOf<File>()

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
        get() = R.layout.activity_simple_compose

    override fun init() {
        address = ControlManager.get().getAddress(false)
        val page = address + "transfer.html"
        addressText.value = "手机或电脑扫描二维码，或访问：\n" + page
        qrBitmap.value = QRCodeGen.generateBitmap(page, 420, 420)
        loadFiles()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeView).setContent {
            TvTheme {
                TransferScreen(
                    qrBitmap = qrBitmap.value,
                    address = addressText.value,
                    progress = progressValue.value,
                    status = statusText.value,
                    files = fileItems,
                    onOpenFile = ::openFile,
                    onDeleteFile = { file -> if (file.delete()) loadFiles() }
                )
            }
        }
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
                progressValue.value = displayPercent
                if (displayPercent > 0 && displayPercent < 100 && name.isNotEmpty()) {
                    statusText.value = "正在上传 " + name + "（" + displayPercent + "%）"
                } else if (fileReady) {
                    statusText.value = "最近一次上传完成"
                } else if (percent >= 100) {
                    statusText.value = "正在确认 " + (if (name.isNotEmpty()) name else "文件")
                } else {
                    statusText.value = "等待网页上传"
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
        if (progressValue.value > 0 && progressName != null && progressName!!.isNotEmpty() && fs != null) {
            for (f in fs) {
                if (f.isFile && progressName == f.name) {
                    progressName = ""
                    progressValue.value = 0
                    statusText.value = "等待网页上传"
                    break
                }
            }
        }
        val signature = StringBuilder()
        if (fs != null) for (f in fs) if (f.isFile) signature.append(f.name).append(':').append(f.length()).append(';')
        if (fileSignature != null && signature.toString() == fileSignature) return
        fileSignature = signature.toString()
        fileItems.clear()
        if (fs == null || fs.isEmpty()) return
        for (f in fs) if (f.isFile) fileItems.add(f)
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
}
