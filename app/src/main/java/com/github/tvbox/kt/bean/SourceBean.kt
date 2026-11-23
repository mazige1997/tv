package com.github.tvbox.kt.bean

/**
 * 站点源配置,Kotlin 移植(与旧版 SourceBean.java 一致)。
 */
class SourceBean {

    var key: String = ""
        set(value) {
            field = safeString(value)
        }
    var name: String = ""
        set(value) {
            field = safeString(value)
        }
    var api: String = ""
        set(value) {
            field = safeString(value)
        }
    var type: Int = 0 // 0 xml 1 json 3 Spider
    var searchable: Int = 0

    var quickSearch: Int = 0

    var changeable: Int = 1

    var filterable: Int = 0

    var playerUrl: String = ""
        set(value) {
            field = safeString(value)
        }
    var ext: String = ""
        set(value) {
            field = safeString(value)
        }
    var jar: String = ""
        set(value) {
            field = safeString(value)
        }
    var categories: ArrayList<String>? = null // 分类&排序
    var playerType: Int = 0 // 1 ijk 2 exo 10 mxplayer -1 以参数设置页面的为准
    var timeout: Int = 0 // 站点播放信息获取超时,单位秒
    var clickSelector: String = ""
        set(value) {
            field = safeString(value)
        }
    var style: String = ""
        set(value) {
            field = safeString(value)
        }

    val isSearchable: Boolean
        get() = searchable != 0

    val isQuickSearch: Boolean
        get() = quickSearch != 0

    val isChangeable: Boolean
        get() = changeable != 0

    val playTimeoutSeconds: Int
        get() = if (timeout > 0) 5.coerceAtLeast(60.coerceAtMost(timeout)) else 15

    private fun safeString(value: String?): String = value ?: ""

    override fun toString(): String =
        "SourceBean{key=$key, name=$name, type=$type, api=$api, jar=$jar}"
}
