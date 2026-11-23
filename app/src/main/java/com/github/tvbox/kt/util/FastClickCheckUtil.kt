package com.github.tvbox.kt.util

import android.os.Handler
import android.view.View

/**
 * 防连点工具,Kotlin 移植(与旧版 FastClickCheckUtil 一致:遥控 OK 键连点会导致误操作)。
 */
object FastClickCheckUtil {
    /**
     * 相同视图点击必须间隔 0.5s 才能有效
     */
    fun check(view: View) {
        check(view, 500)
    }

    /**
     * 设置间隔点击规则,配置间隔点击时长
     */
    fun check(view: View, mills: Int) {
        view.isClickable = false
        Handler().postDelayed({ view.isClickable = true }, mills.toLong())
    }
}
