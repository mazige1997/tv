package com.github.tvbox.kt.util

import com.github.tvbox.kt.KtApp
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.HashMap
import java.util.concurrent.Executors

/**
 * 台标/EPG ID 资产映射,Kotlin 移植(与旧版 EpgUtil 一致)。
 * 优化:epg_data.json(约 100KB)改为后台线程加载并原子发布,
 * 避免低端电视盒子主线程启动卡顿;加载完成前 getEpgInfo 返回 null(调用方自然降级)。
 */
object EpgUtil {

    @Volatile
    private var epgHashMap: Map<String, JsonObject> = emptyMap()
    private val loadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "epg-init").apply { isDaemon = true }
    }

    @Volatile
    private var loadStarted = false

    fun init() {
        if (loadStarted) return
        synchronized(this) {
            if (loadStarted) return
            loadStarted = true
        }
        loadExecutor.execute { loadEpgData() }
    }

    private fun loadEpgData() {
        try {
            // assets 中的文件无法直接访问,使用 AssetManager 访问
            val assetManager = KtApp.instance().assets
            val inputStreamReader = InputStreamReader(assetManager.open("epg_data.json"), "UTF-8")
            val br = BufferedReader(inputStreamReader)
            val builder = StringBuilder()
            var line: String? = null
            while (br.readLine().also { line = it } != null) {
                builder.append(line)
            }
            br.close()
            inputStreamReader.close()
            if (builder.toString().isNotEmpty()) {
                val doc = Gson().fromJson(builder.toString(), JsonObject::class.java)
                val map = HashMap<String, JsonObject>()
                for (opt in doc.get("epgs").asJsonArray) {
                    val obj = opt.asJsonObject
                    val name = obj.get("name").asString.trim()
                    val names = name.split(",".toRegex()).toTypedArray()
                    for (string in names) {
                        map[string] = obj
                    }
                }
                // 一次性原子发布,主线程读取无需加锁
                epgHashMap = map
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getEpgInfo(channelName: String?): Array<String>? {
        return try {
            val map = epgHashMap
            if (map.containsKey(channelName)) {
                val obj = map[channelName]!!
                arrayOf(obj.get("logo").asString, obj.get("epgid").asString)
            } else {
                null
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }
}
