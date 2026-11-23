package com.github.tvbox.kt.core.spider

import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import android.util.Log
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.core.spider.js.JsSpider
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.MD5
import com.lzy.okgo.OkGo
import dalvik.system.DexClassLoader
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * JS 爬虫加载器(quickjs + 可选自定义 jsapi dex),Kotlin 移植(与旧版 JsLoader 一致)。
 */
class JsLoader {

    companion object {
        private val spiders = ConcurrentHashMap<String, Spider>()
        private val classes = ConcurrentHashMap<String, Class<*>>()

        fun destroy() {
            for (spider in spiders.values) {
                spider.cancelByTag()
                spider.destroy()
            }
        }

        fun stopAll() {
            for (spider in spiders.values) {
                spider.cancelByTag()
            }
        }
    }

    // 当前的 Js 爬虫 key
    @Volatile
    private var recentKey = ""

    @Synchronized
    fun clear() {
        for (spider in spiders.values) {
            spider.cancelByTag()
            spider.destroy()
        }
        spiders.clear()
        classes.clear()
        recentKey = ""
    }

    private fun loadClassLoader(jar: String, key: String): Boolean {
        var success = false
        var classInit: Class<*>? = null
        try {
            val cacheDir = File(KtApp.instance().cacheDir.absolutePath + "/catvod_jsapi")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val classLoader = DexClassLoader(jar, cacheDir.absolutePath, null, KtApp.instance().classLoader)
            var count = 0
            do {
                try {
                    try {
                        classInit = classLoader.loadClass("com.github.catvod.js.Function")
                        classInit!!.getDeclaredConstructor(com.whl.quickjs.wrapper.QuickJSContext::class.java)
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Function")
                    } catch (ignored: Throwable) {
                        classInit = classLoader.loadClass("com.github.catvod.js.Method")
                        classInit!!.getDeclaredConstructor(com.whl.quickjs.wrapper.QuickJSContext::class.java)
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Method")
                    }
                    if (classInit != null) {
                        Log.i("JSLoader", "echo-自定义jsapi代码加载成功!")
                        success = true
                        break
                    }
                    Thread.sleep(200)
                } catch (th: Throwable) {
                    th.printStackTrace()
                }
                count++
            } while (count < 5)

            if (success) {
                classes[key] = classInit!!
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        return success
    }

    private fun loadJarInternal(jar: String, md5: String, key: String): Class<*>? {
        if (classes.containsKey(key)) {
            Log.i("JSLoader", "echo-loadJarInternal cached")
            return classes[key]
        }
        val cache = File(KtApp.instance().filesDir.absolutePath + "/csp/" + key + ".jar")
        if (md5.isNotEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equals(md5, ignoreCase = true)) {
                loadClassLoader(cache.absolutePath, key)
                return classes[key]
            }
        } else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if (loadClassLoader(cache.absolutePath, key)) {
                    return classes[key]
                }
            }
        }
        try {
            val response = OkGo.get<File>(jar).execute()
            val is0: InputStream = response.body()!!.byteStream()
            val os: OutputStream = FileOutputStream(cache)
            try {
                val buffer = ByteArray(2048)
                while (true) {
                    val length = is0.read(buffer)
                    if (length <= 0) break
                    os.write(buffer, 0, length)
                }
            } finally {
                try {
                    is0.close()
                    os.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            loadClassLoader(cache.absolutePath, key)
            return classes[key]
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    @Synchronized
    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        recentKey = key
        if (spiders.containsKey(key)) {
            Log.i("JSLoader", "echo-getSpider cached $key")
            return spiders[key]!!
        }
        var classLoader: Class<*>? = null
        if (jar.isNotEmpty()) {
            val urls = jar.split(";md5;".toRegex()).toTypedArray()
            val jarUrl = urls[0]
            val jarKey = MD5.string2MD5(jarUrl)
            val jarMd5 = if (urls.size > 1) urls[1].trim() else ""
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey ?: "")
        }
        try {
            Log.i("JSLoader", "echo-getSpider load")
            val sp = JsSpider(key, api, classLoader)
            sp.siteKey = key
            sp.init(KtApp.instance(), ext)
            spiders[key] = sp
            return sp
        } catch (th: Throwable) {
            LOG.i("echo-getSpider-error " + th.message)
        }
        return SpiderNull()
    }

    fun proxyInvoke(params: Map<String, String>?): Array<Any?>? {
        return try {
            val proxyFun = spiders[recentKey]
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params)
            }
            null
        } catch (th: Throwable) {
            null
        }
    }
}
