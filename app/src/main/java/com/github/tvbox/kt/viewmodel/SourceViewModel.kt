package com.github.tvbox.kt.viewmodel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.bean.AbsJson
import com.github.tvbox.kt.bean.AbsSortJson
import com.github.tvbox.kt.bean.AbsSortXml
import com.github.tvbox.kt.bean.AbsXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.cache.VodRecord
import com.github.tvbox.kt.core.net.OkHttp
import com.github.catvod.crawler.Spider
import com.github.tvbox.kt.data.AppDataManager
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.player.thirdparty.RemoteTVBox
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.MD5
import com.github.tvbox.kt.util.thunder.Thunder
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.lzy.okgo.request.GetRequest
import com.orhanobut.hawk.Hawk
import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.xml.DomDriver
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.Iterator
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据源 ViewModel(首页/分类/详情/搜索/播放),Kotlin 移植(与旧版 SourceViewModel 一致)。
 */
class SourceViewModel : ViewModel() {

    var sortResult: MutableLiveData<AbsSortXml> = MutableLiveData()
    var listResult: MutableLiveData<AbsXml> = MutableLiveData()
    var searchResult: MutableLiveData<AbsXml> = MutableLiveData()
    var quickSearchResult: MutableLiveData<AbsXml> = MutableLiveData()
    var detailFallbackSearchResult: MutableLiveData<AbsXml> = MutableLiveData()
    var detailResult: MutableLiveData<AbsXml> = MutableLiveData()
    var actionResult: MutableLiveData<JSONObject> = MutableLiveData()
    var playResult: MutableLiveData<JSONObject> = MutableLiveData()
    var gson: Gson = Gson()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playRequestSeq = AtomicInteger()

    fun interface HomeRecCallback {
        fun done(videos: List<Movie.Video>?)
    }

    private class PushUrl {
        var url: String = ""
        var headers: HashMap<String, String> = HashMap()
    }

    private class EpisodeListResult

    companion object {
        private const val PUSH_AGENT = "push_agent"
        private const val PUSH_FALLBACK = "push_fallback"
        private const val PUSH_HEADERS_MARKER = "@Headers="

        @JvmField
        val spThreadPool = Executors.newSingleThreadExecutor()
        private val httpPrepareThreadPool = Executors.newFixedThreadPool(3)

        // homeContent 缓存,最多存储 5 个 sourceKey 的 AbsSortXml 对象
        // accessOrder LRU 会被主线程与网络线程并发读写,包一层同步 Map
        private val sortCache: MutableMap<String, AbsSortXml?> =
            Collections.synchronizedMap(object : LinkedHashMap<String, AbsSortXml?>(5, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AbsSortXml?>): Boolean {
                    return size > 5
                }
            })

        private val extendCache = ConcurrentHashMap<String, String>()

        private fun cacheSort(sourceKey: String, sortXml: AbsSortXml?) {
            attachSortSource(sourceKey, sortXml)
            val sourceBean = ApiConfig.get().getSource(sourceKey)
            if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && !hasHomeRecVideos(sortXml)) {
                return
            }
            if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
                sortCache[sourceKey] = sortXml
            }
        }

        @JvmStatic
        fun clearSortCache(sourceKey: String) {
            sortCache.remove(sourceKey)
        }

        @JvmStatic
        fun clearRuntimeCache() {
            sortCache.clear()
            extendCache.clear()
        }

        private fun attachSortSource(sourceKey: String, sortXml: AbsSortXml?): AbsSortXml? {
            sortXml?.sourceKey = sourceKey
            return sortXml
        }

        private fun hasActionSort(sortXml: AbsSortXml?): Boolean {
            if (sortXml == null) return false
            if (hasActionVideo(sortXml.videoList)) return true
            return sortXml.list != null && hasActionVideo(sortXml.list?.videoList)
        }

        private fun hasHomeRecVideos(sortXml: AbsSortXml?): Boolean {
            return sortXml != null && sortXml.videoList != null && sortXml.videoList!!.isNotEmpty()
        }

        private fun hasActionVideo(videos: List<Movie.Video>?): Boolean {
            if (videos == null) return false
            for (video in videos) {
                if (video != null && video.action != null) return true
            }
            return false
        }

        private fun shouldBypassSortCache(sourceKey: String, sourceBean: SourceBean?): Boolean {
            return isFirstSource(sourceKey) && isDoubanSource(sourceBean)
        }

        private fun isFirstSource(sourceKey: String): Boolean {
            val sources = ApiConfig.get().getSourceBeanList()
            return sources.isNotEmpty() && sources[0] != null && sourceKey == sources[0].key
        }

        private fun isDoubanSource(sourceBean: SourceBean?): Boolean {
            if (sourceBean == null) return false
            return containsDouban(sourceBean.key) ||
                containsDouban(sourceBean.name) ||
                containsDouban(sourceBean.api) ||
                containsDouban(sourceBean.ext)
        }

        private fun containsDouban(value: String?): Boolean {
            if (TextUtils.isEmpty(value)) return false
            val lower = value!!.lowercase()
            return lower.contains("douban") || value.contains("豆瓣")
        }

        private fun syncDoubanHistory(sourceBean: SourceBean?, categoryId: String, page: Int) {
            if (page != 1 || "guess_you_like" != categoryId || !isDoubanSource(sourceBean)) return
            try {
                val app = KtApp.instance()
                val preferences =
                    app.getSharedPreferences(app.packageName + "_preferences", Context.MODE_PRIVATE)
                val previous = preferences.getString("historyvodname", "") ?: ""
                val names = ArrayList<String>()
                // JAR 按每页一个历史片名生成推荐,第一页同步后保持分页顺序
                for (record in AppDataManager.get().getVodRecordDao().getAll(Int.MAX_VALUE)) {
                    if (names.size >= 10) break
                    if (TextUtils.isEmpty(record.dataJson)) continue
                    try {
                        val name = JSONObject(record.dataJson).optString("name", "").trim()
                        if (!TextUtils.isEmpty(name) && !names.contains(name)) names.add(name)
                    } catch (ignored: JSONException) {
                    }
                }
                // 保留 JAR 自己记录的片名,本机没有播放记录时也可继续使用
                if (!TextUtils.isEmpty(previous)) {
                    try {
                        val history = JSONArray(previous)
                        for (i in 0 until history.length()) {
                            if (names.size >= 10) break
                            val name = history.optString(i, "").trim()
                            if (!TextUtils.isEmpty(name) && !names.contains(name)) names.add(name)
                        }
                    } catch (ignored: JSONException) {
                    }
                }
                if (names.isNotEmpty()) {
                    val history = JSONArray(names).toString()
                    if (history != previous) preferences.edit().putString("historyvodname", history).apply()
                }
                LOG.i("echo-douban-history--count=" + names.size)
            } catch (e: Exception) {
                LOG.e("echo-douban-history-error--" + e.message)
            }
        }
    }

    private fun postSortResult(sourceKey: String, sortXml0: AbsSortXml?) {
        var sortXml = sortXml0
        if (sortXml == null) {
            sortXml = AbsSortXml()
        }
        sortResult.postValue(attachSortSource(sourceKey, sortXml))
    }

    // ---------------- homeContent ----------------
    fun getSort(sourceKey0: String?) {
        val sourceKey = sourceKey0
        if (sourceKey == null) {
            sortResult.postValue(AbsSortXml())
            return
        }

        // 优先检查缓存
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--$sourceKey")
            postSortResult(sourceKey, null)
            return
        }
        if (sourceBean.name.length <= 3 && sourceBean.name.endsWith("搜")) {
            postSortResult(sourceKey, null)
            return
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            val cached = sortCache[sourceKey]
            if (cached != null) {
                val homeRec = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)
                val shouldUseCache = homeRec != 1 || (cached.videoList != null && cached.videoList!!.isNotEmpty())
                if (shouldUseCache) {
                    attachSortSource(sourceKey, cached)
                    postSortResult(sourceKey, cached)
                    return
                }
            }
        }

        val type = sourceBean.type
        if (type == 3) {
            val waitResponse = Runnable {
                val executor = Executors.newSingleThreadExecutor()
                val future: Future<String> = executor.submit(Callable {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    sp.homeContent(true)
                })
                var sortJson: String? = null
                try {
                    sortJson = future.get(30, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    LOG.i("echo--getSort-timeout--" + sourceBean.key)
                    e.printStackTrace()
                    future.cancel(true)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                } catch (e: ExecutionException) {
                    e.printStackTrace()
                } finally {
                    if (sortJson != null) {
                        val sortXml = sortJson(sortResult, sortJson)
                        attachSortSource(sourceKey, sortXml)
                        if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                            val absXml = json(null, sortJson, sourceBean.key)
                            if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.isNotEmpty()) {
                                sortXml.videoList = absXml.movie!!.videoList
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            } else {
                                getHomeRecList(sourceBean, null) { videos ->
                                    sortXml.videoList = videos
                                    postSortResult(sourceKey, sortXml)
                                    cacheSort(sourceKey, sortXml)
                                }
                            }
                        } else {
                            postSortResult(sourceKey, sortXml)
                            cacheSort(sourceKey, sortXml)
                        }
                    } else {
                        postSortResult(sourceKey, null)
                    }
                    try {
                        executor.shutdown()
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }
            }
            httpPrepareThreadPool.execute(waitResponse)
        } else if (type == 0 || type == 1) {
            OkGo.get<String>(sourceBean.api)
                .tag(sourceBean.key + "_sort")
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        var sortXml: AbsSortXml? = null
                        if (type == 0) {
                            val xml = response.body()
                            sortXml = sortXml(sortResult, xml)
                        } else if (type == 1) {
                            val json = response.body()
                            sortXml = sortJson(sortResult, json)
                        }
                        attachSortSource(sourceKey, sortXml)
                        if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 &&
                            sortXml.list != null && sortXml.list!!.videoList != null && sortXml.list!!.videoList!!.isNotEmpty()
                        ) {
                            val ids = ArrayList<String>()
                            for (vod in sortXml.list!!.videoList!!) {
                                ids.add(vod.id ?: "")
                            }
                            val finalSortXml = sortXml
                            getHomeRecList(sourceBean, ids) { videos ->
                                finalSortXml.videoList = videos
                                postSortResult(sourceKey, finalSortXml)
                                cacheSort(sourceKey, finalSortXml)
                            }
                        } else {
                            postSortResult(sourceKey, sortXml)
                            cacheSort(sourceKey, sortXml)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        postSortResult(sourceKey, null)
                    }
                })
        } else if (type == 4) {
            var extend = getFixUrl(sourceBean.ext)
            if (URLEncoder.encode(extend).length < 1000) {
                val request = OkGo.get<String>(sourceBean.api)
                    .tag(sourceBean.key + "_sort")
                    .params("filter", "true")
                if (!extend.isNullOrEmpty()) {
                    request.params("extend", extend)
                }
                request.execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        val sortJson = response.body()
                        if (sortJson != null) {
                            val sortXml = sortJson(sortResult, sortJson)
                            attachSortSource(sourceKey, sortXml)
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                val absXml = json(null, sortJson, sourceBean.key)
                                if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.isNotEmpty()) {
                                    sortXml.videoList = absXml.movie!!.videoList
                                    postSortResult(sourceKey, sortXml)
                                    cacheSort(sourceKey, sortXml)
                                } else {
                                    getHomeRecList(sourceBean, null) { videos ->
                                        sortXml.videoList = videos
                                        postSortResult(sourceKey, sortXml)
                                        cacheSort(sourceKey, sortXml)
                                    }
                                }
                            } else {
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            }
                        } else {
                            postSortResult(sourceKey, null)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        postSortResult(sourceKey, null)
                    }
                })
            } else {
                try {
                    val params = HashMap<String, String>()
                    params["filter"] = "true"
                    if (!extend.isNullOrEmpty()) {
                        params["extend"] = extend
                    }
                    RemoteTVBox.post(sourceBean.api, params, object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            postSortResult(sourceKey, null)
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            val sortJson = response.body()!!.string()
                            val sortXml = sortJson(sortResult, sortJson)
                            attachSortSource(sourceKey, sortXml)
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                val absXml = json(null, sortJson, sourceBean.key)
                                if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.isNotEmpty()) {
                                    sortXml.videoList = absXml.movie!!.videoList
                                    postSortResult(sourceKey, sortXml)
                                    cacheSort(sourceKey, sortXml)
                                }
                            } else {
                                postSortResult(sourceKey, sortXml)
                                cacheSort(sourceKey, sortXml)
                            }
                        }
                    })
                } catch (ignored: Exception) {
                    postSortResult(sourceKey, null)
                }
            }
        } else {
            postSortResult(sourceKey, null)
        }
    }

    // ---------------- categoryContent ----------------
    fun getList(sortData: MovieSort.SortData?, page: Int) {
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null")
            listResult.postValue(null)
            return
        }
        val homeSourceBean = ApiConfig.get().homeSourceBean
        val type = homeSourceBean.type
        if (type == 3) {
            spThreadPool.execute {
                val executor = Executors.newSingleThreadExecutor()
                val future: Future<String> = executor.submit(Callable {
                    val sp = ApiConfig.get().getCSP(homeSourceBean)
                    syncDoubanHistory(homeSourceBean, sortData.id ?: "", page)
                    sp.categoryContent(sortData.id, page.toString() + "", true, sortData.filterSelect)
                })
                var json: String? = null
                try {
                    json = future.get(homeSourceBean.playTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    LOG.i("echo--getList-timeout--" + homeSourceBean.key)
                    e.printStackTrace()
                    future.cancel(true)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                } catch (e: ExecutionException) {
                    e.printStackTrace()
                } finally {
                    executor.shutdown()
                    if (json != null) {
                        json(listResult, json, homeSourceBean.key)
                    } else {
                        listResult.postValue(null)
                    }
                }
            }
        } else if (type == 0 || type == 1) {
            OkGo.get<String>(homeSourceBean.api)
                .tag(homeSourceBean.api)
                .params("ac", if (type == 0) "videolist" else "detail")
                .params("t", sortData.id)
                .params("pg", page)
                .params(sortData.filterSelect)
                .params(
                    "f",
                    if (sortData.filterSelect == null || sortData.filterSelect.size <= 0) ""
                    else JSONObject(sortData.filterSelect as Map<Any?, Any?>).toString()
                )
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        if (type == 0) {
                            val xml = response.body()
                            xml(listResult, xml, homeSourceBean.key)
                        } else {
                            val json = response.body()
                            json(listResult, json, homeSourceBean.key)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        listResult.postValue(null)
                    }
                })
        } else if (type == 4) {
            var ext = ""
            val extend = getFixUrl(homeSourceBean.ext)
            if (sortData.filterSelect != null && sortData.filterSelect.size > 0) {
                try {
                    val selectExt = JSONObject(sortData.filterSelect as Map<Any?, Any?>).toString()
                    ext = Base64.encodeToString(selectExt.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.NO_WRAP)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                ext = Base64.encodeToString("{}".toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
            }
            val request = OkGo.get<String>(homeSourceBean.api)
                .tag(homeSourceBean.api)
                .params("ac", "detail")
                .params("filter", "true")
                .params("t", sortData.id)
                .params("pg", page)
                .params("ext", ext)
            if (!extend.isNullOrEmpty()) {
                request.params("extend", extend)
            }
            request.execute(object : AbsCallback<String>() {
                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    return try {
                        if (response.body() != null) {
                            response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误,response body 为 null")
                        }
                    } catch (e: Exception) {
                        LOG.i("echo-list: convertResponse error" + e.message)
                        throw e
                    }
                }

                override fun onSuccess(response: Response<String>) {
                    val json = response.body()
                    json(listResult, json, homeSourceBean.key)
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    listResult.postValue(null)
                }
            })
        } else {
            listResult.postValue(null)
        }
    }

    /** Loads a folder/category for a specific source (used by inline search folders). */
    fun getList(sourceKey: String, id: String) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null || TextUtils.isEmpty(id)) {
            listResult.postValue(null)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            spThreadPool.execute {
                val executor = Executors.newSingleThreadExecutor()
                val future: Future<String> = executor.submit(Callable {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    syncDoubanHistory(sourceBean, id, 1)
                    sp.categoryContent(id, "1", true, HashMap())
                })
                var json: String? = null
                try {
                    json = future.get(sourceBean.playTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                } catch (ignored: Throwable) {
                    future.cancel(true)
                } finally {
                    executor.shutdown()
                    if (json != null) {
                        json(listResult, json, sourceBean.key)
                    } else {
                        listResult.postValue(null)
                    }
                }
            }
        } else if (type == 0 || type == 1) {
            OkGo.get<String>(sourceBean.api)
                .tag(sourceBean.key + "_folder")
                .params("ac", if (type == 0) "videolist" else "detail")
                .params("t", id)
                .params("pg", 1)
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) return response.body()!!.string()
                        throw IllegalStateException("网络请求错误")
                    }

                    override fun onSuccess(response: Response<String>) {
                        if (type == 0) xml(listResult, response.body(), sourceBean.key)
                        else json(listResult, response.body(), sourceBean.key)
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        listResult.postValue(null)
                    }
                })
        } else if (type == 4) {
            val extend = getFixUrl(sourceBean.ext)
            val request = OkGo.get<String>(sourceBean.api)
                .tag(sourceBean.key + "_folder")
                .params("ac", "detail")
                .params("filter", "true")
                .params("t", id)
                .params("pg", 1)
                .params("ext", Base64.encodeToString("{}".toByteArray(), Base64.DEFAULT or Base64.NO_WRAP))
            if (!TextUtils.isEmpty(extend)) request.params("extend", extend)
            request.execute(object : AbsCallback<String>() {
                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    if (response.body() != null) return response.body()!!.string()
                    throw IllegalStateException("网络请求错误")
                }

                override fun onSuccess(response: Response<String>) {
                    json(listResult, response.body(), sourceBean.key)
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    listResult.postValue(null)
                }
            })
        } else {
            listResult.postValue(null)
        }
    }

    // ---------------- homeVideoContent ----------------
    fun getHomeRecList(sourceBean: SourceBean, ids: ArrayList<String>?, callback: HomeRecCallback) {
        val type = sourceBean.type
        if (type == 3) {
            val waitResponse = Runnable {
                val executor = Executors.newSingleThreadExecutor()
                val future: Future<String> = executor.submit(Callable {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    sp.homeVideoContent()
                })
                var sortJson: String? = null
                try {
                    sortJson = future.get(20, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    e.printStackTrace()
                    future.cancel(true)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                } catch (e: ExecutionException) {
                    e.printStackTrace()
                } finally {
                    if (sortJson != null) {
                        val absXml = json(null, sortJson, sourceBean.key)
                        if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null) {
                            callback.done(absXml.movie!!.videoList)
                        } else {
                            callback.done(null)
                        }
                    } else {
                        callback.done(null)
                    }
                    try {
                        executor.shutdown()
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }
            }
            spThreadPool.execute(waitResponse)
        } else if (type == 0 || type == 1) {
            OkGo.get<String>(sourceBean.api)
                .tag("detail")
                .params("ac", if (sourceBean.type == 0) "videolist" else "detail")
                .params("ids", TextUtils.join(",", ids!!))
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        val absXml: AbsXml?
                        if (sourceBean.type == 0) {
                            val xml = response.body()
                            absXml = xml(null, xml, sourceBean.key)
                        } else {
                            val json = response.body()
                            absXml = json(null, json, sourceBean.key)
                        }
                        if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null) {
                            callback.done(absXml.movie!!.videoList)
                        } else {
                            callback.done(null)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        callback.done(null)
                    }
                })
        } else {
            callback.done(null)
        }
    }

    // ---------------- detailContent ----------------
    fun getDetail(sourceKey: String, urlid: String) {
        getDetail(sourceKey, urlid, false)
    }

    fun getDetail(sourceKey0: String, urlid0: String, fallback: Boolean) {
        var sourceKey = sourceKey0
        var urlid = urlid0
        if (urlid.startsWith("push://") && ApiConfig.get().getSource(PUSH_AGENT) != null) {
            var pushUrl = urlid.substring(7)
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = String(
                        Base64.decode(pushUrl.substring(4), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                        Charsets.UTF_8
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl)
            }
            sourceKey = if (isCastPushUrl(pushUrl)) PUSH_FALLBACK else PUSH_AGENT
            urlid = pushUrl
        } else if (PUSH_AGENT == sourceKey && isCastPushUrl(urlid)) {
            sourceKey = PUSH_FALLBACK
        }
        val id = urlid

        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (isPushFallback(sourceKey, sourceBean)) {
            detailResult.postValue(createPushDetail(urlid, sourceKey))
            return
        }
        val type = sourceBean?.type ?: run {
            // 站点未加载时显式投递空结果,避免详情页永远停在 loading
            detailResult.postValue(AbsXml())
            return
        }
        if (type == 3) {
            spThreadPool.execute {
                val executor = Executors.newSingleThreadExecutor()
                val future: Future<String> = executor.submit(Callable {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    val ids = ArrayList<String>()
                    ids.add(id)
                    try {
                        sp.detailContent(ids)
                    } catch (e: Exception) {
                        LOG.i("echo--getDetail--error: " + e.message)
                        ""
                    }
                })
                var json: String? = null
                try {
                    json = future.get(if (fallback) 6 else 30, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    LOG.i("echo--getDetail--timeout")
                    future.cancel(true)
                } catch (e: Exception) {
                    LOG.i("echo--getDetail--error: " + e.message)
                } finally {
                    json(detailResult, json, sourceBean.key)
                    executor.shutdown()
                }
            }
        } else if (type == 0 || type == 1 || type == 4) {
            val extend = if (fallback) getFixUrl(sourceBean.ext, 6) else getFixUrl(sourceBean.ext)
            val request = OkGo.get<String>(sourceBean.api)
                .tag("detail")
                .params("ac", if (type == 0) "videolist" else "detail")
                .params("ids", id)
            if (!extend.isNullOrEmpty()) {
                request.params("extend", extend)
            }
            request.execute(object : AbsCallback<String>() {
                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    if (response.body() != null) {
                        return response.body()!!.string()
                    } else {
                        throw IllegalStateException("网络请求错误")
                    }
                }

                override fun onSuccess(response: Response<String>) {
                    if (type == 0) {
                        val xml = response.body()
                        xml(detailResult, xml, sourceBean.key)
                    } else {
                        val json = response.body()
                        LOG.i(json)
                        json(detailResult, json, sourceBean.key)
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    json(detailResult, "", sourceBean.key)
                }
            })
        } else {
            val data = AbsXml()
            data.sourceKey = sourceKey
            detailResult.postValue(data)
        }
    }

    fun action(sourceKey: String, action: String?) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null || action == null) {
            actionResult.postValue(null)
            return
        }
        if (sourceBean.type == 3) {
            spThreadPool.execute {
                try {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    val json = sp.action(action)
                    actionResult.postValue(if (TextUtils.isEmpty(json)) null else JSONObject(json))
                } catch (th: Throwable) {
                    th.printStackTrace()
                    actionResult.postValue(null)
                }
            }
        } else {
            actionResult.postValue(null)
        }
    }

    // ---------------- searchContent ----------------
    fun getSearch(sourceKey: String, wd: String) {
        getSearch(sourceKey, wd, "")
    }

    fun getSearch(sourceKey: String, wd: String, searchToken: String) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search")
    }

    fun getDetailFallbackSearch(sourceKey: String, wd: String, searchToken: String) {
        getSearch(sourceKey, wd, searchToken, detailFallbackSearchResult, "detail_fallback_search")
    }

    private fun getSearch(
        sourceKey: String,
        wd: String,
        searchToken: String,
        result: MutableLiveData<AbsXml>,
        requestTag: String
    ) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            postEmptySearchResult(result, sourceKey, searchToken)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            try {
                val sp = ApiConfig.get().getCSP(sourceBean)
                val search = sp.searchContent(wd, false)
                if (!TextUtils.isEmpty(search)) {
                    json(result, search, sourceBean.key, searchToken)
                } else {
                    json(result, "", sourceBean.key, searchToken)
                }
            } catch (th: Throwable) {
                th.printStackTrace()
                json(result, "", sourceBean.key, searchToken)
            }
        } else if (type == 0 || type == 1) {
            OkGo.get<String>(sourceBean.api)
                .params("wd", wd)
                .params(if (type == 1) "ac" else null, if (type == 1) "detail" else null)
                .tag(requestTag)
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        if (type == 0) {
                            val xml = response.body()
                            xml(result, xml, sourceBean.key, searchToken)
                        } else {
                            val json = response.body()
                            json(result, json, sourceBean.key, searchToken)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        postEmptySearchResult(result, sourceBean.key, searchToken)
                    }
                })
        } else if (type == 4) {
            val searchWd = wd
            httpPrepareThreadPool.execute {
                val extend = getFixUrlDirect(sourceBean.ext)
                val queryWd: String = try {
                    URLEncoder.encode(searchWd, "UTF-8")
                } catch (e: Exception) {
                    e.printStackTrace()
                    searchWd
                }
                val request = OkGo.get<String>(sourceBean.api)
                    .tag(requestTag)
                    .params("wd", queryWd)
                    .params("ac", "detail")
                    .params("quick", "false")
                if (!extend.isNullOrEmpty()) {
                    request.params("extend", extend)
                }
                request.execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            LOG.i("echo-t4 search-网络请求错误")
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        val json = response.body()
                        json(result, json, sourceBean.key, searchToken)
                    }

                    override fun onError(response: Response<String>) {
                        LOG.i("echo-t4 search-onError")
                        super.onError(response)
                        postEmptySearchResult(result, sourceBean.key, searchToken)
                    }
                })
            }
        } else {
            postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }

    // ---------------- quickSearch ----------------
    fun getQuickSearch(sourceKey: String, wd: String) {
        getQuickSearch(sourceKey, wd, "")
    }

    fun getQuickSearch(sourceKey: String, wd: String, searchToken: String) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            postEmptySearchResult(quickSearchResult, sourceKey, searchToken)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            try {
                val sp = ApiConfig.get().getCSP(sourceBean)
                json(quickSearchResult, sp.searchContent(wd, true), sourceBean.key, searchToken)
            } catch (th: Throwable) {
                th.printStackTrace()
                postEmptySearchResult(quickSearchResult, sourceBean.key, searchToken)
            }
        } else if (type == 0 || type == 1) {
            OkGo.get<String>(sourceBean.api)
                .params("wd", wd)
                .params(if (type == 1) "ac" else null, if (type == 1) "detail" else null)
                .tag("quick_search")
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        if (type == 0) {
                            val xml = response.body()
                            xml(quickSearchResult, xml, sourceBean.key, searchToken)
                        } else {
                            val json = response.body()
                            json(quickSearchResult, json, sourceBean.key, searchToken)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        postEmptySearchResult(quickSearchResult, sourceBean.key, searchToken)
                    }
                })
        } else if (type == 4) {
            val searchWd = wd
            httpPrepareThreadPool.execute {
                val extend = getFixUrlDirect(sourceBean.ext)
                val request = OkGo.get<String>(sourceBean.api)
                    .tag("search")
                    .params("wd", searchWd)
                    .params("ac", "detail")
                    .params("quick", "true")
                if (!extend.isNullOrEmpty()) {
                    request.params("extend", extend)
                }
                request.execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        if (response.body() != null) {
                            return response.body()!!.string()
                        } else {
                            throw IllegalStateException("网络请求错误")
                        }
                    }

                    override fun onSuccess(response: Response<String>) {
                        val json = response.body()
                        LOG.i(json)
                        json(quickSearchResult, json, sourceBean.key, searchToken)
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        postEmptySearchResult(quickSearchResult, sourceBean.key, searchToken)
                    }
                })
            }
        } else {
            postEmptySearchResult(quickSearchResult, sourceBean.key, searchToken)
        }
    }

    // ---------------- playerContent ----------------
    fun getPlay(sourceKey: String, playFlag: String, progressKey: String, url: String, subtitleKey: String) {
        val requestSeq = playRequestSeq.incrementAndGet()
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        val pushFallback = isPushFallback(sourceKey, sourceBean)
        val pushUrl: PushUrl = if (pushFallback) parsePushUrl(url) else createPushUrl(url)
        val requestUrl = pushUrl.url
        if (pushFallback) {
            postPlayResult(requestSeq, createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag))
            return
        }
        val type = sourceBean?.type ?: run {
            // 站点未加载时显式投递空结果,避免播放页一直等待解析超时
            postPlayResult(requestSeq, null)
            return
        }
        if (type == 3) {
            spThreadPool.execute {
                val executor = Executors.newSingleThreadExecutor()
                val future: Future<String> = executor.submit(Callable {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    if (TextUtils.isEmpty(requestUrl)) ""
                    else try {
                        LOG.i("echo--getPlay--id: $requestUrl")
                        sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags())
                    } catch (e: Exception) {
                        LOG.i("echo--getPlay--error: " + e.message)
                        ""
                    }
                })
                try {
                    val json = future.get(sourceBean.playTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                    LOG.i("echo--getPlay--result:$json")
                    // 处理返回的 JSON
                    if (!TextUtils.isEmpty(json)) {
                        val result = normalizePlayerResult(JSONObject(json))!!
                        result.put("key", url)
                        mergePushHeaders(result, pushUrl)
                        result.put("proKey", progressKey)
                        result.put("subtKey", subtitleKey)
                        if (!result.has("flag")) result.put("flag", playFlag)
                        if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                            postPlayResult(requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag))
                        } else {
                            postPlayResult(requestSeq, result)
                        }
                    } else {
                        postPlayResult(requestSeq, null)
                    }
                } catch (e: TimeoutException) {
                    // 超时
                    LOG.i("echo--getPlay--timeout")
                    future.cancel(true)
                    postPlayResult(requestSeq, null)
                } catch (e: Exception) {
                    LOG.i("echo--getPlay--error: " + e.message)
                    postPlayResult(requestSeq, null)
                } finally {
                    executor.shutdown()
                }
            }
        } else if (type == 0 || type == 1) {
            val result = JSONObject()
            try {
                result.put("key", url)
                val playUrl = sourceBean.playerUrl.trim()
                if (DefaultConfig.isVideoFormat(requestUrl) && playUrl.isEmpty()) {
                    result.put("parse", 0)
                    result.put("url", requestUrl)
                } else {
                    result.put("parse", 1)
                    result.put("url", requestUrl)
                }
                mergePushHeaders(result, pushUrl)
                result.put("proKey", progressKey)
                result.put("subtKey", subtitleKey)
                result.put("playUrl", playUrl)
                result.put("flag", playFlag)
                postPlayResult(requestSeq, result)
            } catch (th: Throwable) {
                th.printStackTrace()
                postPlayResult(requestSeq, null)
            }
        } else if (type == 4) {
            val extend = getFixUrl(sourceBean.ext)
            val request = OkGo.get<String>(sourceBean.api)
                .tag("play")
                .params("play", requestUrl)
                .params("flag", playFlag)
            if (!extend.isNullOrEmpty()) {
                request.params("extend", extend)
            }
            request.execute(object : AbsCallback<String>() {
                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    if (response.body() != null) {
                        return response.body()!!.string()
                    } else {
                        throw IllegalStateException("网络请求错误")
                    }
                }

                override fun onSuccess(response: Response<String>) {
                    val json = response.body()
                    LOG.i(json)
                    try {
                        val result = normalizePlayerResult(JSONObject(json))!!
                        result.put("key", url)
                        mergePushHeaders(result, pushUrl)
                        result.put("proKey", progressKey)
                        result.put("subtKey", subtitleKey)
                        if (!result.has("flag")) result.put("flag", playFlag)
                        postPlayResult(requestSeq, result)
                    } catch (th: Throwable) {
                        th.printStackTrace()
                        postPlayResult(requestSeq, null)
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    postPlayResult(requestSeq, null)
                }
            })
        } else {
            postPlayResult(requestSeq, null)
        }
    }

    fun cancelPlayRequest() {
        playRequestSeq.incrementAndGet()
    }

    private fun shouldDirectPlay(sourceBean: SourceBean?, requestUrl: String): Boolean {
        return sourceBean != null &&
            !TextUtils.isEmpty(requestUrl) &&
            (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"))
    }

    private fun createDirectPlayResult(rawUrl: String, pushUrl: PushUrl, progressKey: String, subtitleKey: String, playFlag: String): JSONObject? {
        return try {
            val result = JSONObject()
            result.put("key", rawUrl)
            result.put("proKey", progressKey)
            result.put("subtKey", subtitleKey)
            result.put("flag", playFlag)
            result.put("parse", 0)
            result.put("jx", 0)
            result.put("url", pushUrl.url)
            mergePushHeaders(result, pushUrl)
            LOG.i("echo--getPlay--direct:" + pushUrl.url)
            result
        } catch (th: Throwable) {
            th.printStackTrace()
            null
        }
    }

    private fun normalizePlayerResult(result0: JSONObject?): JSONObject? {
        if (result0 == null) return null
        val result = result0
        try {
            val playUrl = result.optString("playUrl", "")
            var url = result.optString("url", "")
            if (TextUtils.isEmpty(url)) return result
            if (url.startsWith("[") && url.endsWith("]")) {
                val array = JSONArray(url)
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    if (item is String) {
                        var str = item
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str)
                            array.put(i, str)
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8)
                            array.put(i, str)
                        }
                    }
                }
                result.put("url", array.toString())
                result.put("parse", 0)
                return result
            }
            if (url.startsWith("video://")) {
                url = url.substring(8)
                result.put("url", url)
                result.put("parse", 1)
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url)
                result.put("url", url)
                result.put("parse", 0)
            } else if (playUrl.isEmpty() && DefaultConfig.isVideoFormat(url) && !result.has("parse") && !result.has("jx")) {
                result.put("parse", 0)
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        return result
    }

    private fun isPushFallback(sourceKey: String, sourceBean: SourceBean?): Boolean {
        return PUSH_FALLBACK == sourceKey ||
            (sourceBean != null && PUSH_AGENT == sourceBean.key && sourceBean.type == -1)
    }

    private fun isCastPushUrl(url: String?): Boolean {
        return !TextUtils.isEmpty(url) && url!!.contains(PUSH_HEADERS_MARKER)
    }

    private fun createPushDetail(url: String, sourceKey: String): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        val movie = Movie()
        movie.videoList = ArrayList()
        val video = Movie.Video()
        video.id = url
        video.name = url
        video.type = "推送"
        video.sourceKey = sourceKey
        video.urlBean = Movie.Video.UrlBean()
        video.urlBean!!.infoList = ArrayList()
        val urlInfo = Movie.Video.UrlBean.UrlInfo()
        urlInfo.flag = "推送"
        urlInfo.urls = "播放$$url"
        urlInfo.beanList = ArrayList()
        urlInfo.beanList!!.add(Movie.Video.UrlBean.InfoBean("播放", url))
        video.urlBean!!.infoList!!.add(urlInfo)
        movie.videoList!!.add(video)
        data.movie = movie
        return data
    }

    private fun createPushPlayResult(rawUrl: String, pushUrl: PushUrl, progressKey: String, subtitleKey: String, playFlag: String): JSONObject? {
        return try {
            val result = JSONObject()
            result.put("key", rawUrl)
            result.put("proKey", progressKey)
            result.put("subtKey", subtitleKey)
            result.put("flag", playFlag)
            result.put("parse", 0)
            result.put("url", pushUrl.url)
            mergePushHeaders(result, pushUrl)
            result
        } catch (th: Throwable) {
            th.printStackTrace()
            null
        }
    }

    private fun mergePushHeaders(result: JSONObject?, pushUrl: PushUrl?) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return
        try {
            var header = result.optJSONObject("header")
            if (header == null) header = result.optJSONObject("headers")
            if (header == null) header = JSONObject()
            for (key in pushUrl.headers.keys) {
                header.put(key, pushUrl.headers[key])
            }
            result.put("header", header)
        } catch (ignored: Throwable) {
        }
    }

    private fun createPushUrl(rawUrl: String?): PushUrl {
        val pushUrl = PushUrl()
        pushUrl.url = rawUrl ?: ""
        return pushUrl
    }

    private fun parsePushUrl(rawUrl: String?): PushUrl {
        val pushUrl = createPushUrl(rawUrl)
        parseMarkedHeaders(pushUrl)
        return pushUrl
    }

    private fun parseMarkedHeaders(pushUrl: PushUrl): Boolean {
        val marker = PUSH_HEADERS_MARKER
        val start = pushUrl.url.indexOf(marker)
        if (start < 0) return false
        val valueStart = start + marker.length
        val end = pushUrl.url.indexOf('@', valueStart)
        if (end < 0) return false
        return try {
            val text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8")
            val json = JSONObject(text)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                val value = json.optString(key, "")
                if (!TextUtils.isEmpty(key)) pushUrl.headers[key] = value
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1)
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun postPlayResult(requestSeq: Int, result: JSONObject?) {
        mainHandler.post {
            if (requestSeq != playRequestSeq.get()) {
                LOG.i("echo--getPlay--ignore stale result")
                return@post
            }
            playResult.setValue(result)
        }
    }

    private fun getFixUrl(extend: String?): String {
        // 调用点均在主线程（getSort/getList/getDetail/getPlay 的 type=4 分支），
        // 同步等待上限由 20s 收紧为 3s，避免 ext 抓取缓慢导致主线程 ANR；
        // 超时后仍返回原始 ext，与原超时语义一致
        return getFixUrl(extend, 3)
    }

    private fun getFixUrl(extend0: String?, timeoutSeconds: Long): String {
        val extend = extend0 ?: return ""
        if (TextUtils.isEmpty(extend)) return ""
        if (!extend.startsWith("http")) return extend
        val key = MD5.string2MD5(extend)!!
        if (extendCache.containsKey(key)) {
            LOG.i("echo-getFixUrl Cache")
            return extendCache[key] ?: ""
        }
        val future: Future<String> = spThreadPool.submit(Callable {
            var result = extend
            if (extend.startsWith("http://127.0.0.1")) {
                var path = extend.replace("^http.+/file/".toRegex(), FileUtils.getRootPath() + "/")
                path = path.replace("localhost/".toRegex(), "/")
                result = FileUtils.readFileToString(path, "UTF-8")
                result = tryMinifyJson(result)
                extendCache.putIfAbsent(key, result)
            } else if (extend.startsWith("http")) {
                // 网络抓取同步收紧到与外层等待相同的超时（OkHttp 超时参数单位为毫秒），
                // 避免超时取消后 spThreadPool 仍被默认 30s 的慢请求长期占死
                result = OkHttp.string(extend, TimeUnit.SECONDS.toMillis(timeoutSeconds))
                if (result.isNotEmpty()) {
                    result = tryMinifyJson(result)
                    if (result.length > 2500) result = extend
                    extendCache.putIfAbsent(key, result)
                }
            }
            result
        })
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (te: TimeoutException) {
            te.printStackTrace()
            future.cancel(true)
            extend
        } catch (e: Exception) {
            e.printStackTrace()
            extend
        }
    }

    private fun tryMinifyJson(raw0: String): String {
        return try {
            val raw = raw0.trim()
            val jsonElement = JsonParser.parseString(raw)
            gson.toJson(jsonElement)
        } catch (e: Exception) {
            raw0
        }
    }

    private fun getSortFilter(obj: JsonObject): MovieSort.SortFilter {
        val key = obj.get("key").asString
        val name = obj.get("name").asString
        val kv = obj.getAsJsonArray("value")
        val values = LinkedHashMap<String, String>()
        for (ele in kv) {
            val ele_obj = ele.asJsonObject
            val values_key = if (ele_obj.has("n")) ele_obj.get("n").asString else ""
            val values_value = if (ele_obj.has("v")) ele_obj.get("v").asString else ""
            values[values_key] = values_value
        }
        val filter = MovieSort.SortFilter()
        filter.key = key
        filter.name = name
        filter.values = values
        return filter
    }

    private fun sortJson(result: MutableLiveData<AbsSortXml>?, json: String?): AbsSortXml? {
        return try {
            if (TextUtils.isEmpty(json)) {
                return AbsSortJson().toAbsSortXml()
            }
            val obj = JsonParser.parseString(json).asJsonObject
            val sortJsonBean = gson.fromJson<AbsSortJson>(obj, object : TypeToken<AbsSortJson>() {}.type)
            val data = sortJsonBean.toAbsSortXml()
            try {
                if (obj.has("filters")) {
                    val sortFilters = LinkedHashMap<String, ArrayList<MovieSort.SortFilter>>()
                    val filters = obj.getAsJsonObject("filters")
                    for (key in filters.keySet()) {
                        val sortFilter = ArrayList<MovieSort.SortFilter>()
                        val one: JsonElement = filters.get(key)
                        if (one.isJsonObject) {
                            sortFilter.add(getSortFilter(one.asJsonObject))
                        } else {
                            for (ele in one.asJsonArray) {
                                sortFilter.add(getSortFilter(ele.asJsonObject))
                            }
                        }
                        sortFilters[key] = sortFilter
                    }
                    if (data.classes != null && data.classes!!.sortList != null) {
                        for (sort in data.classes!!.sortList!!) {
                            if (sortFilters.containsKey(sort.id) && sortFilters[sort.id] != null) {
                                sort.filters = sortFilters[sort.id]!!
                            }
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            data
        } catch (e: Exception) {
            null
        }
    }

    private fun sortXml(result: MutableLiveData<AbsSortXml>?, xml: String?): AbsSortXml? {
        return try {
            val xstream = XStream(DomDriver()) // 创建 XStream 对象
            xstream.autodetectAnnotations(true)
            xstream.processAnnotations(AbsSortXml::class.java)
            xstream.ignoreUnknownElements()
            val data = xstream.fromXML(xml) as AbsSortXml
            for (sort in data.classes!!.sortList!!) {
                if (sort.filters == null) {
                    sort.filters = ArrayList()
                }
            }
            data
        } catch (e: Exception) {
            null
        }
    }

    private fun absXml(data: AbsXml, sourceKey: String) {
        absXml(data, sourceKey, "")
    }

    private fun absXml(data: AbsXml, sourceKey: String, searchToken: String) {
        data.sourceKey = sourceKey
        data.searchToken = searchToken
        if (data.movie != null && data.movie!!.videoList != null) {
            for (video in data.movie!!.videoList!!) {
                if (video.urlBean != null && video.urlBean!!.infoList != null) {
                    for (urlInfo in video.urlBean!!.infoList!!) {
                        val str: Array<String> =
                            if (urlInfo.urls?.contains("#") == true) {
                                urlInfo.urls!!.split("#".toRegex()).toTypedArray()
                            } else {
                                arrayOf(urlInfo.urls ?: "")
                            }
                        val infoBeanList = ArrayList<Movie.Video.UrlBean.InfoBean>()
                        for (s in str) {
                            val ss = s.split("\\$".toRegex(), 2).toTypedArray()
                            if (ss.isNotEmpty()) {
                                if (ss.size >= 2) {
                                    infoBeanList.add(Movie.Video.UrlBean.InfoBean(ss[0], ss[1]))
                                } else {
                                    infoBeanList.add(Movie.Video.UrlBean.InfoBean((infoBeanList.size + 1).toString() + "", ss[0]))
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList
                    }
                }
                video.sourceKey = sourceKey
            }
        }
    }

    private fun checkPush(data0: AbsXml): AbsXml {
        val data = data0
        if (data.movie != null && data.movie!!.videoList != null && data.movie!!.videoList!!.isNotEmpty()) {
            val video = data.movie!!.videoList!![0]
            if (video != null && video.urlBean != null && video.urlBean!!.infoList != null && video.urlBean!!.infoList!!.isNotEmpty()) {
                for (i in video.urlBean!!.infoList!!.indices) {
                    val urlinfo = video.urlBean!!.infoList!![i]
                    if (urlinfo != null && urlinfo.beanList != null && urlinfo.beanList!!.isNotEmpty()) {
                        for (infoBean in urlinfo.beanList!!) {
                            if (infoBean.url?.startsWith("push://") == true) {
                                var pushUrl = infoBean.url!!.substring(7)
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = String(
                                            Base64.decode(pushUrl.substring(4), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                                            Charsets.UTF_8
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl)
                                }

                                val resData = arrayOfNulls<AbsXml>(1)
                                val countDownLatch = CountDownLatch(1)
                                val threadPool = Executors.newSingleThreadExecutor()
                                val finalPushUrl = pushUrl
                                threadPool.execute {
                                    val sb = ApiConfig.get().getSource("push_agent")
                                    if (sb == null) {
                                        countDownLatch.countDown()
                                        return@execute
                                    }
                                    if (sb.type == 4) {
                                        OkGo.get<String>(sb.api)
                                            .tag("detail")
                                            .params("ac", "detail")
                                            .params("ids", finalPushUrl)
                                            .execute(object : AbsCallback<String>() {
                                                @Throws(Throwable::class)
                                                override fun convertResponse(response: okhttp3.Response): String {
                                                    return if (response.body() != null) {
                                                        response.body()!!.string()
                                                    } else {
                                                        ""
                                                    }
                                                }

                                                override fun onSuccess(response: Response<String>) {
                                                    val res = response.body()
                                                    if (!TextUtils.isEmpty(res)) {
                                                        try {
                                                            val absJson =
                                                                gson.fromJson<AbsJson>(res, object : TypeToken<AbsJson>() {}.type)
                                                            resData[0] = absJson.toAbsXml()
                                                            absXml(resData[0]!!, sb.key)
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        }
                                                    }
                                                    countDownLatch.countDown()
                                                }

                                                override fun onError(response: Response<String>) {
                                                    super.onError(response)
                                                    countDownLatch.countDown()
                                                }
                                            })
                                    } else {
                                        try {
                                            val sp = ApiConfig.get().getCSP(sb)
                                            val ids = ArrayList<String>()
                                            ids.add(finalPushUrl)
                                            val res = sp.detailContent(ids)
                                            if (!TextUtils.isEmpty(res)) {
                                                try {
                                                    val absJson = gson.fromJson<AbsJson>(res, object : TypeToken<AbsJson>() {}.type)
                                                    resData[0] = absJson.toAbsXml()
                                                    absXml(resData[0]!!, sb.key)
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                            }
                                        } catch (th: Throwable) {
                                            th.printStackTrace()
                                        }
                                        countDownLatch.countDown()
                                    }
                                }
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS)
                                    threadPool.shutdown()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                }
                                if (resData[0] != null) {
                                    val res = resData[0]!!
                                    if (res.movie != null && res.movie!!.videoList != null && res.movie!!.videoList!!.isNotEmpty()) {
                                        val resVideo = res.movie!!.videoList!![0]
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean!!.infoList != null && resVideo.urlBean!!.infoList!!.isNotEmpty()) {
                                            if (urlinfo.beanList!!.size == 1) {
                                                video.urlBean!!.infoList!!.removeAt(i)
                                            } else {
                                                urlinfo.beanList!!.remove(infoBean)
                                            }
                                            for (resUrlinfo in resVideo.urlBean!!.infoList!!) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && resUrlinfo.beanList!!.isNotEmpty()) {
                                                    video.urlBean!!.infoList!!.add(resUrlinfo)
                                                }
                                            }
                                            video.sourceKey = "push_agent"
                                            return data
                                        }
                                    }
                                }
                                infoBean.name = "解析失败 >>> " + infoBean.name
                            }
                        }
                    }
                }
            }
        }
        return data
    }

    fun checkThunder(data: AbsXml, index: Int) {
        var thunderParse = false
        if (data.movie != null && data.movie!!.videoList != null && data.movie!!.videoList!!.size == 1) {
            val video = data.movie!!.videoList!![0]
            if (video != null && video.urlBean != null && video.urlBean!!.infoList != null) {
                var hasThunder = false
                loop@ for (urlInfo in video.urlBean!!.infoList!!) {
                    for (infoBean in urlInfo.beanList ?: ArrayList()) {
                        if (Thunder.isSupportUrl(infoBean.url ?: "")) {
                            hasThunder = true
                            break@loop
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true
                    Thunder.parse(KtApp.instance(), video.urlBean!!, object : Thunder.ThunderCallback {
                        override fun status(code: Int, info: String?) {
                            if (code >= 0) {
                                LOG.i(info)
                            } else {
                                video.urlBean!!.infoList!![0].beanList!![0].name = info
                                detailResult.postValue(data)
                            }
                        }

                        override fun list(urlMap: Map<Int, String>) {
                            for (key in urlMap.keys) {
                                val playList = urlMap[key]
                                video.urlBean!!.infoList!![key].urls = playList
                                val str = playList?.split("#".toRegex())?.toTypedArray()
                                val infoBeanList = ArrayList<Movie.Video.UrlBean.InfoBean>()
                                if (str != null) {
                                    for (s in str) {
                                        if (s.contains("$")) {
                                            val ss = s.split("\\$".toRegex(), 2).toTypedArray()
                                            if (ss.isNotEmpty()) {
                                                if (ss.size >= 2) {
                                                    infoBeanList.add(Movie.Video.UrlBean.InfoBean(ss[0], ss[1]))
                                                } else {
                                                    infoBeanList.add(Movie.Video.UrlBean.InfoBean((infoBeanList.size + 1).toString() + "", ss[0]))
                                                }
                                            }
                                        }
                                    }
                                }
                                video.urlBean!!.infoList!![key].beanList = infoBeanList
                            }
                            detailResult.postValue(data)
                        }

                        override fun play(url: String?) {
                        }
                    })
                }
            }
        }
        if (!thunderParse && index == 0) {
            detailResult.postValue(data)
        }
    }

    private fun xml(result: MutableLiveData<AbsXml>?, xml: String?, sourceKey: String): AbsXml? {
        return xml(result, xml, sourceKey, "")
    }

    private fun xml(result0: MutableLiveData<AbsXml>?, xml0: String?, sourceKey: String, searchToken: String): AbsXml? {
        val result = result0
        return try {
            var xml = xml0
            val xstream = XStream(DomDriver()) // 创建 XStream 对象
            xstream.autodetectAnnotations(true)
            xstream.processAnnotations(AbsXml::class.java)
            xstream.ignoreUnknownElements()
            if (xml!!.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>")
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>")
            }
            var data = xstream.fromXML(xml) as AbsXml
            absXml(data, sourceKey, searchToken)
            if (searchResult === result) {
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data))
            } else if (quickSearchResult === result) {
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data))
            } else if (result != null) {
                if (result === detailResult) {
                    data = checkPush(data)
                    checkThunder(data, 0)
                } else {
                    postSearchResult(result, data)
                }
            }
            data
        } catch (e: Exception) {
            if (searchResult === result || quickSearchResult === result || detailFallbackSearchResult === result) {
                postEmptySearchResult(result, sourceKey, searchToken)
            } else if (result != null) {
                if (result === detailResult) {
                    val data = AbsXml()
                    data.sourceKey = sourceKey
                    result.postValue(data)
                } else {
                    result.postValue(null)
                }
            }
            null
        }
    }

    private fun json(result: MutableLiveData<AbsXml>?, json: String?, sourceKey: String): AbsXml? {
        return json(result, json, sourceKey, "")
    }

    private fun json(result0: MutableLiveData<AbsXml>?, json: String?, sourceKey: String, searchToken: String): AbsXml? {
        val result = result0
        return try {
            val absJson = gson.fromJson<AbsJson>(json, object : TypeToken<AbsJson>() {}.type)
            val data = absJson.toAbsXml()
            absXml(data, sourceKey, searchToken)
            if (searchResult === result) {
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data))
            } else if (quickSearchResult === result) {
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data))
            } else if (result != null) {
                if (result === detailResult) {
                    val pushed = checkPush(data)
                    checkThunder(pushed, 0)
                } else {
                    postSearchResult(result, data)
                }
            }
            data
        } catch (e: Exception) {
            if (searchResult === result || quickSearchResult === result || detailFallbackSearchResult === result) {
                postEmptySearchResult(result, sourceKey, searchToken)
            } else if (result != null) {
                if (result === detailResult) {
                    val data = AbsXml()
                    data.sourceKey = sourceKey
                    result.postValue(data)
                } else {
                    result.postValue(null)
                }
            }
            null
        }
    }

    private fun getFixUrlDirect(extend: String?): String {
        if (TextUtils.isEmpty(extend)) return ""
        if (!extend!!.startsWith("http")) return extend
        val key = MD5.string2MD5(extend)!!
        if (extendCache.containsKey(key)) {
            return extendCache[key] ?: ""
        }
        var result = extend
        try {
            if (extend.startsWith("http://127.0.0.1")) {
                var path = extend.replace("^http.+/file/".toRegex(), FileUtils.getRootPath() + "/")
                path = path.replace("localhost/".toRegex(), "/")
                result = FileUtils.readFileToString(path, "UTF-8")
                result = tryMinifyJson(result)
                extendCache.putIfAbsent(key, result)
            } else {
                result = OkHttp.string(extend, null)
                if (!TextUtils.isEmpty(result)) {
                    result = tryMinifyJson(result)
                    if (result.length > 2500) result = extend
                    extendCache.putIfAbsent(key, result)
                }
            }
        } catch (th: Throwable) {
            th.printStackTrace()
            return extend
        }
        return result
    }

    private fun postEmptySearchResult(result: MutableLiveData<AbsXml>?, sourceKey: String) {
        postEmptySearchResult(result, sourceKey, "")
    }

    private fun postEmptySearchResult(result: MutableLiveData<AbsXml>?, sourceKey: String, searchToken: String) {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.searchToken = searchToken
        if (searchResult === result) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data))
        } else if (quickSearchResult === result) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data))
        } else if (result != null) {
            postSearchResult(result, data)
        }
    }

    private fun postSearchResult(result: MutableLiveData<AbsXml>, data: AbsXml?) {
        if (result === detailFallbackSearchResult) {
            mainHandler.post { result.setValue(data) }
        } else {
            result.postValue(data)
        }
    }
}
