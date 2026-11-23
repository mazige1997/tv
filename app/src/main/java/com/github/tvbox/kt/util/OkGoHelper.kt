package com.github.tvbox.kt.util

import android.graphics.Bitmap
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.bean.ProxyRule
import com.github.tvbox.kt.core.net.OkHttp
import com.github.tvbox.kt.player.danmu.Parser
import com.github.tvbox.kt.picasso.MyOkhttpDownLoader
import com.github.tvbox.kt.util.net.OkProxySelector
import com.github.tvbox.kt.util.net.ProxyAuthenticator
import com.github.tvbox.kt.util.SSL.SSLSocketFactoryCompat
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.lzy.okgo.OkGo
import com.lzy.okgo.https.HttpsUtils
import com.lzy.okgo.interceptor.HttpLoggingInterceptor
import com.lzy.okgo.model.HttpHeaders
import com.orhanobut.hawk.Hawk
import com.squareup.picasso.Picasso
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.internal.Version
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * HTTP 客户端体系(OkGo / 播放器 / 图片加载),Kotlin 移植(与旧版 OkGoHelper 一致)。
 */
object OkGoHelper {
    const val DEFAULT_MILLISECONDS: Long = 20000

    // 内置 doh json
    private const val dnsConfigJson = "[" +
        "{\"name\": \"腾讯\", \"url\": \"https://doh.pub/dns-query\"}," +
        "{\"name\": \"阿里\", \"url\": \"https://dns.alidns.com/dns-query\"}," +
        "{\"name\": \"360\", \"url\": \"https://doh.360.cn/dns-query\"}" +
        "]"

    private var ItvClient: OkHttpClient? = null
    private var proxySelectorInstance: OkProxySelector? = null
    private var proxyAuthenticatorInstance: ProxyAuthenticator? = null

    @Synchronized
    fun proxySelector(): OkProxySelector {
        if (proxySelectorInstance == null) proxySelectorInstance = OkProxySelector()
        return proxySelectorInstance!!
    }

    @Synchronized
    fun proxyAuthenticator(): ProxyAuthenticator {
        if (proxyAuthenticatorInstance == null) proxyAuthenticatorInstance = ProxyAuthenticator(proxySelector())
        return proxyAuthenticatorInstance!!
    }

    @Synchronized
    fun setProxyList(proxyRules: List<ProxyRule>?) {
        proxySelector().clear()
        if (!proxyRules.isNullOrEmpty()) proxySelector().addAll(proxyRules)
        OkHttp.reset()
    }

    private fun initExoOkHttpClient() {
        val base = getDefaultClient()
        val builder = (base?.newBuilder() ?: OkHttpClient.Builder())
        val loggingInterceptor = HttpLoggingInterceptor("OkExoPlayer")
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY)
            loggingInterceptor.setColorLevel(Level.INFO)
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
            loggingInterceptor.setColorLevel(Level.OFF)
        }
        builder.addInterceptor(loggingInterceptor)
        builder.retryOnConnectionFailure(true)
        builder.followRedirects(true)
        builder.followSslRedirects(true)
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())
        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        builder.dns(CustomDns())
        ItvClient = builder.build()
        ExoMediaSourceHelper.getInstance(KtApp.instance()).setOkClient(ItvClient)
    }

    @JvmField
    var dnsOverHttps: DnsOverHttps? = null

    val dnsHttpsList = ArrayList<String>()

    @JvmField
    var is_doh = false

    @JvmField
    var myHosts: Map<String, String>? = null

    fun getDohUrl(type: Int): String {
        var json = Hawk.get(HawkConfig.DOH_JSON, "")
        if (json.isEmpty()) json = dnsConfigJson
        val jsonArray = JsonParser.parseString(json).asJsonArray
        if (type >= 1 && type < dnsHttpsList.size) {
            val dnsConfig = jsonArray[type - 1].asJsonObject
            return dnsConfig.get("url").asString
        }
        return ""
    }

    fun setDnsList() {
        dnsHttpsList.clear()
        var json = Hawk.get(HawkConfig.DOH_JSON, "")
        if (json.isEmpty()) json = dnsConfigJson
        val jsonArray = JsonParser.parseString(json).asJsonArray
        dnsHttpsList.add("关闭")
        for (i in 0 until jsonArray.size()) {
            val dnsConfig = jsonArray[i].asJsonObject
            val name = if (dnsConfig.has("name")) dnsConfig.get("name").asString else "Unknown Name"
            dnsHttpsList.add(name)
        }
        if (Hawk.get(HawkConfig.DOH_URL, 0) + 1 > dnsHttpsList.size) Hawk.put(HawkConfig.DOH_URL, 0)
        myHosts = com.github.tvbox.kt.api.ApiConfig.get().getMyHost()
    }

    private fun DohIps(ips: JsonArray?): List<InetAddress> {
        val inetAddresses = ArrayList<InetAddress>()
        if (ips != null) {
            for (j in 0 until ips.size()) {
                try {
                    val inetAddress = InetAddress.getByName(ips[j].asString)
                    inetAddresses.add(inetAddress)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return inetAddresses
    }

    private fun initDnsOverHttps() {
        var dohSelector = Hawk.get(HawkConfig.DOH_URL, 0)
        var ips: JsonArray? = null
        try {
            dnsHttpsList.clear()
            dnsHttpsList.add("关闭")
            var json = Hawk.get(HawkConfig.DOH_JSON, "")
            if (json.isEmpty()) json = dnsConfigJson
            val jsonArray = JsonParser.parseString(json).asJsonArray
            if (dohSelector > jsonArray.size()) {
                Hawk.put(HawkConfig.DOH_URL, 0)
                dohSelector = 0
            }
            for (i in 0 until jsonArray.size()) {
                val dnsConfig = jsonArray[i].asJsonObject
                val name = if (dnsConfig.has("name")) dnsConfig.get("name").asString else "Unknown Name"
                dnsHttpsList.add(name)
                if (dohSelector == i + 1) ips = if (dnsConfig.has("ips")) dnsConfig.getAsJsonArray("ips") else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val builder = OkHttpClient.Builder()
        builder.connectionPool(sharedConnectionPool)
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())
        val loggingInterceptor = HttpLoggingInterceptor("OkExoPlayer")
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY)
            loggingInterceptor.setColorLevel(Level.INFO)
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
            loggingInterceptor.setColorLevel(Level.OFF)
        }
        builder.addInterceptor(loggingInterceptor)
        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        builder.cache(Cache(File(KtApp.instance().cacheDir.absolutePath, "dohcache"), 100 * 1024 * 1024))
        val dohClient = builder.build()
        val dohUrl = getDohUrl(Hawk.get(HawkConfig.DOH_URL, 0))
        dnsOverHttps = DnsOverHttps.Builder()
            .client(dohClient)
            .url(if (dohUrl.isEmpty()) HttpUrl.parse("https://doh.pub/dns-query")!! else HttpUrl.get(dohUrl))
            .bootstrapDnsHosts(if (ips != null && dohUrl != "https://doh.pub/dns-query") DohIps(ips) else null)
            .build()
    }

    /** 自定义 DNS 解析器(内置 hosts + 排除失效 CDN IP) */
    class CustomDns : Dns {
        private var map: ConcurrentHashMap<String, List<InetAddress>>? = null
        private val excludeIps = "2409:8087:6c02:14:100::14,2409:8087:6c02:14:100::18,39.134.108.253,39.134.108.245"

        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            val originalHost = hostname
            var hosts = myHosts
            if (hosts == null) hosts = com.github.tvbox.kt.api.ApiConfig.get().getMyHost()
            var resolved = hostname
            if (hosts != null && hosts.isNotEmpty() && hosts.containsKey(originalHost)) {
                resolved = hosts[originalHost] ?: hostname
            }
            if (isValidIpAddress(resolved)) {
                return Collections.singletonList(InetAddress.getByName(resolved))
            } else {
                val dns = dnsOverHttps ?: Dns.SYSTEM
                return dns.lookup(resolved)
            }
        }

        @Synchronized
        @Throws(UnknownHostException::class)
        fun mapHosts(hosts: Map<String, String>) {
            map = ConcurrentHashMap()
            for (entry in hosts.entries) {
                val key = entry.key
                val value = entry.value
                if (isValidIpAddress(value)) {
                    map!![key] = Collections.singletonList(InetAddress.getByName(value))
                } else {
                    map!![key] = getAllByName(value)
                }
            }
        }

        private fun getAllByName(host: String): List<InetAddress> {
            return try {
                val allAddresses = InetAddress.getAllByName(host)
                if (excludeIps.isEmpty()) return allAddresses.toList()
                val validAddresses = ArrayList<InetAddress>()
                val excludeIpsSet = HashSet<String>()
                for (ip in excludeIps.split(",")) {
                    excludeIpsSet.add(ip.trim())
                }
                for (address in allAddresses) {
                    if (!excludeIpsSet.contains(address.hostAddress)) {
                        validAddresses.add(address)
                    }
                }
                validAddresses
            } catch (e: Exception) {
                ArrayList()
            }
        }

        // 简单判断减少开销
        private fun isValidIpAddress(str: String): Boolean {
            return if (str.indexOf('.') > 0) isValidIPv4(str) else str.indexOf(':') > 0
        }

        private fun isValidIPv4(str: String): Boolean {
            val parts = str.split("\\.".toRegex()).toTypedArray()
            if (parts.size != 4) return false
            for (part in parts) {
                try {
                    part.toInt()
                } catch (e: NumberFormatException) {
                    return false
                }
            }
            return true
        }
    }

    private var defaultClient: OkHttpClient? = null
    private var noRedirectClient: OkHttpClient? = null

    /**
     * 全局共享连接池:default/noRedirect/doh 等客户端复用同一池,
     * 避免 TV 盒子上多 OkHttpClient 各自持有连接池导致的 socket 浪费。
     */
    private val sharedConnectionPool = okhttp3.ConnectionPool()

    fun getDefaultClient(): OkHttpClient? = defaultClient

    fun getNoRedirectClient(): OkHttpClient? = noRedirectClient

    fun getItvClient(): OkHttpClient? = ItvClient

    /** OkGo 主客户端构建(init/reloadDns 共用,消除重复代码)。 */
    private fun buildMainClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
        val loggingInterceptor = HttpLoggingInterceptor("OkGo")
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY)
            loggingInterceptor.setColorLevel(Level.INFO)
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE)
            loggingInterceptor.setColorLevel(Level.OFF)
        }
        builder.addInterceptor(loggingInterceptor)
        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.dns(CustomDns())
        builder.proxySelector(proxySelector())
        builder.proxyAuthenticator(proxyAuthenticator())
        builder.connectionPool(sharedConnectionPool)
        try {
            setOkHttpSsl(builder)
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        HttpHeaders.setUserAgent(Version.userAgent())
        val okHttpClient = builder.build()
        OkGo.getInstance().setOkHttpClient(okHttpClient)
        return okHttpClient
    }

    /** 主客户端的非重定向变体(newBuilder 自动继承共享连接池)。 */
    private fun buildNoRedirectClient() {
        val noRedirectBuilder = defaultClient!!.newBuilder()
        noRedirectBuilder.followRedirects(false)
        noRedirectBuilder.followSslRedirects(false)
        noRedirectClient = noRedirectBuilder.build()
    }

    fun init() {
        initDnsOverHttps()
        defaultClient = buildMainClient()
        buildNoRedirectClient()
        initExoOkHttpClient()
        initPicasso(defaultClient!!)
    }

    @Synchronized
    fun reloadDns() {
        initDnsOverHttps()
        defaultClient = buildMainClient()
        buildNoRedirectClient()
        initExoOkHttpClient()
        Parser.resetHttpClient()
        OkHttp.resetClient()
    }

    private fun initPicasso(client: OkHttpClient) {
        client.dispatcher().maxRequestsPerHost = 10
        val downloader = MyOkhttpDownLoader(client)
        val picasso = Picasso.Builder(KtApp.instance())
            .downloader(downloader)
            .defaultBitmapConfig(Bitmap.Config.RGB_565)
            .build()
        try {
            Picasso.setSingletonInstance(picasso)
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    private fun setOkHttpSsl(builder: OkHttpClient.Builder) {
        try {
            val trustAllCert: javax.net.ssl.X509TrustManager = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val sslSocketFactory = SSLSocketFactoryCompat(trustAllCert)
            builder.sslSocketFactory(sslSocketFactory, trustAllCert)
            builder.hostnameVerifier(HttpsUtils.UnSafeHostnameVerifier)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
