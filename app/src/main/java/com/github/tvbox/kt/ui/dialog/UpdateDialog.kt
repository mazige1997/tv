package com.github.tvbox.kt.ui.dialog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.github.tvbox.kt.R
import com.github.tvbox.kt.util.UpdateChecker

/** 更新信息对话框:展示最新版本信息并跳转 Release 页,不下载、不安装。 */
class UpdateDialog(
    context: Context,
    release: UpdateChecker.Release,
    currentVersion: String
) : BaseDialog(context) {

    init {
        setContentView(R.layout.dialog_update)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(R.id.tvUpdateCurrent).text = "当前版本:$currentVersion"
        findViewById<TextView>(R.id.tvUpdateLatest).text = "最新版本:${release.tag}"
        findViewById<TextView>(R.id.tvUpdateTime).text = "发布时间:${release.publishedAt}"
        findViewById<TextView>(R.id.tvUpdateNotes).text =
            release.notes.trim().ifEmpty { "暂无更新说明" }
        findViewById<View>(R.id.btnUpdateCancel).setOnClickListener { dismiss() }
        val download = findViewById<View>(R.id.btnUpdateDownload)
        download.setOnClickListener {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
            } catch (t: Throwable) {
                Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }
        download.requestFocus()
    }
}
