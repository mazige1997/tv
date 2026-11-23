package com.github.tvbox.kt.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.regex.Pattern

/**
 * IP 地址工具,Kotlin 移植(与旧版 LocalIPAddress 一致)。
 */
object LocalIPAddress {

    @SuppressLint("DefaultLocale")
    fun getLocalIPAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        if (ipAddress == 0) {
            try {
                val enumerationNi: java.util.Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
                while (enumerationNi.hasMoreElements()) {
                    val networkInterface = enumerationNi.nextElement()
                    val interfaceName = networkInterface.displayName
                    if (interfaceName == "eth0" || interfaceName == "wlan0") {
                        val enumIpAddr = networkInterface.inetAddresses
                        while (enumIpAddr.hasMoreElements()) {
                            val inetAddress = enumIpAddr.nextElement()
                            if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                                return inetAddress.hostAddress
                            }
                        }
                    }
                }
            } catch (e: SocketException) {
                e.printStackTrace()
            }
        } else {
            return String.format(
                "%d.%d.%d.%d",
                (ipAddress and 0xff), (ipAddress shr 8 and 0xff), (ipAddress shr 16 and 0xff), (ipAddress shr 24 and 0xff)
            )
        }
        return "127.0.0.1"
    }

    fun getIP(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: 0
            intToIp(ipAddress)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                return getLocalIPAddress()
            } catch (e1: Exception) {
                e1.printStackTrace()
            }
            "127.0.0.1"
        }
    }

    fun getLocalIPAddress(): String {
        try {
            val mEnumeration: java.util.Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (mEnumeration.hasMoreElements()) {
                val intf = mEnumeration.nextElement()
                val enumIPAddr = intf.inetAddresses
                while (enumIPAddr.hasMoreElements()) {
                    val inetAddress = enumIPAddr.nextElement()
                    // 如果不是回环地址
                    if (!inetAddress.isLoopbackAddress) {
                        // 直接返回本地 IP 地址
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            System.err.print("error")
        }
        return "127.0.0.1"
    }

    private fun intToIp(i: Int): String {
        return (i and 0xFF).toString() + "." +
            ((i shr 8) and 0xFF) + "." +
            ((i shr 16) and 0xFF) + "." +
            (i shr 24 and 0xFF)
    }

    /** Ipv4 address check */
    private val IPV4_PATTERN: Pattern = Pattern.compile(
        "^(" + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
            "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    )
    private val IPV6_PATTERN: Pattern = Pattern.compile(
        "^\\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:)(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))(%.+)?\\s*$"
    )

    fun isIPv4Address(input: String): Boolean {
        return IPV4_PATTERN.matcher(input).matches()
    }

    fun isIPv6Address(str: String?): Boolean {
        return str != null && IPV6_PATTERN.matcher(str).matches()
    }

    fun isIPAddress(str: String?): Boolean {
        return isIPv4Address(str!!) || isIPv6Address(str)
    }
}
