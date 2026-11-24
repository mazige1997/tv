package com.github.tvbox.kt.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.github.tvbox.kt.R
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.LOG

/**
 * 诊断日志对话框。
 *
 * 在没有 adb 的电视盒上,可直接查看 / 复制 / 分享运行时链路日志,
 * 用于定位「配置加载 / 爬虫初始化 / 分类 / 详情 / 直播」在哪一步断开。
 */
class LogDialog(context: Context) : BaseDialog(context) {

    private val infoView: TextView
    private val content: TextView
    private var fullText = ""

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        setContentView(R.layout.dialog_log)
        infoView = findViewById(R.id.tvDiagInfo)
        content = findViewById(R.id.tvDiagContent)

        findViewById<View>(R.id.btnDiagCopy).setOnClickListener {
            val text = shareText()
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                toast("剪贴板不可用")
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("TVBox 诊断日志", text))
                toast("已复制 ${text.length} 字符,可粘贴发送")
            }
        }

        findViewById<View>(R.id.btnDiagShare).setOnClickListener {
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TVBox 诊断日志")
                    putExtra(Intent.EXTRA_TEXT, shareText())
                }
                val chooser = Intent.createChooser(send, "分享诊断日志")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (th: Throwable) {
                toast("没有可用的分享应用: ${th.message}")
            }
        }

        findViewById<View>(R.id.btnDiagRefresh).setOnClickListener { refresh() }

        findViewById<View>(R.id.btnDiagClear).setOnClickListener {
            LOG.clearDiag()
            LOG.i("DIAG log cleared by user")
            refresh()
        }

        findViewById<View>(R.id.btnDiagClose).setOnClickListener { dismiss() }

        refresh()
    }

    /** 分享/复制有 Binder 大小限制:保留头部启动快照 + 尾部最新日志,避免关键状态被截掉。 */
    private fun shareText(): String {
        if (fullText.length <= MAX_SHARE_CHARS) return fullText
        val head = fullText.substring(0, HEAD_KEEP_CHARS)
        val tail = fullText.substring(fullText.length - (MAX_SHARE_CHARS - HEAD_KEEP_CHARS))
        return head + "\n\n...(中间日志已省略;完整内容见文件 " + LOG.diagPath() + ")...\n\n" + tail
    }

    private fun refresh() {
        // 每次打开/刷新诊断日志时统计一次边下边播缓存:写入日志(复制/分享可带上),
        // 同时直接显示在对话框信息栏,避免 Android 11+ 私有目录无法用文件管理器查看。
        val cacheSummary = FileUtils.exoCacheSummary()
        LOG.i("DIAG exo_cache $cacheSummary")
        fullText = LOG.readDiag()
        val lines = fullText.lines()
        val diagLines = lines.filter { it.contains("DIAG ") }
        val header = "文件: ${LOG.diagPath()}  |  共 ${lines.size} 行 / ${fullText.length} 字符" +
            (if (lines.size > MAX_SHOW_LINES) "  |  下方仅显示最近 $MAX_SHOW_LINES 行" else "") +
            "\n边下边播缓存: $cacheSummary"
        infoView.text = header + (if (diagLines.isEmpty()) {
            "\n(暂无 DIAG 快照行 —— 可能安装的不是含诊断日志的版本)"
        } else {
            "\n" + diagLines.takeLast(12).joinToString("\n")
        })
        content.text = if (lines.size > MAX_SHOW_LINES) {
            lines.takeLast(MAX_SHOW_LINES).joinToString("\n")
        } else {
            fullText
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_SHARE_CHARS = 180_000
        private const val HEAD_KEEP_CHARS = 20_000
        private const val MAX_SHOW_LINES = 300
    }
}
