package com.github.tvbox.kt.bean

import android.net.Uri
import android.text.TextUtils
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.net.InetSocketAddress
import java.net.Proxy

/** 代理规则,Kotlin 移植(与旧版 ProxyRule.java 一致)。 */
class ProxyRule : Comparable<ProxyRule> {

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("hosts")
    private var hosts: List<String>? = null

    @SerializedName("urls")
    private var urls: List<String>? = null

    private var proxies: List<Proxy>? = null
    private var uris: List<Uri>? = null
    private var wildcard = false

    fun init() {
        wildcard = false
        for (host in getHosts()) {
            if (host != null && host.indexOf('*') >= 0) {
                wildcard = true
                break
            }
        }
        val uriList = ArrayList<Uri>()
        for (url in getUrls()) {
            if (TextUtils.isEmpty(url)) continue
            val uri = Uri.parse(url)
            if (isValid(uri)) uriList.add(uri)
        }
        uris = uriList
        val proxyList = ArrayList<Proxy>()
        for (uri in uriList) {
            create(uri)?.let { proxyList.add(it) }
        }
        proxies = proxyList
    }

    fun getName(): String = if (TextUtils.isEmpty(name)) "" else name!!

    fun getHosts(): List<String> = hosts ?: emptyList()

    fun getUrls(): List<String> = urls ?: emptyList()

    fun getProxies(): List<Proxy> = proxies ?: emptyList()

    fun getUserInfo(host: String?): String? {
        if (uris == null || host == null) return null
        for (uri in uris!!) {
            if (uri.host == null) continue
            if (host.equals(uri.host, ignoreCase = true)) {
                val userInfo = uri.userInfo
                if (!TextUtils.isEmpty(userInfo)) return userInfo
            }
        }
        return null
    }

    private fun isValid(uri: Uri?): Boolean =
        uri != null && uri.scheme != null && uri.host != null && uri.port > 0

    private fun create(uri: Uri): Proxy? {
        val address = InetSocketAddress.createUnresolved(uri.host, uri.port)
        if (isScheme(uri, "http")) return Proxy(Proxy.Type.HTTP, address)
        if (isScheme(uri, "socks")) return Proxy(Proxy.Type.SOCKS, address)
        return null
    }

    private fun isScheme(uri: Uri, scheme: String): Boolean =
        uri.scheme != null && uri.scheme!!.startsWith(scheme)

    override fun compareTo(other: ProxyRule): Int =
        this.wildcard.compareTo(other.wildcard)

    companion object {
        fun arrayFrom(element: JsonElement?): List<ProxyRule> {
            return try {
                val listType = object : TypeToken<List<ProxyRule>>() {}.type
                val items: List<ProxyRule>? = Gson().fromJson(element, listType)
                items ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
