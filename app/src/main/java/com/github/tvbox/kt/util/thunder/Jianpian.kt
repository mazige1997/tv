package com.github.tvbox.kt.util.thunder

import android.net.Uri
import android.text.TextUtils
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.util.LocalIPAddress
import com.p2p.P2PClass
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 先锋(p2p)链接解析,Kotlin 移植(与旧版 Jianpian 一致,依赖 libp2p.so)。
 */
object Jianpian {

    fun JPUrlDec(url: String): String {
        return if (KtApp.getp2p() != null) {
            try {
                val decode = URLDecoder.decode(url, "UTF-8")
                val split = decode.split("\\|".toRegex()).toTypedArray()
                var replace = split[0].replace("xg://", "ftp://")
                if (replace.contains("xgplay://")) {
                    replace = split[0].replace("xgplay://", "ftp://")
                }
                if (!TextUtils.isEmpty(KtApp.burl)) {
                    KtApp.getp2p()!!.P2Pdoxpause(KtApp.burl!!.toByteArray(charset("GBK")))
                    KtApp.getp2p()!!.P2Pdoxdel(KtApp.burl!!.toByteArray(charset("GBK")))
                }
                KtApp.burl = replace
                KtApp.getp2p()!!.P2Pdoxstart(replace.toByteArray(charset("GBK")))
                KtApp.getp2p()!!.P2Pdoxadd(replace.toByteArray(charset("GBK")))
                "http://" + LocalIPAddress.getIP(KtApp.instance()) + ":" + P2PClass.port + "/" +
                    URLEncoder.encode(Uri.parse(replace).lastPathSegment, "GBK")
            } catch (e: Exception) {
                e.localizedMessage ?: ""
            }
        } else {
            ""
        }
    }

    fun finish() {
        if (!TextUtils.isEmpty(KtApp.burl) && KtApp.getp2p() != null) {
            try {
                KtApp.getp2p()!!.P2Pdoxpause(KtApp.burl!!.toByteArray(charset("GBK")))
                KtApp.getp2p()!!.P2Pdoxdel(KtApp.burl!!.toByteArray(charset("GBK")))
                KtApp.burl = ""
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isJpUrl(url: String): Boolean {
        return url.startsWith("tvbox-xg:") || (Thunder.isFtp(url) && url.contains("gbl.114s"))
    }
}
