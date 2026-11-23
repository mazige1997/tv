package com.github.tvbox.kt.util.net

import com.github.tvbox.kt.bean.ProxyRule
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress

/**
 * 代理认证,Kotlin 移植(与旧版 ProxyAuthenticator 一致)。
 */
class ProxyAuthenticator(private val selector: OkProxySelector?) : okhttp3.Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (route == null || response.request().header("Proxy-Authorization") != null) return null
        if (route.proxy().address() !is InetSocketAddress) return null
        val proxyAddress = route.proxy().address() as InetSocketAddress
        val userInfo = findUserInfo(response.request().url().host(), proxyAddress.hostName)
        if (userInfo == null || !userInfo.contains(":")) return null
        val index = userInfo.indexOf(':')
        if (index <= 0 || index >= userInfo.length - 1) return null
        return response.request().newBuilder()
            .header(
                "Proxy-Authorization",
                Credentials.basic(userInfo.substring(0, index), userInfo.substring(index + 1))
            )
            .build()
    }

    private fun findUserInfo(requestHost: String?, proxyHost: String?): String? {
        if (selector == null || requestHost == null || proxyHost == null) return null
        for (item in selector.getProxy()) {
            if (matchesHost(item, requestHost)) {
                val userInfo = item.getUserInfo(proxyHost)
                if (userInfo != null) return userInfo
            }
        }
        return null
    }

    private fun matchesHost(item: ProxyRule, requestHost: String): Boolean {
        for (host in item.getHosts()) {
            if (host != null && containOrMatch(requestHost, host)) return true
        }
        return false
    }

    private fun containOrMatch(text: String, rule: String): Boolean {
        return try {
            text.contains(rule) || text.matches(rule.toRegex())
        } catch (th: Throwable) {
            false
        }
    }
}
