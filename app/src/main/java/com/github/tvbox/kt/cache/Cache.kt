package com.github.tvbox.kt.cache

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/** KV 缓存实体,Kotlin 移植(与旧版 Cache 表结构一致)。 */
@Entity(tableName = "cache")
class Cache : Serializable {
    @PrimaryKey(autoGenerate = false)
    var key: String = ""

    var data: ByteArray? = null
}
