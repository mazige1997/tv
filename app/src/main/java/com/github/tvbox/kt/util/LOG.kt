package com.github.tvbox.kt.util

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行时日志,Kotlin 移植(与旧版 LOG.java 一致,含长日志分段)。
 *
 * 诊断增强:所有日志同时落盘到 Download/tvbox_diag.log,便于在没有 adb 的电视盒上
 * 直接用文件管理器取出运行时链路信息(配置加载 / jar / JS 初始化 / 分类 / 详情 / 直播)。
 */
object LOG {
    private const val TAG = "TVBox-runtime"
    private const val MAX_LOG_LENGTH = 3000
    private const val FILE_NAME = "tvbox_diag.log"
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var target: File? = null

    /**
     * 日志落盘开关(默认开):关闭后仅输出到 logcat,不创建/写入诊断文件。
     * 由设置页「日志落盘」控制,启动时从 Hawk 恢复。
     */
    @Volatile
    @JvmStatic
    var diskEnabled: Boolean = true

    /** 优先 Download 目录(文件管理器可见),失败回退 app 外部私有目录;均不可写时返回 null。 */
    private fun diagFile(): File? {
        target?.let { return it }
        return synchronized(lock) {
            val existing = target
            if (existing != null) {
                existing
            } else {
                val candidates = ArrayList<File>(2)
                try {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let {
                        candidates.add(File(it, FILE_NAME))
                    }
                } catch (_: Throwable) {
                }
                try {
                    com.github.tvbox.kt.KtApp.instance().getExternalFilesDir(null)?.let {
                        candidates.add(File(it, FILE_NAME))
                    }
                } catch (_: Throwable) {
                }
                var chosen: File? = null
                for (f in candidates) {
                    try {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f, true).use { } // 探测可写
                        if (f.length() > MAX_FILE_BYTES) {
                            val all = f.readBytes()
                            val keep = (MAX_FILE_BYTES / 2).toInt()
                            f.writeBytes(all.copyOfRange(maxOf(0, all.size - keep), all.size))
                        }
                        f.appendText("\n===== TVBox 诊断日志 新会话 ${stamp.format(Date())} =====\n")
                        chosen = f
                        break
                    } catch (_: Throwable) {
                    }
                }
                target = chosen
                chosen
            }
        }
    }

    private fun write(level: String, msg: String) {
        if (!diskEnabled) return
        try {
            val f = diagFile() ?: return
            synchronized(lock) {
                f.appendText("${stamp.format(Date())} $level $msg\n")
            }
        } catch (_: Throwable) {
        }
    }

    /** 诊断日志文件的实际路径,供启动快照写入。落盘关闭时不触发文件创建。 */
    @JvmStatic
    fun diagPath(): String = try {
        if (!diskEnabled) "(日志落盘已关闭)" else diagFile()?.absolutePath ?: "(诊断日志不可写)"
    } catch (_: Throwable) {
        "(诊断日志不可写)"
    }

    @JvmStatic
    fun e(msg: String?) {
        val m = msg ?: ""
        Log.e(TAG, m)
        write("E", m)
    }

    @JvmStatic
    fun i(msg: String?) {
        val m = msg ?: ""
        Log.i(TAG, m)
        write("I", m)
    }

    fun longI(prefix: String?, msg: String) = longLog(Log.INFO, prefix, msg)

    fun longE(prefix: String?, msg: String) = longLog(Log.ERROR, prefix, msg)

    private fun longLog(priority: Int, prefix: String?, msg: String) {
        val text = msg
        val title = prefix ?: ""
        val length = text.length
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text)
            write(if (priority == Log.ERROR) "E" else "I", title + text)
            return
        }
        val count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH
        for (i in 0 until count) {
            val start = i * MAX_LOG_LENGTH
            val end = minOf(start + MAX_LOG_LENGTH, length)
            val chunk = "$title[${i + 1}/$count] ${text.substring(start, end)}"
            Log.println(priority, TAG, chunk)
            write(if (priority == Log.ERROR) "E" else "I", chunk)
        }
    }
}
