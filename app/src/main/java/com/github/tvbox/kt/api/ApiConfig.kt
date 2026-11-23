package com.github.tvbox.kt.api

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import com.google.android.exoplayer2.util.UriUtil
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.bean.IJKCode
import com.github.tvbox.kt.bean.LiveChannelGroup
import com.github.tvbox.kt.bean.LiveChannelItem
import com.github.tvbox.kt.bean.LiveSettingGroup
import com.github.tvbox.kt.bean.LiveSettingItem
import com.github.tvbox.kt.bean.ParseBean
import com.github.tvbox.kt.bean.ProxyRule
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.core.NativeCore
import com.github.tvbox.kt.core.net.OkHttp
import com.github.tvbox.kt.core.spider.JarLoader
import com.github.tvbox.kt.core.spider.JsLoader
import com.github.catvod.crawler.Spider
import com.github.tvbox.kt.core.spider.python.PyLoader
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.util.AES
import com.github.tvbox.kt.util.AdBlocker
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.LiveParseHelper
import com.github.tvbox.kt.util.M3u8
import com.github.tvbox.kt.util.MD5
import com.github.tvbox.kt.util.OkGoHelper
import com.github.tvbox.kt.util.ProxyUtil
import com.github.tvbox.kt.util.RegexUtils
import com.github.tvbox.kt.util.VideoParseRuler
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.orhanobut.hawk.Hawk
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLDecoder
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * 配置中心(站点/解析/直播/嗅探规则/代理),Kotlin 移植(与旧版 ApiConfig 一致)。
 * 配置解码走 Rust(NativeCore.decodeConfig),失败回退 AES 工具类。
 */
class ApiConfig private constructor() {

    val sourceBeanList = LinkedHashMap<String, SourceBean>()
    private var mHomeSource: SourceBean? = null
    private var mDefaultParse: ParseBean? = null
    val liveChannelGroupList = ArrayList<LiveChannelGroup>()
    val parseBeanList = ArrayList<ParseBean>()
    private var vipParseFlags: List<String> = ArrayList()
    private var myHosts: MutableMap<String, String>? = null
    private var ijkCodes: List<IJKCode>? = null
    private var spider: String? = null
    private var currentPyKey = ""
    private var currentLivePyKey = ""
    private var currentPlaySourceKey = ""
    private var loadedLiveConfigUrl = ""
    var wallpaper: String = ""
        private set
    private var danmaku: String? = ""
    private var currentLiveSpider: String? = ""
    private var liveSpider: String = ""

    private val emptyHome = SourceBean()

    private val jarLoader = JarLoader()
    private val jsLoader = JsLoader()
    private val pyLoader: com.github.tvbox.kt.core.spider.python.IPyLoader = PyLoader()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val configLoadExecutor = Executors.newSingleThreadExecutor()
    private val jarLoadExecutor = Executors.newSingleThreadExecutor()
    private val danmuSearchExecutor = Executors.newSingleThreadExecutor()
    private val liveSettingGroupList = ArrayList<LiveSettingGroup>()

    private val userAgent = "okhttp/3.15"
    private var searchSourceBeanList: List<SourceBean> = ArrayList()
    private var tempKey: String? = null

    init {
        clearLoader()
        Hawk.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        loadDefaultConfig()
    }

    companion object {
        // DCL 单例必须 volatile，避免其他线程（如 OkHttp DNS 解析线程）读到半初始化实例
        @Volatile
        private var instance: ApiConfig? = null
        private const val LOAD_JAR_MAX_RETRY = 1
        private var jarCache = "true"

        @JvmStatic
        fun get(): ApiConfig {
            if (instance == null) {
                synchronized(ApiConfig::class.java) {
                    if (instance == null) {
                        instance = ApiConfig()
                    }
                }
            }
            return instance!!
        }

        fun FindResult(json0: String, configKey: String?): String {
            var json = json0
            return try {
                // Rust 快路径:只有产出合法 JSON 才采用。
                // 空串 / 原文 / 解密乱码 / panic 返回 null 时一律回落到下方与原版
                // ApiConfig.java#FindResult 一致的纯 Kotlin 分支,避免整份配置解析失败。
                if (NativeCore.available) {
                    val decoded = try {
                        NativeCore.decodeConfig(json, configKey)
                    } catch (_: Throwable) {
                        null
                    }
                    if (!decoded.isNullOrEmpty() && AES.isJson(decoded)) return decoded
                }
                var content = json
                if (AES.isJson(content)) return content
                val pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*")
                val matcher = pattern.matcher(content)
                if (matcher.find()) {
                    content = content.substring(content.indexOf(matcher.group()) + 10)
                    content = String(Base64.decode(content, Base64.DEFAULT))
                }
                content = content.trim { it <= ' ' }
                if (content.startsWith("2423")) {
                    content = content.replace("\\s+".toRegex(), "")
                    val data = content.substring(content.indexOf("2324") + 4, content.length - 26)
                    content = String(AES.toBytes(content)).lowercase()
                    val key = AES.rightPadding(
                        content.substring(content.indexOf("$#") + 2, content.indexOf("#$")),
                        "0", 16
                    )
                    val iv = AES.rightPadding(content.substring(content.length - 13), "0", 16)
                    json = AES.CBC(data, key, iv) ?: ""
                } else if (configKey != null && !AES.isJson(content)) {
                    json = AES.ECB(content, configKey) ?: ""
                } else {
                    json = content
                }
                json
            } catch (e: Exception) {
                e.printStackTrace()
                json
            }
        }

        private fun getImgJar(body: String): ByteArray {
            if (NativeCore.available) {
                try {
                    return NativeCore.extractJar(body)
                } catch (_: Throwable) {
                }
            }
            return try {
                val pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*")
                val matcher = pattern.matcher(body)
                if (matcher.find()) {
                    val sub = body.substring(body.indexOf(matcher.group()) + 10)
                    Base64.decode(sub, Base64.DEFAULT)
                } else {
                    "".toByteArray()
                }
            } catch (th: Throwable) {
                "".toByteArray()
            }
        }
    }

    private fun configUrl(apiUrl0: String): String {
        var apiUrl = apiUrl0
        tempKey = null
        val pk = ";pk;"
        apiUrl = apiUrl.replace("file://", "clan://localhost/")
        return if (apiUrl.contains(pk)) {
            val a = apiUrl.split(pk.toRegex()).toTypedArray()
            tempKey = a[1]
            when {
                apiUrl.startsWith("clan") -> clanToAddress(a[0])
                apiUrl.startsWith("http") -> a[0]
                else -> "http://" + a[0]
            }
        } else if (apiUrl.startsWith("clan")) {
            clanToAddress(apiUrl)
        } else if (!apiUrl.startsWith("http")) {
            "http://$apiUrl"
        } else {
            apiUrl
        }
    }

    fun loadConfig(useCache: Boolean, callback: LoadConfigCallback, activity: Activity?) {
        val apiUrl = Hawk.get(HawkConfig.API_URL, "")
        if (apiUrl.isEmpty()) {
            callback.error("-1")
            return
        }
        val cache = File(KtApp.instance().filesDir.absolutePath + "/" + MD5.encode(apiUrl))
        if (useCache && cache.exists()) {
            // 缓存命中:文件读取 + 全量 Gson 解析移入后台单线程 executor(与网络解析路径互斥,序列化同原主线程),回调回主线程
            configLoadExecutor.execute {
                try {
                    val json = readConfigFile(cache)
                    if (switchApiCollectionIfNeeded(apiUrl, json)) {
                        mainHandler.post { loadConfig(false, callback, activity) }
                        return@execute
                    }
                    clearApiLinesIfUnmatched(apiUrl)
                    parseJson(apiUrl, json)
                    mainHandler.post { callback.success() }
                } catch (th: Throwable) {
                    th.printStackTrace()
                    // 缓存损坏/解析失败:与原逻辑一致,回退网络路径
                    mainHandler.post { loadConfig(false, callback, activity) }
                }
            }
            return
        }
        val configUrl = configUrl(apiUrl)
        val configKey = tempKey

        fetchConfigAsync(apiUrl, configUrl, configKey, object : ConfigFetchCallback {
            override fun success(json: String) {
                // 大配置的全量解析放后台,完成后回主线程
                configLoadExecutor.execute {
                    try {
                        if (switchApiCollectionIfNeeded(apiUrl, json)) {
                            FileUtils.saveCache(cache, json)
                            mainHandler.post { loadConfig(false, callback, activity) }
                            return@execute
                        }
                        clearApiLinesIfUnmatched(apiUrl)
                        parseJson(apiUrl, json)
                        FileUtils.saveCache(cache, json)
                        mainHandler.post { callback.success() }
                    } catch (th: Throwable) {
                        th.printStackTrace()
                        mainHandler.post { callback.error("配置解析失败") }
                    }
                }
            }

            override fun error(error: String) {
                if (cache.exists()) {
                    configLoadExecutor.execute {
                        try {
                            val json = readConfigFile(cache)
                            if (switchApiCollectionIfNeeded(apiUrl, json)) {
                                mainHandler.post { loadConfig(false, callback, activity) }
                                return@execute
                            }
                            clearApiLinesIfUnmatched(apiUrl)
                            parseJson(apiUrl, json)
                            mainHandler.post { callback.success() }
                        } catch (th: Throwable) {
                            th.printStackTrace()
                            mainHandler.post { callback.error("拉取配置失败\n$error") }
                        }
                    }
                    return
                }
                callback.error("拉取配置失败\n$error")
            }
        })
    }

    fun loadLiveConfig(useCache: Boolean, callback: LoadConfigCallback) {
        var apiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "")
        if (apiUrl.isEmpty()) {
            apiUrl = Hawk.get(HawkConfig.API_URL, "")
        }
        if (apiUrl.isEmpty()) {
            callback.error("-1")
            return
        }
        val liveApiUrl = apiUrl
        val liveApiConfigUrl = configUrl(liveApiUrl)
        val liveConfigKey = tempKey
        val liveCache = File(KtApp.instance().filesDir.absolutePath + "/" + MD5.encode(liveApiUrl))
        LOG.i("echo-load live config $liveApiUrl")
        if (useCache && liveCache.exists()) {
            try {
                parseLiveConfigContent(liveApiUrl, liveCache)
                if (hasLiveConfigResult()) {
                    loadedLiveConfigUrl = liveApiUrl
                    callback.success()
                    return
                }
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }
        fetchConfigAsync(liveApiUrl, liveApiConfigUrl, liveConfigKey, object : ConfigFetchCallback {
            override fun success(json: String) {
                try {
                    parseLiveConfigContent(liveApiUrl, json)
                    if (!hasLiveConfigResult()) {
                        callback.error("直播配置解析失败")
                        return
                    }
                    loadedLiveConfigUrl = liveApiUrl
                    FileUtils.saveCache(liveCache, json)
                    callback.success()
                } catch (th: Throwable) {
                    th.printStackTrace()
                    callback.error("直播配置解析失败")
                }
            }

            override fun error(error: String) {
                if (liveCache.exists()) {
                    try {
                        parseLiveConfigContent(liveApiUrl, liveCache)
                        if (hasLiveConfigResult()) {
                            loadedLiveConfigUrl = liveApiUrl
                            callback.success()
                            return
                        }
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }
                callback.error("直播配置拉取失败")
            }
        })
    }

    private fun hasLiveConfigResult(): Boolean {
        return liveChannelGroupList.isNotEmpty()
    }

    fun shouldReloadLiveConfig(): Boolean {
        var apiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "")
        if (apiUrl.isEmpty()) apiUrl = Hawk.get(HawkConfig.API_URL, "")
        return liveChannelGroupList.isEmpty() || apiUrl != loadedLiveConfigUrl
    }

    fun getLiveGroupIndexKey(): String {
        val liveApiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "")
        return if (liveApiUrl.isEmpty()) HawkConfig.LIVE_GROUP_INDEX else HawkConfig.LIVE_GROUP_INDEX + "_" + liveApiUrl
    }

    fun getLiveGroupIndex(): Int {
        return Hawk.get(getLiveGroupIndexKey(), 0)
    }

    fun setLiveGroupIndex(index: Int) {
        Hawk.put(getLiveGroupIndexKey(), index)
    }

    private interface JarDownloadCallback {
        fun complete(file: File?, error: String)
    }

    private interface ConfigFetchCallback {
        fun success(body: String)
        fun error(error: String)
    }

    private fun fetchConfigAsync(apiUrl: String, requestUrl: String, configKey: String?, callback: ConfigFetchCallback) {
        configLoadExecutor.execute {
            var result = ""
            var error = ""
            var response: okhttp3.Response? = null
            try {
                val request = okhttp3.Request.Builder()
                    .url(requestUrl)
                    .header("User-Agent", userAgent)
                    .build()
                var client = OkGoHelper.getDefaultClient()
                if (client == null) client = OkHttp.client()
                response = client!!.newCall(request).execute()
                if (!response.isSuccessful) {
                    error = "HTTP " + response.code()
                } else if (response.body() == null) {
                    error = "empty body"
                } else {
                    result = FindResult(response.body()!!.string(), configKey)
                    if (apiUrl.startsWith("clan")) {
                        result = clanContentFix(clanToAddress(apiUrl), result)
                    }
                    result = fixContentPath(apiUrl, result)
                }
            } catch (th: Throwable) {
                error = th.message ?: th.toString()
            } finally {
                closeQuietly(response?.body())
            }
            val finalResult = result
            val finalError = error
            mainHandler.post {
                if (TextUtils.isEmpty(finalError)) {
                    callback.success(finalResult)
                } else {
                    callback.error(finalError)
                }
            }
        }
    }

    private fun loadJarAsync(file: File, callback: (success: Boolean) -> Unit) {
        jarLoadExecutor.execute {
            var success = false
            try {
                success = file.exists() && jarLoader.load(file.absolutePath)
            } catch (th: Throwable) {
                LOG.e("echo---jar Loader threw exception: " + th.message)
            }
            val result = success
            mainHandler.post { callback(result) }
        }
    }

    private fun downloadJarAsync(url: String, isJarInImg: Boolean, cache: File, callback: JarDownloadCallback) {
        jarLoadExecutor.execute {
            var result: File? = null
            var error = ""
            var response: okhttp3.Response? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            val temp = File(cache.absolutePath + ".tmp")
            try {
                val cacheDir = cache.parentFile
                if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs()
                if (temp.exists()) temp.delete()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()
                var client = OkGoHelper.getDefaultClient()
                if (client == null) client = OkHttp.client()
                response = client!!.newCall(request).execute()
                if (!response.isSuccessful) {
                    error = "HTTP " + response.code()
                } else if (response.body() == null) {
                    error = "empty body"
                } else if (isJarInImg) {
                    val respData = response.body()!!.string()
                    LOG.i("echo---jar Response: $respData")
                    val imgJar = getImgJar(respData)
                    if (imgJar.isEmpty()) {
                        error = "empty img jar"
                    } else {
                        outputStream = FileOutputStream(temp)
                        outputStream.write(imgJar)
                        outputStream.flush()
                        closeQuietly(outputStream)
                        outputStream = null
                        result = replaceCache(temp, cache)
                    }
                } else {
                    inputStream = response.body()!!.byteStream()
                    outputStream = FileOutputStream(temp)
                    val buffer = ByteArray(16384)
                    while (true) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                    closeQuietly(outputStream)
                    outputStream = null
                    result = replaceCache(temp, cache)
                }
            } catch (th: Throwable) {
                error = th.message ?: ""
            } finally {
                closeQuietly(inputStream)
                closeQuietly(outputStream)
                closeQuietly(response?.body())
                if (result == null && temp.exists()) temp.delete()
            }
            val finalResult = result
            val finalError = error
            mainHandler.post { callback.complete(finalResult, finalError) }
        }
    }

    @Throws(IOException::class)
    private fun replaceCache(temp: File, cache: File): File {
        if (cache.exists() && !cache.delete()) {
            LOG.i("echo---delete old jar cache failed:" + cache.absolutePath)
        }
        if (!temp.renameTo(cache)) {
            FileUtils.copyFile(temp, cache)
            temp.delete()
        }
        return cache
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (ignored: Throwable) {
        }
    }

    private fun loadJar(useCache: Boolean, spider: String, callback: LoadConfigCallback, retryCount: Int) {
        val urls = spider.split(";md5;".toRegex()).toTypedArray()
        var jarUrl = urls[0]
        val md5 = if (urls.size > 1) urls[1].trim() else ""
        val cache = File(KtApp.instance().filesDir.absolutePath + "/csp/" + MD5.string2MD5(jarUrl) + ".jar")

        if (md5.isNotEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.getFileMd5(cache).equals(md5, ignoreCase = true))) {
                if (cache.exists()) {
                    loadJarAsync(cache) { success ->
                        if (success) {
                            callback.success()
                        } else {
                            callback.error("md5缓存失效")
                        }
                    }
                    return
                }
                if (jarLoader.load(cache.absolutePath)) {
                    callback.success()
                } else {
                    callback.error("md5缓存失效")
                }
                return
            }
        } else {
            if (java.lang.Boolean.parseBoolean(jarCache) && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                LOG.i("echo-load jar jarCache:$jarUrl")
                if (cache.exists()) {
                    loadJarAsync(cache) { success ->
                        if (success) {
                            callback.success()
                        } else {
                            loadJar(false, spider, callback, retryCount)
                        }
                    }
                    return
                }
                if (jarLoader.load(cache.absolutePath)) {
                    callback.success()
                    return
                }
            }
        }

        val isJarInImg = jarUrl.startsWith("img+")
        jarUrl = jarUrl.replace("img+", "")
        LOG.i("echo-load jar start:$jarUrl")
        val requestUrl = jarUrl
        downloadJarAsync(requestUrl, isJarInImg, cache, object : JarDownloadCallback {
            private fun retryLoad(reason: String): Boolean {
                if (retryCount >= LOAD_JAR_MAX_RETRY) return false
                if (cache.exists() && !cache.delete()) {
                    LOG.i("echo---delete bad jar cache failed:" + cache.absolutePath)
                }
                LOG.i("echo---retry load jar reason:$reason url:$requestUrl retry:${retryCount + 1}")
                loadJar(false, spider, callback, retryCount + 1)
                return true
            }

            override fun complete(file: File?, error: String) {
                if (file != null && file.exists()) {
                    loadJarAsync(file) { success ->
                        if (success) {
                            LOG.i("echo---load-jar-success")
                            callback.success()
                        } else {
                            LOG.e("echo---jar Loader returned false")
                            if (retryLoad("loader_false")) return@loadJarAsync
                            callback.error("JAR加载失败")
                        }
                    }
                    return
                }
                if (!TextUtils.isEmpty(error)) {
                    LOG.i("echo---jar Request failed: $error")
                }
                if (cache.exists()) {
                    loadJarAsync(cache) { success ->
                        if (success) {
                            callback.success()
                        } else {
                            if (retryLoad("request_error")) return@loadJarAsync
                            callback.error("网络错误")
                        }
                    }
                    return
                }
                if (retryLoad("request_error")) return
                callback.error("网络错误")
            }
        })
    }

    fun loadJar(useCache: Boolean, spider: String?, callback: LoadConfigCallback) {
        loadJar(useCache, spider ?: "", callback, 0)
    }

    @Throws(Throwable::class)
    private fun parseJson(apiUrl: String, f: File) {
        parseJson(apiUrl, readConfigFile(f))
    }

    @Throws(Throwable::class)
    private fun readConfigFile(f: File): String {
        val bReader = BufferedReader(InputStreamReader(FileInputStream(f), "UTF-8"))
        val sb = StringBuilder()
        var s: String?
        while (bReader.readLine().also { s = it } != null) {
            sb.append(s).append("\n")
        }
        bReader.close()
        return sb.toString()
    }

    private fun switchApiCollectionIfNeeded(apiUrl: String, jsonStr: String): Boolean {
        val apiLines = parseApiCollection(jsonStr)
        if (apiLines.isEmpty()) {
            return false
        }
        val firstApi = HistoryHelper.getApiLineUrl(apiLines[0])
        if (TextUtils.isEmpty(firstApi) || firstApi == apiUrl) {
            return false
        }
        Hawk.put(HawkConfig.API_LINE_LIST, apiLines)
        Hawk.put(HawkConfig.API_LINE_SOURCE, apiUrl)
        Hawk.put(HawkConfig.API_URL, firstApi)
        HistoryHelper.setApiHistory(apiUrl)
        val liveApiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "")
        if (TextUtils.isEmpty(liveApiUrl) || liveApiUrl == apiUrl) {
            Hawk.put(HawkConfig.LIVE_API_URL, firstApi)
            HistoryHelper.setLiveApiHistory(firstApi)
        }
        return true
    }

    private fun parseApiCollection(jsonStr: String): ArrayList<String> {
        val apiLines = ArrayList<String>()
        try {
            val json = trimJsonObject(jsonStr)
            if (TextUtils.isEmpty(json)) {
                return apiLines
            }
            val infoJson = gson.fromJson(json, JsonObject::class.java)
            if (infoJson == null || infoJson.has("sites") || !infoJson.has("urls") || !infoJson.get("urls").isJsonArray) {
                return apiLines
            }
            val urls = infoJson.get("urls").asJsonArray
            for (element in urls) {
                var name = ""
                var url = ""
                if (element.isJsonObject) {
                    val item = element.asJsonObject
                    name = DefaultConfig.safeJsonString(item, "name", "")
                    url = DefaultConfig.safeJsonString(item, "url", "")
                    if (TextUtils.isEmpty(url)) {
                        url = DefaultConfig.safeJsonString(item, "api", "")
                    }
                } else if (element.isJsonPrimitive) {
                    url = element.asString
                }
                if (!TextUtils.isEmpty(url)) {
                    apiLines.add(HistoryHelper.buildApiLine(name, url))
                }
            }
        } catch (ignored: Throwable) {
        }
        return apiLines
    }

    private fun trimJsonObject(content: String?): String {
        if (content == null) {
            return ""
        }
        val trimContent = content.trim { it <= ' ' }
        val start = trimContent.indexOf("{")
        val end = trimContent.lastIndexOf("}")
        if (start >= 0 && end > start) {
            return trimContent.substring(start, end + 1)
        }
        return trimContent
    }

    private fun resetConfigData() {
        clearSpiderCache()
        currentPlaySourceKey = ""
        sourceBeanList.clear()
        liveChannelGroupList.clear()
        parseBeanList.clear()
        searchSourceBeanList = ArrayList()
        Hawk.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
    }

    private fun clearApiLinesIfUnmatched(apiUrl: String) {
        val apiLines = Hawk.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
        if (apiLines.isEmpty()) {
            return
        }
        for (apiLine in apiLines) {
            if (apiUrl == HistoryHelper.getApiLineUrl(apiLine)) {
                return
            }
        }
        HistoryHelper.clearApiLineList()
    }

    private fun parseJson(apiUrl: String, jsonStr: String) {
        resetConfigData()
        val infoJson = gson.fromJson(jsonStr, JsonObject::class.java)
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "")
        jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true")
        danmaku = DefaultConfig.safeJsonString(infoJson, "danmaku", "")
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "")
        // 远端站点源
        var firstSite: SourceBean? = null
        for (opt in infoJson.get("sites").asJsonArray) {
            val obj = opt.asJsonObject
            if (!obj.has("key") || !obj.has("type") || !obj.has("api")) {
                LOG.i("echo-skip incomplete site config: $obj")
                continue
            }
            val sb = SourceBean()
            val siteKey = obj.get("key").asString.trim()
            sb.key = siteKey
            sb.name = if (obj.has("name")) obj.get("name").asString.trim() else siteKey
            sb.type = obj.get("type").asInt
            sb.api = obj.get("api").asString.trim()
            sb.searchable = DefaultConfig.safeJsonInt(obj, "searchable", 1)
            sb.quickSearch = DefaultConfig.safeJsonInt(obj, "quickSearch", 1)
            sb.changeable = DefaultConfig.safeJsonInt(obj, "changeable", 1)
            if (siteKey.startsWith("py_")) {
                sb.filterable = 1
            } else {
                sb.filterable = DefaultConfig.safeJsonInt(obj, "filterable", 1)
            }
            sb.playerUrl = DefaultConfig.safeJsonString(obj, "playUrl", "")
            sb.ext = DefaultConfig.safeJsonString(obj, "ext", "")
            sb.jar = DefaultConfig.safeJsonString(obj, "jar", "")
            sb.playerType = DefaultConfig.safeJsonInt(obj, "playerType", -1)
            sb.categories = DefaultConfig.safeJsonStringList(obj, "categories")
            sb.timeout = DefaultConfig.safeJsonInt(obj, "timeout", 0)
            sb.clickSelector = DefaultConfig.safeJsonString(obj, "click", "")
            sb.style = DefaultConfig.safeJsonString(obj, "style", "")
            if (firstSite == null) firstSite = sb
            sourceBeanList[siteKey] = sb
        }
        if (sourceBeanList.isNotEmpty()) {
            val home = Hawk.get(HawkConfig.HOME_API, "")
            val sh = getSource(home)
            if (sh == null) {
                setSourceBean(firstSite!!)
            } else {
                setSourceBean(sh)
            }
        }
        // 需要使用 vip 解析的 flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags")
        // 解析地址
        parseBeanList.clear()
        if (infoJson.has("parses")) {
            val parses = infoJson.get("parses").asJsonArray
            for (opt in parses) {
                val obj = opt.asJsonObject
                val pb = ParseBean()
                pb.name = obj.get("name").asString.trim()
                pb.url = obj.get("url").asString.trim()
                val ext = if (obj.has("ext")) obj.get("ext").asJsonObject.toString() else ""
                pb.ext = ext
                pb.type = DefaultConfig.safeJsonInt(obj, "type", 0)
                parseBeanList.add(pb)
            }
            if (parseBeanList.isNotEmpty()) addSuperParse()
        }
        // 获取默认解析
        if (parseBeanList.isNotEmpty()) {
            val defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "")
            if (!TextUtils.isEmpty(defaultParse)) {
                for (pb in parseBeanList) {
                    if (pb.name == defaultParse) setDefaultParse(pb)
                }
            }
            if (mDefaultParse == null) setDefaultParse(parseBeanList[0])
        }

        // 直播源
        val liveApiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "")
        if (liveApiUrl.isEmpty() || apiUrl == liveApiUrl) {
            LOG.i("echo-load-config_live")
            initLiveSettings()
            if (infoJson.has("lives")) {
                val livesGroups = infoJson.get("lives").asJsonArray
                var liveGroupIndex = getLiveGroupIndex()
                if (liveGroupIndex > livesGroups.size() - 1) liveGroupIndex = 0
                Hawk.put(HawkConfig.LIVE_GROUP_LIST, livesGroups)
                // 加载多源配置
                try {
                    val liveSettingItemList = ArrayList<LiveSettingItem>()
                    for (i in 0 until livesGroups.size()) {
                        val jsonObject = livesGroups[i].asJsonObject
                        val name = if (jsonObject.has("name")) jsonObject.get("name").asString else "线路" + (i + 1)
                        val liveSettingItem = LiveSettingItem()
                        liveSettingItem.itemIndex = i
                        liveSettingItem.itemName = name
                        liveSettingItemList.add(liveSettingItem)
                    }
                    liveSettingGroupList[5].liveSettingItems = liveSettingItemList
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val livesOBJ = livesGroups[liveGroupIndex].asJsonObject
                loadLiveApi(livesOBJ)
            }
        }

        myHosts = HashMap()
        if (infoJson.has("hosts")) {
            val hostsArray = infoJson.getAsJsonArray("hosts")
            for (i in 0 until hostsArray.size()) {
                val entry = hostsArray[i].asString
                val parts = entry.split("=".toRegex(), 2).toTypedArray() // 只分割一次,防止 value 里有 =
                if (parts.size == 2) {
                    myHosts!![parts[0]] = parts[1]
                }
            }
        }

        loadProxyRules(infoJson)

        // video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule()
            for (oneHostRule in infoJson.getAsJsonArray("rules")) {
                val obj = oneHostRule.asJsonObject
                // 嗅探过滤规则
                if (obj.has("host")) {
                    val host = obj.get("host").asString
                    if (obj.has("rule")) {
                        val ruleJsonArr = obj.getAsJsonArray("rule")
                        val rule = ArrayList<String>()
                        for (one in ruleJsonArr) {
                            rule.add(one.asString)
                        }
                        if (rule.isNotEmpty()) {
                            VideoParseRuler.addHostRule(host, rule)
                        }
                    }
                    if (obj.has("filter")) {
                        val filterJsonArr = obj.getAsJsonArray("filter")
                        val filter = ArrayList<String>()
                        for (one in filterJsonArr) {
                            filter.add(one.asString)
                        }
                        if (filter.isNotEmpty()) {
                            VideoParseRuler.addHostFilter(host, filter)
                        }
                    }
                }
                // 广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    val rule = ArrayList<String>()
                    val ads = ArrayList<String>()
                    val regexArray = obj.getAsJsonArray("regex")
                    for (one in regexArray) {
                        val regex = one.asString
                        if (M3u8.isAd(regex)) ads.add(regex) else rule.add(regex)
                    }
                    val array = obj.getAsJsonArray("hosts")
                    for (one in array) {
                        val host = one.asString
                        VideoParseRuler.addHostRule(host, rule)
                        VideoParseRuler.addHostRegex(host, ads)
                    }
                }
                // 嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    val scripts = ArrayList<String>()
                    val scriptArray = obj.getAsJsonArray("script")
                    for (one in scriptArray) {
                        scripts.add(one.asString)
                    }
                    val array = obj.getAsJsonArray("hosts")
                    for (one in array) {
                        val host = one.asString
                        VideoParseRuler.addHostScript(host, scripts)
                    }
                }
            }
        }

        if (infoJson.has("doh")) {
            val dohJson = infoJson.getAsJsonArray("doh").toString()
            if (Hawk.get(HawkConfig.DOH_JSON, "") != dohJson) {
                Hawk.put(HawkConfig.DOH_URL, 0)
                Hawk.put(HawkConfig.DOH_JSON, dohJson)
            }
        } else {
            Hawk.put(HawkConfig.DOH_JSON, "")
        }
        OkGoHelper.setDnsList()
        LOG.i("echo-api-config-----------load")
        // 追加的广告拦截
        if (infoJson.has("ads")) {
            for (host in infoJson.getAsJsonArray("ads")) {
                if (!AdBlocker.hasHost(host.asString)) {
                    AdBlocker.addAdHost(host.asString)
                }
            }
        }
    }

    private fun loadDefaultConfig() {
        val defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}"
        val defaultJson = gson.fromJson(defaultIJKADS, JsonObject::class.java)
        // 广告地址
        if (AdBlocker.isEmpty()) {
            // 默认广告拦截
            for (host in defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.asString)
            }
        }
        // IJK 解码配置
        if (ijkCodes == null) {
            val codes = ArrayList<IJKCode>()
            var foundOldSelect = false
            var ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "硬解码")
            val ijkJsonArray = defaultJson.get("ijk").asJsonArray
            for (opt in ijkJsonArray) {
                val obj = opt.asJsonObject
                val name = obj.get("group").asString
                val baseOpt = LinkedHashMap<String, String>()
                for (cfg in obj.get("options").asJsonArray) {
                    val cObj = cfg.asJsonObject
                    val key = cObj.get("category").asString + "|" + cObj.get("name").asString
                    val `val` = cObj.get("value").asString
                    baseOpt[key] = `val`
                }
                val codec = IJKCode()
                codec.name = name
                codec.option = baseOpt
                if (name == ijkCodec || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true)
                    ijkCodec = name
                    foundOldSelect = true
                } else {
                    codec.selected(false)
                }
                codes.add(codec)
            }
            if (!foundOldSelect && codes.isNotEmpty()) {
                codes[0].selected(true)
            }
            ijkCodes = codes
        }
        LOG.i("echo-default-config-----------load")
    }

    @Throws(Throwable::class)
    private fun parseLiveConfigContent(apiUrl: String, f: File) {
        val bReader = BufferedReader(InputStreamReader(FileInputStream(f), "UTF-8"))
        val sb = StringBuilder()
        var s: String?
        while (bReader.readLine().also { s = it } != null) {
            sb.append(s).append("\n")
        }
        bReader.close()
        parseLiveConfigContent(apiUrl, sb.toString())
    }

    private fun parseLiveConfigContent(apiUrl: String, content: String) {
        val jsonContent = trimJsonObject(content)
        if (!TextUtils.isEmpty(jsonContent)) {
            try {
                val infoJson = gson.fromJson(jsonContent, JsonObject::class.java)
                if (infoJson != null && infoJson.has("lives")) {
                    parseLiveJson(apiUrl, jsonContent)
                    return
                }
            } catch (ignored: Throwable) {
            }
        }
        if (isLiveJsonContent(content)) {
            parseLiveJson(apiUrl, jsonContent)
        } else {
            parseLiveText(apiUrl, content)
        }
    }

    private fun isLiveJsonContent(content: String?): Boolean {
        if (content == null) return false
        var text = content.trim { it <= ' ' }
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim()
        return text.startsWith("{")
    }

    private fun parseLiveText(apiUrl: String, content: String) {
        liveChannelGroupList.clear()
        liveSpider = ""
        currentLiveSpider = ""
        currentLivePyKey = ""
        initLiveSettings()
        Hawk.put(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        Hawk.put(HawkConfig.EPG_URL, extractLiveTextEpg(content))
        Hawk.put(HawkConfig.LIVE_PLAY_TYPE, Hawk.get(HawkConfig.PLAY_TYPE, 2))
        Hawk.put(HawkConfig.LIVE_WEB_HEADER, null)
        val livesArray = LiveParseHelper.parseToJsonArray(content)
        loadLives(livesArray)
        LOG.i("echo-live-text-config-----------load:$apiUrl")
    }

    private fun extractLiveTextEpg(content: String?): String {
        if (content == null) return ""
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split("\n".toRegex()).toTypedArray()
        for (line0 in lines) {
            var line = line0.trim()
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim()
            if (!line.startsWith("#EXTM3U")) continue
            var epg = extractQuotedAttr(line, "x-tvg-url")
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "tvg-url")
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "url-tvg")
            return epg
        }
        return ""
    }

    private fun extractQuotedAttr(line: String, key: String): String {
        val token = "$key=\""
        val start = line.indexOf(token)
        if (start < 0) return ""
        val start2 = start + token.length
        val end = line.indexOf("\"", start2)
        if (end < 0) return ""
        return line.substring(start2, end).trim()
    }

    private fun parseLiveJson(apiUrl: String, jsonStr: String) {
        liveChannelGroupList.clear()
        val infoJson = gson.fromJson(jsonStr, JsonObject::class.java)
        // spider
        liveSpider = DefaultConfig.safeJsonString(infoJson, "spider", "")
        // 直播源
        initLiveSettings()
        if (infoJson.has("lives")) {
            val livesGroups = infoJson.get("lives").asJsonArray
            var liveGroupIndex = getLiveGroupIndex()
            if (liveGroupIndex > livesGroups.size() - 1) liveGroupIndex = 0
            Hawk.put(HawkConfig.LIVE_GROUP_LIST, livesGroups)
            try {
                val liveSettingItemList = ArrayList<LiveSettingItem>()
                for (i in 0 until livesGroups.size()) {
                    val jsonObject = livesGroups[i].asJsonObject
                    val name = if (jsonObject.has("name")) jsonObject.get("name").asString else "线路" + (i + 1)
                    val liveSettingItem = LiveSettingItem()
                    liveSettingItem.itemIndex = i
                    liveSettingItem.itemName = name
                    liveSettingItemList.add(liveSettingItem)
                }
                liveSettingGroupList[5].liveSettingItems = liveSettingItemList
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val livesOBJ = livesGroups[liveGroupIndex].asJsonObject
            loadLiveApi(livesOBJ)
        }

        myHosts = HashMap()
        if (infoJson.has("hosts")) {
            val hostsArray = infoJson.getAsJsonArray("hosts")
            for (i in 0 until hostsArray.size()) {
                val entry = hostsArray[i].asString
                val parts = entry.split("=".toRegex(), 2).toTypedArray()
                if (parts.size == 2) {
                    myHosts!![parts[0]] = parts[1]
                }
            }
        }
        LOG.i("echo-api-live-config-----------load")
    }

    private fun initLiveSettings() {
        val groupNames = ArrayList(Arrays.asList("线路选择", "画面比例", "播放解码", "超时换源", "偏好设置", "多源切换", "配置切换"))
        val itemsArrayList = ArrayList<ArrayList<String>>()
        val sourceItems = ArrayList<String>()
        val scaleItems = ArrayList(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪"))
        val playerDecoderItems = ArrayList(Arrays.asList("ijk硬解", "ijk软解", "exo"))
        val timeoutItems = ArrayList(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s"))
        val personalSettingItems = ArrayList(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类"))
        val yumItems = ArrayList<String>()
        val liveApiHistoryItems = ArrayList<String>()

        itemsArrayList.add(sourceItems)
        itemsArrayList.add(scaleItems)
        itemsArrayList.add(playerDecoderItems)
        itemsArrayList.add(timeoutItems)
        itemsArrayList.add(personalSettingItems)
        itemsArrayList.add(yumItems)
        itemsArrayList.add(liveApiHistoryItems)

        liveSettingGroupList.clear()
        for (i in groupNames.indices) {
            val liveSettingGroup = LiveSettingGroup()
            val liveSettingItemList = ArrayList<LiveSettingItem>()
            liveSettingGroup.groupIndex = i
            liveSettingGroup.groupName = groupNames[i]
            for (j in itemsArrayList[i].indices) {
                val liveSettingItem = LiveSettingItem()
                liveSettingItem.itemIndex = j
                liveSettingItem.itemName = itemsArrayList[i][j]
                liveSettingItemList.add(liveSettingItem)
            }
            liveSettingGroup.liveSettingItems = liveSettingItemList
            liveSettingGroupList.add(liveSettingGroup)
        }
        refreshLiveApiHistoryItems()
    }

    fun getLiveSettingGroupList(): List<LiveSettingGroup> {
        return liveSettingGroupList
    }

    fun refreshLiveApiHistoryItems() {
        if (liveSettingGroupList.size < 7) return
        val liveSettingItemList = ArrayList<LiveSettingItem>()
        val history = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        for (i in history.indices) {
            val liveSettingItem = LiveSettingItem()
            liveSettingItem.itemIndex = i
            liveSettingItem.itemName = history[i]
            liveSettingItemList.add(liveSettingItem)
        }
        liveSettingGroupList[6].liveSettingItems = liveSettingItemList
    }

    fun loadLives(livesArray: JsonArray) {
        liveChannelGroupList.clear()
        var groupIndex = 0
        var channelIndex = 0
        var channelNum = 0
        for (groupElement in livesArray) {
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.liveChannelItems = ArrayList()
            liveChannelGroup.groupIndex = groupIndex++
            val groupName = groupElement.asJsonObject.get("group").asString.trim()
            val splitGroupName = groupName.split("_".toRegex(), 2).toTypedArray()
            liveChannelGroup.groupName = splitGroupName[0]
            if (splitGroupName.size > 1) liveChannelGroup.groupPassword = splitGroupName[1]
            else liveChannelGroup.groupPassword = ""
            channelIndex = 0
            for (channelElement in groupElement.asJsonObject.get("channels").asJsonArray) {
                val obj = channelElement.asJsonObject
                val liveChannelItem = LiveChannelItem()
                liveChannelItem.setChannelName(DefaultConfig.safeJsonString(obj, "name", "").trim())
                liveChannelItem.setChannelLogo(DefaultConfig.safeJsonString(obj, "logo", ""))
                liveChannelItem.setChannelEpg(DefaultConfig.safeJsonString(obj, "epg", ""))
                liveChannelItem.setChannelUa(DefaultConfig.safeJsonString(obj, "ua", ""))
                liveChannelItem.setChannelClick(DefaultConfig.safeJsonString(obj, "click", ""))
                liveChannelItem.setChannelFormat(DefaultConfig.safeJsonString(obj, "format", ""))
                liveChannelItem.setChannelOrigin(DefaultConfig.safeJsonString(obj, "origin", ""))
                liveChannelItem.setChannelReferer(DefaultConfig.safeJsonString(obj, "referer", ""))
                liveChannelItem.setChannelTvgId(DefaultConfig.safeJsonString(obj, "tvg-id", ""))
                liveChannelItem.setChannelTvgName(DefaultConfig.safeJsonString(obj, "tvg-name", ""))
                if (obj.has("parse")) {
                    try {
                        liveChannelItem.setChannelParse(obj.get("parse").asInt)
                    } catch (ignored: Throwable) {
                    }
                }
                if (obj.has("catchup")) {
                    val catchupObj = JsonObject()
                    if (obj.get("catchup").isJsonObject) {
                        obj.getAsJsonObject("catchup").let { c ->
                            for ((k, v) in c.entrySet()) catchupObj.add(k, v)
                        }
                    } else {
                        catchupObj.addProperty("type", obj.get("catchup").asString)
                        if (obj.has("catchup-source")) catchupObj.addProperty("source", obj.get("catchup-source").asString)
                        if (obj.has("catchup-replace")) catchupObj.addProperty("replace", obj.get("catchup-replace").asString)
                    }
                    liveChannelItem.setChannelCatchup(catchupObj)
                }
                if (obj.has("header") && obj.get("header").isJsonObject) {
                    val headerObj = obj.getAsJsonObject("header")
                    val channelHeader = HashMap<String, String>()
                    for (entry in headerObj.entrySet()) {
                        channelHeader[entry.key] = entry.value.asString
                    }
                    liveChannelItem.setChannelHeader(channelHeader)
                }
                val urls = DefaultConfig.safeJsonStringList(obj, "urls")
                val sourceNames = ArrayList<String>()
                val sourceUrls = ArrayList<String>()
                var sourceIndex = 1
                for (url in urls) {
                    val splitText = url.split("\\$".toRegex(), 2).toTypedArray()
                    sourceUrls.add(splitText[0])
                    if (splitText.size > 1) sourceNames.add(splitText[1])
                    else sourceNames.add("源$sourceIndex")
                    sourceIndex++
                }
                liveChannelItem.setChannelSourceNames(sourceNames)
                liveChannelItem.setChannelUrls(sourceUrls)
                if (mergeLiveChannel(liveChannelGroup.liveChannelItems!!, liveChannelItem)) {
                    liveChannelItem.channelIndex = channelIndex++
                    liveChannelItem.channelNum = ++channelNum
                }
            }
            liveChannelGroupList.add(liveChannelGroup)
        }
    }

    private fun mergeLiveChannel(channelItems: ArrayList<LiveChannelItem>, newItem: LiveChannelItem): Boolean {
        val oldItem = findLiveChannel(channelItems, newItem.getChannelName())
        if (oldItem == null) {
            channelItems.add(newItem)
            return true
        }
        mergeLiveChannelUrls(oldItem, newItem)
        return false
    }

    private fun findLiveChannel(channelItems: ArrayList<LiveChannelItem>, channelName: String): LiveChannelItem? {
        for (item in channelItems) {
            if (channelName == item.getChannelName()) return item
        }
        return null
    }

    private fun mergeLiveChannelUrls(oldItem: LiveChannelItem, newItem: LiveChannelItem) {
        var oldUrls = oldItem.getChannelUrls()
        var oldSourceNames = oldItem.getChannelSourceNames()
        if (oldUrls == null) {
            oldUrls = ArrayList()
            oldItem.setChannelUrls(oldUrls)
        }
        if (oldSourceNames == null) {
            oldSourceNames = ArrayList()
            oldItem.setChannelSourceNames(oldSourceNames)
        }
        while (oldSourceNames.size < oldUrls.size) {
            oldSourceNames.add("源" + (oldSourceNames.size + 1))
        }
        val newUrls = newItem.getChannelUrls() ?: return
        val newSourceNames = newItem.getChannelSourceNames()
        for (i in newUrls.indices) {
            val url = newUrls[i]
            if (oldUrls.contains(url)) continue
            oldUrls.add(url)
            if (newSourceNames != null && i < newSourceNames.size) {
                oldSourceNames.add(newSourceNames[i])
            } else {
                oldSourceNames.add("源" + (oldSourceNames.size + 1))
            }
        }
        oldItem.setChannelUrls(oldUrls)
        oldItem.setChannelSourceNames(oldSourceNames)
    }

    fun loadLiveApi(livesOBJ: JsonObject) {
        try {
            LOG.i("echo-loadLiveApi")
            liveChannelGroupList.clear()
            currentLiveSpider = ""
            currentLivePyKey = ""
            val lives = livesOBJ.toString()
            val index = lives.indexOf("proxy://")
            val url: String
            if (index != -1) {
                val endIndex = lives.lastIndexOf("\"")
                var u = lives.substring(index, endIndex)
                u = DefaultConfig.checkReplaceProxy(u)
                val extUrl = Uri.parse(u).getQueryParameter("ext")
                if (extUrl != null && !extUrl.isEmpty()) {
                    val extUrlFix0: String = if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                        extUrl
                    } else {
                        String(Base64.decode(extUrl, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                    }
                    val extUrlFix = Base64.encodeToString(
                        extUrlFix0.toByteArray(Charsets.UTF_8),
                        Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
                    )
                    u = u.replace(extUrl, extUrlFix)
                }
                url = u
            } else {
                val api = if (livesOBJ.has("api")) livesOBJ.get("api").asString.trim() else ""
                val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else (if (isLiveSpiderApi(api)) "3" else "0")
                if (type == "0" || type == "3") {
                    var u = if (livesOBJ.has("url")) livesOBJ.get("url").asString else ""
                    if (u.isEmpty()) u = api
                    LOG.i("echo-liveurl$u")
                    if (!u.startsWith("http://127.0.0.1")) {
                        if (u.startsWith("http")) {
                            u = Base64.encodeToString(u.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
                        }
                        u = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=$u"
                    }
                    if (type == "3") {
                        val jarUrl = if (livesOBJ.has("jar")) livesOBJ.get("jar").asString.trim() else ""
                        LOG.i("echo-liveApi1$api")
                        if (api.contains(".py")) {
                            LOG.i("echo-pyLoader.getSpider")
                            var ext = ""
                            if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)) {
                                ext = livesOBJ.get("ext").toString()
                            } else {
                                ext = DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                            }
                            currentLivePyKey = MD5.string2MD5(api) ?: ""
                            currentLiveSpider = api
                            pyLoader.getSpider(currentLivePyKey, api, ext)
                        } else if (api.contains(".js")) {
                            LOG.i("echo-jsLoader.getSpider")
                            var ext = ""
                            if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)) {
                                ext = livesOBJ.get("ext").toString()
                            } else {
                                ext = DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                            }
                            currentLiveSpider = api
                            jsLoader.getSpider(MD5.string2MD5(api) ?: "", api, ext, jarUrl)
                        }
                        if (jarUrl.isNotEmpty() && !isLiveSpiderApi(api)) {
                            jarLoader.loadLiveJar(jarUrl)
                            if (TextUtils.isEmpty(currentLiveSpider)) {
                                currentLiveSpider = jarUrl
                            }
                        } else if (liveSpider.isNotEmpty() && !isLiveSpiderApi(api)) {
                            jarLoader.loadLiveJar(liveSpider)
                            if (TextUtils.isEmpty(currentLiveSpider)) {
                                currentLiveSpider = liveSpider
                            }
                        }
                    }
                    url = u
                } else {
                    liveChannelGroupList.clear()
                    return
                }
            }
            // 设置 epg
            if (livesOBJ.has("epg")) {
                val epg = livesOBJ.get("epg").asString
                Hawk.put(HawkConfig.EPG_URL, epg)
            } else {
                Hawk.put(HawkConfig.EPG_URL, "")
            }
            // 直播播放器类型
            if (livesOBJ.has("playerType")) {
                val livePlayType = livesOBJ.get("playerType").asString
                Hawk.put(HawkConfig.LIVE_PLAY_TYPE, livePlayType)
            } else {
                Hawk.put(HawkConfig.LIVE_PLAY_TYPE, Hawk.get(HawkConfig.PLAY_TYPE, 2))
            }
            // 设置超时
            if (livesOBJ.has("timeout")) {
                val timeout = livesOBJ.get("timeout").asInt.coerceIn(5, 30)
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, (timeout + 4) / 5 - 1)
            }
            if (livesOBJ.has("header")) {
                val headerObj = livesOBJ.getAsJsonObject("header")
                val liveHeader = HashMap<String, String>()
                for (entry in headerObj.entrySet()) {
                    liveHeader[entry.key] = entry.value.asString
                }
                Hawk.put(HawkConfig.LIVE_WEB_HEADER, liveHeader)
            } else if (livesOBJ.has("ua")) {
                val ua = livesOBJ.get("ua").asString
                val liveHeader = HashMap<String, String>()
                liveHeader["User-Agent"] = ua
                Hawk.put(HawkConfig.LIVE_WEB_HEADER, liveHeader)
            } else {
                Hawk.put(HawkConfig.LIVE_WEB_HEADER, null)
            }
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.groupName = url
            liveChannelGroupList.clear()
            liveChannelGroupList.add(liveChannelGroup)
        } catch (th: Throwable) {
            // P3-6:直播源解析失败不能静默,记录 echo- 日志便于归因(用户提示由 LivePlayActivity 的 Toast 兜底)
            LOG.e("echo-loadLiveApi 直播源加载异常: " + th.message)
            th.printStackTrace()
        }
    }

    fun setLiveJar(liveJar: String) {
        if (liveJar.contains(".py")) {
            currentLivePyKey = MD5.string2MD5(liveJar) ?: ""
            pyLoader.getSpider(currentLivePyKey, liveJar, "")
            pyLoader.setRecentPyKey(currentLivePyKey)
        } else if (liveJar.contains(".js")) {
            jsLoader.getSpider(MD5.string2MD5(liveJar) ?: "", liveJar, "", "")
        } else {
            val jarUrl = if (liveJar.isNotEmpty()) liveJar else liveSpider
            jarLoader.setRecentJarKey(MD5.string2MD5(jarUrl))
        }
        currentLiveSpider = liveJar
    }

    fun getSpider(): String? {
        return spider
    }

    fun getDanmaku(): String {
        return danmaku ?: ""
    }

    fun getCSP(sourceBean: SourceBean): Spider {
        return if (sourceBean.api.endsWith(".js") || sourceBean.api.contains(".js?")) {
            currentPyKey = ""
            jsLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext, sourceBean.jar)
        } else if (sourceBean.api.contains(".py")) {
            currentPyKey = sourceBean.key
            pyLoader.setRecentPyKey(currentPyKey)
            pyLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext)
        } else {
            currentPyKey = ""
            jarLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext, sourceBean.jar)
        }
    }

    fun getPyCSP(url: String): Spider {
        currentLivePyKey = MD5.string2MD5(url) ?: ""
        currentLiveSpider = url
        return pyLoader.getSpider(currentLivePyKey, url, "")
    }

    fun getJsCSP(url: String): Spider {
        currentLiveSpider = url
        return jsLoader.getSpider(MD5.string2MD5(url) ?: "", url, "", "")
    }

    fun getLiveCSP(url: String): Spider {
        return if (url.contains(".js")) getJsCSP(url) else getPyCSP(url)
    }

    fun searchDanmuUi(name: String?, episode: String?, longClick: Boolean) {
        danmuSearchExecutor.execute {
            try {
                jarLoader.searchDanmuUi(name, episode, longClick)
            } catch (th: Throwable) {
                LOG.e("ApiConfig searchDanmuUi error: " + th.message)
                th.printStackTrace()
            }
        }
    }

    fun hasDanmuSearchUi(): Boolean {
        return jarLoader.hasDanmuSearchUi()
    }

    fun getLiveConnectTimeoutSeconds(): Int {
        return (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5
    }

    private fun isLiveSpiderApi(api: String): Boolean {
        return api.contains(".py") || api.contains(".js")
    }

    fun proxyLocal(param: MutableMap<String, String>): Array<Any?>? {
        val source = getCurrentProxySource(param)
        val api = source.api

        val siteKey = param["siteKey"]
        val action = param["do"]

        val isJs = "js" == action
        val isPy = "py" == action
        val isLive = Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)
        val isApiJs = api.contains(".js")
        val isApiPy = api.contains(".py")

        val canUseType3 = !TextUtils.isEmpty(siteKey) &&
            source.type == 3 &&
            !isJs && !isPy && !isLive && !isApiJs && !isApiPy

        if (canUseType3) {
            try {
                val spider = getCSP(source)
                var result = spider.proxy(param)
                if (result != null) return result
                result = jarLoader.proxyInvoke(param)
                if (result != null) return result
                result = proxyDirect(param)
                if (result != null) return result
                return null
            } catch (th: Throwable) {
                LOG.e("echo-proxy siteKey error: " + th.message)
                return null
            }
        }

        if (isJs) {
            return jsLoader.proxyInvoke(param)
        }

        if (isLive) {
            val liveApi = currentLiveSpider ?: ""
            if (liveApi.contains(".py")) {
                return pyLoader.proxyInvoke(param, currentLivePyKey)
            }
            if (liveApi.contains(".js")) {
                return jsLoader.proxyInvoke(param)
            }
            return jarLoader.proxyInvoke(param)
        }

        if (isPy) {
            return pyLoader.proxyInvoke(param, getCurrentPyKey())
        }

        if (isApiPy) {
            return pyLoader.proxyInvoke(param, getCurrentPyKey())
        }

        return jarLoader.proxyInvoke(param)
    }

    private fun proxyDirect(param: MutableMap<String, String>): Array<Any?>? {
        try {
            var url = param["url"]
            if (TextUtils.isEmpty(url)) return null
            url = URLDecoder.decode(url, "UTF-8")
            if (!url!!.startsWith("http://") && !url.startsWith("https://")) return null
            if (!DefaultConfig.isVideoFormat(url)) return null
            if (url.contains(".m3u8")) {
                param["url"] = url
                param["go"] = "live"
                param["type"] = "m3u8"
                return ProxyUtil.itv(param)
            }
            return null
        } catch (th: Throwable) {
            LOG.e("echo-proxy direct fallback error: " + th.message)
            return null
        }
    }

    private fun getCurrentProxySource(param: MutableMap<String, String>): SourceBean {
        var siteKey = param["siteKey"]
        if (TextUtils.isEmpty(siteKey)) {
            siteKey = currentPlaySourceKey
            if (!TextUtils.isEmpty(siteKey)) param["siteKey"] = siteKey
        }
        val sourceBean = if (TextUtils.isEmpty(siteKey)) null else getSource(siteKey)
        return sourceBean ?: get().homeSourceBean
    }

    fun setCurrentPlaySourceKey(sourceKey: String?) {
        currentPlaySourceKey = sourceKey ?: ""
    }

    private fun getCurrentPyKey(): String? {
        val sourceBean = getCurrentProxySource(HashMap())
        if (sourceBean.api.contains(".py")) {
            if (sourceBean.key != currentPyKey) {
                currentPyKey = sourceBean.key
                pyLoader.getSpider(currentPyKey, sourceBean.api, sourceBean.ext)
                pyLoader.setRecentPyKey(currentPyKey)
            }
            return currentPyKey
        }
        return currentPyKey
    }

    fun jsonExt(key: String?, jxs: LinkedHashMap<String, String>?, url: String?): JSONObject? {
        return jarLoader.jsonExt(key, jxs, url)
    }

    fun jsonExtMix(flag: String?, key: String?, name: String?, jxs: LinkedHashMap<String, HashMap<String, String>>?, url: String?): JSONObject? {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url)
    }

    interface LoadConfigCallback {
        fun success()
        fun error(msg: String)
        fun notice(msg: String)
    }

    interface FastParseCallback {
        fun success(parse: Boolean, url: String?, header: Map<String, String>?)
        fun fail(code: Int, msg: String?)
    }

    fun getSource(key: String?): SourceBean? {
        if (!sourceBeanList.containsKey(key)) {
            if ("push_agent" == key) {
                val sourceBean = SourceBean()
                sourceBean.key = "push_agent"
                sourceBean.name = "推送"
                sourceBean.type = -1
                return sourceBean
            }
            return null
        }
        return sourceBeanList[key]
    }

    fun setSourceBean(sourceBean: SourceBean) {
        mHomeSource = sourceBean
        Hawk.put(HawkConfig.HOME_API, sourceBean.key)
    }

    fun setDefaultParse(parseBean: ParseBean) {
        mDefaultParse?.setDefault(false)
        mDefaultParse = parseBean
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.name)
        parseBean.setDefault(true)
    }

    fun getDefaultParse(): ParseBean? {
        return mDefaultParse
    }

    fun getSourceBeanList(): List<SourceBean> {
        return ArrayList(sourceBeanList.values)
    }

    fun getSwitchSourceBeanList(): List<SourceBean> {
        val filteredList = ArrayList<SourceBean>()
        for (bean in sourceBeanList.values) {
            filteredList.add(bean)
        }
        return filteredList
    }

    fun getSearchSourceBeanList(): List<SourceBean> {
        if (searchSourceBeanList.isEmpty()) {
            LOG.i("echo-第一次getSearchSourceBeanList")
            val list = ArrayList<SourceBean>()
            for (bean in sourceBeanList.values) {
                if (bean.isSearchable) {
                    list.add(bean)
                }
            }
            searchSourceBeanList = list
        }
        return searchSourceBeanList
    }

    fun getParseBeanList(): List<ParseBean> {
        return parseBeanList
    }

    fun getVipParseFlags(): List<String> {
        return vipParseFlags
    }

    val homeSourceBean: SourceBean
        get() = mHomeSource ?: emptyHome

    fun getChannelGroupList(): List<LiveChannelGroup> {
        return liveChannelGroupList
    }

    fun getIjkCodes(): List<IJKCode>? {
        return ijkCodes
    }

    fun getCurrentIJKCode(): IJKCode {
        val codeName = Hawk.get(HawkConfig.IJK_CODEC, "硬解码")
        return getIJKCodec(codeName)
    }

    fun getIJKCodec(name: String?): IJKCode {
        for (code in ijkCodes!!) {
            if (code.name == name) return code
        }
        return ijkCodes!![0]
    }

    private fun clanToAddress(lanLink: String): String {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/")
        } else {
            val link = lanLink.substring(7)
            val end = link.indexOf('/')
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1)
        }
    }

    private fun clanContentFix(lanLink: String, content: String): String {
        val fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6)
        return content.replace("clan://localhost/", fix).replace("file://", fix)
    }

    private fun fixContentPath(url0: String, content: String): String {
        var url = url0
        if (content.contains("\"./") || content.contains("\"../")) {
            url = url.replace("file://", "clan://localhost/")
            if (!url.startsWith("http") && !url.startsWith("clan://")) {
                url = "http://$url"
            }
            if (url.startsWith("clan://")) url = clanToAddress(url)
            return content
                .replace("../", UriUtil.resolve(url, "../"))
                .replace("./", UriUtil.resolve(url, "./"))
        }
        return content
    }

    fun getMyHost(): Map<String, String>? {
        return myHosts
    }

    private fun loadProxyRules(infoJson: JsonObject) {
        if (!infoJson.has("proxy")) {
            OkGoHelper.setProxyList(null)
            return
        }
        try {
            OkGoHelper.setProxyList(ProxyRule.arrayFrom(infoJson.get("proxy")))
        } catch (th: Throwable) {
            th.printStackTrace()
            OkGoHelper.setProxyList(null)
        }
    }

    fun clearJarLoader() {
        jarLoader.clear()
    }

    private fun addSuperParse() {
        val superPb = ParseBean()
        superPb.name = "超级解析"
        superPb.url = "SuperParse"
        superPb.ext = ""
        superPb.type = 4
        parseBeanList.add(0, superPb)
    }

    fun clearLoader() {
        jarLoader.clear()
        pyLoader.clear()
        jsLoader.clear()
    }

    fun clearSpiderCache() {
        currentPyKey = ""
        currentLivePyKey = ""
        currentLiveSpider = ""
        clearLoader()
    }
}
