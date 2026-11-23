package com.github.tvbox.kt.core.net

import com.github.tvbox.kt.util.OkGoHelper
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义 DNS( hosts 映射 + DoH),Kotlin 移植(与旧版 OkDns 一致)。
 */
class OkDns : Dns {

    private val hosts = ConcurrentHashMap<String, String>()

    fun addAll(hosts: List<String>?) {
        if (hosts == null) return
        for (host in hosts) {
            if (host == null) continue
            val splits = host.split("=".toRegex(), 2).toTypedArray()
            if (splits.size == 2) this.hosts[splits[0].trim()] = splits[1].trim()
        }
    }

    /** 与原版 com.github.catvod.net.OkDns.clear() 对齐,兼容 catvod.net.OkDns 委托层 */
    fun clear() {
        hosts.clear()
    }

    private fun get(hostname: String): String {
        val target = hosts[hostname]
        if (target != null) return target
        for (entry in hosts.entries) {
            if (hostname.contains(entry.key)) return entry.value
        }
        return hostname
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val dns = OkGoHelper.dnsOverHttps ?: Dns.SYSTEM
        return dns.lookup(get(hostname))
    }
}
