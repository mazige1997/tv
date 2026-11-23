package com.github.tvbox.kt.server

import android.content.Context
import android.text.TextUtils
import com.orhanobut.hawk.Hawk
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream

/** KV 缓存请求处理,Kotlin 移植(与旧版 CacheRequestProcess 一致)。 */
class CacheRequestProcess : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean {
        return fileName.startsWith("/cache")
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params0: Map<String, String>?,
        files: Map<String, String>?
    ): NanoHTTPD.Response {
        var params = params0
        if (params == null) params = session.parms
        if (files != null && files.isNotEmpty()) (params as MutableMap<String, String>).putAll(files)
        val action = params?.get("do")
        val key = params?.get("key")
        if (TextUtils.isEmpty(key)) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "")
        val cacheKey = getKey(params?.get("rule"), key)
        if ("get" == action) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, Hawk.get(cacheKey, ""))
        if ("set" == action) {
            val value = params?.get("value")
            Hawk.put(cacheKey, value ?: "")
        }
        if ("del" == action) Hawk.delete(cacheKey)
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "OK")
    }

    private fun getKey(rule: String?, key: String?): String {
        return "cache_" + (if (TextUtils.isEmpty(rule)) "" else "$rule" + "_") + key
    }
}

/** 资源文件加载,Kotlin 移植(与旧版 RawRequestProcess 一致)。 */
class RawRequestProcess(
    private val mContext: Context,
    private val fileName: String,
    private val resourceId: Int,
    private val mimeType: String
) : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean {
        return session.method == NanoHTTPD.Method.GET && this.fileName.equals(fileName, ignoreCase = true)
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params: Map<String, String>?,
        files: Map<String, String>?
    ): NanoHTTPD.Response {
        val inputStream: InputStream = mContext.resources.openRawResource(resourceId)
        return try {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "$mimeType; charset=utf-8",
                inputStream, inputStream.available().toLong()
            )
        } catch (IOExc: IOException) {
            RemoteServer.createPlainTextResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "SERVER INTERNAL ERROR: IOException: " + IOExc.message
            )
        }
    }
}

/** 响应按键和输入,Kotlin 移植(与旧版 InputRequestProcess 一致)。 */
class InputRequestProcess(private val remoteServer: RemoteServer) : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean {
        if (session.method == NanoHTTPD.Method.POST) {
            when (fileName) {
                "/action" -> return true
            }
        }
        return false
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params: Map<String, String>?,
        files: Map<String, String>?
    ): NanoHTTPD.Response {
        val mDataReceiver = remoteServer.getDataReceiver()
        return when (fileName) {
            "/action" -> {
                if (params?.get("do") != null && mDataReceiver != null) {
                    when (params["do"]) {
                        "search" -> mDataReceiver.onTextReceived(params["word"]?.trim())
                        "api" -> mDataReceiver.onApiReceived(params["url"]?.trim())
                        "liveApi" -> mDataReceiver.onLiveApiReceived(params["url"]?.trim())
                        "danmuApi" -> mDataReceiver.onDanmuApiReceived(params["url"]?.trim())
                        "push" -> {
                            val url = params["url"]
                            if (url != null && url.trim().isNotEmpty()) {
                                mDataReceiver.onPushReceived(url.trim())
                            }
                        }
                    }
                }
                RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok")
            }
            else -> RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.")
        }
    }
}
