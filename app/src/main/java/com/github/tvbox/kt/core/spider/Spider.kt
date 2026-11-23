package com.github.tvbox.kt.core.spider

import android.content.Context
import com.github.tvbox.kt.core.net.OkHttp
import org.json.JSONObject
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * 爬虫基类,Kotlin 移植(与旧版 com.github.catvod.crawler.Spider 一致)。
 * jar 爬虫(DexClassLoader 内的实现)继承此类,接口签名不可改动。
 */
open class Spider {

    var empty: JSONObject = JSONObject()

    @JvmField
    var siteKey: String? = null

    protected var mContext: Context? = null

    open fun init(context: Context?) {
        mContext = context
    }

    open fun init(context: Context?, extend: String?) {
        init(context)
    }

    open fun initApi(api: SpiderApi?) {}

    /** 首页数据内容 */
    open fun homeContent(filter: Boolean): String = ""

    /** 首页最近更新数据 */
    open fun homeVideoContent(): String = ""

    /** 分类数据 */
    open fun categoryContent(tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?): String = ""

    /** 详情数据 */
    open fun detailContent(ids: List<String>?): String = ""

    /** 搜索数据内容 */
    open fun searchContent(key: String?, quick: Boolean): String = ""

    open fun searchContent(key: String?, quick: Boolean, pg: String?): String {
        return searchContent(key, quick)
    }

    /** 播放信息 */
    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    /** webview 解析时使用,可自定义判断当前加载的 url 是否是视频 */
    open fun isVideoFormat(url: String?): Boolean = false

    /** 是否手动检测 webview 中加载的 url */
    open fun manualVideoCheck(): Boolean = false

    /** 直播 list */
    open fun liveContent(url: String?): String = ""

    fun safeDns(): Dns = OkHttp.dns()

    fun client(): OkHttpClient = OkHttp.client()

    /** 取消请求 tag */
    open fun cancelByTag() {}

    /** 销毁 */
    open fun destroy() {}

    /** 爬虫代理 */
    open fun proxyLocal(params: Map<String, String>?): Array<Any?>? = null

    open fun proxy(params: Map<String, String>?): Array<Any?>? {
        return proxyLocal(params)
    }

    open fun action(action: String?): String? = null
}
