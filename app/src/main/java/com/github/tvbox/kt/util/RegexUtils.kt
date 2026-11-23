package com.github.tvbox.kt.util

import java.util.HashMap
import java.util.regex.Pattern

/**
 * 正则缓存工具,Kotlin 移植(与旧版 RegexUtils.java 一致)。
 */
object RegexUtils {
    private val patternCache = HashMap<String, Pattern>()

    fun getPattern(regex: String): Pattern {
        val cached = patternCache[regex]
        if (cached != null) return cached
        val pattern = Pattern.compile(regex)
        patternCache[regex] = pattern
        return pattern
    }

    fun getPattern(regex: String, flag: Int): Pattern {
        val cached = patternCache[regex]
        if (cached != null) return cached
        val pattern = Pattern.compile(regex, flag)
        patternCache[regex] = pattern
        return pattern
    }
}
