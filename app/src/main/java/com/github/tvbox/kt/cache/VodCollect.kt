package com.github.tvbox.kt.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/** 收藏实体,Kotlin 移植(与旧版 VodCollect 表结构一致)。 */
@Entity(tableName = "vodCollect")
class VodCollect : Serializable {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    @ColumnInfo(name = "vodId")
    var vodId: String? = null

    @ColumnInfo(name = "updateTime")
    var updateTime: Long = 0

    @ColumnInfo(name = "sourceKey")
    var sourceKey: String? = null

    @ColumnInfo(name = "name")
    var name: String? = null

    @ColumnInfo(name = "pic")
    var pic: String? = null
}
