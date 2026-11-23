package com.github.tvbox.kt.picasso

import android.text.TextUtils
import com.google.gson.Gson
import com.squareup.picasso.Downloader
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder

/**
 * Picasso OkHttp 下载器(支持 @Headers/@Cookie/@User-Agent/@Referer 内联参数),
 * Kotlin 移植(与旧版 MyOkhttpDownLoader 一致)。
 */
class MyOkhttpDownLoader(client: OkHttpClient) : Downloader {

    private val client: Call.Factory = client
    private val cache: Cache? = client.cache()
    private val sharedClient = true

    @Throws(IOException::class)
    override fun load(request: Request): Response {
        var url = request.url().toString()
        var header: String? = null
        var cookie: String? = null
        var ua: String? = null
        var referer: String? = null

        // 检查链接里面是否有自定义 header
        if (url.contains("@Headers=")) {
            header = url.split("@Headers=")[1].split("@")[0]
            header = URLDecoder.decode(header, "UTF-8")
        }
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0]
        if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=")[1].split("@")[0]
        if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0]

        url = url.split("@")[0]
        val mRequestBuilder = request.newBuilder().url(url)
        if (!TextUtils.isEmpty(header)) {
            val jsonInfo = Gson().fromJson(header, com.google.gson.JsonObject::class.java)
            for (key in jsonInfo.keySet()) {
                val `val` = jsonInfo.get(key).asString
                if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(`val`)) {
                    mRequestBuilder.header(key, `val`.trim())
                }
            }
        }
        if (!TextUtils.isEmpty(cookie)) {
            mRequestBuilder.header("Cookie", cookie)
        }
        if (!TextUtils.isEmpty(ua)) {
            mRequestBuilder.header("User-Agent", ua)
        } else if (TextUtils.isEmpty(mRequestBuilder.build().header("User-Agent"))) {
            mRequestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
        }
        if (!TextUtils.isEmpty(referer)) {
            mRequestBuilder.header("Referer", referer)
        }
        return client.newCall(mRequestBuilder.build()).execute()
    }

    override fun shutdown() {
        if (!sharedClient && cache != null) {
            try {
                cache.close()
            } catch (ignored: IOException) {
            }
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 13; M2102J2SC Build/TKQ1.220829.002)"
    }
}
