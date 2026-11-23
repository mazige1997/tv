package com.github.tvbox.kt.event

/** 历史状态事件,Kotlin 移植(与旧版 HistoryStateEvent.java 一致)。 */
class HistoryStateEvent {
    companion object {
        const val TYPE_TOP = 0
    }

    var type = 0

    constructor(type: Int) {
        this.type = type
    }
}
