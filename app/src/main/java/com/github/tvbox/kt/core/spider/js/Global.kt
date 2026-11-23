package com.github.tvbox.kt.core.spider.js

import androidx.annotation.Keep
import com.github.tvbox.kt.core.NativeProxy
import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService

/**
 * JS 爬虫全局函数注入(getProxy/js2Proxy/pdfh/pdfa/加密等),
 * Kotlin 移植(与旧版 crawler.js.Global 一致,方法名即 JS API 名,不可改动)。
 */
class Global(private val executor: ExecutorService) {

    private var runtime: QuickJSContext? = null

    // P2-3:懒创建的 daemon Timer——未用到 setTimeout 不再占用线程;即使 cancelTimer 后再次
    // setTimeout 也会安全重建,不会抛 IllegalStateException;daemon 兜底避免线程阻塞进程退出
    private var timer: Timer? = null

    /** 释放内部 Timer 线程(由 JsSpider.destroy 调用,与其 executor 串行,无并发竞态) */
    fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    @Keep
    @Function
    fun getProxy(local: Boolean): String {
        return NativeProxy.getUrl(local) + "?do=js"
    }

    @Keep
    @Function
    fun js2Proxy(dynamic: Boolean?, siteType: Int?, siteKey: String?, url: String?, headers: JSObject?): String {
        val local = dynamic == null || !dynamic
        return getProxy(local) + "&from=catvod" +
            "&siteType=" + siteType +
            "&siteKey=" + siteKey +
            "&header=" + URLEncoder.encode(headers?.stringify() ?: "{}") +
            "&url=" + URLEncoder.encode(url ?: "")
    }

    @Keep
    @Function
    fun joinUrl(parent: String?, child: String?): String {
        return HtmlParser.joinUrl(parent, child)
    }

    @Keep
    @Function
    fun pd(html: String?, rule: String?, add_url: String?): String {
        return HtmlParser.parseDomForUrl(html ?: "", rule ?: "", add_url)
    }

    @Keep
    @Function
    fun pdfh(html: String?, rule: String?): String {
        return HtmlParser.parseDomForUrl(html ?: "", rule ?: "", "")
    }

    @Keep
    @Function
    fun pdfa(html: String?, rule: String?): JSArray {
        return JSUtils<String>().toArray(runtime, HtmlParser.parseDomForArray(html ?: "", rule ?: ""))
    }

    @Keep
    @Function
    fun pdfla(html: String?, p1: String?, list_text: String?, list_url: String?, add_url: String?): JSArray {
        return JSUtils<String>().toArray(
            runtime,
            HtmlParser.parseDomForList(html ?: "", p1 ?: "", list_text ?: "", list_url ?: "", add_url)
        )
    }

    @Keep
    @Function
    fun s2t(text: String?): String {
        return try {
            Trans.s2t(false, text) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @Function
    fun t2s(text: String?): String {
        return try {
            Trans.t2s(false, text) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @Function
    fun aesX(mode: String, encrypt: Boolean, input: String, inBase64: Boolean, key: String, iv: String?, outBase64: Boolean): String {
        return Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64)
    }

    @Keep
    @Function
    fun rsaX(mode: String?, pub: Boolean, encrypt: Boolean, input: String, inBase64: Boolean, key: String, outBase64: Boolean): String {
        return Crypto.rsa(pub, encrypt, input, inBase64, key, outBase64)
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?): String {
        return rsaEncrypt(data, key, null)
    }

    /**
     * RSA 加密
     * @param options 加密选项:{ config, type(1 公钥加密/2 私钥加密), long(1 普通/2 分段), block }
     */
    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?, options: JSObject?): String {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return try {
            when (mType) {
                1 -> if (mConfig != null) RSAEncrypt.encryptByPublicKey(data, key, mConfig, mLong, mBlock) ?: ""
                else RSAEncrypt.encryptByPublicKey(data, key, mLong, mBlock) ?: ""
                2 -> if (mConfig != null) RSAEncrypt.encryptByPrivateKey(data, key, mConfig, mLong, mBlock) ?: ""
                else RSAEncrypt.encryptByPrivateKey(data, key, mLong, mBlock) ?: ""
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?): String {
        return rsaDecrypt(encryptBase64Data, key, null)
    }

    /**
     * RSA 解密
     * @param options 解密选项:{ config, type, long, block }
     */
    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?, options: JSObject?): String {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return try {
            when (mType) {
                1 -> if (mConfig != null) RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mConfig, mLong, mBlock) ?: ""
                else RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mLong, mBlock) ?: ""
                2 -> if (mConfig != null) RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mConfig, mLong, mBlock) ?: ""
                else RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mLong, mBlock) ?: ""
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun req(url: String, options: JSObject?): JSObject {
        return try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            val res = Connect.to(url, req).execute()
            Connect.success(runtime!!, req, res)
        } catch (e: Exception) {
            Connect.error(runtime!!)
        }
    }

    @Keep
    @Function
    fun _http(url: String, options: JSObject?): JSObject? {
        val complete = options?.getJSFunction("complete") ?: return req(url, options)
        val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
        Connect.to(url, req).enqueue(getCallback(complete, req))
        return null
    }

    @Keep
    @Function
    fun setTimeout(func: JSFunction?, delay: Int?) {
        func?.hold()
        val timer = this.timer ?: Timer("js-global-timer", true).also { this.timer = it }
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!executor.isShutdown) executor.submit { func!!.call() }
            }
        }, delay?.toLong() ?: 0L)
    }

    private fun getCallback(complete: JSFunction, req: Req): Callback {
        return object : Callback {
            override fun onResponse(call: Call, res: Response) {
                executor.submit {
                    complete.call(Connect.success(runtime!!, req, res))
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                executor.submit {
                    complete.call(Connect.error(runtime!!))
                }
            }
        }
    }

    /** 声明用于依赖注入的 QuickJSContext */
    @Keep
    @ContextSetter
    fun setJSContext(rt: QuickJSContext) {
        this.runtime = rt
    }
}
