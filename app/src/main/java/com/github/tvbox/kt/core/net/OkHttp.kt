package com.github.tvbox.kt.core.net

import com.github.tvbox.kt.util.OkGoHelper
import com.github.tvbox.kt.util.SSL.SSLSocketFactoryCompat
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/**
 * 全局 OkHttp 封装,Kotlin 移植(与旧版 com.github.catvod.net.OkHttp 一致)。
 */
object OkHttp {

    private val TIMEOUT = TimeUnit.SECONDS.toMillis(30)

    private var dns: OkDns? = null
    private var client: OkHttpClient? = null

    val TRUST_ALL_CERT: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    @Synchronized
    fun dns(): OkDns {
        if (dns == null) dns = OkDns()
        return dns!!
    }

    @Synchronized
    fun client(): OkHttpClient {
        if (client != null) return client!!
        val base = OkGoHelper.getDefaultClient()
        if (base != null) {
            client = base.newBuilder().dns(dns()).build()
            return client!!
        }
        val builder = OkHttpClient.Builder()
            .dns(dns())
            .proxySelector(OkGoHelper.proxySelector())
            .proxyAuthenticator(OkGoHelper.proxyAuthenticator())
            .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
        setOkHttpSsl(builder)
        client = builder.build()
        return client!!
    }

    fun player(): OkHttpClient = client()

    fun client(timeout: Long): OkHttpClient {
        return client().newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    fun noRedirect(): OkHttpClient = noRedirect(TIMEOUT)

    fun noRedirect(timeout: Long): OkHttpClient {
        var base = OkGoHelper.getNoRedirectClient()
        if (base == null) base = client()
        return base.newBuilder()
            .dns(dns())
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Synchronized
    fun reset() {
        client = null
        dns = null
    }

    @Synchronized
    fun resetClient() {
        client = null
    }

    fun client(redirect: Boolean, timeout: Long): OkHttpClient =
        if (redirect) client(timeout) else noRedirect(timeout)

    fun string(url: String?): String {
        if (url == null || !url.startsWith("http")) return ""
        return try {
            newCall(url).execute().use { res ->
                res.body()?.string() ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun string(url: String?, timeout: Long): String {
        if (url == null || !url.startsWith("http")) return ""
        return try {
            newCall(client(timeout), url).execute().use { res ->
                res.body()?.string() ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun string(url: String?, headers: Map<String, String>?): String {
        if (url == null || !url.startsWith("http")) return ""
        return try {
            newCall(url, headers).execute().use { res ->
                res.body()?.string() ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun newCall(url: String): Call = client().newCall(Request.Builder().url(url).build())

    fun newCall(url: String, tag: String?): Call =
        client().newCall(Request.Builder().url(url).tag(tag).build())

    fun newCall(client: OkHttpClient, url: String): Call =
        client.newCall(Request.Builder().url(url).build())

    fun newCall(client: OkHttpClient, url: String, tag: String?): Call =
        client.newCall(Request.Builder().url(url).tag(tag).build())

    fun newCall(url: String, headers: Map<String, String>?): Call =
        client().newCall(Request.Builder().url(url).headers(headers(headers)).build())

    fun newCall(url: String, headers: Map<String, String>?, params: Map<String, String>?): Call =
        client().newCall(
            Request.Builder().url(buildUrl(url, params)).headers(headers(headers)).build()
        )

    fun newCall(url: String, headers: Map<String, String>?, body: RequestBody): Call =
        client().newCall(Request.Builder().url(url).headers(headers(headers)).post(body).build())

    fun newCall(url: String, body: RequestBody, tag: String?): Call =
        client().newCall(Request.Builder().url(url).post(body).tag(tag).build())

    fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call =
        client.newCall(Request.Builder().url(url).post(body).build())

    fun cancel(tag: String?) = cancel(client(), tag)

    fun cancel(client: OkHttpClient?, tag: String?) {
        if (client == null || tag == null) return
        for (call in client.dispatcher().queuedCalls()) {
            if (tag == call.request().tag()) call.cancel()
        }
        for (call in client.dispatcher().runningCalls()) {
            if (tag == call.request().tag()) call.cancel()
        }
    }

    fun cancelAll() = cancelAll(client())

    fun cancelAll(client: OkHttpClient?) {
        client?.dispatcher()?.cancelAll()
    }

    fun toBody(params: Map<String, String>?): FormBody {
        val body = FormBody.Builder()
        if (params != null) {
            for (entry in params.entries) body.add(entry.key, entry.value)
        }
        return body.build()
    }

    private fun headers(headers: Map<String, String>?): Headers =
        if (headers == null) Headers.Builder().build() else Headers.of(headers)

    private fun setOkHttpSsl(builder: OkHttpClient.Builder) {
        try {
            val sslSocketFactory = SSLSocketFactoryCompat(TRUST_ALL_CERT)
            builder.sslSocketFactory(sslSocketFactory, TRUST_ALL_CERT)
            builder.hostnameVerifier { _, _ -> true }
        } catch (ignored: Throwable) {
        }
    }

    private fun buildUrl(url: String, params: Map<String, String>?): HttpUrl {
        val builder = HttpUrl.parse(url)!!.newBuilder()
        if (params != null) {
            for (entry in params.entries) builder.addQueryParameter(entry.key, entry.value)
        }
        return builder.build()
    }
}
