package com.github.tvbox.kt.util

import com.orhanobut.hawk.Hawk

/**
 * 历史/接口线路记录,Kotlin 移植(与旧版 HistoryHelper 一致)。
 */
object HistoryHelper {
    private val hisNumArray = arrayOf(30, 50, 100)
    private const val API_LINE_SPLIT = "\t"

    fun getHistoryNumName(index: Int): String {
        return getHisNum(index).toString() + "条"
    }

    fun getHisNum(index: Int): Int {
        val value = if (index >= 0 && index < hisNumArray.size) hisNumArray[index] else hisNumArray[0]
        return value
    }

    fun setSearchHistory(title: String) {
        // 读取历史记录
        val history = Hawk.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
        history.remove(title)
        history.add(0, title)
        // 最多保留 30 条,超出的删除最后一条
        if (history.size > 30) {
            history.removeAt(history.size - 1)
        }
        Hawk.put(HawkConfig.SEARCH_HISTORY, history)
    }

    fun clearSearchHistory() {
        Hawk.put(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
    }

    fun setLiveApiHistory(value: String) {
        val history = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        if (!history.contains(value)) {
            history.add(0, value)
        }
        if (history.size > 30) {
            history.removeAt(30)
        }
        Hawk.put(HawkConfig.LIVE_API_HISTORY, history)
    }

    fun setApiHistory(value: String) {
        val history = Hawk.get(HawkConfig.API_HISTORY, ArrayList<String>())
        if (!history.contains(value)) {
            history.add(0, value)
        }
        if (history.size > 30) {
            history.removeAt(30)
        }
        Hawk.put(HawkConfig.API_HISTORY, history)
    }

    fun buildApiLine(name: String?, url: String?): String {
        var lineName = name?.trim() ?: ""
        val lineUrl = url?.trim() ?: ""
        if (lineName.isEmpty()) {
            lineName = lineUrl
        }
        return lineName + API_LINE_SPLIT + lineUrl
    }

    fun getApiLineName(value: String?): String {
        if (value == null) return ""
        val splitIndex = value.indexOf(API_LINE_SPLIT)
        val name = if (splitIndex >= 0) value.substring(0, splitIndex) else value
        return name.trim()
    }

    fun getApiLineUrl(value: String?): String {
        if (value == null) return ""
        val splitIndex = value.indexOf(API_LINE_SPLIT)
        val url = if (splitIndex >= 0) value.substring(splitIndex + API_LINE_SPLIT.length) else value
        return url.trim()
    }

    fun isApiLineUrl(url: String?): Boolean {
        if (url == null || url.trim().isEmpty()) return false
        val trimUrl = url.trim()
        val apiLines = Hawk.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
        for (apiLine in apiLines) {
            if (trimUrl == getApiLineUrl(apiLine)) {
                return true
            }
        }
        return false
    }

    fun isApiLineSource(url: String?): Boolean {
        if (url == null || url.trim().isEmpty()) return false
        val source = Hawk.get(HawkConfig.API_LINE_SOURCE, "")
        return url.trim() == source
    }

    fun isApiLineHistory(url: String?): Boolean {
        return isApiLineSource(url) || isApiLineUrl(url)
    }

    fun clearApiLineList() {
        Hawk.put(HawkConfig.API_LINE_LIST, ArrayList<String>())
        Hawk.put(HawkConfig.API_LINE_SOURCE, "")
    }
}
