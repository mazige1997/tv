package com.github.tvbox.kt.bean

import android.util.Base64
import com.github.tvbox.kt.util.DefaultConfig

/**
 * 解析器配置,Kotlin 移植(与旧版 ParseBean.java 一致)。
 */
class ParseBean {

    var name: String = ""
    var url: String = ""
        get() = DefaultConfig.checkReplaceProxy(field)
        set(value) {
            field = value
        }
    var ext: String = ""

    var type: Int = 0 // 0 普通嗅探 1 json 2 Json扩展 3 聚合

    var isDefault: Boolean = false
        private set

    fun setDefault(b: Boolean) {
        isDefault = b
    }

    fun mixUrl(): String {
        if (!ext.isEmpty()) {
            val idx = url.indexOf("?")
            if (idx > 0) {
                val encoded = Base64.encodeToString(
                    ext.toByteArray(), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
                )
                return url.substring(0, idx + 1) + "cat_ext=" + encoded + "&" + url.substring(idx + 1)
            }
        }
        return url
    }
}
