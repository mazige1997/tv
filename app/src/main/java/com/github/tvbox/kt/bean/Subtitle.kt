package com.github.tvbox.kt.bean

/** 字幕条目,Kotlin 移植(与旧版 Subtitle.java 一致)。 */
class Subtitle {
    var name: String? = null
    var url: String? = null
    private var isZip = false

    fun getIsZip(): Boolean = isZip

    fun setIsZip(zip: Boolean) {
        isZip = zip
    }

    override fun toString(): String =
        "Subtitle{name='$name', url='$url', isZip=$isZip}"
}
