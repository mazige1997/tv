package com.github.tvbox.kt.cache

import com.github.tvbox.kt.data.AppDataManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 对象序列化 KV 缓存,Kotlin 移植(与旧版 CacheManager 一致)。
 */
object CacheManager {
    // P2-5:进程级内存缓存——save 在调用线程(主线程)立即生效,getCache 优先命中,
    // 序列化 + Room 写入则串行丢到后台单线程执行,避免换集/暂停时主线程序列化+SQLite 写
    private val memCache = Collections.synchronizedMap(LinkedHashMap<String, Any>())
    // P2-5:delete 后到 DB 落盘前的窗口内阻止 getCache 读到旧数据,保证与原同步版语义一致
    private val pendingDeleteKeys = ConcurrentHashMap<String, Boolean>()
    // P2-5:串行单线程,保证 save/delete 按调用顺序落盘(防乱序)
    private val dbExecutor = Executors.newSingleThreadExecutor()

    // 反序列,把二进制数据转换成 java object 对象
    private fun toObject(data: ByteArray?): Any? {
        var bais: ByteArrayInputStream? = null
        var ois: ObjectInputStream? = null
        try {
            bais = ByteArrayInputStream(data)
            ois = ObjectInputStream(bais)
            return ois.readObject()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                bais?.close()
                ois?.close()
            } catch (ignore: Exception) {
                ignore.printStackTrace()
            }
        }
        return null
    }

    // 序列化存储数据需要转换成二进制
    private fun <T> toByteArray(body: T): ByteArray {
        var baos: ByteArrayOutputStream? = null
        var oos: ObjectOutputStream? = null
        try {
            baos = ByteArrayOutputStream()
            oos = ObjectOutputStream(baos)
            oos.writeObject(body)
            oos.flush()
            return baos.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                baos?.close()
                oos?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ByteArray(0)
    }

    @JvmStatic
    fun <T> delete(key: String, body: T) {
        // P2-5:先同步清内存缓存并标记待删除,再由 dbExecutor 串行落盘(排在既有 save 之后)
        memCache.remove(key)
        pendingDeleteKeys[key] = true
        dbExecutor.execute {
            val cache = Cache()
            cache.key = key
            cache.data = toByteArray(body)
            AppDataManager.get().getCacheDao().delete(cache)
            pendingDeleteKeys.remove(key)
        }
    }

    @JvmStatic
    fun <T> save(key: String, body: T) {
        // P2-5:调用线程立即更新内存缓存(零阻塞);null 值不进内存缓存(getCache 走 DB 路径,结果一致)
        if (body != null) {
            memCache[key] = body
            pendingDeleteKeys.remove(key)
        }
        dbExecutor.execute {
            val cache = Cache()
            cache.key = key
            cache.data = toByteArray(body)
            AppDataManager.get().getCacheDao().save(cache)
        }
    }

    fun getCache(key: String): Any? {
        // P2-5:优先命中进程级内存缓存;未命中才读 DB(调用线程,与原版一致)
        val hit = memCache[key]
        if (hit != null) {
            return hit
        }
        if (pendingDeleteKeys.containsKey(key)) {
            return null
        }
        val cache = AppDataManager.get().getCacheDao().getCache(key)
        if (cache != null && cache.data != null) {
            return toObject(cache.data)
        }
        return null
    }
}
