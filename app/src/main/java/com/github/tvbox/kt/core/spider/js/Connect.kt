package com.github.tvbox.kt.core.spider.js

import android.util.Base64
import com.google.common.net.HttpHeaders
import com.lzy.okgo.OkGo
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.Random

/**
 * JS req 的 HTTP 执行桥,Kotlin 移植(与旧版 crawler.js.Connect 一致)。
 */
object Connect {

    var client: OkHttpClient? = null

    fun to(url: String, req: Req): Call {
        client = com.github.tvbox.kt.util.OkGoHelper.getDefaultClient()
        return client!!.newCall(getRequest(url, req, Headers.of(req.getHeader())))
    }

    fun success(ctx: QuickJSContext, req: Req, res: Response): JSObject {
        return try {
            val jsObject = ctx.createNewJSObject()
            val jsHeader = ctx.createNewJSObject()
            setHeader(ctx, res, jsHeader)
            ctx.setProperty(jsObject, "headers", jsHeader)
            if (req.getBuffer() == 0) ctx.setProperty(jsObject, "content", String(res.body()!!.bytes(), charset(req.getCharset())))
            if (req.getBuffer() == 1) {
                val array = ctx.createNewJSArray()
                val bytes = res.body()!!.bytes()
                for (i in bytes.indices) array.set(bytes[i].toInt(), i)
                ctx.setProperty(jsObject, "content", array)
            }
            if (req.getBuffer() == 2) ctx.setProperty(
                jsObject, "content",
                Base64.encodeToString(res.body()!!.bytes(), Base64.DEFAULT or Base64.NO_WRAP)
            )
            jsObject
        } catch (e: Exception) {
            error(ctx)
        }
    }

    fun error(ctx: QuickJSContext): JSObject {
        val jsObject = ctx.createNewJSObject()
        val jsHeader = ctx.createNewJSObject()
        ctx.setProperty(jsObject, "headers", jsHeader)
        ctx.setProperty(jsObject, "content", "")
        return jsObject
    }

    private fun getRequest(url: String, req: Req, headers: Headers): Request {
        return when {
            req.getMethod().equals("post", ignoreCase = true) ->
                Request.Builder().url(url).tag("js_okhttp_tag").headers(headers)
                    .post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build()
            req.getMethod().equals("header", ignoreCase = true) ->
                Request.Builder().url(url).tag("js_okhttp_tag").headers(headers).head().build()
            else ->
                Request.Builder().url(url).tag("js_okhttp_tag").headers(headers).get().build()
        }
    }

    private fun getPostBody(req: Req, contentType: String?): RequestBody {
        if (req.getData() != null && req.getPostType() == "json") return getJsonBody(req)
        if (req.getData() != null && req.getPostType() == "form") return getFormBody(req)
        if (req.getData() != null && req.getPostType() == "form-data") return getFormDataBody(req)
        if (req.getBody() != null && contentType != null) return RequestBody.create(MediaType.get(contentType), req.getBody())
        return RequestBody.create(null, "")
    }

    private fun getJsonBody(req: Req): RequestBody {
        return RequestBody.create(MediaType.get("application/json"), req.getData().toString())
    }

    private fun getFormBody(req: Req): RequestBody {
        val formBody = FormBody.Builder()
        val params = Json.toMap(req.getData())
        for (key in params.keys) formBody.add(key, params[key])
        return formBody.build()
    }

    private fun getFormDataBody(req: Req): RequestBody {
        val boundary = "--dio-boundary-" + Random().nextInt(42949) + "" + Random().nextInt(67296)
        val builder = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
        val params = Json.toMap(req.getData())
        for (key in params.keys) builder.addFormDataPart(key, params[key])
        return builder.build()
    }

    private fun setHeader(ctx: QuickJSContext, res: Response, `object`: JSObject) {
        for (entry in res.headers().toMultimap().entries) {
            if (entry.value.size == 1) ctx.setProperty(`object`, entry.key, entry.value[0])
            if (entry.value.size >= 2) ctx.setProperty(`object`, entry.key, JSUtils<String>().toArray(ctx, entry.value))
        }
    }

    fun cancelByTag(tag: Any?) {
        try {
            if (client != null) {
                for (call in client!!.dispatcher().queuedCalls()) {
                    if (tag == call.request().tag()) {
                        call.cancel()
                    }
                }
                for (call in client!!.dispatcher().runningCalls()) {
                    if (tag == call.request().tag()) {
                        call.cancel()
                    }
                }
            }
            OkGo.getInstance().cancelTag(tag)
            cancelDefaultClient(tag)
        } catch (e: Exception) {
        }
    }

    private fun cancelDefaultClient(tag: Any?) {
        val defaultClient = com.github.tvbox.kt.util.OkGoHelper.getDefaultClient() ?: return
        if (tag == null) return
        for (call in defaultClient.dispatcher().queuedCalls()) {
            if (tag == call.request().tag()) {
                call.cancel()
            }
        }
        for (call in defaultClient.dispatcher().runningCalls()) {
            if (tag == call.request().tag()) {
                call.cancel()
            }
        }
    }
}
