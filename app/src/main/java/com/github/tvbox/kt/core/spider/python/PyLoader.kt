package com.github.tvbox.kt.core.spider.python

import com.github.tvbox.kt.core.spider.Spider
import com.github.tvbox.kt.core.spider.SpiderNull

/**
 * python 爬虫加载器接口,Kotlin 移植(与旧版 IPyLoader 一致)。
 *
 * 说明:重构决策为"电视版跳过 python 爬虫(Chaquopy)",此接口保留为扩展位,
 * 默认实现 [PyLoader] 返回空爬虫;如需启用 python 爬虫,可替换实现并引入 pyramid 模块。
 */
interface IPyLoader {
    fun clear()
    fun setConfig(jsonStr: String?)
    fun setRecentPyKey(key: String?)
    fun getSpider(key: String?, cls: String?, ext: String?): Spider
    fun proxyInvoke(params: Map<String, String>?): Array<Any?>?
    fun proxyInvoke(params: Map<String, String>?, key: String?): Array<Any?>?
}

/** 默认实现:python 爬虫不可用,返回空爬虫(与旧版 normal flavor 的 pyLoader 行为一致)。 */
class PyLoader : IPyLoader {
    override fun clear() {}

    override fun setConfig(jsonStr: String?) {}

    override fun setRecentPyKey(key: String?) {}

    override fun getSpider(key: String?, cls: String?, ext: String?): Spider = SpiderNull()

    override fun proxyInvoke(params: Map<String, String>?): Array<Any?>? = null

    override fun proxyInvoke(params: Map<String, String>?, key: String?): Array<Any?>? = null
}
