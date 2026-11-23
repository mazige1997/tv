package com.github.tvbox.kt.server

import fi.iki.elonen.NanoHTTPD

/** 服务器数据回调,Kotlin 移植(与旧版 DataReceiver 一致)。 */
interface DataReceiver {
    fun onTextReceived(text: String?)

    fun onApiReceived(url: String?)

    fun onLiveApiReceived(url: String?)

    fun onDanmuApiReceived(url: String?)

    fun onPushReceived(url: String?)
}

/** 请求处理器接口,Kotlin 移植(与旧版 RequestProcess 一致)。 */
interface RequestProcess {
    fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean

    fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params: Map<String, String>?,
        files: Map<String, String>?
    ): NanoHTTPD.Response

    companion object {
        const val KEY_ACTION_PRESSED = 0
        const val KEY_ACTION_DOWN = 1
        const val KEY_ACTION_UP = 2
    }
}
