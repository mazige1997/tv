package com.github.tvbox.kt.event

/** 顶部状态事件,Kotlin 移植(与旧版 TopStateEvent.java 一致)。 */
class TopStateEvent {
    companion object {
        const val TYPE_TOP = 0
    }

    var type = 0

    constructor(type: Int) {
        this.type = type
    }
}
