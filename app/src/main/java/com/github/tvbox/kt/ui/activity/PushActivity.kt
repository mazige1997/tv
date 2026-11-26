package com.github.tvbox.kt.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.receiver.PushReceiver
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.compose.PushScreen
import com.github.tvbox.kt.ui.compose.TvTheme
import com.github.tvbox.kt.ui.tv.widget.QRCodeGen
import me.jessyan.autosize.utils.AutoSizeUtils

/** 推送入口页(二维码 + 剪贴板推送),UI 由 Jetpack Compose 承载。 */
class PushActivity : BaseActivity() {
    private val qrBitmap = mutableStateOf<Bitmap?>(null)
    private val addressText = mutableStateOf("")

    override val layoutResID: Int
        get() = R.layout.activity_simple_compose

    override fun init() {
        refreshQRCode()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeView).setContent {
            TvTheme {
                PushScreen(
                    qrBitmap = qrBitmap.value,
                    address = addressText.value,
                    onPushClipboard = ::pushClipboard
                )
            }
        }
    }

    private fun refreshQRCode() {
        val address = ControlManager.get().getAddress(false)
        addressText.value = String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address)
        qrBitmap.value = QRCodeGen.generateBitmap(
            address + "push.html",
            AutoSizeUtils.mm2px(this, 300f),
            AutoSizeUtils.mm2px(this, 300f),
            4
        )
    }

    private fun pushClipboard() {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (manager != null && manager.hasPrimaryClip() && manager.primaryClip != null && manager.primaryClip!!.itemCount > 0) {
                val item: ClipData.Item = manager.primaryClip!!.getItemAt(0)
                val text = item.coerceToText(this)
                if (text != null) PushReceiver.send(this, text.toString().trim())
            }
        } catch (th: Throwable) {
            // 剪贴板内容不可用时忽略
        }
    }
}
