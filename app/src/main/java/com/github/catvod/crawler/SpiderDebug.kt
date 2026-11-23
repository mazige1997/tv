package com.github.catvod.crawler

import android.util.Log

/** 爬虫调试日志,Kotlin 移植(与旧版 SpiderDebug 一致)。 */
object SpiderDebug {
    fun log(th: Throwable?) {
        try {
            Log.d("SpiderLog", th?.message, th)
        } catch (th1: Throwable) {
        }
    }

    fun log(msg: String?) {
        try {
            Log.d("SpiderLog", msg ?: "null")
        } catch (th1: Throwable) {
        }
    }

    fun ec(i: Int): String {
        return ""
    }
}
