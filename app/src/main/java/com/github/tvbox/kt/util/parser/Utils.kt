package com.github.tvbox.kt.util.parser

import android.annotation.TargetApi
import android.os.Build
import android.webkit.ValueCallback
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject
import java.util.Arrays
import java.util.regex.Pattern

/** 嗅探/解析工具,Kotlin 移植(与旧版 util.parser.Utils 一致)。 */
@TargetApi(Build.VERSION_CODES.KITKAT)
object Utils {

    val RULE: Pattern = Pattern.compile(
        "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3)\\?.*|http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3)|http((?!http).)*?video/tos*"
    )
    const val UaWinChrome =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
    const val UaMobile = "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.1 Mobile/15E148 Safari/604.1"

    fun isVip(url: String): Boolean {
        val hosts = Arrays.asList(
            "iqiyi.com", "v.qq.com", "youku.com", "le.com", "tudou.com", "mgtv.com",
            "sohu.com", "acfun.cn", "bilibili.com", "baofeng.com", "pptv.com"
        )
        for (host in hosts) if (url.contains(host)) return true
        return false
    }

    fun isVideoFormat(url: String): Boolean {
        if (url.contains("url=http") || url.contains(".js") || url.contains(".css") || url.contains(".html")) return false
        return RULE.matcher(url).find()
    }

    fun substring(text: String?): String? {
        return substring(text, 1)
    }

    fun substring(text: String?, num: Int): String? {
        return if (text != null && text.length > num) {
            text.substring(0, text.length - num)
        } else {
            text
        }
    }

    fun loadUrl(webView: WebView, script: String) {
        loadUrl(webView, script, null)
    }

    fun loadUrl(webView: WebView, script: String, callback: ValueCallback<String>?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) webView.evaluateJavascript(script, callback)
        else webView.loadUrl(script)
    }

    fun isBlackVodUrl(input: String?, url: String): Boolean {
        if (url.contains("973973.xyz") || url.contains(".fit:")) return true
        return false
    }

    @Throws(JSONException::class)
    fun fixJsonVodHeader(headers0: JSONObject?, input: String, url: String): JSONObject {
        val headers = headers0 ?: JSONObject()
        if (input.contains("www.mgtv.com")) {
            headers.put("Referer", " ")
            headers.put("User-Agent", " Mozilla/5.0")
        } else if (url.contains("titan.mgtv")) {
            headers.put("Referer", " ")
            headers.put("User-Agent", " Mozilla/5.0")
        } else if (input.contains("bilibili")) {
            headers.put("Referer", " https://www.bilibili.com/")
            headers.put("User-Agent", " $UaWinChrome")
        }
        return headers
    }

    @Throws(JSONException::class)
    fun jsonParse(input: String, json: String): JSONObject? {
        val jsonPlayData = JSONObject(json)
        val url: String = if (jsonPlayData.has("data")) {
            jsonPlayData.getJSONObject("data").getString("url")
        } else {
            jsonPlayData.getString("url")
        }
        var playUrl = url
        if (playUrl.startsWith("//")) {
            playUrl = "https:$playUrl"
        }
        if (!playUrl.startsWith("http")) {
            return null
        }
        if (playUrl == input) {
            if (isVip(playUrl) || !isVideoFormat(playUrl)) {
                return null
            }
        }
        if (isBlackVodUrl(input, playUrl)) {
            return null
        }
        var headers = JSONObject()
        val ua = jsonPlayData.optString("user-agent", "")
        if (ua.trim().isNotEmpty()) {
            headers.put("User-Agent", " $ua")
        }
        val referer = jsonPlayData.optString("referer", "")
        if (referer.trim().isNotEmpty()) {
            headers.put("Referer", " $referer")
        }
        headers = fixJsonVodHeader(headers, input, playUrl)
        val taskResult = JSONObject()
        taskResult.put("header", headers)
        taskResult.put("url", playUrl)
        return taskResult
    }
}
