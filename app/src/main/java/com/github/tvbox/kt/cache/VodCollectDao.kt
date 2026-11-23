package com.github.tvbox.kt.cache

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 收藏 DAO,Kotlin 移植(与旧版 VodCollectDao 一致)。 */
@Dao
interface VodCollectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: VodCollect): Long

    @Query("select * from vodCollect  order by updateTime desc")
    fun getAll(): List<VodCollect>

    @Query("select * from vodCollect where `id`=:id")
    fun getVodCollect(id: Int): VodCollect?

    @Query("delete from vodCollect where `id`=:id")
    fun delete(id: Int)

    @Query("select * from vodCollect where `sourceKey`=:sourceKey and `vodId`=:vodId")
    fun getVodCollect(sourceKey: String, vodId: String): VodCollect?

    @Delete
    fun delete(record: VodCollect): Int

    @Query("DELETE FROM vodCollect")
    fun deleteAll()
}
