package com.github.tvbox.kt.util

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.ArrayList

/**
 * WebView 广告拦截,Kotlin 移植(与旧版 AdBlocker 一致)。
 */
object AdBlocker {
    private val AD_HOSTS = ArrayList<String>()

    fun clear() {
        AD_HOSTS.clear()
    }

    fun isEmpty(): Boolean = AD_HOSTS.isEmpty()

    fun addAdHost(host: String) {
        AD_HOSTS.add(host)
    }

    fun hasHost(host: String): Boolean = AD_HOSTS.contains(host)

    fun isAd(url: String): Boolean {
        val lower = url.lowercase()
        for (adHost in AD_HOSTS) {
            if (lower.contains(adHost)) return true
        }
        return false
    }

    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
    }
}
