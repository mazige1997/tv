package com.github.tvbox.kt.bean

import com.google.gson.JsonObject
import java.util.HashMap
import java.util.Objects

/**
 * 直播频道条目,Kotlin 移植(与旧版 LiveChannelItem.java 一致,含多线路与回看)。
 */
class LiveChannelItem {
    /** 频道索引号 */
    var channelIndex = 0

    /** 频道序号 */
    var channelNum = 0

    private var channelName: String? = null
    private var channelLogo: String? = null
    private var channelEpg: String? = null
    private var channelUa: String? = null
    private var channelClick: String? = null
    private var channelFormat: String? = null
    private var channelOrigin: String? = null
    private var channelReferer: String? = null
    private var channelTvgId: String? = null
    private var channelTvgName: String? = null
    private var channelCatchup: JsonObject? = null
    private var channelHeader: Map<String, String>? = null
    private var channelParse: Int? = null
    private var channelSourceNames: ArrayList<String>? = null
    private var channelUrls: ArrayList<String>? = null

    @JvmField
    var sourceIndex = 0

    @JvmField
    var sourceNum = 0

    @JvmField
    var include_back = false

    fun setinclude_back(include_back: Boolean) {
        this.include_back = include_back
    }

    fun getinclude_back(): Boolean = include_back

    fun setChannelName(channelName: String?) {
        this.channelName = channelName
    }

    fun getChannelName(): String = channelName ?: ""

    fun setChannelLogo(channelLogo: String?) {
        this.channelLogo = channelLogo
    }

    fun getChannelLogo(): String = channelLogo ?: ""

    fun setChannelEpg(channelEpg: String?) {
        this.channelEpg = channelEpg
    }

    fun getChannelEpg(): String = channelEpg ?: ""

    fun setChannelUa(channelUa: String?) {
        this.channelUa = channelUa
    }

    fun getChannelUa(): String = channelUa ?: ""

    fun setChannelClick(channelClick: String?) {
        this.channelClick = channelClick
    }

    fun getChannelClick(): String = channelClick ?: ""

    fun setChannelFormat(channelFormat: String?) {
        this.channelFormat = channelFormat
    }

    fun getChannelFormat(): String = channelFormat ?: ""

    fun setChannelOrigin(channelOrigin: String?) {
        this.channelOrigin = channelOrigin
    }

    fun getChannelOrigin(): String = channelOrigin ?: ""

    fun setChannelReferer(channelReferer: String?) {
        this.channelReferer = channelReferer
    }

    fun getChannelReferer(): String = channelReferer ?: ""

    fun setChannelTvgId(channelTvgId: String?) {
        this.channelTvgId = channelTvgId
    }

    fun getChannelTvgId(): String = channelTvgId ?: ""

    fun setChannelTvgName(channelTvgName: String?) {
        this.channelTvgName = channelTvgName
    }

    fun getChannelTvgName(): String = channelTvgName ?: ""

    fun setChannelCatchup(channelCatchup: JsonObject?) {
        this.channelCatchup = channelCatchup
    }

    fun getChannelCatchup(): JsonObject = channelCatchup ?: JsonObject()

    fun hasCatchup(): Boolean = channelCatchup != null && channelCatchup!!.entrySet().isNotEmpty()

    fun setChannelHeader(channelHeader: Map<String, String>?) {
        this.channelHeader = channelHeader
    }

    fun getChannelHeader(): Map<String, String> = channelHeader ?: HashMap()

    fun setChannelParse(channelParse: Int?) {
        this.channelParse = channelParse
    }

    fun getChannelParse(): Int = channelParse ?: 0

    fun getHeaders(): Map<String, String> {
        val headers: MutableMap<String, String> = HashMap(getChannelHeader())
        if (getChannelUa().isNotEmpty()) headers["User-Agent"] = getChannelUa()
        if (getChannelOrigin().isNotEmpty()) headers["Origin"] = getChannelOrigin()
        if (getChannelReferer().isNotEmpty()) headers["Referer"] = getChannelReferer()
        return headers
    }

    fun getChannelUrls(): ArrayList<String>? = channelUrls

    fun setChannelUrls(channelUrls: ArrayList<String>?) {
        this.channelUrls = channelUrls
        sourceNum = channelUrls?.size ?: 0
    }

    fun preSource() {
        sourceIndex--
        if (sourceIndex < 0) sourceIndex = sourceNum - 1
    }

    fun nextSource() {
        sourceIndex++
        if (sourceIndex == sourceNum) sourceIndex = 0
    }

    fun setSourceIndex(sourceIndex: Int) {
        this.sourceIndex = sourceIndex
    }

    fun getSourceIndex(): Int = sourceIndex

    fun getUrl(): String = channelUrls!![sourceIndex]

    fun getSourceNum(): Int = sourceNum

    fun getChannelSourceNames(): ArrayList<String>? = channelSourceNames

    fun setChannelSourceNames(channelSourceNames: ArrayList<String>?) {
        this.channelSourceNames = channelSourceNames
    }

    fun getSourceName(): String = channelSourceNames!![sourceIndex]

    fun isEmptyCatchup(): Boolean = channelCatchup == null || channelCatchup!!.entrySet().isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as LiveChannelItem
        return Objects.equals(channelName, that.channelName) &&
            Objects.equals(channelUrls!![sourceIndex], that.getUrl())
    }

    override fun hashCode(): Int = Objects.hash(channelName, channelUrls!![sourceIndex])
}
