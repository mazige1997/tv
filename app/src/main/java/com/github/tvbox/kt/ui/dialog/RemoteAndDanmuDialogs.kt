package com.github.tvbox.kt.ui.dialog

import android.content.Context
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.callback.EmptyCallback
import com.github.tvbox.kt.callback.LoadingCallback
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.player.thirdparty.RemoteTVBox
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter
import com.github.tvbox.kt.ui.fragment.ModelSettingFragment
import com.github.tvbox.kt.util.HawkConfig
import com.kingja.loadsir.callback.Callback
import com.kingja.loadsir.core.LoadService
import com.kingja.loadsir.core.LoadSir
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** 附近 TVBox 搜索对话框,Kotlin 移植(与旧版 SearchRemoteTvDialog 一致)。 */
class SearchRemoteTvDialog(context: Context) : BaseDialog(context) {

    private var mLoadService: LoadService<*>? = null

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SETTING_SEARCH_TV) {
            showRemoteTvDialog(ModelSettingFragment.foundRemoteTv)
        }
    }

    init {
        setContentView(R.layout.dialog_search_remotetv)
    }

    fun setTip(tip: String) {
        (findViewById<View>(R.id.title) as TextView).text = tip
        setLoadSir(findViewById(R.id.list))
        showLoading()
    }

    private fun showRemoteTvDialog(found: Boolean) {
        if (!found) {
            ModelSettingFragment.loadingSearchRemoteTvDialog?.showEmpty()
            Toast.makeText(context, "未找到附近TVBox", Toast.LENGTH_SHORT).show()
            return
        }
        ModelSettingFragment.loadingSearchRemoteTvDialog?.dismiss()
        val remoteTvHostList = ModelSettingFragment.remoteTvHostList ?: return
        RemoteTVBox.setAvalible(remoteTvHostList[0])
        val dialog = SelectDialog<String>(context)
        dialog.setTip("附近TVBox")
        val defaultPos = 0
        dialog.setAdapter(
            object : SelectDialogAdapter.SelectDialogInterface<String> {
                override fun click(value: String, pos: Int) {
                    RemoteTVBox.setAvalible(value)
                    Toast.makeText(context, "设置成功", Toast.LENGTH_SHORT).show()
                }

                override fun getDisplay(`val`: String): String {
                    return `val`
                }
            },
            object : DiffUtil.ItemCallback<String>() {
                override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                    return oldItem == newItem
                }
            },
            remoteTvHostList, defaultPos
        )
        dialog.show()
    }

    private fun setLoadSir(view: View) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view) { }
        }
    }

    fun showLoading() {
        mLoadService?.showCallback(LoadingCallback::class.java)
    }

    fun showEmpty() {
        mLoadService?.showCallback(EmptyCallback::class.java)
    }

    fun showSuccess() {
        mLoadService?.showSuccess()
    }
}

/** 弹幕接口配置对话框,Kotlin 移植(与旧版 DanmuApiDialog 一致)。 */
class DanmuApiDialog(context: Context) : BaseDialog(context) {
    private val input: EditText
    private var listener: OnListener? = null

    interface OnListener {
        fun onChange(api: String)
    }

    init {
        setContentView(R.layout.dialog_danmu_api)
        setCanceledOnTouchOutside(false)
        input = findViewById(R.id.input)
        input.setText(Hawk.get(HawkConfig.DANMU_API, ""))
        input.hint = getDefaultApi()
        findViewById<View>(R.id.inputDefault).setOnClickListener { saveDefault() }
        findViewById<View>(R.id.inputSubmit).setOnClickListener { save(input.text.toString().trim()) }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                save(input.text.toString().trim())
                true
            } else {
                false
            }
        }
    }

    private fun getDefaultApi(): String {
        val api = DanmakuApi.getDisplayApiUrl()
        return if (api.isEmpty()) "请输入弹幕搜索地址" else api
    }

    private fun save(api: String) {
        DanmakuApi.setCustomApi(api)
        listener?.onChange(api)
        dismiss()
    }

    private fun saveDefault() {
        DanmakuApi.setUseDefault(true)
        listener?.onChange("")
        dismiss()
    }

    fun setOnListener(listener: OnListener?) {
        this.listener = listener
    }
}
