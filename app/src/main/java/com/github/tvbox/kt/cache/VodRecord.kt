package com.github.tvbox.kt.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/** 观看记录实体,Kotlin 移植(与旧版 VodRecord 表结构一致)。 */
@Entity(tableName = "vodRecord")
class VodRecord : Serializable {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    @ColumnInfo(name = "vodId")
    var vodId: String? = null

    @ColumnInfo(name = "updateTime")
    var updateTime: Long = 0

    @ColumnInfo(name = "sourceKey")
    var sourceKey: String? = null

    var dataJson: String? = null
}
