package com.github.tvbox.kt

import android.app.Activity
import android.app.Application
import android.os.Build
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.callback.EmptyCallback
import com.github.tvbox.kt.callback.LoadingCallback
import com.github.tvbox.kt.core.NativeCore
import com.github.tvbox.kt.core.spider.JsLoader
import com.github.tvbox.kt.data.AppDataManager
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.util.AppManager
import com.github.tvbox.kt.util.EpgUtil
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.OkGoHelper
import com.github.tvbox.kt.util.PlayerHelper
import com.kingja.loadsir.core.LoadSir
import com.orhanobut.hawk.Hawk
import com.whl.quickjs.android.QuickJSLoader
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits

/**
 * 应用入口,Kotlin 移植(与旧版 base.App 一致;新增 NativeCore 加载)。
 */
class KtApp : Application() {

    private var vodInfo: VodInfo? = null
    private var dashData: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        initParams()
        OkGoHelper.init() // 台标获取
        EpgUtil.init()
        NativeCore.load() // Rust 原生核心(诊断期整体禁用,加载失败时亦自动回退 Kotlin 实现)
        logStartupDiag()
        // 初始化 Web 服务器
        ControlManager.init(this)
        // 初始化数据库
        AppDataManager.init()
        LoadSir.beginBuilder()
            .addCallback(EmptyCallback())
            .addCallback(LoadingCallback())
            .commit()
        AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
            .setSupportDP(false)
            .setSupportSP(false)
            .setSupportSubunits(Subunits.MM)
        PlayerHelper.init()
        QuickJSLoader.init()
        // 播放器缓存清理移到后台线程:冷启动时缓存目录可能残留大量文件,主线程删除会造成卡顿
        Thread {
            try {
                FileUtils.cleanPlayerCache()
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }.apply { isDaemon = true; name = "clean-player-cache"; start() }
        startCacheDiagTimer()
    }

    /**
     * 边下边播缓存自动快照:app 运行期间每 15 秒统计一次 exo-video-cache,
     * 仅在统计结果变化且日志落盘开启时写入,避免刷屏。这样无需打开「诊断日志」
     * 对话框,直接复制播放日志即可看到 files/size 随播放增长,确认缓存确实落盘。
     * 完全独立于播放链路,只读取缓存目录,不影响播放。
     */
    private var lastCacheSummary: String? = null
    private val cacheDiagHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cacheDiagRunnable = object : Runnable {
        override fun run() {
            try {
                if (LOG.diskEnabled) {
                    val summary = FileUtils.exoCacheSummary()
                    // 注意:Java 类在 Kotlin 里不能作为值引用(无 companion object),
                    // 必须用全限定类名直接访问其静态字段。
                    val evt = try {
                        xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.sCacheEvent
                    } catch (_: Throwable) {
                        "?"
                    }
                    // 决定性诊断:写入失败的证据全部来自这里,不依赖 EventListener(首次
                    // 播放不会触发它的回调)。writes=本次 write() 调用字节,opens=sink
                    // 打开次数,err=首个 IOException。files/size 恒为 0 时看这三个值即可:
                    //   writes>0          -> 落盘正常,只是统计口径问题;
                    //   writes=0 err!=none -> 写入抛异常,err 即根因;
                    //   opens=0           -> HLS 请求压根没经过 CacheDataSource。
                    val writes = try { xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.sCacheWriteBytes.get() } catch (_: Throwable) { -1L }
                    val wcalls = try { xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.sCacheWriteCalls.get() } catch (_: Throwable) { -1L }
                    val opens = try { xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.sCacheOpenCalls.get() } catch (_: Throwable) { -1L }
                    val werr = try { xyz.doikki.videoplayer.exo.ExoMediaSourceHelper.sCacheWriteError } catch (_: Throwable) { "?" }
                    val line = "$summary evt=$evt writes=${writes}B wcalls=$wcalls opens=$opens werr=$werr"
                    if (line != lastCacheSummary) {
                        lastCacheSummary = line
                        LOG.i("DIAG exo_cache_auto $line")
                    }
                }
            } catch (_: Throwable) {
            }
            cacheDiagHandler.postDelayed(this, 15_000L)
        }
    }

    private fun startCacheDiagTimer() {
        cacheDiagHandler.postDelayed(cacheDiagRunnable, 15_000L)
    }

    /**
     * 崩溃堆栈落盘:电视盒没有 adb 时,也能从设置页的诊断日志里拿到完整异常链。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val activity = try {
                    AppManager.instance.currentActivity()?.javaClass?.name ?: "?"
                } catch (_: Throwable) {
                    "?"
                }
                LOG.e("DIAG ==== CRASH thread=${thread.name} activity=$activity ====")
                var depth = 0
                var cause: Throwable? = throwable
                while (depth < 6) {
                    val c = cause ?: break
                    val sw = java.io.StringWriter()
                    c.printStackTrace(java.io.PrintWriter(sw))
                    LOG.e("DIAG crash[$depth] ${c.javaClass.name}: ${c.message}\n$sw")
                    cause = c.cause
                    depth++
                }
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    /**
     * 启动诊断快照:在没有 adb 的电视盒上,用于确认测的是哪一版、配置是否已设置、
     * 原生核心是否启用。结果写入 Download/tvbox_diag.log。
     */
    private fun logStartupDiag() {
        try {
            val ver = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: Throwable) {
                "?"
            }
            val apiUrl = Hawk.get(HawkConfig.API_URL, "") ?: ""
            LOG.i("DIAG ==== app start ====")
            LOG.i("DIAG version=$ver sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString(",")} native=${NativeCore.available}")
            LOG.i("DIAG api_url_set=${apiUrl.isNotEmpty()} len=${apiUrl.length} prefix=${apiUrl.take(20)}")
            LOG.i("DIAG log_file=${LOG.diagPath()}")
            LOG.i("DIAG log_disk=${if (LOG.diskEnabled) "on" else "off"} play_cache=${if (Hawk.get(HawkConfig.PLAY_CACHE, false)) "on" else "off"}")
        } catch (th: Throwable) {
            LOG.e("DIAG snapshot failed: ${th.message}")
        }
    }

    private fun initParams() {
        Hawk.init(this).build()
        LOG.diskEnabled = Hawk.get(HawkConfig.LOG_DISK, true)
        Hawk.put(HawkConfig.DEBUG_OPEN, false)
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false)
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2)
        } else if (Hawk.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        JsLoader.destroy()
    }

    fun setVodInfo(vodinfo: VodInfo?) {
        this.vodInfo = vodinfo
    }

    fun getVodInfo(): VodInfo? {
        return this.vodInfo
    }

    fun getCurrentActivity(): Activity? {
        return try {
            AppManager.instance.currentActivity()
        } catch (th: Throwable) {
            LOG.e(th.toString())
            null
        }
    }

    private var p: com.p2p.P2PClass? = null

    fun setDashData(data: String?) {
        dashData = data
    }

    fun getDashData(): String? = dashData

    companion object {
        private lateinit var instance: KtApp
        private var p2p: com.p2p.P2PClass? = null

        @JvmField
        var burl: String? = null // p2p 当前播放地址(与旧版 App.burl 一致)

        @JvmStatic
        fun instance(): KtApp = instance

        @JvmStatic
        @Synchronized // 防止并发首次调用重复初始化原生 p2p 引擎
        fun getp2p(): com.p2p.P2PClass? {
            return try {
                if (p2p == null) {
                    p2p = com.p2p.P2PClass(FileUtils.getExternalCachePath())
                }
                p2p
            } catch (e: Exception) {
                LOG.e(e.toString())
                null
            }
        }
    }
}
