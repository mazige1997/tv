package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.kt.R
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.adapter.ApiHistoryDialogAdapter
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter
import com.github.tvbox.kt.ui.tv.widget.QRCodeGen
import com.owen.tvrecyclerview.widget.TvRecyclerView
import me.jessyan.autosize.utils.AutoSizeUtils

/** 通用选择对话框,Kotlin 移植(与旧版 SelectDialog 一致)。 */
open class SelectDialog<T>(context: Context) : BaseDialog(context) {

    constructor(context: Context, resId: Int) : this(context) {
        setContentView(resId)
    }

    init {
        setContentView(R.layout.dialog_select)
    }

    fun setTip(tip: String) {
        (findViewById<View>(R.id.title) as TextView).text = tip
    }

    fun setAdapter(
        sourceBeanSelectDialogInterface: SelectDialogAdapter.SelectDialogInterface<T>,
        sourceBeanItemCallback: DiffUtil.ItemCallback<T>,
        data: List<T>,
        select: Int
    ) {
        val selectIdx = select
        val adapter = SelectDialogAdapter(sourceBeanSelectDialogInterface, sourceBeanItemCallback)
        adapter.setData(data, select)
        val tvRecyclerView = findViewById<TvRecyclerView>(R.id.list)
        tvRecyclerView.setAdapter(adapter)
        tvRecyclerView.setSelectedPosition(select)
        if (select < 10) {
            tvRecyclerView.setSelection(select)
        }
        tvRecyclerView.post {
            if (selectIdx >= 10) {
                tvRecyclerView.smoothScrollToPosition(selectIdx)
                tvRecyclerView.setSelectionWithSmooth(selectIdx)
            }
        }
    }
}

/** 直播分组密码对话框,Kotlin 移植(与旧版 LivePasswordDialog 一致)。 */
class LivePasswordDialog(context: Context) : BaseDialog(context) {
    private val inputPassword: EditText

    private var listener: OnListener? = null

    interface OnListener {
        fun onChange(password: String)

        fun onCancel()
    }

    init {
        setOwnerActivity(context as Activity)
        setContentView(R.layout.dialog_live_password)
        inputPassword = findViewById(R.id.input)
        findViewById<View>(R.id.inputSubmit).setOnClickListener { v ->
            val password = inputPassword.text.toString().trim()
            if (password.isNotEmpty()) {
                listener?.onChange(password)
                dismiss()
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        listener?.onCancel()
        dismiss()
    }

    fun setOnListener(listener: OnListener?) {
        this.listener = listener
    }
}

/** 遥控二维码对话框,Kotlin 移植(与旧版 RemoteDialog 一致)。 */
class RemoteDialog(context: Context) : BaseDialog(context) {
    private val ivQRCode: ImageView
    private val tvAddress: TextView

    init {
        setContentView(R.layout.dialog_remote)
        setCanceledOnTouchOutside(false)
        ivQRCode = findViewById(R.id.ivQRCode)
        tvAddress = findViewById(R.id.tvAddress)
        refreshQRCode()
    }

    private fun refreshQRCode() {
        val address = ControlManager.get().getAddress(false)
        tvAddress.text = String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address)
        ivQRCode.setImageBitmap(
            QRCodeGen.generateBitmap(
                address,
                AutoSizeUtils.mm2px(context, 240f),
                AutoSizeUtils.mm2px(context, 240f)
            )
        )
    }
}

/** 接口历史对话框,Kotlin 移植(与旧版 ApiHistoryDialog 一致)。 */
class ApiHistoryDialog(context: Context) : BaseDialog(context, R.style.CustomDialogStyleDim) {

    init {
        setContentView(R.layout.dialog_api_history)
    }

    fun setTip(tip: String) {
        (findViewById<View>(R.id.title) as TextView).text = tip
    }

    fun setAdapter(
        sourceBeanSelectDialogInterface: ApiHistoryDialogAdapter.SelectDialogInterface,
        data: List<String>,
        select: Int
    ) {
        val adapter = ApiHistoryDialogAdapter(sourceBeanSelectDialogInterface)
        adapter.setData(data, select)
        val tvRecyclerView = findViewById<TvRecyclerView>(R.id.list)
        tvRecyclerView.setAdapter(adapter)
        tvRecyclerView.setSelectedPosition(select)
        tvRecyclerView.post { tvRecyclerView.scrollToPosition(select) }
    }
}
