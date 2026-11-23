package com.github.tvbox.kt.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

/** XML 站点响应根节点,Kotlin 移植(与旧版 AbsXml.java 一致)。 */
@XStreamAlias("rss")
class AbsXml : Serializable {
    var sourceKey: String? = null
    var searchToken: String? = null

    @XStreamAlias("list")
    var movie: Movie? = null

    @XStreamAlias("msg")
    var msg: String? = null
}
