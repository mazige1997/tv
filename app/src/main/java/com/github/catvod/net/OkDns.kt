package com.github.catvod.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * com.github.catvod.net.OkDns 兼容层 — 保持原版 JVM ABI。
 *
 * 第三方爬虫 jar 静态引用此类型（如强转 dns() 返回值、调用 addAll/clear）。
 * 真实实现委托给 com.github.tvbox.kt.core.net.OkDns 的单例（与 OkHttp.client()
 * 内部使用同一个实例），保证 hosts 映射对实际请求生效。
 */
class OkDns : Dns {

    private val impl: com.github.tvbox.kt.core.net.OkDns

    /** 默认构造：委托给 kt 版 dns 单例，与 OkHttp.client() 内部一致 */
    constructor() {
        this.impl = com.github.tvbox.kt.core.net.OkHttp.dns()
    }

    /** 显式指定委托实例 */
    constructor(impl: com.github.tvbox.kt.core.net.OkDns) {
        this.impl = impl
    }

    /** public void addAll(List) */
    fun addAll(hosts: List<String>?) {
        impl.addAll(hosts)
    }

    /** public void clear() */
    fun clear() {
        impl.clear()
    }

    /** public List<InetAddress> lookup(String) throws UnknownHostException */
    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        return impl.lookup(hostname)
    }
}
