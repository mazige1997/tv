package com.github.tvbox.kt.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.github.tvbox.kt.cache.Cache
import com.github.tvbox.kt.cache.CacheDao
import com.github.tvbox.kt.cache.VodCollect
import com.github.tvbox.kt.cache.VodCollectDao
import com.github.tvbox.kt.cache.VodRecord
import com.github.tvbox.kt.cache.VodRecordDao

/** Room 数据库,Kotlin 移植(与旧版 AppDataBase schema 一致,版本 1)。 */
@Database(entities = [Cache::class, VodRecord::class, VodCollect::class], version = 1, exportSchema = false)
abstract class AppDataBase : RoomDatabase() {
    abstract fun getCacheDao(): CacheDao

    abstract fun getVodRecordDao(): VodRecordDao

    abstract fun getVodCollectDao(): VodCollectDao
}
