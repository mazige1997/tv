package com.github.tvbox.kt.ui.activity

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.ui.compose.SettingScreen
import com.github.tvbox.kt.ui.compose.TvTheme
import com.github.tvbox.kt.util.AppManager
import com.github.tvbox.kt.util.HawkConfig
import com.orhanobut.hawk.Hawk

/**
 * 设置 Activity(Jetpack Compose 版)。
 * UI 由 [SettingScreen] 声明式构建;保留开发者模式(dispatchKeyEvent)与配置变更返回(onBackPressed)逻辑。
 */
class SettingActivity : BaseActivity() {
    private var mHandler = Handler()
    private var currentApi = ""
    private var homeRec = 0
    private var currentLiveApi = ""
    private var devMode = ""
    private var restartRequested = false

    private val mDevModeRun = Runnable { devMode = "" }
    private val restartRunnable = Runnable {
        if (!isFinishing) onBackPressed()
    }

    interface DevModeCallback {
        fun onChange()
    }

    override val layoutResID: Int
        get() = R.layout.activity_setting_compose

    override fun onCreate(savedInstanceState: Bundle?) {
        restartRequested = savedInstanceState?.getBoolean("restartRequested", false) ?: false
        super.onCreate(savedInstanceState)
        if (restartRequested) scheduleConfigRestart()
    }

    override fun shouldRefreshAutoSize(): Boolean {
        return true
    }

    override fun init() {
        currentApi = Hawk.get(HawkConfig.API_URL, "")
        homeRec = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)
        currentLiveApi = Hawk.get(HawkConfig.LIVE_API_URL, "")
        findViewById<ComposeView>(R.id.settingComposeView).setContent {
            TvTheme {
                SettingScreen(this)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_0) {
            mHandler.removeCallbacks(mDevModeRun)
            devMode += "0"
            mHandler.postDelayed(mDevModeRun, 200)
            if (devMode.length >= 4) {
                callback?.onChange()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun requestConfigRestart() {
        restartRequested = true
        scheduleConfigRestart()
    }

    private fun scheduleConfigRestart() {
        mHandler.removeCallbacks(restartRunnable)
        mHandler.postDelayed(restartRunnable, 2500)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("restartRequested", restartRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mHandler.removeCallbacks(restartRunnable)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (restartRequested || currentApi != Hawk.get(HawkConfig.API_URL, "")) {
            AppManager.instance.finishActivity(HomeActivity::class.java)
            jumpActivity(HomeActivity::class.java)
            finish()
            return
        }
        if (homeRec != Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) ||
            currentLiveApi != Hawk.get(HawkConfig.LIVE_API_URL, "")
        ) {
            jumpActivity(HomeActivity::class.java, createBundle())
        }
        super.onBackPressed()
    }

    private fun createBundle(): Bundle {
        val bundle = Bundle()
        bundle.putBoolean("useCache", true)
        return bundle
    }

    companion object {
        @JvmField
        var callback: DevModeCallback? = null
    }
}
