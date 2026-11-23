package com.github.tvbox.kt.bean

import android.text.TextUtils

/** 弹幕搜索结果,Kotlin 移植(与旧版 DanmuSearchResult.java 一致)。 */
class DanmuSearchResult(private val name: String?, private val url: String?, private val builtIn: Boolean) {
    fun getName(): String = if (TextUtils.isEmpty(name)) getUrl() else name!!

    fun getUrl(): String = url ?: ""

    fun isBuiltIn(): Boolean = builtIn
}
