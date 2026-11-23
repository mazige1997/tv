package com.github.tvbox.kt.core.spider.js

import android.text.TextUtils
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream
import java.util.Arrays
import java.util.Objects

/**
 * JS proxy 响应结果,Kotlin 移植(与旧版 crawler.js.Res 一致)。
 */
class Res {

    @SerializedName("code")
    private var code: Int? = null

    @SerializedName("buffer")
    private var buffer: Int? = null

    @SerializedName("content")
    private var content: String? = null

    @SerializedName("headers")
    private var headers: JsonElement? = null

    fun getCode(): Int = code ?: 200

    fun getBuffer(): Int = buffer ?: 0

    fun getContent(): String = if (TextUtils.isEmpty(content)) "" else content!!

    private fun getHeaders(): JsonElement? = headers

    fun getHeader(): Map<String, String> = Json.toMap(getHeaders())

    fun getContentType(): String {
        val header = getHeader()
        val keys = Arrays.asList("Content-Type", "content-type")
        for (key in keys) if (header.containsKey(key)) return header[key]!!
        return "application/octet-stream"
    }

    fun getStream(): ByteArrayInputStream {
        if (getBuffer() == 2) return ByteArrayInputStream(Base64.decode(getContent(), Base64.DEFAULT))
        return ByteArrayInputStream(getContent().toByteArray())
    }

    companion object {
        fun objectFrom(json: String): Res {
            return Gson().fromJson(json, Res::class.java)
        }
    }
}
