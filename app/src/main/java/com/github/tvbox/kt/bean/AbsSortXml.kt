package com.github.tvbox.kt.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

/** XML 分类响应,Kotlin 移植(与旧版 AbsSortXml.java 一致)。 */
@XStreamAlias("rss")
class AbsSortXml : Serializable {
    var sourceKey: String? = null

    @XStreamAlias("class")
    var classes: MovieSort? = null

    @XStreamAlias("list")
    var list: Movie? = null

    var videoList: List<Movie.Video>? = null
}
