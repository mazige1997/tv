package com.github.tvbox.kt.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable
import java.util.LinkedHashMap

/**
 * 分类数据,Kotlin 移植(与旧版 MovieSort.java 一致,保留 xstream XML 解析注解)。
 */
@XStreamAlias("class")
class MovieSort : Serializable {

    @XStreamImplicit(itemFieldName = "ty")
    var sortList: MutableList<SortData>? = null

    @XStreamAlias("ty")
    @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["name"])
    class SortData : Serializable, Comparable<SortData> {

        @XStreamAsAttribute
        var id: String? = null
        var name: String? = null
        var sort: Int = -1
        var select: Boolean = false
        var filters: ArrayList<SortFilter> = ArrayList()
        var filterSelect: LinkedHashMap<String, String> = LinkedHashMap()
        var flag: String? = null // 类型

        constructor()

        constructor(id: String?, name: String?) {
            this.id = id
            this.name = name
        }

        fun filterSelectCount(): Int {
            var count = 0
            for (filter in filterSelect.values) {
                if (!filter.isNullOrEmpty()) count++
            }
            return count
        }

        override fun compareTo(other: SortData): Int = this.sort - other.sort

        override fun toString(): String =
            "SortData{id=$id, name=$name, sort=$sort, select=$select, filters=$filters, filterSelect=$filterSelect, flag=$flag}"
    }

    class SortFilter {
        var key: String? = null
        var name: String? = null
        var values: LinkedHashMap<String, String>? = null

        override fun toString(): String =
            "SortFilter{key=$key, name=$name, values=$values}"
    }
}
