package com.github.tvbox.kt.cache

import android.text.TextUtils
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.data.AppDataManager
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.orhanobut.hawk.Hawk

/**
 * Room 数据业务层(观看记录/收藏),Kotlin 移植(与旧版 RoomDataManger 一致)。
 */
object RoomDataManger {

    private val vodInfoStrategy: ExclusionStrategy = object : ExclusionStrategy {
        override fun shouldSkipField(field: FieldAttributes): Boolean {
            if (field.declaringClass == VodInfo::class.java && field.name == "seriesFlags") {
                return true
            }
            if (field.declaringClass == VodInfo::class.java && field.name == "seriesMap") {
                return true
            }
            return false
        }

        override fun shouldSkipClass(clazz: Class<*>?): Boolean {
            return false
        }
    }

    private fun getVodInfoGson(): Gson {
        return GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create()
    }

    fun insertVodRecord(sourceKey: String, vodInfo: VodInfo) {
        val dao = AppDataManager.get().getVodRecordDao()
        if (Hawk.get(HawkConfig.HISTORY_MERGE, false)) {
            removeSameNameVodRecords(dao, sourceKey, vodInfo)
        }
        var record = dao.getVodRecord(sourceKey, vodInfo.id ?: "")
        if (record == null) {
            record = VodRecord()
        }
        record.sourceKey = sourceKey
        record.vodId = vodInfo.id
        record.updateTime = System.currentTimeMillis()
        record.dataJson = getVodInfoGson().toJson(vodInfo)
        dao.insert(record)
    }

    fun getVodInfo(sourceKey: String, vodId: String): VodInfo? {
        val record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId)
        return try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                val vodInfo =
                    getVodInfoGson().fromJson<VodInfo>(record.dataJson, object : TypeToken<VodInfo>() {}.type)
                if (vodInfo.name == null) null else vodInfo
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteVodRecord(sourceKey: String, vodInfo: VodInfo) {
        val record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id ?: "")
        if (record != null) {
            AppDataManager.get().getVodRecordDao().delete(record)
        }
    }

    fun getAllVodRecord(limit: Int): List<VodInfo> {
        val dao = AppDataManager.get().getVodRecordDao()
        val index = Hawk.get(HawkConfig.HISTORY_NUM, 0)
        val hisNum = HistoryHelper.getHisNum(index)
        val recordList = dao.getAll(Int.MAX_VALUE)
        val vodInfoList = ArrayList<VodInfo>()
        val historyMerge = Hawk.get(HawkConfig.HISTORY_MERGE, false)
        val historyNames: MutableSet<String>? = if (historyMerge) HashSet() else null
        for (record in recordList) {
            var info: VodInfo? = null
            try {
                if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                    info = getVodInfoGson().fromJson<VodInfo>(record.dataJson, object : TypeToken<VodInfo>() {}.type)
                    info.sourceKey = record.sourceKey
                    if (info.name == null) info = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val name = getVodRecordName(info)
            if (info != null) {
                if (!historyMerge || TextUtils.isEmpty(name) || historyNames!!.add(name)) {
                    vodInfoList.add(info)
                }
            }
        }
        if (dao.getCount() > hisNum) {
            dao.reserver(hisNum)
        }
        val size = Math.min(vodInfoList.size, Math.min(limit, hisNum))
        return ArrayList(vodInfoList.subList(0, size))
    }

    private fun removeSameNameVodRecords(dao: VodRecordDao, sourceKey: String, vodInfo: VodInfo) {
        val name = getVodRecordName(vodInfo)
        if (TextUtils.isEmpty(name)) return
        for (record in dao.getAll(Int.MAX_VALUE)) {
            if (TextUtils.equals(sourceKey, record.sourceKey) && TextUtils.equals(vodInfo.id, record.vodId)) {
                continue
            }
            try {
                val history = getVodInfoGson().fromJson<VodInfo>(record.dataJson, object : TypeToken<VodInfo>() {}.type)
                if (TextUtils.equals(name, getVodRecordName(history))) {
                    dao.delete(record)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun getVodRecordName(vodInfo: VodInfo?): String {
        return if (vodInfo == null || vodInfo.name == null) "" else vodInfo.name!!.trim()
    }

    fun insertVodCollect(sourceKey: String, vodInfo: VodInfo) {
        val record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id ?: "")
        if (record != null) {
            return
        }
        val newRecord = VodCollect()
        newRecord.sourceKey = sourceKey
        newRecord.vodId = vodInfo.id
        newRecord.updateTime = System.currentTimeMillis()
        newRecord.name = vodInfo.name
        newRecord.pic = vodInfo.pic
        AppDataManager.get().getVodCollectDao().insert(newRecord)
    }

    fun deleteVodCollect(id: Int) {
        AppDataManager.get().getVodCollectDao().delete(id)
    }

    fun deleteVodCollect(sourceKey: String, vodInfo: VodInfo) {
        val record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id ?: "")
        if (record != null) {
            AppDataManager.get().getVodCollectDao().delete(record)
        }
    }

    fun deleteVodCollectAll() {
        AppDataManager.get().getVodCollectDao().deleteAll()
    }

    fun deleteVodRecordAll() {
        AppDataManager.get().getVodRecordDao().deleteAll()
    }

    fun isVodCollect(sourceKey: String, vodId: String): Boolean {
        val record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId)
        return record != null
    }

    fun getAllVodCollect(): List<VodCollect> {
        return AppDataManager.get().getVodCollectDao().getAll()
    }
}
