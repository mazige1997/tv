package com.github.tvbox.kt.util

import java.lang.reflect.Array

/**
 * 字符串工具,Kotlin 移植(与旧版 StringUtils 一致)。
 */
object StringUtils {

    fun isEmpty(str: CharSequence?): Boolean {
        return str == null || str.length == 0
    }

    fun isNotEmpty(str: CharSequence?): Boolean {
        return !isEmpty(str)
    }

    fun isNull(obj: Any?): Boolean {
        return obj == null
    }

    fun isNotNull(obj: Any?): Boolean {
        return !isNull(obj)
    }

    fun isEmpty(obj: Any?): Boolean {
        if (obj == null) return true
        else if (obj is CharSequence) return obj.length == 0
        else if (obj is Collection<*>) return obj.isEmpty()
        else if (obj is Map<*, *>) return obj.isEmpty()
        else if (obj.javaClass.isArray) return Array.getLength(obj) == 0
        return false
    }

    fun isNotEmpty(obj: Any?): Boolean {
        return !isEmpty(obj)
    }

    private val U2028 = String(byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0xA8.toByte()))
    private val U2029 = String(byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0xA9.toByte()))

    /**
     * Escape JavaString string
     */
    fun escapeJavaScriptString(line: String): String {
        val sb = StringBuilder()
        for (i in 0 until line.length) {
            val c = line[i]
            when (c) {
                '"', '\'', '\\' -> {
                    sb.append('\\')
                    sb.append(c)
                }
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        // 与 Java 版一致:U2028 常量本身就是 \u2028 字符,此处替换是恒等操作
        return sb.toString()
            .replace(U2028, " ")
            .replace(U2029, " ")
    }

    fun getBaseUrl(url: String?): String? {
        if (isEmpty(url)) {
            return url
        }
        val baseUrls = url!!.replace("http://", "").replace("https://", "")
        val baseUrl2 = baseUrls.split("/".toRegex()).toTypedArray()[0]
        val baseUrl: String
        if (url.startsWith("https")) {
            baseUrl = "https://$baseUrl2"
        } else {
            baseUrl = "http://$baseUrl2"
        }
        return baseUrl
    }

    fun arrayToString(list: kotlin.Array<String>?, fromIndex: Int, cha: String): String {
        return arrayToString(list, fromIndex, if (list == null) 0 else list.size, cha)
    }

    fun arrayToString(list: kotlin.Array<String>?, fromIndex: Int, endIndex: Int, cha: String): String {
        val builder = StringBuilder()
        if (list == null || list.size <= fromIndex) {
            return ""
        } else if (list.size <= 1) {
            return list[0]
        } else {
            builder.append(list[fromIndex])
        }
        var i = 1 + fromIndex
        while (i < list.size && i < endIndex) {
            builder.append(cha).append(list[i])
            i++
        }
        return builder.toString()
    }

    fun listToString(list: List<String>?, cha: String): String {
        val builder = StringBuilder()
        if (list == null || list.size <= 0) {
            return ""
        } else if (list.size <= 1) {
            return list[0]
        } else {
            builder.append(list[0])
        }
        for (i in 1 until list.size) {
            builder.append(cha).append(list[i])
        }
        return builder.toString()
    }

    fun listToString(list: List<String>?, fromIndex: Int, cha: String): String {
        val builder = StringBuilder()
        if (list == null || list.size <= fromIndex) {
            return ""
        } else if (list.size <= 1) {
            return list[0]
        } else {
            builder.append(list[fromIndex])
        }
        for (i in fromIndex + 1 until list.size) {
            builder.append(cha).append(list[i])
        }
        return builder.toString()
    }

    fun listToString(list: List<String>?): String {
        return listToString(list, "&&")
    }

    fun isBlank(text: String?): Boolean {
        return trim(text).length == 0
    }

    fun trimBlanks(str: String?): String? {
        if (str == null || str.isEmpty()) {
            return str
        }
        val len = str.length
        var st = 0
        while (st < len && (str[st] == '\n' || str[st] == '\r' || str[st] == '' || str[st] == '\t')) {
            st++
        }
        var end = len
        while (st < end && (str[end - 1] == '\n' || str[end - 1] == '\r' || str[end - 1] == '' || str[end - 1] == '\t')) {
            end--
        }
        return if ((st > 0) || (end < str.length)) str.substring(st, end) else str
    }

    fun trim(string: String?): String {
        if (string == null || string.isEmpty() || " " == string) return ""
        var start = 0
        val len = string.length
        var end = len - 1
        while (start < end && (string[start] <= ' ' || string[start] == '　')) {
            ++start
        }
        while (start < end && (string[end] <= ' ' || string[end] == '　')) {
            --end
        }
        return if ((start > 0) || (end < len)) string.substring(start, end) else string
    }

    fun isJsonType(text: String?): Boolean {
        var result = false
        if (isNotEmpty(text)) {
            val trimmed = trim(text)
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                result = true
            } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                result = true
            }
        }
        return result
    }

    fun isJsonObject(text: String?): Boolean {
        var result = false
        if (isNotEmpty(text)) {
            val trimmed = trim(text)
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                result = true
            }
        }
        return result
    }

    fun isJsonArray(text: String?): Boolean {
        var result = false
        if (isNotEmpty(text)) {
            val trimmed = trim(text)
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                result = true
            }
        }
        return result
    }
}
