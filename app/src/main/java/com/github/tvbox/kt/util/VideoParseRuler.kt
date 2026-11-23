package com.github.tvbox.kt.util

import android.net.Uri
import java.util.ArrayList
import java.util.HashMap
import java.util.regex.Pattern

/**
 * 视频嗅探规则(hosts 规则/过滤/正则/脚本),Kotlin 移植(与旧版 VideoParseRuler 一致)。
 */
object VideoParseRuler {

    private val HOSTS_RULE = HashMap<String, ArrayList<ArrayList<String>>>()
    private val HOSTS_FILTER = HashMap<String, ArrayList<ArrayList<String>>>()
    private val HOSTS_REGEX = HashMap<String, ArrayList<String>>()
    private val HOSTS_SCRIPT = HashMap<String, ArrayList<String>>()

    fun clearRule() {
        HOSTS_RULE.clear()
        HOSTS_FILTER.clear()
        HOSTS_REGEX.clear()
        HOSTS_SCRIPT.clear()
    }

    fun addHostRule(host: String, rule: ArrayList<String>) {
        val rules = ArrayList<ArrayList<String>>()
        if (HOSTS_RULE[host] != null && HOSTS_RULE[host]!!.isNotEmpty()) {
            rules.addAll(HOSTS_RULE[host]!!)
        }
        rules.add(rule)
        HOSTS_RULE[host] = rules
    }

    fun getHostRules(host: String?): ArrayList<ArrayList<String>>? {
        return if (HOSTS_RULE.containsKey(host)) {
            HOSTS_RULE[host]
        } else null
    }

    fun addHostFilter(host: String, rule: ArrayList<String>) {
        val filters = ArrayList<ArrayList<String>>()
        if (HOSTS_FILTER[host] != null && HOSTS_FILTER[host]!!.isNotEmpty()) {
            filters.addAll(HOSTS_FILTER[host]!!)
        }
        filters.add(rule)
        HOSTS_FILTER[host] = filters
    }

    fun getHostFilters(host: String?): ArrayList<ArrayList<String>>? {
        return if (HOSTS_FILTER.containsKey(host)) {
            HOSTS_FILTER[host]
        } else null
    }

    fun addHostRegex(host: String, regex: ArrayList<String>?) {
        if (regex == null || regex.isEmpty()) return
        val temp = ArrayList<String>()
        if (HOSTS_REGEX[host] != null && HOSTS_REGEX[host]!!.isNotEmpty()) temp.addAll(HOSTS_REGEX[host]!!)
        temp.addAll(regex)
        HOSTS_REGEX[host] = temp
    }

    fun getHostsRegex(): HashMap<String, ArrayList<String>> {
        return HOSTS_REGEX
    }

    fun checkIsVideoForParse(webUrl: String?, url: String?): Boolean {
        try {
            var isVideo = DefaultConfig.isVideoFormat(url ?: "")
            if (HOSTS_RULE.isNotEmpty() && !isVideo && webUrl != null) {
                val uri = Uri.parse(webUrl)
                isVideo = if (getHostRules(uri.host) != null) {
                    checkVideoForOneHostRules(uri.host, url)
                } else {
                    checkVideoForOneHostRules("*", url)
                }
            }
            return isVideo
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun checkVideoForOneHostRules(host: String?, url: String?): Boolean {
        var isVideo = false
        val hostRules = getHostRules(host)
        if (hostRules != null && hostRules.size > 0) {
            var isVideoRuleCheck = false
            for (i in hostRules.indices) {
                var checkIsVideo = true
                if (hostRules[i].isNotEmpty()) {
                    for (j in hostRules[i].indices) {
                        val onePattern = RegexUtils.getPattern(hostRules[i][j])
                        if (!onePattern.matcher(url ?: "").find()) {
                            checkIsVideo = false
                            break
                        }
                        LOG.i("echo-VIDEO RULE:" + hostRules[i][j])
                    }
                } else {
                    checkIsVideo = false
                }
                if (checkIsVideo) {
                    isVideoRuleCheck = true
                    break
                }
            }
            if (isVideoRuleCheck) {
                isVideo = true
            }
        }
        return isVideo
    }

    fun isFilter(webUrl: String?, url: String?): Boolean {
        try {
            var isFilter = false
            if (HOSTS_FILTER.isNotEmpty() && webUrl != null) {
                val uri = Uri.parse(webUrl)
                if (getHostFilters(uri.host) != null) {
                    isFilter = checkIsFilterForOneHostRules(uri.host, url)
                }
            }
            return isFilter
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun checkIsFilterForOneHostRules(host: String?, url: String?): Boolean {
        var isFilter = false
        val hostFilters = getHostFilters(host)
        if (hostFilters != null && hostFilters.size > 0) {
            var isFilterRuleCheck = false
            for (i in hostFilters.indices) {
                var checkIsFilter = true
                if (hostFilters[i].isNotEmpty()) {
                    for (j in hostFilters[i].indices) {
                        val onePattern = RegexUtils.getPattern(hostFilters[i][j])
                        if (!onePattern.matcher(url ?: "").find()) {
                            checkIsFilter = false
                            break
                        }
                        LOG.i("echo-FILTER RULE:" + hostFilters[i][j])
                    }
                } else {
                    checkIsFilter = false
                }
                if (checkIsFilter) {
                    isFilterRuleCheck = true
                    break
                }
            }
            if (isFilterRuleCheck) {
                isFilter = true
            }
        }
        return isFilter
    }

    fun addHostScript(host: String, script: ArrayList<String>?) {
        if (script == null || script.isEmpty()) return
        val temp = ArrayList<String>()
        if (HOSTS_SCRIPT[host] != null && HOSTS_SCRIPT[host]!!.isNotEmpty()) temp.addAll(HOSTS_SCRIPT[host]!!)
        temp.addAll(script)
        HOSTS_SCRIPT[host] = temp
    }

    fun getHostScript(url: String): String {
        for (entry in HOSTS_SCRIPT.entries) {
            val host = entry.key
            if (url.contains(host)) {
                val list = entry.value
                if (list != null && list.isNotEmpty()) {
                    return list[0]
                }
            }
        }
        return ""
    }
}
