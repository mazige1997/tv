package com.github.tvbox.kt.bean

/** IP 扫描条目,Kotlin 移植(与旧版 IpScanningVo.java 一致)。 */
class IpScanningVo(private var hostName: String, private var ip: String) {
    fun getHostName(): String = hostName

    fun setHostName(hostName: String) {
        this.hostName = hostName
    }

    fun getIp(): String = ip

    fun setIp(ip: String) {
        this.ip = ip
    }
}
