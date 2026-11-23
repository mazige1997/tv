package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.github.tvbox.kt.R
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.adapter.ApiHistoryDialogAdapter
import com.github.tvbox.kt.ui.tv.widget.QRCodeGen
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.orhanobut.hawk.Hawk
import me.jessyan.autosize.utils.AutoSizeUtils
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList

/** 配置地址对话框,Kotlin 移植(与旧版 ApiDialog 一致)。 */
class ApiDialog(context: Context) : BaseDialog(context) {
    private val ivQRCode: ImageView
    private val tvAddress: TextView
    private val inputApi: EditText
    private val inputApiLive: EditText
    private val inputConfirm: View

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            inputApi.setText(event.obj as String?)
            inputApiLive.setText(event.obj as String?)
        } else if (event.type == RefreshEvent.TYPE_LIVE_API_URL_CHANGE) {
            inputApiLive.setText(event.obj as String?)
        }
    }

    private var listener: OnListener? = null

    interface OnListener {
        fun onchange(api: String)

        fun onLocalConfig(live: Boolean)
    }

    init {
        setContentView(R.layout.dialog_api)
        setCanceledOnTouchOutside(false)
        ivQRCode = findViewById(R.id.ivQRCode)
        tvAddress = findViewById(R.id.tvAddress)
        inputApi = findViewById(R.id.input)
        inputApiLive = findViewById(R.id.inputLive)
        inputConfirm = findViewById(R.id.inputConfirm)
        // 内置网络接口在此处添加
        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""))
        inputApiLive.setText(Hawk.get(HawkConfig.LIVE_API_URL, Hawk.get(HawkConfig.API_URL, "")))
        findViewById<View>(R.id.inputSubmit).setOnClickListener {
            listener?.onLocalConfig(false)
        }
        findViewById<View>(R.id.inputSubmitLive).setOnClickListener {
            listener?.onLocalConfig(true)
        }
        inputConfirm.setOnClickListener { saveAndDismiss() }
        findViewById<View>(R.id.apiHistory).setOnClickListener {
            val history = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
            if (history.isEmpty()) {
                Toast.makeText(context, "直播历史为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = Hawk.get(HawkConfig.LIVE_API_URL, "")
            var idx = 0
            if (history.contains(current)) idx = history.indexOf(current)
            val dialog = ApiHistoryDialog(context)
            dialog.setTip("直播历史配置")
            dialog.setAdapter(object : ApiHistoryDialogAdapter.SelectDialogInterface {
                override fun click(value: String) {
                    inputApiLive.setText(value)
                    Hawk.put(HawkConfig.LIVE_API_URL, value)
                    dialog.dismiss()
                }

                override fun del(value: String, data: ArrayList<String>) {
                    Hawk.put(HawkConfig.LIVE_API_HISTORY, data)
                }
            }, history, idx)
            dialog.show()
        }
        findViewById<View>(R.id.storagePermission).setOnClickListener { v ->
            if (XXPermissions.isGranted(context, Permission.Group.STORAGE)) {
                Toast.makeText(context, "已获得存储权限", Toast.LENGTH_SHORT).show()
            } else {
                XXPermissions.with(context)
                    .permission(Permission.Group.STORAGE)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                            if (all) {
                                Toast.makeText(context, "已获得存储权限", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                            if (never) {
                                Toast.makeText(context, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show()
                                XXPermissions.startPermissionActivity(context as Activity, permissions)
                            } else {
                                Toast.makeText(context, "获取存储权限失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
            }
        }
        inputApi.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || isSoftKeyboardEnter(actionId, event)) {
                inputApiLive.requestFocus()
                inputApiLive.setSelection(inputApiLive.text?.length ?: 0)
                true
            } else {
                false
            }
        }
        inputApiLive.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || isSoftKeyboardEnter(actionId, event)) {
                focusConfirm()
                true
            } else {
                false
            }
        }
        refreshQRCode()
    }

    private fun isSoftKeyboardEnter(actionId: Int, event: KeyEvent?): Boolean {
        return actionId == EditorInfo.IME_NULL &&
            event != null &&
            event.action == KeyEvent.ACTION_UP &&
            event.keyCode == KeyEvent.KEYCODE_ENTER
    }

    private fun focusConfirm() {
        val imm = inputApiLive.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(inputApiLive.windowToken, 0)
        inputApiLive.clearFocus()
        inputConfirm.requestFocus()
    }

    private fun refreshQRCode() {
        val address = ControlManager.get().getAddress(false)
        tvAddress.text = String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address)
        ivQRCode.setImageBitmap(
            QRCodeGen.generateBitmap(
                address + "api.html",
                AutoSizeUtils.mm2px(context, 300f),
                AutoSizeUtils.mm2px(context, 300f)
            )
        )
    }

    private fun saveAndDismiss() {
        val newApi = inputApi.text.toString().trim()
        var newLiveApi = inputApiLive.text.toString().trim()
        if (newApi.isNotEmpty()) {
            HistoryHelper.setApiHistory(newApi)
            if (newApi != Hawk.get(HawkConfig.API_URL, newApi)) {
                inputApiLive.setText(newApi)
                newLiveApi = newApi
            }
        }
        if (newLiveApi.isNotEmpty()) {
            HistoryHelper.setLiveApiHistory(newLiveApi)
        }
        Hawk.put(HawkConfig.LIVE_API_URL, newLiveApi)
        listener?.onchange(newApi)
        dismiss()
    }

    fun setOnListener(listener: OnListener?) {
        this.listener = listener
    }

    fun setLocalApi(api: String, live: Boolean) {
        if (live) {
            inputApiLive.setText(api)
        } else {
            inputApi.setText(api)
        }
    }
}
