package com.github.tvbox.kt.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.receiver.PushReceiver
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.tv.widget.QRCodeGen
import me.jessyan.autosize.utils.AutoSizeUtils

/** 推送入口页(二维码 + 剪贴板推送),Kotlin 移植(与旧版 PushActivity 一致)。 */
class PushActivity : BaseActivity() {
    private var ivQRCode: ImageView? = null
    private var tvAddress: TextView? = null

    override val layoutResID: Int
        get() = R.layout.activity_push

    override fun init() {
        initView()
        initData()
    }

    private fun initView() {
        ivQRCode = findViewById(R.id.ivQRCode)
        tvAddress = findViewById(R.id.tvAddress)
        refreshQRCode()
        findViewById<View>(R.id.pushLocal).setOnClickListener { _ ->
            try {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (manager != null) {
                    if (manager.hasPrimaryClip() && manager.primaryClip != null && manager.primaryClip!!.itemCount > 0) {
                        val addedText: ClipData.Item = manager.primaryClip!!.getItemAt(0)
                        val text = addedText.coerceToText(this)
                        if (text != null) PushReceiver.send(this, text.toString().trim())
                    }
                }
            } catch (th: Throwable) {

            }
        }
    }

    private fun refreshQRCode() {
        val address = ControlManager.get().getAddress(false)
        tvAddress?.text = String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address)
        ivQRCode?.setImageBitmap(
            QRCodeGen.generateBitmap(
                address + "push.html",
                AutoSizeUtils.mm2px(this, 300f),
                AutoSizeUtils.mm2px(this, 300f),
                4
            )
        )
    }

    private fun initData() {

    }
}
