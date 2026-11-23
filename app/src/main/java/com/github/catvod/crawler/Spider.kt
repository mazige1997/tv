package com.github.catvod.crawler

import android.content.Context
import com.github.tvbox.kt.core.net.OkHttp
import org.json.JSONObject
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * 爬虫基类 — 必须与旧版 com.github.catvod.crawler.Spider 的 JVM ABI 完全一致。
 *
 * 第三方爬虫 jar 由 DexClassLoader 加载，其代码在编译期按原版签名生成字节码：
 * - 静态读取 Spider.empty / Spider.mContext
 * - 静态调用 Spider.client() / Spider.safeDns()
 * 因此这些成员必须生成真正的 JVM 静态字段/方法（@JvmField / @JvmStatic），
 * 否则 jar 运行时抛 NoSuchFieldError / NoSuchMethodError，表现为分类/详情/直播全部无数据。
 */
open class Spider {

    companion object {
        /** public static JSONObject empty — 原版行18 */
        @JvmField
        var empty: JSONObject = JSONObject()

        /** protected static Context mContext — 原版行21。用 @JvmField 生成静态字段 */
        @JvmField
        var mContext: Context? = null

        /** public static Dns safeDns() — 原版行129 */
        @JvmStatic
        fun safeDns(): Dns = OkHttp.dns()

        /** public static OkHttpClient client() — 原版行133 */
        @JvmStatic
        fun client(): OkHttpClient = OkHttp.client()
    }

    /** public String siteKey — 实例字段,原版行19 */
    @JvmField
    var siteKey: String? = null

    /** init(Context): mContext = context — 原版行23 */
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
