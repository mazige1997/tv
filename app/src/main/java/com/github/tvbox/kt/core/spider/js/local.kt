package com.github.tvbox.kt.core.spider.js

import androidx.annotation.Keep
import com.orhanobut.hawk.Hawk
import com.whl.quickjs.wrapper.Function

/** JS 爬虫本地存储桥(local),Kotlin 移植(与旧版 local 一致)。 */
class local {
    @Keep
    @Function
    fun delete(str: String?, str2: String?) {
        try {
            Hawk.delete("jsRuntime_" + str + "_" + str2)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Keep
    @Function
    fun get(str: String?, str2: String?): String? {
        return try {
            Hawk.get("jsRuntime_" + str + "_" + str2, "")
        } catch (e: Exception) {
            Hawk.delete(str)
            str2
        }
    }

    @Keep
    @Function
    fun set(str: String?, str2: String?, str3: String?) {
        try {
            Hawk.put("jsRuntime_" + str + "_" + str2, str3)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
