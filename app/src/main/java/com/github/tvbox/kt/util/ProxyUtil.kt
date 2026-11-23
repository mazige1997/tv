package com.github.tvbox.kt.util

import com.github.catvod.crawler.SpiderDebug
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.util.parser.SuperParse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 本地代理处理器(live/bom/SuperParse),Kotlin 移植(与旧版 util.Proxy 一致)。
 */
object ProxyUtil {
    private val URI_ATTR: Pattern = Pattern.compile("URI=\"([^\"]+)\"")

    fun proxy(params: Map<String, String>): Array<Any?>? {
        try {
            val what = params["go"]
            if ("live" == what) {
                return itv(params)
            } else if ("bom" == what) {
                return removeBOMFromM3U8(params)
            } else if ("ad" == what) {
                // TODO
                return null
            } else if ("SuperParse" == what) {
                return SuperParse.loadHtml(params["flag"], params["url"])
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    @Throws(Exception::class)
    fun itv(params: Map<String, String>): Array<Any?>? {
        try {
            val result = arrayOfNulls<Any>(4)
            var url = params["url"] ?: ""
            val type = params["type"]
            url = URLDecoder.decode(url, "UTF-8")

            val client = OkGoHelper.getItvClient()
            if ("m3u8" == type) {
                val request = buildRequest(url, params)
                client!!.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respContent = response.body()!!.string()
                        val finalUrl = response.request().url().toString()
                        val m3u8Content = processM3u8Content(respContent, finalUrl, params)
                        result[0] = 200
                        result[1] = "application/vnd.apple.mpegurl"
                        result[2] = ByteArrayInputStream(m3u8Content.toByteArray(Charsets.UTF_8))
                    } else {
                        throw IOException("M3U8 Request failed with code: " + response.code())
                    }
                }
            } else if ("ts" == type || "media" == type || "key" == type) {
                val request = buildRequest(url, params)
                val response = executeRequest(client!!, request)
                if (response.isSuccessful) {
                    result[0] = response.code()
                    result[1] = getMime(type, url, response)
                    result[2] = ResponseInputStream(response)
                    result[3] = responseHeaders(response)
                } else {
                    val code = response.code()
                    response.close()
                    throw IOException("Media Request failed with code: $code")
                }
            } else {
                throw IllegalArgumentException("Invalid type: $type")
            }
            return result
        } catch (e: Exception) {
            SpiderDebug.log(e)
            return null
        }
    }

    @Throws(Exception::class)
    fun removeBOMFromM3U8(params: Map<String, String>): Array<Any?>? {
        try {
            val result = arrayOfNulls<Any>(3)
            var url = params["url"] ?: ""
            url = URLDecoder.decode(url, "UTF-8")

            val client = OkGoHelper.getItvClient()
            val redirectUrl = getRedirectedUrl(url)

            val request = Request.Builder().url(redirectUrl).build()
            client!!.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    var m3u8Content = response.body()!!.string()
                    // 检查并去除 UTF-8 BOM 头(BOM 为 \uFEFF)
                    if (m3u8Content.startsWith("\uFEFF")) {
                        m3u8Content = m3u8Content.substring(1)
                    }
                    result[0] = 200
                    result[1] = "application/vnd.apple.mpegurl"
                    result[2] = ByteArrayInputStream(m3u8Content.toByteArray())
                } else {
                    throw IOException("M3U8 Request failed with code: " + response.code())
                }
            }
            return result
        } catch (e: Exception) {
            SpiderDebug.log(e)
            return null
        }
    }

    private fun buildRequest(url: String, params: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        copyHeader(builder, params, "ua", "User-Agent")
        copyHeader(builder, params, "user-agent", "User-Agent")
        copyHeader(builder, params, "User-Agent", "User-Agent")
        copyHeader(builder, params, "referer", "Referer")
        copyHeader(builder, params, "Referer", "Referer")
        copyHeader(builder, params, "origin", "Origin")
        copyHeader(builder, params, "Origin", "Origin")
        copyHeader(builder, params, "cookie", "Cookie")
        copyHeader(builder, params, "Cookie", "Cookie")
        copyHeader(builder, params, "range", "Range")
        copyHeader(builder, params, "Range", "Range")
        copyHeader(builder, params, "accept", "Accept")
        copyHeader(builder, params, "Accept", "Accept")
        copyHeader(builder, params, "accept-language", "Accept-Language")
        copyHeader(builder, params, "Accept-Language", "Accept-Language")
        return builder.build()
    }

    private fun copyHeader(builder: Request.Builder, params: Map<String, String>?, paramKey: String, headerKey: String) {
        if (params == null) return
        val value = params[paramKey]
        if (value.isNullOrEmpty()) return
        builder.header(headerKey, value)
    }

    @Throws(IOException::class)
    private fun executeRequest(client: OkHttpClient, request: Request): Response {
        try {
            return client.newCall(request).execute()
        } catch (e: IOException) {
            System.err.println("网络请求异常:" + e.message)
            throw e
        }
    }

    private fun processM3u8Content(m3u8Content0: String?, m3u8Url: String, params: Map<String, String>): String {
        if (m3u8Content0 == null) return ""
        var m3u8Content = m3u8Content0
        if (m3u8Content.startsWith("\uFEFF")) m3u8Content = m3u8Content.substring(1)
        val m3u8Lines = m3u8Content.replace("\r\n", "\n").replace('\r', '\n').split("\n".toRegex()).dropLast(1).toTypedArray()
        val processedM3u8 = StringBuilder()
        var nextIsVariant = false

        for (line in m3u8Lines) {
            val item = line.trim()
            if (item.isEmpty()) {
                processedM3u8.append(line).append("\n")
            } else if (item.startsWith("#")) {
                processedM3u8.append(rewriteUriAttributes(m3u8Url, line, params)).append("\n")
                nextIsVariant = item.startsWith("#EXT-X-STREAM-INF")
            } else {
                val type = if (nextIsVariant || isM3u8Url(item)) "m3u8" else "media"
                processedM3u8.append(joinUrl(m3u8Url, line, type, params)).append("\n")
                nextIsVariant = false
            }
        }
        return processedM3u8.toString().replace("\\n\\n", "\n")
    }

    private fun rewriteUriAttributes(base: String, line: String, params: Map<String, String>): String {
        val matcher = URI_ATTR.matcher(line)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val uri = matcher.group(1)
            val type = proxyTypeForUriAttr(line, uri)
            val replacement = "URI=\"" + joinUrl(base, uri, type, params) + "\""
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    private fun proxyTypeForUriAttr(line: String?, uri: String): String {
        val upper = line?.uppercase() ?: ""
        if (upper.startsWith("#EXT-X-KEY") || upper.startsWith("#EXT-X-SESSION-KEY")) return "key"
        if (isM3u8Url(uri) || upper.startsWith("#EXT-X-I-FRAME-STREAM-INF")) return "m3u8"
        return "media"
    }

    private fun isM3u8Url(url: String?): Boolean {
        if (url == null) return false
        var lower = url.lowercase()
        val query = lower.indexOf('?')
        if (query >= 0) lower = lower.substring(0, query)
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u")
    }

    private fun joinUrl(base0: String?, url0: String?, type: String, params: Map<String, String>): String? {
        var base = base0 ?: ""
        var url = url0 ?: ""
        return try {
            url = url.trim()
            if (url.startsWith("data:") || url.startsWith("blob:")) return url
            val baseUri = URI(base.trim())
            val urlUri = URI(url)
            val proxyUrl = ControlManager.get().getAddress(true) + "proxy?go=live&type=" + type + headerQuery(params) + "&url="
            if (url.startsWith("http://") || url.startsWith("https://")) {
                proxyUrl + URLEncoder.encode(urlUri.toString(), "UTF-8")
            } else if (url.startsWith("://")) {
                proxyUrl + URLEncoder.encode(URI(baseUri.scheme + url).toString(), "UTF-8")
            } else if (url.startsWith("//")) {
                proxyUrl + URLEncoder.encode(URI(baseUri.scheme + ":" + url).toString(), "UTF-8")
            } else {
                val resolvedUri = baseUri.resolve(url)
                proxyUrl + URLEncoder.encode(resolvedUri.toString(), "UTF-8")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Throws(Exception::class)
    private fun headerQuery(params: Map<String, String>?): String {
        val sb = StringBuilder()
        appendQueryHeader(sb, params, "User-Agent", "ua")
        appendQueryHeader(sb, params, "user-agent", "ua")
        appendQueryHeader(sb, params, "Referer", "referer")
        appendQueryHeader(sb, params, "referer", "referer")
        appendQueryHeader(sb, params, "Origin", "origin")
        appendQueryHeader(sb, params, "origin", "origin")
        appendQueryHeader(sb, params, "Cookie", "cookie")
        appendQueryHeader(sb, params, "cookie", "cookie")
        return sb.toString()
    }

    @Throws(Exception::class)
    private fun appendQueryHeader(sb: StringBuilder, params: Map<String, String>?, from: String, to: String) {
        if (params == null) return
        if (sb.indexOf("&$to=") >= 0) return
        val value = params[from]
        if (value.isNullOrEmpty()) return
        sb.append("&").append(to).append("=").append(URLEncoder.encode(value, "UTF-8"))
    }

    private fun getMime(type: String?, url: String?, response: Response): String {
        val contentType = response.header("Content-Type")
        if (contentType != null && contentType.isNotEmpty()) return contentType
        if ("key" == type) return "application/octet-stream"
        var lower = url?.lowercase() ?: ""
        val query = lower.indexOf('?')
        if (query >= 0) lower = lower.substring(0, query)
        if (lower.endsWith(".m4s") || lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4"
        if (lower.endsWith(".aac")) return "audio/aac"
        if (lower.endsWith(".vtt")) return "text/vtt"
        return "video/mp2t"
    }

    private fun responseHeaders(response: Response): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        copyResponseHeader(response, headers, "Content-Range")
        copyResponseHeader(response, headers, "Accept-Ranges")
        copyResponseHeader(response, headers, "Content-Length")
        copyResponseHeader(response, headers, "Cache-Control")
        return headers
    }

    private fun copyResponseHeader(response: Response, headers: MutableMap<String, String>, key: String) {
        val value = response.header(key)
        if (value != null && value.isNotEmpty()) headers[key] = value
    }

    private class ResponseInputStream(response: Response) : FilterInputStream(response.body()!!.byteStream()) {
        private val response: Response = response

        @Throws(IOException::class)
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    @Throws(IOException::class)
    fun getRedirectedUrl(url: String): String {
        val base = OkGoHelper.getDefaultClient()
        val client = (base?.newBuilder()
            ?: OkHttpClient.Builder()
                .proxySelector(OkGoHelper.proxySelector())
                .proxyAuthenticator(OkGoHelper.proxyAuthenticator()))
            .followRedirects(false) // 不自动跟随重定向
            .build()

        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            return if (response.isRedirect) { // 判断是否为重定向
                response.header("Location")!!
            } else {
                url
            }
        }
    }

    @Throws(IOException::class)
    fun getM3U8Content(url: String): String {
        val request = Request.Builder().url(url).build()
        val client = OkGoHelper.getItvClient()
        client!!.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body()!!.string()
            } else {
                throw IOException("请求失败,HTTP 状态码: " + response.code())
            }
        }
    }
}
