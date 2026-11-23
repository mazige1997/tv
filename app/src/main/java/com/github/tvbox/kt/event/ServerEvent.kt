package com.github.tvbox.kt.event

/** 服务器事件,Kotlin 移植(与旧版 ServerEvent.java 一致)。 */
class ServerEvent {
    companion object {
        const val SERVER_SUCCESS = 0
        const val SERVER_CONNECTION = 1
        const val SERVER_SEARCH = 2
    }

    var type = 0
    var obj: Any? = null

    constructor(type: Int) {
        this.type = type
    }

    constructor(type: Int, obj: Any?) {
        this.type = type
        this.obj = obj
    }
}
