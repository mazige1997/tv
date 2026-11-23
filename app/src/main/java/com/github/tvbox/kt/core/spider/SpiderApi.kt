package com.github.tvbox.kt.core.spider

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Base64
import android.view.Surface
import android.view.WindowManager
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.server.ControlManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/**
 * 注入到 jar 爬虫的环境 API,Kotlin 移植(与旧版 SpiderApi 一致)。
 */
open class SpiderApi {

    fun getAddress(local: Boolean): String {
        return try {
            ControlManager.get().getAddress(local)
        } catch (th: Throwable) {
            ""
        }
    }

    fun getPort(): String {
        return try {
            val address = ControlManager.get().getAddress(true)
            val idx = address.lastIndexOf(":")
            if (idx >= 0) address.substring(idx + 1).replace("/", "") else ""
        } catch (th: Throwable) {
            ""
        }
    }

    fun log(msg: String?) {
        try {
            SpiderDebug.log(msg)
        } catch (ignored: Throwable) {
        }
    }

    fun getScreenOrientation(): Int {
        return try {
            val activity: Activity? = KtApp.instance().getCurrentActivity()
            val context: Context = activity ?: KtApp.instance()
            val orientation = context.resources.configuration.orientation
            var rotation = Surface.ROTATION_0
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null && windowManager.defaultDisplay != null) {
                rotation = windowManager.defaultDisplay.rotation
            }
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return if (rotation == Surface.ROTATION_90) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            }
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (th: Throwable) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun multiReq(array: JsonArray?): String {
        return try {
            if (array == null || array.size() == 0) return ""
            val executor = java.util.concurrent.Executors.newFixedThreadPool(minOf(array.size(), 6))
            val futures = ArrayList<java.util.concurrent.Future<String>>()
            for (element in array) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                futures.add(executor.submit<String> { request(obj) })
            }
            val result = JsonArray()
            for (future in futures) result.add(toResult(future.get()))
            executor.shutdown()
            result.toString()
        } catch (th: Throwable) {
            ""
        }
    }

    fun webParse(url: String?, flag: String?): String {
        return try {
            if (url == null || url.isEmpty()) return ""
            val encoded = Base64.encodeToString(
                url.toByteArray(Charsets.UTF_8),
                Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
            )
            "proxy://go=SuperParse&flag=" + (flag ?: "") + "&url=" + encoded
        } catch (th: Throwable) {
            ""
        }
    }

    private fun request(obj: JsonObject): String {
        return try {
            val url = string(obj, "url")
            if (url.isEmpty()) return ""
            val method = string(obj, "method")
            val headers = headers(obj.get("headers"))
            val builder = Request.Builder().url(url).headers(headers)
            if ("POST".equals(method, ignoreCase = true)) builder.post(body(obj))
            val client = com.github.tvbox.kt.core.net.OkHttp.client()
            client.newCall(builder.build()).execute().use { response ->
                response.body()?.string() ?: ""
            }
        } catch (th: Throwable) {
            ""
        }
    }

    private fun toResult(text: String?): JsonElement {
        if (text == null) return JsonPrimitive("")
        return try {
            val trim = text.trim()
            if (trim.startsWith("{") || trim.startsWith("[")) {
                JsonParser.parseString(trim)
            } else {
                JsonPrimitive(text)
            }
        } catch (ignored: Throwable) {
            JsonPrimitive(text)
        }
    }

    private fun body(obj: JsonObject): RequestBody {
        val data = obj.get("data")
        if (data == null || data.isJsonNull) return RequestBody.create(null, "")
        val postType = string(obj, "postType")
        if ("form".equals(postType, ignoreCase = true) && data.isJsonObject) {
            val builder = FormBody.Builder()
            for (entry in data.asJsonObject.entrySet()) {
                builder.add(entry.key, entry.value.asString)
            }
            return builder.build()
        }
        return RequestBody.create(null, if (data.isJsonPrimitive) data.asString else data.toString())
    }

    private fun headers(element: JsonElement?): Headers {
        return try {
            if (element == null || element.isJsonNull || !element.isJsonObject) Headers.Builder().build()
            else {
                val map = HashMap<String, String>()
                for (entry in element.asJsonObject.entrySet()) {
                    map[entry.key] = entry.value.asString
                }
                Headers.of(map)
            }
        } catch (th: Throwable) {
            Headers.Builder().build()
        }
    }

    private fun string(obj: JsonObject, key: String): String {
        val element = obj.get(key)
        return if (element == null || element.isJsonNull) "" else element.asString
    }
}
