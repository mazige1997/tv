package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.github.tvbox.kt.R
import xyz.doikki.videoplayer.util.CutoutUtil

/**
 * 对话框基类(沉浸式 + 防泄漏),Kotlin 移植(与旧版 BaseDialog 一致)。
 */
open class BaseDialog : Dialog {

    constructor(context: Context) : super(context, R.style.CustomDialogStyle)

    constructor(context: Context, customDialogStyle: Int) : super(context, customDialogStyle)

    override fun onCreate(savedInstanceState: Bundle?) {
        CutoutUtil.adaptCutoutAboveAndroidP(this, true) // 设置刘海
        super.onCreate(savedInstanceState)
    }

    override fun show() {
        if (isContextInvalid()) {
            return
        }
        window!!.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        super.show()
        hideSysBar()
        window!!.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    private fun isContextInvalid(): Boolean {
        val context = context ?: return false
        if (context !is Activity) {
            return false
        }
        return context.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && context.isDestroyed)
    }

    private fun hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            var uiOptions = window!!.decorView.systemUiVisibility
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window!!.decorView.systemUiVisibility = uiOptions
        }
    }
}
