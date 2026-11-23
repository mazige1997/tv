package com.github.tvbox.kt.bean

import com.github.tvbox.kt.util.HawkConfig
import com.orhanobut.hawk.Hawk

/**
 * IJK 解码器选项,Kotlin 移植(与旧版 IJKCode.java 一致)。
 */
class IJKCode {
    var name: String? = null
    var option: LinkedHashMap<String, String>? = null
    private var selected = false

    fun selected(selected: Boolean) {
        this.selected = selected
        if (selected) {
            Hawk.put(HawkConfig.IJK_CODEC, name)
        }
    }

    fun isSelected(): Boolean = selected
}
