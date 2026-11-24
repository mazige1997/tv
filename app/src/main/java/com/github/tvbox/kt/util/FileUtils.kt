package com.github.tvbox.kt.util

import android.os.Environment
import android.text.TextUtils
import android.util.Base64
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.core.net.OkHttp
import com.github.tvbox.kt.server.ControlManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.orhanobut.hawk.Hawk
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.regex.Pattern

/**
 * 文件与 JS 模块缓存工具,Kotlin 移植(与旧版 FileUtils 一致)。
 */
object FileUtils {

    @JvmStatic
    fun writeSimple(data: ByteArray, dst: File): Boolean {
        return try {
            if (dst.exists()) dst.delete()
            val bos = BufferedOutputStream(FileOutputStream(dst))
            bos.write(data)
            bos.close()
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun readSimple(src: File): ByteArray? {
        return try {
            val bis = BufferedInputStream(FileInputStream(src))
            val len = bis.available()
            val data = ByteArray(len)
            bis.read(data)
            bis.close()
            data
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    @Throws(IOException::class)
    fun copyFile(source: File, dest: File) {
        var is0: InputStream? = null
        var os: OutputStream? = null
        try {
            is0 = FileInputStream(source)
            os = FileOutputStream(dest)
            val buffer = ByteArray(1024)
            while (true) {
                val length = is0!!.read(buffer)
                if (length <= 0) break
                os!!.write(buffer, 0, length)
            }
        } finally {
            is0?.close()
            os?.close()
        }
    }

    fun recursiveDelete(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            val files = file.listFiles()
            if (files != null) {
                for (f in files) {
                    recursiveDelete(f)
                }
            }
        }
        file.delete()
    }

    fun readFileToString(path: String, charsetName: String): String {
        var jsonString = ""
        try {
            BufferedReader(InputStreamReader(FileInputStream(File(path)), charsetName)).use { input ->
                var thisLine: String? = null
                while (input.readLine().also { thisLine = it } != null) {
                    jsonString += thisLine
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return jsonString
    }

    fun getRootPath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun getLocal(path: String): File {
        return File(path.replace("file:/", getRootPath()))
    }

    fun getCacheDir(): File = KtApp.instance().cacheDir

    fun getCachePath(): String = getCacheDir().absolutePath

    /**
     * 边下边播(ExoPlayer SimpleCache)的缓存目录,必须与
     * xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.externalCacheDir()/newCache() 一致:
     * 优先外部私有缓存目录,回退内部缓存目录,子目录名 exo-video-cache。
     */
    @JvmStatic
    fun exoCacheDir(): File? {
        return try {
            val base = KtApp.instance().externalCacheDir ?: KtApp.instance().cacheDir
            File(base, "exo-video-cache")
        } catch (_: Throwable) {
            null
        }
    }

    /** 统计边下边播缓存:返回 "path=... files=N size=X",供诊断日志界面实时显示。 */
    @JvmStatic
    fun exoCacheSummary(): String {
        return try {
            val dir = exoCacheDir()
            if (dir == null || !dir.exists()) {
                "path=(不存在,尚未开始缓存) files=0 size=0B"
            } else {
                val files = dir.listFiles() ?: emptyArray()
                var count = 0
                var bytes = 0L
                for (f in files) {
                    if (!f.isFile) continue
                    count++
                    bytes += f.length()
                }
                "path=${dir.absolutePath} files=$count size=${formatSize(bytes)}"
            }
        } catch (th: Throwable) {
            "统计失败: ${th.javaClass.simpleName}"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.2fMB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    fun getFilePath(): String = KtApp.instance().filesDir.absolutePath

    fun cleanDirectory(dir: File?) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles() ?: return
        if (files.isEmpty()) return
        for (one in files) {
            try {
                deleteFile(one)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isWeekAgo(file: File): Boolean {
        val oneWeekMillis = 3L * 24 * 60 * 60 * 1000
        val timeDiff = System.currentTimeMillis() - file.lastModified()
        return timeDiff > oneWeekMillis
    }

    fun deleteFile(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isFile) {
            if (file.canWrite()) file.delete()
            return
        }
        if (file.isDirectory) {
            val files = file.listFiles()
            if (files == null || files.isEmpty()) {
                if (file.canWrite()) file.delete()
                return
            }
            for (one in files) {
                deleteFile(one)
            }
        }
    }

    fun cleanPlayerCache() {
        val ijkCachePath = getCachePath() + "/ijkcaches/"
        val thunderCachePath = getCachePath() + "/thunder/"
        val ijkCacheDir = File(ijkCachePath)
        val thunderCacheDir = File(thunderCachePath)
        try {
            if (ijkCacheDir.exists()) cleanDirectory(ijkCacheDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (thunderCacheDir.exists()) cleanDirectory(thunderCacheDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearSpiderCacheFiles() {
        cleanDirectory(File(getFilePath() + "/csp/"))
        cleanDirectory(File(getCachePath() + "/jar/"))
        cleanDirectory(File(getCachePath() + "/py/"))
        cleanDirectory(File(getCachePath() + "/catvod_jsapi/"))
        clearJsModuleCache()
    }

    private fun clearJsModuleCache() {
        val externalCacheDir = File(getExternalCachePath())
        val files = externalCacheDir.listFiles() ?: return
        for (file in files) {
            if (file != null && file.name.startsWith("qjscache_")) {
                deleteFile(file)
            }
        }
    }

    fun read(path: String): String {
        return try {
            val br = BufferedReader(InputStreamReader(FileInputStream(getLocal(path))))
            val sb = StringBuilder()
            var text: String?
            while (br.readLine().also { text = it } != null) sb.append(text).append("\n")
            br.close()
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun getFileName(filePath: String?): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath
        val p = fileName!!.lastIndexOf(File.separatorChar)
        if (p != -1) {
            fileName = fileName.substring(p + 1)
        }
        return fileName
    }

    fun getFileNameWithoutExt(filePath: String?): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath
        var p = fileName!!.lastIndexOf(File.separatorChar)
        if (p != -1) {
            fileName = fileName.substring(p + 1)
        }
        p = fileName.indexOf('.')
        if (p != -1) {
            fileName = fileName.substring(0, p)
        }
        return fileName
    }

    @JvmStatic
    fun hasExtension(path: String): Boolean {
        val lastDotIndex = path.lastIndexOf(".")
        val lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"))
        // 如果路径中有点号,并且点号在最后一个斜杠之后,认为有后缀
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length - 1
    }

    fun saveCache(cache: File, json: String) {
        try {
            val cacheDir = cache.parentFile
            if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs()
            if (cache.exists()) cache.delete()
            val fos = FileOutputStream(cache)
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.close()
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    // ---------------- JS 模块加载 ----------------
    private val URL_JOIN: Pattern =
        Pattern.compile("^http.*\\.(js|txt|json)", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)

    fun loadModule(name: String): String? {
        var rel: String? = null
        try {
            var module = name
            if (module.contains("gbk.js")) {
                module = "gbk.js"
            } else if (module.contains("模板.js")) {
                module = "模板.js"
            } else if (module.contains("cat.js")) {
                module = "cat.js"
            }
            LOG.i("echo-loadModule $module")
            val m = URL_JOIN.matcher(module)
            if (m.find()) {
                if (!Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
                    val cache = getCache(MD5.encode(module))
                    rel = cache
                    if (StringUtils.isEmpty(cache)) {
                        val netStr = get(module)
                        if (!TextUtils.isEmpty(netStr)) {
                            setCache(604800, MD5.encode(module), netStr)
                        }
                        rel = netStr
                    }
                } else {
                    rel = get(module)
                }
            } else if (module.startsWith("assets://")) {
                rel = getAsOpen(module.substring(9))
            } else if (isAsFile(module, "js/lib")) {
                rel = getAsOpen("js/lib/$module")
            } else if (module.startsWith("file://")) {
                rel = get(
                    ControlManager.get().getAddress(true) + "file/" +
                        module.replace("file:///", "").replace("file://", "")
                )
            } else if (module.startsWith("clan://localhost/")) {
                rel = get(
                    ControlManager.get().getAddress(true) + "file/" +
                        module.replace("clan://localhost/", "")
                )
            } else if (module.startsWith("clan://")) {
                val substring = module.substring(7)
                val indexOf = substring.indexOf(47.toChar())
                rel = get("http://" + substring.substring(0, indexOf) + "/file/" + substring.substring(indexOf + 1))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rel
    }

    private val cachedDirFiles = HashMap<String, Set<String>>()

    fun isAsFile(name: String, dir: String): Boolean {
        // 1. 先从缓存里取目录列表
        var files = cachedDirFiles[dir]
        if (files == null) {
            LOG.i("echo-读取AssetsList")
            val loaded: Set<String> = try {
                val list = KtApp.instance().assets.list(dir)
                HashSet(Arrays.asList(*list))
            } catch (e: IOException) {
                Collections.emptySet()
            }
            files = loaded
            cachedDirFiles[dir] = files
        }
        // 2. 内存查找
        return files.contains(name.trim())
    }

    fun getAsOpen(name: String): String {
        return try {
            val is0 = KtApp.instance().assets.open(name)
            val data = ByteArray(is0.available())
            is0.read(data)
            String(data, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getCache(name: String): String {
        return try {
            var code = ""
            val file = open(name)
            if (file.exists()) {
                code = String(readSimple(file) ?: ByteArray(0))
            }
            if (TextUtils.isEmpty(code)) {
                return ""
            }
            val asJsonObject = Gson().fromJson(code, JsonObject::class.java).asJsonObject
            if (asJsonObject.get("expires").asInt.toLong() <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name))
            }
            asJsonObject.get("data").asString
        } catch (e4: Exception) {
            ""
        }
    }

    fun setCache(time: Int, name: String, data: String) {
        try {
            val jSONObject = JSONObject()
            jSONObject.put("expires", (time + (System.currentTimeMillis() / 1000)).toInt())
            jSONObject.put("data", data)
            writeSimple(jSONObject.toString().toByteArray(), open(name))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setCacheByte(name: String, data: ByteArray) {
        try {
            writeSimple(
                byteMerger("//DRPY".toByteArray(), Base64.encode(data, Base64.URL_SAFE)),
                open("B_$name")
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun byteMerger(bt1: ByteArray, bt2: ByteArray): ByteArray {
        val bt3 = ByteArray(bt1.size + bt2.size)
        System.arraycopy(bt1, 0, bt3, 0, bt1.size)
        System.arraycopy(bt2, 0, bt3, bt1.size, bt2.size)
        return bt3
    }

    fun get(str: String): String {
        return get(str, null)
    }

    fun get(str: String, headerMap: Map<String, String>?): String {
        val headers = headerMap ?: HashMap<String, String>().apply {
            put("User-Agent", if (str.startsWith("https://gitcode.net/")) UA.random() else "okhttp/3.15")
        }
        return OkHttp.string(str, headers)
    }

    fun open(str: String): File {
        return File(getExternalCachePath() + "/qjscache_" + str + ".js")
    }

    fun getExternalCachePath(): String {
        val externalCacheDir = KtApp.instance().externalCacheDir
        if (externalCacheDir == null) {
            return getCachePath()
        }
        return externalCacheDir.absolutePath
    }
}
