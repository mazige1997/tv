package com.github.tvbox.kt.ui.dialog

import android.content.Context
import android.content.DialogInterface
import android.widget.TextView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.cache.VodCollect
import com.github.tvbox.kt.ui.activity.CollectActivity
import com.github.tvbox.kt.ui.activity.HistoryActivity
import java.util.ArrayList

/** 双按钮提示对话框,Kotlin 移植(与旧版 TipDialog 一致)。 */
class TipDialog(
    context: Context,
    tip: String,
    left: String,
    right: String,
    listener: OnListener
) : BaseDialog(context) {

    interface OnListener {
        fun left()

        fun right()

        fun cancel()
    }

    init {
        setContentView(R.layout.dialog_tip)
        setCanceledOnTouchOutside(false)
        val tipInfo = findViewById<TextView>(R.id.tipInfo)
        val leftBtn = findViewById<TextView>(R.id.leftBtn)
        val rightBtn = findViewById<TextView>(R.id.rightBtn)
        tipInfo.text = tip
        leftBtn.text = left
        rightBtn.text = right
        leftBtn.setOnClickListener { listener.left() }
        rightBtn.setOnClickListener { listener.right() }
        setOnCancelListener { listener.cancel() }
    }
}

/** 清空确认对话框,Kotlin 移植(与旧版 ConfirmClearDialog 一致)。 */
class ConfirmClearDialog(context: Context, type: String) : BaseDialog(context) {
    private val tvYes: TextView
    private val tvNo: TextView

    init {
        setContentView(R.layout.dialog_confirm)
        setCanceledOnTouchOutside(true)
        tvYes = findViewById(R.id.btnConfirm)
        tvNo = findViewById(R.id.btnCancel)

        tvYes.setOnClickListener {
            // if removing all Favorites
            if (type == "Collect") {
                val vodInfoList = ArrayList<VodCollect>()
                // P2-1:改为空安全调用,适配宿主 Activity 销毁时静态 adapter 已置空的场景
                CollectActivity.collectAdapter?.setNewData(vodInfoList)
                CollectActivity.collectAdapter?.notifyDataSetChanged()
                RoomDataManger.deleteVodCollectAll()
                // if removing all History
            } else if (type == "History") {
                val vodInfoList = ArrayList<VodInfo>()
                HistoryActivity.historyAdapter?.setNewData(vodInfoList)
                HistoryActivity.historyAdapter?.notifyDataSetChanged()
                RoomDataManger.deleteVodRecordAll()
            }
            dismiss()
        }
        tvNo.setOnClickListener { dismiss() }
    }
}

/** 关于对话框,Kotlin 移植(与旧版 AboutDialog 一致)。 */
class AboutDialog(context: Context) : BaseDialog(context) {
    init {
        setContentView(R.layout.dialog_about)
    }
}

/** 影片简介对话框,Kotlin 移植(与旧版 DescDialog 一致)。 */
class DescDialog(context: Context) : BaseDialog(context) {

    fun setDescribe(describe: String) {
        val tvDescribe = findViewById<TextView>(R.id.describe)
        tvDescribe.text = describe
        tvDescribe.requestFocus()
        tvDescribe.requestFocusFromTouch()
    }
}
