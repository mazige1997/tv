package com.github.catvod.net

import androidx.collection.ArrayMap
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody

/**
 * com.github.catvod.net.OkHttp 兼容层 — 保持原版 26 个 public static 方法的 JVM ABI。
 *
 * 第三方爬虫 jar 在编译期按原版签名生成 invokestatic 指令，硬编码引用
 * com/github/catvod/net/OkHttp。缺失此类或签名不符会直接 NoSuchClassDefFound /
 * NoSuchMethodError，导致 jar 爬虫全部失效。所有实现委托给
 * com.github.tvbox.kt.core.net.OkHttp（Kotlin 移植的功能等价实现）。
 */
object OkHttp {

    // 与 client() 内部共用 kt 版 dns 单例；包装成一个兼容层 OkDns 复用
    private val compatDns = OkDns(com.github.tvbox.kt.core.net.OkHttp.dns())

    @JvmStatic
    fun dns(): OkDns = compatDns

    @JvmStatic
    fun client(): OkHttpClient = com.github.tvbox.kt.core.net.OkHttp.client()

    @JvmStatic
    fun player(): OkHttpClient = com.github.tvbox.kt.core.net.OkHttp.player()

    @JvmStatic
    fun client(timeout: Long): OkHttpClient = com.github.tvbox.kt.core.net.OkHttp.client(timeout)

    @JvmStatic
    fun noRedirect(): OkHttpClient = com.github.tvbox.kt.core.net.OkHttp.noRedirect()

    @JvmStatic
    fun noRedirect(timeout: Long): OkHttpClient = com.github.tvbox.kt.core.net.OkHttp.noRedirect(timeout)

    @JvmStatic
    fun reset() = com.github.tvbox.kt.core.net.OkHttp.reset()

    @JvmStatic
    fun resetClient() = com.github.tvbox.kt.core.net.OkHttp.resetClient()

    @JvmStatic
    fun client(redirect: Boolean, timeout: Long): OkHttpClient =
        com.github.tvbox.kt.core.net.OkHttp.client(redirect, timeout)

    @JvmStatic
    fun string(url: String?): String = com.github.tvbox.kt.core.net.OkHttp.string(url)

    @JvmStatic
    fun string(url: String?, timeout: Long): String =
        com.github.tvbox.kt.core.net.OkHttp.string(url, timeout)

    @JvmStatic
    fun string(url: String?, headers: Map<String, String>?): String =
        com.github.tvbox.kt.core.net.OkHttp.string(url, headers)

    @JvmStatic
    fun newCall(url: String): Call = com.github.tvbox.kt.core.net.OkHttp.newCall(url)

    @JvmStatic
    fun newCall(url: String, tag: String?): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(url, tag)

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(client, url)

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, tag: String?): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(client, url, tag)

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>?): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(url, headers)

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>?, params: ArrayMap<String, String>?): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(url, headers, params)

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>?, body: RequestBody): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(url, headers, body)

    @JvmStatic
    fun newCall(url: String, body: RequestBody, tag: String?): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(url, body, tag)

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call =
        com.github.tvbox.kt.core.net.OkHttp.newCall(client, url, body)

    @JvmStatic
    fun cancel(tag: String?) = com.github.tvbox.kt.core.net.OkHttp.cancel(tag)

    @JvmStatic
    fun cancel(client: OkHttpClient?, tag: String?) =
        com.github.tvbox.kt.core.net.OkHttp.cancel(client, tag)

    @JvmStatic
    fun cancelAll() = com.github.tvbox.kt.core.net.OkHttp.cancelAll()

    @JvmStatic
    fun cancelAll(client: OkHttpClient?) = com.github.tvbox.kt.core.net.OkHttp.cancelAll(client)

    @JvmStatic
    fun toBody(params: ArrayMap<String, String>?): FormBody =
        com.github.tvbox.kt.core.net.OkHttp.toBody(params)
}
