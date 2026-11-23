package com.github.tvbox.kt.core

import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.server.RemoteServer

/**
 * 本地代理地址工具,Kotlin 移植(与旧版 com.github.catvod.Proxy 一致)。
 */
object NativeProxy {

    private var port = RemoteServer.serverPort

    fun set(port: Int) {
        NativeProxy.port = port
    }

    fun getPort(): Int = if (port > 0) port else RemoteServer.serverPort

    fun getUrl(local: Boolean): String {
        return "http://" + (if (local) "127.0.0.1" else getIp()) + ":" + getPort() + "/proxy"
    }

    private fun getIp(): String {
        return try {
            com.github.tvbox.kt.util.LocalIPAddress.getLocalIPAddress(KtApp.instance())
        } catch (th: Throwable) {
            "127.0.0.1"
        }
    }
}
