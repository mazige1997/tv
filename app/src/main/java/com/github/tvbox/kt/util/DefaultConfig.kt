package com.github.tvbox.kt.util

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.server.ControlManager
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * 通用默认配置,Kotlin 移植(与旧版 DefaultConfig.java 一致)。
 */
object DefaultConfig {

    fun adjustSort(sourceKey: String?, list: List<MovieSort.SortData>?, withMy: Boolean): List<MovieSort.SortData> {
        val data = ArrayList<MovieSort.SortData>()
        if (sourceKey != null && list != null) {
            val sb: SourceBean? = ApiConfig.get().getSource(sourceKey)
            if (sb == null || sb.categories == null) {
                for (sortData in list) {
                    if (sortData.filters == null) sortData.filters = ArrayList()
                    data.add(sortData)
                }
                if (withMy) data.add(0, MovieSort.SortData("my0", "主页"))
                data.sort()
                return data
            }
            val categories = sb.categories
            if (!categories.isNullOrEmpty()) {
                for (cate in categories) {
                    for (sortData in list) {
                        if (sortData.name == cate) {
                            if (sortData.filters == null) sortData.filters = ArrayList()
                            data.add(sortData)
                        }
                    }
                }
            } else {
                for (sortData in list) {
                    if (sortData.filters == null) sortData.filters = ArrayList()
                    data.add(sortData)
                }
            }
        }
        if (withMy) data.add(0, MovieSort.SortData("my0", "主页"))
        data.sort()
        return data
    }

    fun getAppVersionCode(mContext: Context): Int {
        return try {
            val pm = mContext.packageManager
            val packageInfo = pm.getPackageInfo(mContext.packageName, 0)
            packageInfo.versionCode
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    fun getAppVersionName(mContext: Context): String {
        return try {
            val pm = mContext.packageManager
            val packageInfo = pm.getPackageInfo(mContext.packageName, 0)
            packageInfo.versionName ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /** 后缀 */
    fun getFileSuffix(name: String?): String {
        if (TextUtils.isEmpty(name)) return ""
        val endP = name!!.lastIndexOf(".")
        return if (endP > -1) name.substring(endP) else ""
    }

    /** 获取文件的前缀 */
    fun getFilePrefixName(fileName: String?): String {
        if (TextUtils.isEmpty(fileName)) return ""
        val start = fileName!!.lastIndexOf(".")
        return if (start > -1) fileName.substring(0, start) else fileName
    }

    private val snifferMatch: Pattern = Pattern.compile(
        "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)\\?.*|" +
            "http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)|" +
            "http((?!http).)*?video/tos*|" +
            "http((?!http).){20,}?/m3u8\\?pt=m3u8.*|" +
            "http((?!http).)*?default\\.ixigua\\.com/.*|" +
            "http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*|" +
            "http.*?/player/m3u8play\\.php\\?url=.*|" +
            "http.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
            "http.*?/playlist/m3u8/\\?vid=.*|" +
            "http.*?\\.php\\?type=m3u8&.*|" +
            "http.*?/download.aspx\\?.*|" +
            "http.*?/api/up_api.php\\?.*|" +
            "https.*?\\.66yk\\.cn.*|" +
            "http((?!http).)*?netease\\.com/file/.*"
    )

    fun isVideoFormat(url: String): Boolean {
        val uri = Uri.parse(url)
        val path = uri.path
        if (TextUtils.isEmpty(path)) return false
        return snifferMatch.matcher(url).find()
    }

    fun safeJsonString(obj: JsonObject, key: String, defaultVal: String): String {
        return try {
            if (obj.has(key)) {
                val el = obj.get(key)
                if (el.isJsonObject || el.isJsonArray) el.toString().trim()
                else obj.getAsJsonPrimitive(key).asString.trim()
            } else defaultVal
        } catch (th: Throwable) {
            defaultVal
        }
    }

    fun safeJsonInt(obj: JsonObject, key: String, defaultVal: Int): Int {
        return try {
            if (obj.has(key)) obj.getAsJsonPrimitive(key).asInt else defaultVal
        } catch (th: Throwable) {
            defaultVal
        }
    }

    fun safeJsonStringList(obj: JsonObject, key: String): ArrayList<String> {
        val result = ArrayList<String>()
        try {
            if (obj.has(key)) {
                val el = obj.get(key)
                if (el.isJsonObject) {
                    result.add(el.asString)
                } else {
                    for (opt in obj.getAsJsonArray(key)) {
                        result.add(opt.asString)
                    }
                }
            }
        } catch (th: Throwable) {
        }
        return result
    }

    fun checkReplaceProxy(urlOri: String): String {
        if (urlOri.startsWith("proxy://")) {
            return urlOri.replace("proxy://", ControlManager.get().getAddress(true) + "proxy?")
        }
        return urlOri
    }

    private val NO_AD_KEYWORDS = listOf(
        "tx", "youku", "qq", "qiyi", "letv", "leshi", "sohu", "mgtv", "bilibili", "imgo",
        "优酷", "芒果", "腾讯", "奇艺"
    )

    fun noAd(flag: String?): Boolean {
        if (flag.isNullOrEmpty()) return false
        for (keyword in NO_AD_KEYWORDS) {
            if (flag == keyword || flag.contains(keyword)) return true
        }
        return false
    }
}
