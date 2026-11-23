package com.github.tvbox.kt.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.event.ServerEvent
import com.github.tvbox.kt.ui.activity.DetailActivity
import com.github.tvbox.kt.ui.activity.SearchActivity
import com.github.tvbox.kt.util.AppManager
import org.greenrobot.eventbus.EventBus

/** 服务器搜索推送接收器,Kotlin 移植(与旧版 SearchReceiver 一致)。 */
class SearchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (action == intent.action && intent.extras != null) {
            if (AppManager.instance.getActivity(SearchActivity::class.java) != null) {
                AppManager.instance.backActivity(SearchActivity::class.java)
                EventBus.getDefault().post(ServerEvent(ServerEvent.SERVER_SEARCH, intent.extras!!.getString("title")))
            } else {
                val newIntent = Intent(context, SearchActivity::class.java)
                newIntent.putExtra("title", intent.extras!!.getString("title"))
                newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(newIntent)
            }
        }
    }

    companion object {
        const val action = "android.content.movie.search.Action"
    }
}

/** 推送接收器,Kotlin 移植(与旧版 PushReceiver 一致)。 */
class PushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (action != intent.action || intent.extras == null) return
        val url = intent.extras!!.getString("url")
        if (TextUtils.isEmpty(url) || ApiConfig.get().getSource("push_agent") == null) return
        val newIntent = Intent(context, DetailActivity::class.java)
        newIntent.putExtra("id", url)
        newIntent.putExtra("sourceKey", "push_agent")
        newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        context.startActivity(newIntent)
    }

    companion object {
        const val action = "android.content.movie.push.Action"

        fun send(context: Context?, url: String?) {
            if (context == null || TextUtils.isEmpty(url)) return
            val intent = Intent()
            intent.action = action
            intent.setPackage(context.packageName)
            intent.setComponent(ComponentName(context, PushReceiver::class.java))
            intent.putExtra("url", url)
            context.sendBroadcast(intent)
        }
    }
}

/** 自定义 Web 动作接收器,Kotlin 移植(与旧版 CustomWebReceiver 一致)。 */
class CustomWebReceiver : BroadcastReceiver() {

    interface Callback {
        fun onChange(action: String, obj: Any?)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (action == intent.action && intent.extras != null) {
            val action = intent.extras!!.getString("action")
            if (action == REFRESH_PARSE) {
                return
            } else if (action == REFRESH_LIVE) {
                return
            } else {
                return
            }
        }
    }

    companion object {
        const val action = "android.content.movie.custom.web.Action"

        const val REFRESH_SOURCE = "source"
        const val REFRESH_LIVE = "live"
        const val REFRESH_PARSE = "parse"

        var callback: List<Callback> = ArrayList()
    }
}
