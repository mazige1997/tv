package com.github.tvbox.kt.util.net

import com.github.tvbox.kt.bean.ProxyRule
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 按域名规则的代理选择器,Kotlin 移植(与旧版 OkProxySelector 一致)。
 */
class OkProxySelector : ProxySelector() {

    val proxy = CopyOnWriteArrayList<ProxyRule>()
    private val system: ProxySelector? = ProxySelector.getDefault()

    @Synchronized
    fun addAll(items: List<ProxyRule>?) {
        if (items.isNullOrEmpty()) return
        for (item in items) {
            item?.init()
        }
        proxy.addAll(items)
        Collections.sort(proxy)
    }

    @Synchronized
    fun clear() {
        proxy.clear()
    }

    fun getProxy(): List<ProxyRule> = proxy

    private fun fallback(uri: URI?): List<Proxy> {
        if (system != null && uri != null) return system.select(uri)
        val result = ArrayList<Proxy>()
        result.add(Proxy.NO_PROXY)
        return result
    }

    override fun select(uri: URI?): List<Proxy> {
        if (proxy.isEmpty() || uri == null || uri.host == null) return fallback(uri)
        val host = uri.host
        if ("127.0.0.1" == host || "localhost".equals(host, ignoreCase = true)) return fallback(uri)
        for (item in proxy) {
            for (rule in item.getHosts()) {
                if (rule == null) continue
                if (containOrMatch(host, rule)) {
                    val proxies = item.getProxies()
                    return if (proxies.isEmpty()) fallback(uri) else proxies
                }
            }
        }
        return fallback(uri)
    }

    override fun connectFailed(uri: URI?, socketAddress: SocketAddress?, e: IOException?) {
        system?.connectFailed(uri, socketAddress, e)
    }

    private fun containOrMatch(text: String, rule: String): Boolean {
        return try {
            text.contains(rule) || text.matches(rule.toRegex())
        } catch (th: Throwable) {
            false
        }
    }
}
