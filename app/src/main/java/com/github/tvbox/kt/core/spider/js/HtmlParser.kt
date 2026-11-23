package com.github.tvbox.kt.core.spider.js

import android.text.TextUtils
import com.github.tvbox.kt.core.NativeCore
import com.github.tvbox.kt.util.StringUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.MalformedURLException
import java.net.URL
import java.util.ArrayList
import java.util.Arrays
import java.util.regex.Pattern

/**
 * JS 爬虫 DOM 选择器引擎(pdfh/pdfa),Kotlin 移植(与旧版 crawler.js.HtmlParser 一致)。
 */
object HtmlParser {
    private var pdfh_html = ""
    private var pdfa_html = ""
    private val p: Pattern = Pattern.compile("url\\((.*?)\\)", Pattern.MULTILINE or Pattern.DOTALL)
    private val NOADD_INDEX: Pattern =
        Pattern.compile(":eq|:lt|:gt|:first|:last|^body$|^#") // 不自动加eq下标索引
    private val URLJOIN_ATTR: Pattern = Pattern.compile(
        "(url|src|href|-original|-src|-play|-url|style)$",
        Pattern.MULTILINE or Pattern.CASE_INSENSITIVE
    ) // 需要自动urljoin的属性
    private val SPECIAL_URL: Pattern = Pattern.compile(
        "^(ftp|magnet|thunder|ws):",
        Pattern.MULTILINE or Pattern.CASE_INSENSITIVE
    ) // 过滤特殊链接,不走urlJoin
    private var pdfh_doc: Document? = null
    private var pdfa_doc: Document? = null

    fun joinUrl(parent: String?, child: String?): String {
        if (StringUtils.isEmpty(parent)) {
            return child ?: ""
        }
        var q = parent
        try {
            // 优先使用 Rust 实现(与 java.net.URL 语义一致);失败回退 JDK
            if (NativeCore.available) {
                return NativeCore.joinUrl(parent!!, child ?: "")
            }
            val url = URL(URL(parent), child)
            q = url.toExternalForm()
        } catch (e: MalformedURLException) {
            e.printStackTrace()
        }
        return q ?: ""
    }

    class Painfo {
        var nparse_rule: String = ""
        var nparse_index: Int = 0
        var excludes: List<String>? = null
    }

    private fun getParseInfo(nparse: String): Painfo {
        /*
         根据传入的单规则获取 parse规则,索引位置,排除列表
         */
        val painfo = Painfo()
        painfo.nparse_rule = nparse
        if (nparse.contains(":eq")) {
            painfo.nparse_rule = nparse.split(":".toRegex())[0]
            var nparse_pos = nparse.split(":".toRegex())[1]
            if (painfo.nparse_rule.contains("--")) {
                val rules = painfo.nparse_rule.split("--".toRegex()).toTypedArray()
                painfo.excludes = ArrayList(Arrays.asList(*rules))
                (painfo.excludes as ArrayList).removeAt(0)
                painfo.nparse_rule = rules[0]
            } else if (nparse_pos.contains("--")) {
                val rules = nparse_pos.split("--".toRegex()).toTypedArray()
                painfo.excludes = ArrayList(Arrays.asList(*rules))
                (painfo.excludes as ArrayList).removeAt(0)
                nparse_pos = rules[0]
            }
            painfo.nparse_index = try {
                nparse_pos.replace("eq(", "").replace(")", "").toInt()
            } catch (e1: Exception) {
                0
            }
        } else {
            if (nparse.contains("--")) {
                val rules = painfo.nparse_rule.split("--".toRegex()).toTypedArray()
                painfo.excludes = ArrayList(Arrays.asList(*rules))
                (painfo.excludes as ArrayList).removeAt(0)
                painfo.nparse_rule = rules[0]
            }
        }
        return painfo
    }

    fun isIndex(str: String?): Boolean {
        if (StringUtils.isEmpty(str)) {
            return false
        }
        for (str2 in arrayOf(":eq", ":lt", ":gt", ":first", ":last", "body", "#")) {
            if (str!!.contains(str2)) {
                if (str2 == "body" || str2 == "#") {
                    return str.startsWith(str2)
                }
                return true
            }
        }
        return false
    }

    fun isUrl(str: String?): Boolean {
        if (StringUtils.isEmpty(str)) {
            return false
        }
        for (str2 in arrayOf("url", "src", "href", "-original", "-play")) {
            if (str!!.contains(str2)) {
                return true
            }
        }
        return false
    }

    private fun parseHikerToJq(parse: String, first: Boolean): String {
        /*
         海阔解析表达式转原生表达式,自动补eq,如果传了first就最后一个也取eq(0)
         */
        var parse = parse
        // 不自动加eq下标索引
        if (parse.contains("&&")) {
            val parses = parse.split("&&".toRegex()).toTypedArray()
            val newParses = ArrayList<String>()
            for (i in parses.indices) {
                val pss = parses[i].split(" ".toRegex()).toTypedArray()
                val ps = pss[pss.size - 1]
                val m = NOADD_INDEX.matcher(ps)
                if (!m.find()) {
                    if (!first && i >= parses.size - 1) {
                        newParses.add(parses[i])
                    } else {
                        newParses.add(parses[i] + ":eq(0)")
                    }
                } else {
                    newParses.add(parses[i])
                }
            }
            parse = TextUtils.join(" ", newParses)
        } else {
            val pss = parse.split(" ".toRegex()).toTypedArray()
            val ps = pss[pss.size - 1]
            val m = NOADD_INDEX.matcher(ps)
            if (!m.find() && first) {
                parse = parse + ":eq(0)"
            }
        }
        return parse
    }

    fun parseDomForUrl(html: String, rule: String, add_url: String?): String {
        if (pdfh_html != html) {
            pdfh_html = html
            pdfh_doc = Jsoup.parse(html)
        }
        val doc = pdfh_doc!!
        if (rule == "body&&Text" || rule == "Text") {
            return doc.text()
        } else if (rule == "body&&Html" || rule == "Html") {
            return doc.html()
        }
        var option = ""
        var rule = rule
        if (rule.contains("&&")) {
            val rs = rule.split("&&".toRegex()).toTypedArray()
            option = rs[rs.size - 1]
            val excludes = ArrayList(Arrays.asList(*rs))
            excludes.removeAt(rs.size - 1)
            rule = TextUtils.join("&&", excludes)
        }
        rule = parseHikerToJq(rule, true)
        val parses = rule.split(" ".toRegex()).toTypedArray()
        var ret = Elements()
        for (nparse in parses) {
            ret = parseOneRule(doc, nparse, ret)
            if (ret.isEmpty()) {
                return ""
            }
        }
        var result: String
        if (StringUtils.isNotEmpty(option)) {
            result = when (option) {
                "Text" -> ret.text()
                "Html" -> ret.html()
                else -> {
                    var value = ret.attr(option)
                    if (option.lowercase().contains("style") && value.contains("url(")) {
                        val m = p.matcher(value)
                        if (m.find()) {
                            value = m.group(1)
                        }
                        if (StringUtils.isNotEmpty(value)) {
                            value = value.replace("^['|\"](.*)['|\"]$".toRegex(), "$1")
                        }
                    }
                    if (StringUtils.isNotEmpty(value) && StringUtils.isNotEmpty(add_url)) {
                        // 需要自动urljoin的属性
                        val m = URLJOIN_ATTR.matcher(option)
                        val n = SPECIAL_URL.matcher(value)
                        if (m.find() && !n.find()) {
                            value = if (value.contains("http")) {
                                value.substring(value.indexOf("http"))
                            } else {
                                joinUrl(add_url, value)
                            }
                        }
                    }
                    value
                }
            }
        } else {
            result = ret.outerHtml()
        }
        return result
    }

    fun parseDomForArray(html: String, rule: String): List<String> {
        if (pdfa_html != html) {
            pdfa_html = html
            pdfa_doc = Jsoup.parse(html)
        }
        val doc = pdfa_doc!!
        val jqRule = parseHikerToJq(rule, false)
        val parses = jqRule.split(" ".toRegex()).toTypedArray()
        var ret = Elements()
        for (pars in parses) {
            ret = parseOneRule(doc, pars, ret)
            if (ret.isEmpty()) {
                return ArrayList()
            }
        }
        val eleHtml = ArrayList<String>()
        for (i in ret.indices) {
            val element1: Element = ret[i]
            eleHtml.add(element1.outerHtml())
        }
        return eleHtml
    }

    private fun parseOneRule(doc: Document, nparse: String, retIn: Elements): Elements {
        var ret = retIn
        val painfo = getParseInfo(nparse)
        ret = if (ret.isEmpty()) {
            doc.select(painfo.nparse_rule)
        } else {
            ret.select(painfo.nparse_rule)
        }
        if (nparse.contains(":eq")) {
            ret = if (painfo.nparse_index < 0) {
                ret.eq(ret.size + painfo.nparse_index)
            } else {
                ret.eq(painfo.nparse_index)
            }
        }
        if (painfo.excludes != null && !ret.isEmpty()) {
            ret = ret.clone() // 克隆一个,免得直接remove会影响doc的缓存
            for (i in painfo.excludes!!.indices) {
                ret.select(painfo.excludes!![i]).remove()
            }
        }
        return ret
    }

    fun parseDomForList(html: String, p1: String, list_text: String, list_url: String, add_url: String?): List<String> {
        if (pdfa_html != html) {
            pdfa_html = html
            pdfa_doc = Jsoup.parse(html)
        }
        val doc = pdfa_doc!!
        val jqP1 = parseHikerToJq(p1, false)
        val parses = jqP1.split(" ".toRegex()).toTypedArray()
        var ret = Elements()
        for (pars in parses) {
            ret = parseOneRule(doc, pars, ret)
            if (ret.isEmpty()) {
                return ArrayList()
            }
        }
        val newVodList = ArrayList<String>()
        for (i in ret.indices) {
            val it: String = ret[i].outerHtml()
            newVodList.add(parseDomForUrl(it, list_text, "").trim { it <= ' ' } + '$' + parseDomForUrl(it, list_url, add_url))
        }
        return newVodList
    }
}
