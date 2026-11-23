package com.github.tvbox.kt.player.thirdparty

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.server.RemoteServer
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.LocalIPAddress
import com.github.tvbox.kt.util.OkGoHelper
import com.orhanobut.hawk.Hawk
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kodi 外部播放,Kotlin 移植(与旧版 Kodi 一致)。
 */
object Kodi {
    const val TAG = "ThirdParty.Kodi"

    private const val PACKAGE_NAME = "org.xbmc.kodi"
    private const val PLAYBACK_ACTIVITY = "org.xbmc.kodi.Splash"

    class KodiPackageInfo(val packageName: String, val activityName: String)

    private val PACKAGES = arrayOf(KodiPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

    /** @return null if any Kodi packages not exist. */
    fun getPackageInfo(): KodiPackageInfo? {
        for (pkg in PACKAGES) {
            try {
                val info = KtApp.instance().packageManager.getApplicationInfo(pkg.packageName, 0)
                if (info.enabled) return pkg
                else Log.v(TAG, "Kodi package `" + pkg.packageName + "` is disabled.")
            } catch (ex: PackageManager.NameNotFoundException) {
                Log.v(TAG, "Kodi package `" + pkg.packageName + "` does not exist.")
            }
        }
        return null
    }

    fun run(activity: Activity, url0: String, title: String?, subtitle: String?, headers: HashMap<String, String>?): Boolean {
        val packageInfo = getPackageInfo() ?: return false
        return try {
            var url = url0
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setPackage(packageInfo.packageName)
            intent.setClassName(packageInfo.packageName, packageInfo.activityName)
            if (headers != null && headers.isNotEmpty()) {
                url += "|"
                var idx = 0
                for (hk in headers.keys) {
                    url += hk + "=" + URLEncoder.encode(headers[hk], "UTF-8")
                    if (idx < headers.keys.size - 1) {
                        url += "&"
                    }
                    idx++
                }
            }
            intent.data = Uri.parse(url)
            intent.putExtra("title", title)
            intent.putExtra("name", title)
            if (subtitle != null && subtitle.isNotEmpty()) {
                intent.putExtra("subs", subtitle)
            }
            activity.startActivity(intent)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Can't run Kodi", ex)
            false
        }
    }
}

/**
 * MXPlayer 外部播放,Kotlin 移植(与旧版 MXPlayer 一致)。
 */
object MXPlayer {
    const val TAG = "ThirdParty.MXPlayer"

    private const val PACKAGE_NAME_PRO = "com.mxtech.videoplayer.pro"
    private const val PACKAGE_NAME_AD = "com.mxtech.videoplayer.ad"
    private const val PLAYBACK_ACTIVITY_PRO = "com.mxtech.videoplayer.ActivityScreen"
    private const val PLAYBACK_ACTIVITY_AD = "com.mxtech.videoplayer.ad.ActivityScreen"

    class MXPackageInfo(val packageName: String, val activityName: String)

    private val PACKAGES = arrayOf(
        MXPackageInfo(PACKAGE_NAME_PRO, PLAYBACK_ACTIVITY_PRO),
        MXPackageInfo(PACKAGE_NAME_AD, PLAYBACK_ACTIVITY_AD)
    )

    fun getPackageInfo(): MXPackageInfo? {
        for (pkg in PACKAGES) {
            try {
                val info = KtApp.instance().packageManager.getApplicationInfo(pkg.packageName, 0)
                if (info.enabled) return pkg
                else Log.v(TAG, "MX Player package `" + pkg.packageName + "` is disabled.")
            } catch (ex: PackageManager.NameNotFoundException) {
                Log.v(TAG, "MX Player package `" + pkg.packageName + "` does not exist.")
            }
        }
        return null
    }

    fun run(activity: Activity, url0: String, title: String?, subtitle: String?, headers: HashMap<String, String>?): Boolean {
        val packageInfo = getPackageInfo() ?: return false
        return try {
            var url = url0
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setPackage(packageInfo.packageName)
            intent.setClassName(packageInfo.packageName, packageInfo.activityName)
            if (headers != null && headers.isNotEmpty()) {
                url += "|"
                var idx = 0
                for (hk in headers.keys) {
                    url += hk + "=" + URLEncoder.encode(headers[hk], "UTF-8")
                    if (idx < headers.keys.size - 1) {
                        url += "&"
                    }
                    idx++
                }
            }
            intent.data = Uri.parse(url)
            intent.putExtra("title", title)
            if (subtitle != null && subtitle.isNotEmpty()) {
                val parcels = arrayOfNulls<Parcelable>(1)
                parcels[0] = Uri.parse(subtitle)
                intent.putExtra("subs", parcels)
                intent.putExtra("subs.enable", parcels)
            }
            activity.startActivity(intent)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Can't run MX Player(Pro)", ex)
            false
        }
    }
}

/**
 * ReexPlayer 外部播放,Kotlin 移植(与旧版 ReexPlayer 一致)。
 */
object ReexPlayer {
    const val TAG = "ThirdParty.Reex"

    private const val PACKAGE_NAME = "xyz.re.player.ex"
    private const val PLAYBACK_ACTIVITY = "xyz.re.player.ex.MainActivity"

    class ReexPackageInfo(val packageName: String, val activityName: String)

    private val PACKAGES = arrayOf(ReexPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

    fun getPackageInfo(): ReexPackageInfo? {
        for (pkg in PACKAGES) {
            try {
                val info = KtApp.instance().packageManager.getApplicationInfo(pkg.packageName, 0)
                if (info.enabled) return pkg
                else Log.v(TAG, "Reex Player package `" + pkg.packageName + "` is disabled.")
            } catch (ex: PackageManager.NameNotFoundException) {
                Log.v(TAG, "Reex Player package `" + pkg.packageName + "` does not exist.")
            }
        }
        return null
    }

    fun run(activity: Activity, url: String, title: String?, subtitle: String?, headers: HashMap<String, String>?): Boolean {
        val packageInfo = getPackageInfo() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(packageInfo.packageName)
        intent.setComponent(ComponentName(packageInfo.packageName, packageInfo.activityName))
        intent.data = Uri.parse(url)
        intent.putExtra("title", title)
        intent.putExtra("name", title)
        intent.putExtra("reex.extra.title", title)
        if (headers != null && headers.isNotEmpty()) {
            try {
                val json = JSONObject()
                for (key in headers.keys) {
                    json.put(key, headers[key]!!.trim())
                }
                intent.putExtra("reex.extra.http_header", json.toString())
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
        if (subtitle != null && subtitle.isNotEmpty()) {
            intent.putExtra("reex.extra.subtitle", subtitle)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Can't run Reex Player(Pro)", ex)
            false
        }
    }
}

/**
 * VLC 外部播放,Kotlin 移植(与旧版 VlcPlayer 一致)。
 */
object VlcPlayer {
    const val TAG = "ThirdParty.VLC"

    private const val PACKAGE_NAME = "org.videolan.vlc"
    private const val PLAYBACK_ACTIVITY = "org.videolan.vlc.gui.video.VideoPlayerActivity"

    class VlcPackageInfo(val packageName: String, val activityName: String)

    private val PACKAGES = arrayOf(VlcPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

    fun getPackageInfo(): VlcPackageInfo? {
        for (pkg in PACKAGES) {
            try {
                val info = KtApp.instance().packageManager.getApplicationInfo(pkg.packageName, 0)
                if (info.enabled) return pkg
                else Log.v(TAG, "VLC Player package `" + pkg.packageName + "` is disabled.")
            } catch (ex: PackageManager.NameNotFoundException) {
                Log.v(TAG, "VLC Player package `" + pkg.packageName + "` does not exist.")
            }
        }
        return null
    }

    fun run(activity: Activity, url: String, title: String?, subtitle: String?, progress: Long): Boolean {
        val packageInfo = getPackageInfo() ?: return false

        // https://wiki.videolan.org/Android_Player_Intents/
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(packageInfo.packageName)
        intent.setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
        intent.putExtra("title", title)
        if (subtitle != null && subtitle.isNotEmpty()) {
            intent.putExtra("subtitles_location", subtitle)
        }
        if (progress > 0) {
            intent.putExtra("from_start", false)
            intent.putExtra("position", progress)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Can't run VLC Player(Pro)", ex)
            false
        }
    }
}

/**
 * 附近 TVBox 推送,Kotlin 移植(与旧版 RemoteTVBox 一致)。
 */
object RemoteTVBox {

    interface Callback {
        fun found(viewHost: String, end: Boolean)
        fun fail(all: Boolean, end: Boolean)
    }

    fun run(activity: Activity, url0: String, title: String?, subtitle: String?, headers: HashMap<String, String>?): Boolean {
        val actionUrl = getAvalibleActionUrl()
        if (TextUtils.isEmpty(actionUrl)) {
            return false
        }
        try {
            var url = url0
            if (headers != null && headers.isNotEmpty()) {
                url += "|"
                var idx = 0
                for (hk in headers.keys) {
                    url += URLEncoder.encode(hk, "UTF-8") + "=" + URLEncoder.encode(headers[hk], "UTF-8")
                    if (idx < headers.keys.size - 1) {
                        url += "&"
                    }
                    idx++
                }
            }
            val params = HashMap<String, String>()
            params["do"] = "push"
            params["url"] = url
            post(actionUrl, params, object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    val pushResult = response.body()!!.string()
                    if (pushResult == "ok") {
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    fun searchAvalible(callback: Callback) {
        val localIp = RemoteServer.getLocalIPAddress(KtApp.instance())
        val divisionIp = if (TextUtils.isEmpty(localIp)) -1 else localIp.lastIndexOf(".")
        if (divisionIp <= 0) {
            callback.fail(true, true)
            return
        }
        val prefix = localIp.substring(0, divisionIp + 1)
        val port = 9978
        val finishedNum = AtomicInteger(0)
        val foundNum = AtomicInteger(0)
        val total = 254
        for (i in 1..255) {
            val ip = "$prefix$i"
            if (ip == localIp) {
                continue
            }
            val actionUrl = "http://$ip:$port/action"
            val viewHost = "$ip:$port"
            try {
                post(actionUrl, null, object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        notifySearchFail(callback, foundNum, finishedNum, total)
                    }

                    @Throws(IOException::class)
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.body()?.string() ?: ""
                            val end = finishedNum.incrementAndGet() == total
                            if (result.equals("ok", ignoreCase = true)) {
                                foundNum.incrementAndGet()
                                callback.found(viewHost, end)
                            } else {
                                callback.fail(foundNum.get() == 0 && end, end)
                            }
                        } finally {
                            response.close()
                        }
                    }
                })
            } catch (e: Exception) {
                notifySearchFail(callback, foundNum, finishedNum, total)
            }
        }
    }

    private fun notifySearchFail(callback: Callback, foundNum: AtomicInteger, finishedNum: AtomicInteger, total: Int) {
        val end = finishedNum.incrementAndGet() == total
        callback.fail(foundNum.get() == 0 && end, end)
    }

    fun getAvalible(): String? {
        return Hawk.get<String>(HawkConfig.REMOTE_TVBOX, null)
    }

    fun getAvalibleActionUrl(): String {
        if (getAvalible() == null) {
            return ""
        }
        return "http://" + getAvalible() + "/action"
    }

    fun setAvalible(viewHost: String) {
        Hawk.put(HawkConfig.REMOTE_TVBOX, viewHost)
    }

    fun post(url: String, params: Map<String, String>?, callback: okhttp3.Callback) {
        val base = OkGoHelper.getDefaultClient()
        val builder = base?.newBuilder()
            ?: OkHttpClient.Builder()
                .proxySelector(OkGoHelper.proxySelector())
                .proxyAuthenticator(OkGoHelper.proxyAuthenticator())
        builder.readTimeout(1000, TimeUnit.MILLISECONDS)
        builder.writeTimeout(1000, TimeUnit.MILLISECONDS)
        builder.connectTimeout(1000, TimeUnit.MILLISECONDS)
        val client = builder.build()
        val formBodyBuilder = FormBody.Builder()
        if (params != null && params.isNotEmpty()) {
            for (entry in params.entries) {
                formBodyBuilder.add(entry.key, entry.value)
            }
        }
        val formBody = formBodyBuilder.build()
        client.newCall(Request.Builder().url(url).post(formBody).build()).enqueue(callback)
    }
}
