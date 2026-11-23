package com.github.tvbox.kt.core.spider.js

import android.text.TextUtils
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.util.Arrays
import java.util.Objects

/**
 * JS req 请求参数,Kotlin 移植(与旧版 crawler.js.Req 一致)。
 */
class Req {

    @SerializedName("buffer")
    private var buffer: Int? = null

    @SerializedName("redirect")
    private var redirect: Int? = null

    @SerializedName("timeout")
    private var timeout: Int? = null

    @SerializedName("postType")
    private var postType: String? = null

    @SerializedName("method")
    private var method: String? = null

    @SerializedName("body")
    private var body: String? = null

    @SerializedName("data")
    private var data: JsonElement? = null

    @SerializedName("headers")
    private var headers: JsonElement? = null

    fun getBuffer(): Int = buffer ?: 0

    fun getRedirect(): Int = redirect ?: 1

    fun getTimeout(): Int = timeout ?: 10000

    fun getPostType(): String = if (TextUtils.isEmpty(postType)) "json" else postType!!

    fun getMethod(): String = if (TextUtils.isEmpty(method)) "get" else method!!

    fun getBody(): String? = body

    fun getData(): JsonElement? = data

    private fun getHeaders(): JsonElement? = headers

    fun isRedirect(): Boolean = getRedirect() == 1

    fun getHeader(): Map<String, String> = Json.toMap(getHeaders())

    fun getCharset(): String {
        val header = getHeader()
        val keys = Arrays.asList("Content-Type", "content-type")
        for (key in keys) if (header.containsKey(key)) return getCharset(header[key]!!)
        return "UTF-8"
    }

    private fun getCharset(value: String): String {
        for (text in value.split(";".toRegex())) {
            if (text.contains("charset=")) return text.split("=".toRegex())[1]
        }
        return "UTF-8"
    }

    companion object {
        fun objectFrom(json: String): Req {
            return Gson().fromJson(json, Req::class.java)
        }
    }
}
