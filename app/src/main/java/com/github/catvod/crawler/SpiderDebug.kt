package com.github.catvod.crawler

import android.util.Log

/**
 * 爬虫调试日志 — 必须与旧版 com.github.catvod.crawler.SpiderDebug 的 JVM ABI 一致。
 *
 * 原版是 public class + 三个 public static 方法；第三方 jar 静态调用
 * SpiderDebug.log(...) / SpiderDebug.ec(...)。必须用 @JvmStatic 生成静态签名，
 * 不能用 Kotlin object（会生成 INSTANCE.log()，导致 NoSuchMethodError）。
 */
class SpiderDebug {

    companion object {

        @JvmStatic
        fun log(th: Throwable?) {
            try {
                Log.d("SpiderLog", th?.message, th)
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun log(msg: String?) {
            try {
                Log.d("SpiderLog", msg ?: "null")
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun ec(i: Int): String = ""
    }
}
