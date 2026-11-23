package com.github.tvbox.kt.util.parser

import android.util.Base64
import com.github.tvbox.kt.core.spider.SpiderDebug
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import org.json.JSONObject
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 并发解析,直到获得第一个结果,Kotlin 移植(与旧版 JsonParallel 一致)。
 */
object JsonParallel {

    /**
     * 单次 parse 会话：client/executorService/futures 均归属本次调用，
     * 并发调用各自持有独立会话，互不覆盖；
     * session 字段仅供 cancelTasks 跨线程取消当前会话使用。
     */
    private class ParseSession(val client: OkHttpClient) {
        val executorService: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(5)
        val futures = ArrayList<Future<JSONObject>>()

        fun cancel() {
            client.dispatcher().cancelAll()
            for (future in futures) {
                try {
                    future.cancel(true)
                } catch (t: Throwable) {
                }
            }
            futures.clear()
            executorService.shutdownNow()
        }
    }

    @Volatile
    private var session: ParseSession? = null

    fun parse(jx: LinkedHashMap<String, String>?, url: String): JSONObject {
        var localSession: ParseSession? = null
        try {
            if (jx != null && jx.isNotEmpty()) {
                // 每次调用新建局部会话，submit/poll 全部使用局部引用，避免并发调用互踩共享字段
                localSession = ParseSession(OkHttpClient())
                session = localSession
                val client = localSession.client
                val executorService = localSession.executorService
                val futures = localSession.futures
                val completionService: CompletionService<JSONObject> = ExecutorCompletionService(executorService)

                // 遍历所有的解析配置
                for (jxName in jx.keys) {
                    val parseUrl = jx[jxName]
                    futures.add(completionService.submit(Callable<JSONObject> {
                        try {
                            // 获取请求头,并从中取出实际 url
                            val reqHeaders = getReqHeader(parseUrl!!)
                            val realUrl = reqHeaders["url"]
                            reqHeaders.remove("url")
                            val headers = Headers.of(reqHeaders)
                            val request = Request.Builder()
                                .url("$realUrl$url")
                                .headers(headers)
                                .tag("ParseTag")
                                .build()
                            val call = client.newCall(request)
                            val response = call.execute()
                            val json = response.body()!!.string()
                            val taskResult = Utils.jsonParse(url, json) ?: return@Callable null
                            taskResult.put("jxFrom", jxName)
                            taskResult
                        } catch (th: Throwable) {
                            null
                        }
                    }))
                }

                var pTaskResult: JSONObject? = null
                for (i in futures.indices) {
                    val completed = completionService.take()
                    try {
                        pTaskResult = completed.get()
                        if (pTaskResult != null) {
                            client.dispatcher().cancelAll()
                            for (future in futures) {
                                try {
                                    future.cancel(true)
                                } catch (t: Throwable) {
                                    SpiderDebug.log(t)
                                }
                            }
                            futures.clear()
                            break
                        }
                    } catch (th: Throwable) {
                        SpiderDebug.log(th)
                    }
                }
                if (pTaskResult != null) return pTaskResult
            }
        } catch (th: Throwable) {
            SpiderDebug.log(th)
        } finally {
            // 无论成功、失败还是提前返回，都关闭本次会话的线程池，避免线程池泄漏
            if (localSession != null) {
                localSession.executorService.shutdownNow()
                if (session === localSession) session = null
            }
        }
        return JSONObject()
    }

    fun cancelTasks() {
        // 取消当前（最近一次）parse 会话；会话之间互不影响
        session?.cancel()
    }

    fun getReqHeader(url: String): HashMap<String, String> {
        val reqHeaders = HashMap<String, String>()
        reqHeaders["url"] = url
        if (url.contains("cat_ext")) {
            try {
                val start = url.indexOf("cat_ext=")
                val end = url.indexOf("&", start)
                var ext = url.substring(start + 8, end)
                ext = String(Base64.decode(ext, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP))
                val newUrl = url.substring(0, start) + url.substring(end + 1)
                val jsonObject = JSONObject(ext)
                if (jsonObject.has("header")) {
                    val headerJson = jsonObject.optJSONObject("header")
                    val keys = headerJson!!.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        reqHeaders[key] = headerJson.optString(key, "")
                    }
                }
                reqHeaders["url"] = newUrl
            } catch (ignored: Throwable) {
            }
        }
        return reqHeaders
    }
}
