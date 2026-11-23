package com.github.tvbox.kt.base

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.PermissionChecker
import com.github.tvbox.kt.R
import com.github.tvbox.kt.callback.EmptyCallback
import com.github.tvbox.kt.callback.LoadingCallback
import com.github.tvbox.kt.util.AppManager
import com.kingja.loadsir.callback.Callback
import com.kingja.loadsir.core.LoadService
import com.kingja.loadsir.core.LoadSir
import me.jessyan.autosize.AutoSizeCompat
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.internal.CustomAdapt
import xyz.doikki.videoplayer.util.CutoutUtil
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Activity 基类(沉浸式全屏 + AutoSize 适配 + 壁纸),Kotlin 移植(与旧版 BaseActivity 一致)。
 */
abstract class BaseActivity : AppCompatActivity(), CustomAdapt {

    protected lateinit var mContext: Context
    private var mLoadService: LoadService<*>? = null

    private var screenRatio = -100.0f
    private val refreshAutoSizeRunnable = Runnable {
        if (shouldRefreshAutoSize()) {
            refreshAutoSize()
        }
    }
    private val hideSysBarRunnable = Runnable {
        hideSysBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            if (screenRatio < 0) {
                val dm = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(dm)
                updateScreenRatio(dm)
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        super.onCreate(savedInstanceState)
        setContentView(layoutResID)
        mContext = this
        initSystemUiListener()
        CutoutUtil.adaptCutoutAboveAndroidP(mContext, true) // 设置刘海
        AppManager.instance.addActivity(this)
        init()
    }

    override fun onResume() {
        super.onResume()
        hideSysBar()
        changeWallpaper(false)
        if (shouldRefreshAutoSize()) {
            refreshAutoSize()
            scheduleRefreshAutoSize()
        }
    }

    open fun hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            var uiOptions = window.decorView.systemUiVisibility
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility = uiOptions
        }
    }

    private fun initSystemUiListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val decorView = window.decorView
            decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                val hiddenBars = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                if ((visibility and hiddenBars) != hiddenBars) {
                    decorView.removeCallbacks(hideSysBarRunnable)
                    decorView.postDelayed(hideSysBarRunnable, 300)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(refreshAutoSizeRunnable)
        window.decorView.removeCallbacks(hideSysBarRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSysBar()
            if (shouldRefreshAutoSize()) {
                scheduleRefreshAutoSize()
            }
        }
    }

    protected open fun shouldRefreshAutoSize(): Boolean {
        return false
    }

    private fun scheduleRefreshAutoSize() {
        val decorView = window.decorView
        decorView.removeCallbacks(refreshAutoSizeRunnable)
        decorView.postDelayed(refreshAutoSizeRunnable, 300)
    }

    private fun refreshAutoSize() {
        try {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                return
            }
            updateScreenRatio(dm)
            AutoSizeConfig.getInstance()
                .setScreenWidth(dm.widthPixels)
                .setScreenHeight(dm.heightPixels)
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
            window.decorView.requestLayout()
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    private fun updateScreenRatio(dm: DisplayMetrics) {
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val min = minOf(screenWidth, screenHeight)
        if (min > 0) {
            screenRatio = maxOf(screenWidth, screenHeight).toFloat() / min.toFloat()
        }
    }

    override fun getResources(): Resources {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
        }
        return super.getResources()
    }

    fun hasPermission(permission: String?): Boolean {
        var has = true
        try {
            has = PermissionChecker.checkSelfPermission(this, permission!!) == PermissionChecker.PERMISSION_GRANTED
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return has
    }

    protected abstract val layoutResID: Int

    /** 进入界面后的初始化 */
    protected abstract fun init()

    protected fun setLoadSir(view: View) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view) { }
        }
    }

    fun showLoading() {
        mLoadService?.showCallback(LoadingCallback::class.java)
    }

    fun isLoading(): Boolean {
        if (mLoadService != null && mLoadService!!.currentCallback != null) {
            return mLoadService!!.currentCallback == LoadingCallback::class.java
        }
        return false
    }

    fun showEmpty() {
        mLoadService?.showCallback(EmptyCallback::class.java)
    }

    fun showSuccess() {
        mLoadService?.showSuccess()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppManager.instance.finishActivity(this)
    }

    fun jumpActivity(clazz: Class<out BaseActivity>) {
        val intent = Intent(mContext, clazz)
        startActivity(intent)
    }

    fun jumpActivity(clazz: Class<out BaseActivity>, bundle: Bundle?) {
        val intent = Intent(mContext, clazz)
        intent.putExtras(bundle!!)
        startActivity(intent)
    }

    protected fun getAssetText(fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val assets: AssetManager = assets
            val bf = BufferedReader(InputStreamReader(assets.open(fileName)))
            var line: String? = null
            while (bf.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
            return stringBuilder.toString()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return ""
    }

    override fun getSizeInDp(): Float {
        return if (isBaseOnWidth) 1280f else 720f
    }

    override fun isBaseOnWidth(): Boolean {
        return !(screenRatio >= 4.0f)
    }

    open fun changeWallpaper(force: Boolean) {
        // 已有全局壁纸且非强制刷新:直接复用,避免每次 onResume 主线程重复解码壁纸文件
        if (!force && globalWp != null) {
            window.setBackgroundDrawable(globalWp)
            return
        }
        try {
            val wp = File(filesDir.absolutePath + "/wp")
            if (wp.exists()) {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeFile(wp.absolutePath, opts)
                // 从 Options 中获取图片的分辨率
                val imageHeight = opts.outHeight
                val imageWidth = opts.outWidth
                val picHeight = 720
                val picWidth = 1080
                val scaleX = imageWidth / picWidth
                val scaleY = imageHeight / picHeight
                var scale = 1
                if (scaleX > scaleY && scaleY >= 1) {
                    scale = scaleX
                }
                if (scaleX < scaleY && scaleX >= 1) {
                    scale = scaleY
                }
                opts.inJustDecodeBounds = false
                // 采样率
                opts.inSampleSize = scale
                globalWp = BitmapDrawable(BitmapFactory.decodeFile(wp.absolutePath, opts))
            } else {
                globalWp = null
            }
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            globalWp = null
        }
        if (globalWp != null) window.setBackgroundDrawable(globalWp)
        else window.setBackgroundDrawableResource(R.drawable.app_bg)
    }

    companion object {
        var globalWp: BitmapDrawable? = null
    }
}
