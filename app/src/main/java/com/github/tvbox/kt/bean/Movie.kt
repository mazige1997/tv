package com.github.tvbox.kt.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable

/**
 * 视频数据(XML 站点),Kotlin 移植(与旧版 Movie.java 一致,保留 xstream 注解)。
 */
@XStreamAlias("list")
class Movie : Serializable {

    @XStreamAsAttribute
    var page: Int = 0

    @XStreamAsAttribute
    var pagecount: Int = 0 // 总页数

    @XStreamAsAttribute
    var pagesize: Int = 0

    @XStreamAsAttribute
    var recordcount: Int = 0 // 总条数

    @XStreamImplicit(itemFieldName = "video")
    var videoList: MutableList<Video>? = null

    @XStreamAlias("video")
    class Video : Serializable {

        @XStreamAlias("last")
        var last: String? = null // 时间

        @XStreamAlias("id")
        var id: String? = null // 内容id

        @XStreamAlias("tid")
        var tid: Int = 0 // 父级id

        @XStreamAlias("name")
        var name: String? = null // 影片名称

        @XStreamAlias("type")
        var type: String? = null // 类型名称

        @XStreamAlias("pic")
        var pic: String? = null // 图片

        @XStreamAlias("lang")
        var lang: String? = null // 语言

        @XStreamAlias("area")
        var area: String? = null // 地区

        @XStreamAlias("year")
        var year: Int = 0

        @XStreamAlias("state")
        var state: String? = null

        @XStreamAlias("note")
        var note: String? = null // 描述集数或者影片信息

        @XStreamAlias("actor")
        var actor: String? = null // 演员

        @XStreamAlias("director")
        var director: String? = null // 导演

        @XStreamAlias("dl")
        var urlBean: UrlBean? = null

        @XStreamAlias("des")
        var des: String? = null

        var sourceKey: String? = null

        @XStreamAlias("tag")
        var tag: String? = null

        @XStreamAlias("action")
        var action: String? = null

        @XStreamAlias("dl")
        class UrlBean : Serializable {

            @XStreamImplicit(itemFieldName = "dd")
            var infoList: MutableList<UrlInfo>? = null

            @XStreamAlias("dd")
            @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["urls"])
            class UrlInfo : Serializable {

                @XStreamAsAttribute
                var flag: String? = null // zuidam3u8,zuidall(MP4)

                var urls: String? = null

                var beanList: MutableList<InfoBean>? = null
            }

            class InfoBean(var name: String?, var url: String?) : Serializable
        }
    }
}
