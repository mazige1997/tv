package com.github.tvbox.kt.bean

import com.github.tvbox.kt.util.RegexUtils
import java.io.Serializable
import java.util.Collections
import java.util.LinkedHashMap

/**
 * 影片详情信息,Kotlin 移植(与旧版 VodInfo.java 一致,含智能集序判定)。
 */
class VodInfo : Serializable {
    var last: String? = null // 时间
    var id: String? = null // 内容id
    var tid: Int = 0 // 父级id
    var name: String? = null // 影片名称
    var type: String? = null // 类型名称
    var dt: String? = null // 视频分类
    var pic: String? = null // 图片
    var lang: String? = null // 语言
    var area: String? = null // 地区
    var year: Int = 0
    var state: String? = null
    var note: String? = null // 描述集数或者影片信息
    var actor: String? = null // 演员
    var director: String? = null // 导演
    var seriesFlags: ArrayList<VodSeriesFlag>? = null
    var seriesMap: LinkedHashMap<String, List<VodSeries>>? = null
    var des: String? = null
    var playFlag: String? = null
    var playIndex: Int = 0
    var playNote: String = ""
    var sourceKey: String? = null
    var playerCfg: String = ""
    var reverseSort: Boolean = false

    fun setVideo(video: Movie.Video) {
        last = video.last
        id = video.id
        tid = video.tid
        name = video.name
        type = video.type
        pic = video.pic
        lang = video.lang
        area = video.area
        year = video.year
        state = video.state
        note = video.note
        actor = video.actor
        director = video.director
        des = video.des
        val infoList = video.urlBean?.infoList
        if (!infoList.isNullOrEmpty()) {
            val tempSeriesMap = LinkedHashMap<String, List<VodSeries>>()
            val flags = ArrayList<VodSeriesFlag>()
            for (urlInfo in infoList) {
                if (!urlInfo.beanList.isNullOrEmpty()) {
                    val seriesList = ArrayList<VodSeries>()
                    for (infoBean in urlInfo.beanList!!) {
                        seriesList.add(VodSeries(infoBean.name, infoBean.url))
                    }
                    val flagName = urlInfo.flag ?: ""
                    tempSeriesMap[flagName] = seriesList
                    flags.add(VodSeriesFlag(flagName))
                }
            }
            // Kotlin 移植遗漏:详情页多处对 seriesFlags 使用 !! 解引用,
            // 这里必须把线路列表赋给字段,否则进入详情必现 NullPointerException。
            seriesFlags = flags
            seriesMap = LinkedHashMap()
            for (flag in flags) {
                val list: List<VodSeries> = tempSeriesMap[flag.name] ?: continue
                if (flags.size <= 5) {
                    if (isReverse(list)) Collections.reverse(list)
                }
                seriesMap!![flag.name ?: ""] = list
            }
        } else {
            seriesFlags = ArrayList()
            seriesMap = LinkedHashMap()
        }
    }

    private fun extractNumber(name: String?): Int {
        val matcher = RegexUtils.getPattern("\\d+").matcher(name ?: "")
        if (!matcher.find()) return 0
        return matcher.group()?.toIntOrNull() ?: 0
    }

    private fun isReverse(list: List<VodSeries>): Boolean {
        var ascCount = 0
        var descCount = 0
        // 比较最多前 6 个相邻元素对
        val limit = minOf(list.size - 1, 6)
        for (i in 0 until limit) {
            val current = extractNumber(list[i].name)
            val next = extractNumber(list[i + 1].name)
            if (current < next) {
                ascCount++
                if (ascCount == 2) return false
            } else if (current > next) {
                descCount++
                if (descCount == 2) return true
            }
        }
        return false
    }

    fun reverse() {
        val flags = seriesMap?.keys ?: return
        for (flag in flags) {
            Collections.reverse(seriesMap!![flag])
        }
    }

    class VodSeriesFlag(var name: String?) : Serializable {
        @JvmField
        var selected = false
    }

    class VodSeries(var name: String?, var url: String?) : Serializable {
        @JvmField
        var selected = false
    }
}
