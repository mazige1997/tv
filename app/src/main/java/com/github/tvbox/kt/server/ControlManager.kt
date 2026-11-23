package com.github.tvbox.kt.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.receiver.PushReceiver
import com.github.tvbox.kt.receiver.SearchReceiver
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.EventBus
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.IOException

/**
 * 本地服务器管理,Kotlin 移植(与旧版 ControlManager 一致)。
 */
class ControlManager private constructor() {

    private var mServer: RemoteServer? = null

    fun getAddress(local: Boolean): String {
        if (mServer == null || !mServer!!.isStarting()) {
            startServer()
        }
        if (mServer == null || !mServer!!.isStarting()) {
            return ""
        }
        return if (local) mServer!!.getLoadAddress() else mServer!!.getServerAddress()
    }

    @Synchronized // getAddress 会被配置加载线程与主线程并发调用,防止创建两个 RemoteServer
    fun startServer() {
        if (mServer != null && mServer!!.isStarting()) {
            return
        }
        do {
            mServer = RemoteServer(RemoteServer.serverPort, mContext!!)
            mServer!!.setDataReceiver(object : DataReceiver {
                override fun onTextReceived(text: String?) {
                    if (!TextUtils.isEmpty(text)) {
                        val intent = Intent()
                        val bundle = Bundle()
                        bundle.putString("title", text)
                        intent.action = SearchReceiver.action
                        intent.setPackage(mContext!!.packageName)
                        intent.component = ComponentName(mContext!!, SearchReceiver::class.java)
                        intent.putExtras(bundle)
                        mContext!!.sendBroadcast(intent)
                    }
                }

                override fun onApiReceived(url: String?) {
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE, url))
                }

                override fun onLiveApiReceived(url: String?) {
                    if (!TextUtils.isEmpty(url)) {
                        Hawk.put(HawkConfig.LIVE_API_URL, url)
                        HistoryHelper.setLiveApiHistory(url!!)
                    }
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_LIVE_API_URL_CHANGE, url))
                }

                override fun onDanmuApiReceived(url: String?) {
                    Hawk.put(HawkConfig.DANMU_API, if (TextUtils.isEmpty(url)) "" else url!!)
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
                }

                override fun onPushReceived(url: String?) {
                    PushReceiver.send(mContext!!, url)
                }
            })
            try {
                mServer!!.start()
                com.github.tvbox.kt.core.NativeProxy.set(RemoteServer.serverPort)
                IjkMediaPlayer.setDotPort(Hawk.get(HawkConfig.DOH_URL, 0) > 0, RemoteServer.serverPort)
                break
            } catch (ex: IOException) {
                RemoteServer.serverPort++
                mServer!!.stop()
            }
        } while (RemoteServer.serverPort < 9999)
    }

    fun stopServer() {
        if (mServer != null && mServer!!.isStarting()) {
            mServer!!.stop()
        }
        mServer = null
    }

    companion object {
        // DCL 单例必须 volatile，避免读到半初始化实例
        @Volatile
        private var instance: ControlManager? = null
        private var mContext: Context? = null

        @JvmStatic
        fun get(): ControlManager {
            if (instance == null) {
                synchronized(ControlManager::class.java) {
                    if (instance == null) {
                        instance = ControlManager()
                    }
                }
            }
            return instance!!
        }

        @JvmStatic
        fun init(context: Context) {
            mContext = context
        }
    }
}
