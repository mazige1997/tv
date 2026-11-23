package com.github.tvbox.kt.bean

/** 字幕搜索结果集,Kotlin 移植(与旧版 SubtitleData.java 一致)。 */
class SubtitleData {
    private var isNew: Boolean? = null
    private var subtitleList: List<Subtitle>? = null
    private var isZip: Boolean? = null

    fun getIsNew(): Boolean? = isNew

    fun getSubtitleList(): List<Subtitle>? = subtitleList

    fun getIsZip(): Boolean? = isZip

    fun setIsNew(isNew: Boolean?) {
        this.isNew = isNew
    }

    fun setSubtitleList(subtitle: List<Subtitle>?) {
        this.subtitleList = subtitle
    }

    fun setIsZip(zip: Boolean?) {
        isZip = zip
    }

    override fun toString(): String =
        "SubtitleData{isNew='$isNew'}"
}
