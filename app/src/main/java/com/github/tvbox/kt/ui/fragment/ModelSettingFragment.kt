package com.github.tvbox.kt.ui.fragment

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.kt.R
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.base.BaseLazyFragment
import com.github.tvbox.kt.bean.IJKCode
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.activity.LocalFileActivity
import com.github.tvbox.kt.ui.activity.SettingActivity
import com.github.tvbox.kt.ui.adapter.ApiHistoryDialogAdapter
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter
import com.github.tvbox.kt.ui.dialog.AboutDialog
import com.github.tvbox.kt.ui.dialog.ApiDialog
import com.github.tvbox.kt.ui.dialog.ApiHistoryDialog
import com.github.tvbox.kt.ui.dialog.BackupDialog
import com.github.tvbox.kt.ui.dialog.DanmuApiDialog
import com.github.tvbox.kt.ui.dialog.SearchRemoteTvDialog
import com.github.tvbox.kt.ui.dialog.SelectDialog
import com.github.tvbox.kt.ui.dialog.UpdateDialog
import com.github.tvbox.kt.ui.dialog.XWalkInitDialog
import com.github.tvbox.kt.util.DanmuHelper
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.MD5
import com.github.tvbox.kt.util.OkGoHelper
import com.github.tvbox.kt.util.PlayerHelper
import com.github.tvbox.kt.util.UpdateChecker
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.FileCallback
import com.lzy.okgo.model.Progress
import com.lzy.okgo.model.Response
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.EventBus
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayList

/**
 * 设置 Fragment(解码/播放器/数据源/弹幕等全部设置项),Kotlin 移植(与旧版 ModelSettingFragment 一致)。
 * TODO: 依赖 com.github.tvbox.kt.ui.activity.LocalFileActivity / HomeActivity(由 ui.activity 包移植任务提供)。
 * TODO: IjkMediaPlayer.toggleDotPort 引用原 tv.danmaku.ijk.media.player.IjkMediaPlayer 类。
 */
class ModelSettingFragment : BaseLazyFragment() {
    private var tvDebugOpen: TextView? = null
    private var tvMediaCodec: TextView? = null
    private var tvParseWebView: TextView? = null
    private var tvPlay: TextView? = null
    private var tvRender: TextView? = null
    private var tvScale: TextView? = null
    private var tvApi: TextView? = null
    private var tvApiLine: TextView? = null
    private var llApi: View? = null
    private var llApiHistory: View? = null
    private var llApiLine: View? = null
    private var tvHomeApi: TextView? = null
    private var tvDns: TextView? = null
    private var tvHomeRec: TextView? = null
    private var tvHistoryNum: TextView? = null
    private var tvHistoryMerge: TextView? = null
    private var tvSearchView: TextView? = null
    private var tvShowPreviewText: TextView? = null
    private var tvFastSearchText: TextView? = null
    private var tvm3u8AdText: TextView? = null
    private var tvAutoSwitchLineText: TextView? = null
    private var tvRecStyleText: TextView? = null
    private var tvIjkCachePlay: TextView? = null
    private var tvPlayCache: TextView? = null
    private var tvLogDisk: TextView? = null
    private var tvHomeDefaultShow: TextView? = null
    private var apiDialog: ApiDialog? = null
    private var selectLocalLive = false
    private var tvDanmuOpenText: TextView? = null
    private var tvDanmuApiText: TextView? = null

    fun setArguments(): ModelSettingFragment {
        return this
    }

    override val layoutResID: Int
        get() = R.layout.fragment_model

    override fun init() {
        tvFastSearchText = findViewById(R.id.showFastSearchText)
        tvFastSearchText!!.text = if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) "开启" else "关闭"
        tvm3u8AdText = findViewById(R.id.m3u8AdText)
        tvm3u8AdText!!.text = if (Hawk.get(HawkConfig.M3U8_PURIFY, false)) "开启" else "关闭"
        tvDanmuOpenText = findViewById(R.id.danmuOpenText)
        tvDanmuOpenText!!.text = if (DanmuHelper.isOpen()) "开启" else "关闭"
        tvDanmuApiText = findViewById(R.id.danmuApiText)
        refreshDanmuApiText()
        tvAutoSwitchLineText = findViewById(R.id.autoSwitchLineText)
        tvAutoSwitchLineText!!.text = if (Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true)) "开启" else "关闭"
        tvRecStyleText = findViewById(R.id.showRecStyleText)
        tvRecStyleText!!.text = if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) "是" else "否"
        tvShowPreviewText = findViewById(R.id.showPreviewText)
        tvShowPreviewText!!.text = if (Hawk.get(HawkConfig.SHOW_PREVIEW, true)) "开启" else "关闭"
        tvDebugOpen = findViewById(R.id.tvDebugOpen)
        tvParseWebView = findViewById(R.id.tvParseWebView)
        tvMediaCodec = findViewById(R.id.tvMediaCodec)
        tvPlay = findViewById(R.id.tvPlay)
        tvRender = findViewById(R.id.tvRenderType)
        tvScale = findViewById(R.id.tvScaleType)
        llApi = findViewById(R.id.llApi)
        llApiHistory = findViewById(R.id.llApiHistory)
        llApiLine = findViewById(R.id.llApiLine)
        tvApi = findViewById(R.id.tvApi)
        tvApiLine = findViewById(R.id.tvApiLine)
        tvHomeApi = findViewById(R.id.tvHomeApi)
        tvDns = findViewById(R.id.tvDns)
        tvHomeRec = findViewById(R.id.tvHomeRec)
        tvHistoryNum = findViewById(R.id.tvHistoryNum)
        tvHistoryMerge = findViewById(R.id.tvHistoryMerge)
        tvSearchView = findViewById(R.id.tvSearchView)
        tvIjkCachePlay = findViewById(R.id.tvIjkCachePlay)
        tvPlayCache = findViewById(R.id.tvPlayCache)
        tvPlayCache!!.text = if (Hawk.get(HawkConfig.PLAY_CACHE, false)) "开启" else "关闭"
        tvLogDisk = findViewById(R.id.tvLogDisk)
        tvLogDisk!!.text = if (Hawk.get(HawkConfig.LOG_DISK, true)) "开启" else "关闭"
        tvMediaCodec!!.text = Hawk.get(HawkConfig.IJK_CODEC, "硬解码")
        tvDebugOpen!!.text = if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) "已打开" else "已关闭"
        tvParseWebView!!.text = if (Hawk.get(HawkConfig.PARSE_WEBVIEW, true)) "系统自带" else "XWalkView"
        tvApi!!.text = Hawk.get(HawkConfig.API_URL, "")
        refreshApiLineText()

        tvDns!!.text = OkGoHelper.dnsHttpsList[Hawk.get(HawkConfig.DOH_URL, 0)]
        tvHomeRec!!.text = getHomeRecName(Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC))
        tvHistoryNum!!.text = HistoryHelper.getHistoryNumName(Hawk.get(HawkConfig.HISTORY_NUM, 0))
        tvHistoryMerge!!.text = if (Hawk.get(HawkConfig.HISTORY_MERGE, false)) "开启" else "关闭"
        tvSearchView!!.text = getSearchView(Hawk.get(HawkConfig.SEARCH_VIEW, 0))
        tvHomeApi!!.text = ApiConfig.get().homeSourceBean.name
        tvScale!!.text = PlayerHelper.getScaleName(Hawk.get(HawkConfig.PLAY_SCALE, 0))
        tvPlay!!.text = PlayerHelper.getPlayerName(Hawk.get(HawkConfig.PLAY_TYPE, 2))
        tvRender!!.text = PlayerHelper.getRenderName(Hawk.get(HawkConfig.PLAY_RENDER, 0))
        tvIjkCachePlay!!.text = if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) "开启" else "关闭"
        tvHomeDefaultShow = findViewById(R.id.tvHomeText)
        tvHomeDefaultShow!!.text = if (Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) "直播" else "点播"
        findViewById<View>(R.id.llDebug)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            Hawk.put(HawkConfig.DEBUG_OPEN, !Hawk.get(HawkConfig.DEBUG_OPEN, false))
            tvDebugOpen!!.text = if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) "已打开" else "已关闭"
        }
        findViewById<View>(R.id.llParseWebVew)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val useSystem = !Hawk.get(HawkConfig.PARSE_WEBVIEW, true)
            Hawk.put(HawkConfig.PARSE_WEBVIEW, useSystem)
            tvParseWebView!!.text = if (Hawk.get(HawkConfig.PARSE_WEBVIEW, true)) "系统自带" else "XWalkView"
            if (!useSystem) {
                Toast.makeText(mContext, "注意: XWalkView只适用于部分低Android版本，Android5.0以上推荐使用系统自带", Toast.LENGTH_LONG).show()
                val dialog = XWalkInitDialog(mActivity!!)
                dialog.setOnListener(object : XWalkInitDialog.OnListener {
                    override fun onchange() {
                    }
                })
                dialog.show()
            }
        }
        findViewById<View>(R.id.llBackup)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val dialog = BackupDialog(mActivity!!)
            dialog.show()
        }
        findViewById<View>(R.id.llAbout)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val dialog = AboutDialog(mActivity!!)
            dialog.show()
        }
        findViewById<View>(R.id.llWp)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            if (ApiConfig.get().wallpaper.isNotEmpty())
                OkGo.get<File>(ApiConfig.get().wallpaper).execute(object : FileCallback(requireActivity().filesDir.absolutePath, "wp") {
                    override fun onSuccess(response: Response<File>) {
                        (requireActivity() as BaseActivity).changeWallpaper(true)
                    }

                    override fun onError(response: Response<File>) {
                        super.onError(response)
                    }

                    override fun downloadProgress(progress: Progress) {
                        super.downloadProgress(progress)
                    }
                })
        }
        findViewById<View>(R.id.llWpRecovery)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val wp = File(requireActivity().filesDir.absolutePath + "/wp")
            if (wp.exists()) wp.delete()
            (requireActivity() as BaseActivity).changeWallpaper(true)
        }
        findViewById<View>(R.id.llHomeApi)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val sites = ApiConfig.get().getSwitchSourceBeanList()
            if (sites.size > 0) {
                val dialog = SelectDialog<SourceBean>(mActivity!!)
                dialog.setTip("请选择首页数据源")
                var select = sites.indexOf(ApiConfig.get().homeSourceBean)
                if (select < 0) select = 0
                dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<SourceBean> {
                    override fun click(value: SourceBean, pos: Int) {
                        dialog.dismiss()
                        ApiConfig.get().setSourceBean(value)
                        tvHomeApi!!.text = ApiConfig.get().homeSourceBean.name
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE))
                    }

                    override fun getDisplay(`val`: SourceBean): String {
                        return `val`.name
                    }
                }, object : DiffUtil.ItemCallback<SourceBean>() {
                    override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                        return oldItem === newItem
                    }

                    override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                        return oldItem.key == newItem.key
                    }
                }, sites, select)
                dialog.show()
            }
        }
        findViewById<View>(R.id.llDns)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val dohUrl = Hawk.get(HawkConfig.DOH_URL, 0)

            val dialog = SelectDialog<String>(mActivity!!)
            dialog.setTip("请选择安全DNS")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<String> {
                override fun click(value: String, pos: Int) {
                    tvDns!!.text = OkGoHelper.dnsHttpsList[pos]
                    Hawk.put(HawkConfig.DOH_URL, pos)
                    //                        String url = OkGoHelper.getDohUrl(pos);
                    //                        OkGoHelper.dnsOverHttps.setUrl(url.isEmpty() ? null : HttpUrl.get(url));
                    OkGoHelper.reloadDns()
                    IjkMediaPlayer.toggleDotPort(pos > 0)
                }

                override fun getDisplay(`val`: String): String {
                    return `val`
                }
            }, object : DiffUtil.ItemCallback<String>() {
                override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                    return oldItem == newItem
                }
            }, OkGoHelper.dnsHttpsList, dohUrl)
            dialog.show()
        }
        findViewById<View>(R.id.llApi)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            apiDialog = ApiDialog(mActivity!!)
            val dialog = apiDialog!!
            EventBus.getDefault().register(dialog)
            dialog.setOnListener(object : ApiDialog.OnListener {
                override fun onchange(api: String) {
                    val oldApi = Hawk.get(HawkConfig.API_URL, "")
                    Hawk.put(HawkConfig.API_URL, api)
                    if (!HistoryHelper.isApiLineHistory(api)) {
                        HistoryHelper.clearApiLineList()
                    }
                    tvApi!!.text = api
                    refreshApiLineText()
                    if (oldApi != api) {
                        restartAppAfterConfigChanged()
                    }
                }

                override fun onLocalConfig(live: Boolean) {
                    openLocalConfig(live)
                }
            })
            dialog.setOnDismissListener { dialogInterface ->
                (mActivity as BaseActivity).hideSysBar()
                EventBus.getDefault().unregister(dialogInterface)
                apiDialog = null
            }
            dialog.show()
        }

        findViewById<View>(R.id.llApiHistory)!!.setOnClickListener { v ->
            val history = Hawk.get(HawkConfig.API_HISTORY, ArrayList<String>())
            if (history.isEmpty()) return@setOnClickListener
            val current = Hawk.get(HawkConfig.API_URL, "")
            var idx = 0
            if (history.contains(current)) idx = history.indexOf(current)
            val dialog = ApiHistoryDialog(mActivity!!)
            dialog.setTip("历史配置列表")
            dialog.setAdapter(object : ApiHistoryDialogAdapter.SelectDialogInterface {
                override fun click(value: String) {
                    val oldApi = Hawk.get(HawkConfig.API_URL, "")
                    if (!HistoryHelper.isApiLineHistory(value)) {
                        HistoryHelper.clearApiLineList()
                    }
                    Hawk.put(HawkConfig.API_URL, value)
                    Hawk.put(HawkConfig.LIVE_API_URL, value)
                    HistoryHelper.setLiveApiHistory(value)
                    tvApi!!.text = value
                    refreshApiLineText()
                    dialog.dismiss()
                    if (oldApi != value) {
                        restartAppAfterConfigChanged()
                    }
                }

                override fun del(value: String, data: ArrayList<String>) {
                    Hawk.put(HawkConfig.API_HISTORY, data)
                }
            }, history, idx)
            dialog.show()
        }

        findViewById<View>(R.id.llApiLine)!!.setOnClickListener { v ->
            val apiLines = Hawk.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
            if (apiLines.isEmpty()) {
                Toast.makeText(mContext, "线路列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = Hawk.get(HawkConfig.API_URL, "")
            var idx = 0
            for (i in apiLines.indices) {
                if (current == HistoryHelper.getApiLineUrl(apiLines[i])) {
                    idx = i
                    break
                }
            }
            val dialog = SelectDialog<String>(mActivity!!)
            dialog.setTip("线路选择")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<String> {
                override fun click(value: String, pos: Int) {
                    val newApi = HistoryHelper.getApiLineUrl(value)
                    val oldApi = Hawk.get(HawkConfig.API_URL, "")
                    if (newApi.isEmpty()) {
                        return
                    }
                    Hawk.put(HawkConfig.API_URL, newApi)
                    Hawk.put(HawkConfig.LIVE_API_URL, newApi)
                    HistoryHelper.setLiveApiHistory(newApi)
                    tvApi!!.text = newApi
                    refreshApiLineText()
                    dialog.dismiss()
                    if (oldApi != newApi) {
                        restartAppAfterConfigChanged()
                    }
                }

                override fun getDisplay(`val`: String): String {
                    return HistoryHelper.getApiLineName(`val`)
                }
                // 等价 SelectDialogAdapter.stringDiff(字符串项比较)
            }, object : DiffUtil.ItemCallback<String>() {
                override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                    return oldItem == newItem
                }
            }, apiLines, idx)
            dialog.show()
        }


        findViewById<View>(R.id.llMediaCodec)!!.setOnClickListener { v ->
            val ijkCodes = ApiConfig.get().getIjkCodes()
            if (ijkCodes == null || ijkCodes.size == 0) return@setOnClickListener
            FastClickCheckUtil.check(v)

            var defaultPos = 0
            val ijkSel = Hawk.get(HawkConfig.IJK_CODEC, "硬解码")
            for (j in ijkCodes.indices) {
                if (ijkSel == ijkCodes[j].name) {
                    defaultPos = j
                    break
                }
            }

            val dialog = SelectDialog<IJKCode>(mActivity!!)
            dialog.setTip("请选择IJK解码")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<IJKCode> {
                override fun click(value: IJKCode, pos: Int) {
                    value.selected(true)
                    tvMediaCodec!!.text = value.name
                }

                override fun getDisplay(`val`: IJKCode): String {
                    return `val`.name ?: ""
                }
            }, object : DiffUtil.ItemCallback<IJKCode>() {
                override fun areItemsTheSame(oldItem: IJKCode, newItem: IJKCode): Boolean {
                    return oldItem === newItem
                }

                override fun areContentsTheSame(oldItem: IJKCode, newItem: IJKCode): Boolean {
                    return oldItem.name == newItem.name
                }
            }, ijkCodes, defaultPos)
            dialog.show()
        }
        findViewById<View>(R.id.llScale)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.PLAY_SCALE, 0)
            val players = ArrayList<Int>()
            players.add(0)
            players.add(1)
            players.add(2)
            players.add(3)
            players.add(4)
            players.add(5)
            val dialog = SelectDialog<Int>(mActivity!!)
            dialog.setTip("请选择默认画面缩放")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<Int> {
                override fun click(value: Int, pos: Int) {
                    Hawk.put(HawkConfig.PLAY_SCALE, value)
                    tvScale!!.text = PlayerHelper.getScaleName(value)
                }

                override fun getDisplay(`val`: Int): String {
                    return PlayerHelper.getScaleName(`val`)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, players, defaultPos)
            dialog.show()
        }
        findViewById<View>(R.id.llPlay)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val playerType = Hawk.get(HawkConfig.PLAY_TYPE, 2)
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            val renders = ArrayList<Int>()
            for (p in players.indices) {
                renders.add(p)
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            val dialog = SelectDialog<Int>(mActivity!!)
            dialog.setTip("请选择默认播放器")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<Int> {
                override fun click(value: Int, pos: Int) {
                    val thisPlayerType = players[pos]
                    Hawk.put(HawkConfig.PLAY_TYPE, thisPlayerType)
                    tvPlay!!.text = PlayerHelper.getPlayerName(thisPlayerType)
                    PlayerHelper.init()
                }

                override fun getDisplay(`val`: Int): String {
                    val playerType = players[`val`]
                    return PlayerHelper.getPlayerName(playerType)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, renders, defaultPos)
            dialog.show()
        }
        findViewById<View>(R.id.llRender)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.PLAY_RENDER, 0)
            val renders = ArrayList<Int>()
            renders.add(0)
            renders.add(1)
            val dialog = SelectDialog<Int>(mActivity!!)
            dialog.setTip("请选择默认渲染方式")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<Int> {
                override fun click(value: Int, pos: Int) {
                    Hawk.put(HawkConfig.PLAY_RENDER, value)
                    tvRender!!.text = PlayerHelper.getRenderName(value)
                    PlayerHelper.init()
                }

                override fun getDisplay(`val`: Int): String {
                    return PlayerHelper.getRenderName(`val`)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, renders, defaultPos)
            dialog.show()
        }
        findViewById<View>(R.id.llHomeRec)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            types.add(2)
            val dialog = SelectDialog<Int>(mActivity!!)
            dialog.setTip("请选择首页列表数据")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<Int> {
                override fun click(value: Int, pos: Int) {
                    Hawk.put(HawkConfig.HOME_REC, value)
                    tvHomeRec!!.text = getHomeRecName(value)
                }

                override fun getDisplay(`val`: Int): String {
                    return getHomeRecName(`val`)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, defaultPos)
            dialog.show()
        }
        findViewById<View>(R.id.llSearchView)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.SEARCH_VIEW, 0)
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            val dialog = SelectDialog<Int>(mActivity!!)
            dialog.setTip("请选择搜索视图")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<Int> {
                override fun click(value: Int, pos: Int) {
                    Hawk.put(HawkConfig.SEARCH_VIEW, value)
                    tvSearchView!!.text = getSearchView(value)
                }

                override fun getDisplay(`val`: Int): String {
                    return getSearchView(`val`)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, defaultPos)
            dialog.show()
        }
        SettingActivity.callback = object : SettingActivity.DevModeCallback {
            override fun onChange() {
                findViewById<View>(R.id.llDebug)!!.visibility = View.VISIBLE
            }
        }

        findViewById<View>(R.id.showPreview)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            Hawk.put(HawkConfig.SHOW_PREVIEW, !Hawk.get(HawkConfig.SHOW_PREVIEW, true))
            tvShowPreviewText!!.text = if (Hawk.get(HawkConfig.SHOW_PREVIEW, true)) "开启" else "关闭"
        }
        findViewById<View>(R.id.llHistoryNum)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.HISTORY_NUM, 0)
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            types.add(2)
            val dialog = SelectDialog<Int>(mActivity!!)
            dialog.setTip("保留历史记录数量")
            dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<Int> {
                override fun click(value: Int, pos: Int) {
                    Hawk.put(HawkConfig.HISTORY_NUM, value)
                    tvHistoryNum!!.text = HistoryHelper.getHistoryNumName(value)
                }

                override fun getDisplay(`val`: Int): String {
                    return HistoryHelper.getHistoryNumName(`val`)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, defaultPos)
            dialog.show()
        }
        findViewById<View>(R.id.showFastSearch)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            Hawk.put(HawkConfig.FAST_SEARCH_MODE, !Hawk.get(HawkConfig.FAST_SEARCH_MODE, true))
            tvFastSearchText!!.text = if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) "开启" else "关闭"
        }
        findViewById<View>(R.id.m3u8Ad)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val is_purify = Hawk.get(HawkConfig.M3U8_PURIFY, false)
            Hawk.put(HawkConfig.M3U8_PURIFY, !is_purify)
            tvm3u8AdText!!.text = if (!is_purify) "开启" else "关闭"
        }
        findViewById<View>(R.id.danmuOpen)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val open = !DanmuHelper.isOpen()
            DanmuHelper.setOpen(open)
            tvDanmuOpenText!!.text = if (open) "开启" else "关闭"
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, open))
        }
        findViewById<View>(R.id.danmuApi)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val dialog = DanmuApiDialog(mActivity!!)
            dialog.setOnListener(object : DanmuApiDialog.OnListener {
                override fun onChange(api: String) {
                    refreshDanmuApiText()
                }
            })
            dialog.show()
        }
        findViewById<View>(R.id.autoSwitchLine)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val enable = !Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true)
            Hawk.put(HawkConfig.AUTO_SWITCH_LINE, enable)
            tvAutoSwitchLineText!!.text = if (enable) "开启" else "关闭"
        }
        findViewById<View>(R.id.llHomeRecStyle)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            Hawk.put(HawkConfig.HOME_REC_STYLE, !Hawk.get(HawkConfig.HOME_REC_STYLE, false))
            tvRecStyleText!!.text = if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) "是" else "否"
        }

        findViewById<View>(R.id.llHistoryMerge)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val historyMerge = !Hawk.get(HawkConfig.HISTORY_MERGE, false)
            Hawk.put(HawkConfig.HISTORY_MERGE, historyMerge)
            tvHistoryMerge!!.text = if (historyMerge) "开启" else "关闭"
        }

        //下次进入
        findViewById<View>(R.id.tvHomeLive)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            Hawk.put(HawkConfig.DEFAULT_LOAD_LIVE, !Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false))
            tvHomeDefaultShow!!.text = if (Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) "直播" else "点播"
        }

        findViewById<View>(R.id.llIjkCachePlay)!!.setOnClickListener { v -> onClickIjkCachePlay(v) }
        findViewById<View>(R.id.llPlayCache)!!.setOnClickListener { v -> onClickPlayCache(v) }
        findViewById<View>(R.id.llLogDisk)!!.setOnClickListener { v -> onClickLogDisk(v) }
        findViewById<View>(R.id.llClearCache)!!.setOnClickListener { v -> onClickClearCache(v) }
        findViewById<View>(R.id.llCheckUpdate)!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            checkUpdate()
        }
        initSectionFolding()
    }

    private data class SettingSection(val headerId: Int, val key: String, val label: String)

    private val settingSections = listOf(
        SettingSection(R.id.tvGroupConfig, "config", "配置与数据源"),
        SettingSection(R.id.tvGroupDisplay, "display", "界面与显示"),
        SettingSection(R.id.tvGroupPlay, "play", "播放与网络"),
        SettingSection(R.id.tvGroupDanmu, "danmu", "弹幕设置"),
        SettingSection(R.id.tvGroupAdvanced, "advanced", "界面高级"),
        SettingSection(R.id.tvGroupTools, "tools", "缓存与工具")
    )

    /** 分组标题可点击折叠;只隐藏整组子 View,不改任何选项 id 与点击绑定。 */
    private fun initSectionFolding() {
        val headers = settingSections.mapNotNull { findViewById<View>(it.headerId) as? TextView }
        if (headers.size != settingSections.size) return
        val parent = headers[0].parent as? LinearLayout ?: return
        val headerIndices = headers.map { parent.indexOfChild(it) }
        settingSections.forEachIndexed { index, section ->
            val header = headers[index]
            val start = headerIndices[index] + 1
            val end = headerIndices.filter { it >= start }.sorted().firstOrNull() ?: parent.childCount
            applySectionFolding(parent, header, section, start, end)
            header.setOnClickListener { v ->
                FastClickCheckUtil.check(v)
                val key = HawkConfig.SETTING_GROUP_COLLAPSED_PREFIX + section.key
                Hawk.put(key, !Hawk.get(key, false))
                applySectionFolding(parent, header, section, start, end)
                header.requestFocus()
            }
        }
    }

    private fun applySectionFolding(
        parent: LinearLayout,
        header: TextView,
        section: SettingSection,
        start: Int,
        end: Int
    ) {
        val collapsed = Hawk.get(HawkConfig.SETTING_GROUP_COLLAPSED_PREFIX + section.key, false)
        for (i in start until end) {
            parent.getChildAt(i)?.visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        header.text = section.label + (if (collapsed) "  ▸" else "  ▾")
    }

    private fun checkUpdate() {
        Toast.makeText(mContext, "正在检查更新...", Toast.LENGTH_SHORT).show()
        UpdateChecker.check { release ->
            if (!isAdded) return@check
            if (release == null) {
                Toast.makeText(mContext, "检查更新失败,请稍后重试", Toast.LENGTH_SHORT).show()
                return@check
            }
            val dialog = UpdateDialog(mActivity, release, DefaultConfig.getAppVersionName(mContext))
            dialog.show()
        }
    }

    private fun restartAppAfterConfigChanged() {
        Toast.makeText(mContext, "配置已切换,即将重新加载!", Toast.LENGTH_SHORT).show()
        clearConfigSwitchCache()
        Handler().postDelayed({
            if (mActivity != null && !mActivity!!.isFinishing) {
                mActivity!!.onBackPressed()
            }
        }, 2500)
    }

    private fun clearConfigSwitchCache() {
        try {
            SourceViewModel.clearRuntimeCache()
            //            FileUtils.clearSpiderCacheFiles();
            LOG.i("echo-clear-config-switch-cache")
        } catch (e: Exception) {
            LOG.i("echo-clear-config-switch-cache-error:" + e.message)
            e.printStackTrace()
        }
    }

    private fun restartAppAfterCacheCleared() {
        Toast.makeText(mContext, "缓存已清空,即将回到主页!", Toast.LENGTH_LONG).show()
        Handler().postDelayed({ restartApp() }, 2500)
    }

    private fun refreshApiLineText() {
        if (tvApiLine == null) return
        val apiLines = Hawk.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
        val current = Hawk.get(HawkConfig.API_URL, "")
        val showLine = HistoryHelper.isApiLineUrl(current)
        if (llApiLine != null) {
            llApiLine!!.visibility = if (showLine) View.VISIBLE else View.GONE
        }
        updateApiRowWeight(showLine)
        var lineName = ""
        if (showLine) {
            for (apiLine in apiLines) {
                if (current == HistoryHelper.getApiLineUrl(apiLine)) {
                    lineName = HistoryHelper.getApiLineName(apiLine)
                    break
                }
            }
        }
        tvApiLine!!.text = lineName
    }

    private fun refreshDanmuApiText() {
        if (tvDanmuApiText == null) return
        if (DanmakuApi.isUseDefault()) {
            tvDanmuApiText!!.text = "默认"
            return
        }
        val custom = Hawk.get(HawkConfig.DANMU_API, "")
        if (custom.isNotEmpty()) {
            tvDanmuApiText!!.text = "自定义"
            return
        }
        val config = ApiConfig.get().getDanmaku()
        tvDanmuApiText!!.text = if (config.isEmpty()) "默认" else "接口"
    }

    private fun updateApiRowWeight(showLine: Boolean) {
        if (llApi == null) return
        val params = llApi!!.layoutParams as LinearLayout.LayoutParams
        params.weight = if (showLine) 1.0f else 3.08f
        llApi!!.layoutParams = params
        if (llApiHistory != null) {
            val historyParams = llApiHistory!!.layoutParams as LinearLayout.LayoutParams
            val margin = if (showLine) resources.getDimensionPixelSize(R.dimen.vs_5) else 0
            historyParams.rightMargin = margin
            historyParams.marginEnd = margin
            llApiHistory!!.layoutParams = historyParams
        }
    }

    private fun restartApp() {
        if (mContext == null) return
        val intent = mContext.packageManager.getLaunchIntentForPackage(mContext.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            System.exit(0)
        }
    }

    private fun onClickIjkCachePlay(v: View) {
        FastClickCheckUtil.check(v)
        Hawk.put(HawkConfig.IJK_CACHE_PLAY, !Hawk.get(HawkConfig.IJK_CACHE_PLAY, false))
        tvIjkCachePlay!!.text = if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) "开启" else "关闭"
    }

    /** EXO 边下边播:下一次起播生效(媒体源在 setDataSource 时构建)。 */
    private fun onClickPlayCache(v: View) {
        FastClickCheckUtil.check(v)
        val enabled = !Hawk.get(HawkConfig.PLAY_CACHE, false)
        Hawk.put(HawkConfig.PLAY_CACHE, enabled)
        tvPlayCache!!.text = if (enabled) "开启" else "关闭"
        Toast.makeText(mContext, if (enabled) "边下边播已开启,下次播放生效" else "边下边播已关闭", Toast.LENGTH_SHORT).show()
    }

    /** 诊断日志落盘:立即生效(同时作用于 logcat 之外的文件写入)。 */
    private fun onClickLogDisk(v: View) {
        FastClickCheckUtil.check(v)
        val enabled = !Hawk.get(HawkConfig.LOG_DISK, true)
        Hawk.put(HawkConfig.LOG_DISK, enabled)
        LOG.diskEnabled = enabled
        tvLogDisk!!.text = if (enabled) "开启" else "关闭"
        LOG.i("DIAG log_disk=${if (enabled) "on" else "off"}")
    }

    private fun openLocalConfig(live: Boolean) {
        selectLocalLive = live
        if (!XXPermissions.isGranted(mContext, Permission.Group.STORAGE)) {
            Toast.makeText(context, "请选择文件前需要先授予存储权限", Toast.LENGTH_SHORT).show()
            XXPermissions.with(mActivity)
                .permission(Permission.Group.STORAGE)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) {
                            Toast.makeText(context, "已获得存储权限", Toast.LENGTH_SHORT).show()
                            openLocalFileActivity(selectLocalLive)
                        }
                    }

                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        if (never) {
                            Toast.makeText(context, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show()
                            XXPermissions.startPermissionActivity(mActivity, permissions)
                        } else {
                            Toast.makeText(context, "获取存储权限失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            return
        }
        openLocalFileActivity(live)
    }

    private fun openLocalFileActivity(live: Boolean) {
        val intent = Intent(mContext, LocalFileActivity::class.java)
        intent.putExtra(LocalFileActivity.EXTRA_LIVE, live)
        startActivityForResult(intent, REQUEST_LOCAL_CONFIG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOCAL_CONFIG || resultCode != Activity.RESULT_OK || data == null || data.data == null) {
            return
        }
        val api = localConfigToApi(data.data!!)
        if (api == null || api.isEmpty()) {
            Toast.makeText(context, "读取本地配置失败", Toast.LENGTH_SHORT).show()
            return
        }
        apiDialog?.setLocalApi(api, selectLocalLive)
    }

    private fun localConfigToApi(uri: Uri): String {
        var path = getPathFromUri(uri)
        if (path == null || path.isEmpty()) {
            path = copyUriToLocalConfig(uri)
        }
        if (path == null || path.isEmpty()) {
            return ""
        }
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length).replaceFirst("^/+".toRegex(), "")
        }
        path = copyUriToLocalConfig(uri)
        if (path != null && path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length).replaceFirst("^/+".toRegex(), "")
        }
        return ""
    }

    private fun getPathFromUri(uri: Uri): String? {
        try {
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                return uri.path
            }
            if (DocumentsContract.isDocumentUri(mContext, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if ("com.android.externalstorage.documents" == uri.authority) {
                    val split = docId.split(":")
                    if (split.size > 1 && "primary".equals(split[0], ignoreCase = true)) {
                        return Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                    }
                }
                if ("com.android.providers.downloads.documents" == uri.authority && docId.startsWith("raw:")) {
                    return docId.substring(4)
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun copyUriToLocalConfig(uri: Uri): String {
        var input: InputStream? = null
        var output: FileOutputStream? = null
        try {
            input = mContext.contentResolver.openInputStream(uri)
            if (input == null) return ""
            val dir = File(FileUtils.getExternalCachePath(), "config")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, getDisplayName(uri))
            output = FileOutputStream(file)
            val buffer = ByteArray(8192)
            var length: Int
            while (input.read(buffer).also { length = it } != -1) {
                output.write(buffer, 0, length)
            }
            return file.absolutePath
        } catch (th: Throwable) {
            th.printStackTrace()
            return ""
        } finally {
            try {
                output?.close()
            } catch (ignored: Throwable) {
            }
            try {
                input?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "local_config.json"
        var cursor: Cursor? = null
        try {
            cursor = mContext.contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val displayName = cursor.getString(index)
                    if (displayName != null && displayName.isNotEmpty()) {
                        name = displayName
                    }
                }
            }
        } catch (ignored: Throwable) {
        } finally {
            cursor?.close()
        }
        return name
    }

    private fun onClickClearCache(v: View) {
        FastClickCheckUtil.check(v)
        val cachePath = FileUtils.getCachePath()
        val cacheDir = File(cachePath)
        Thread {
            try {
                ApiConfig.get().clearSpiderCache()
                if (cacheDir.exists()) FileUtils.cleanDirectory(cacheDir)
                FileUtils.clearSpiderCacheFiles()
                // 配置改为冷启动优先复用本地缓存,这里同时删掉配置缓存作为手动刷新入口
                for (url in listOf(Hawk.get(HawkConfig.API_URL, ""), Hawk.get(HawkConfig.LIVE_API_URL, ""))) {
                    if (url.isEmpty()) continue
                    try {
                        File(KtApp.instance().filesDir.absolutePath + "/" + MD5.encode(url)).delete()
                    } catch (_: Throwable) {
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (mActivity != null) {
                    mActivity!!.runOnUiThread { restartAppAfterCacheCleared() }
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SettingActivity.callback = null
    }

    fun getHomeRecName(type: Int): String {
        if (type == 1) {
            return "站点推荐"
        } else if (type == 2) {
            return "观看历史"
        } else {
            return "豆瓣热播"
        }
    }

    fun getSearchView(type: Int): String {
        if (type == 0) {
            return "文字列表"
        } else {
            return "缩略图"
        }
    }

    companion object {
        private const val REQUEST_LOCAL_CONFIG = 1001

        @JvmField
        var loadingSearchRemoteTvDialog: SearchRemoteTvDialog? = null

        @JvmField
        var remoteTvHostList: MutableList<String>? = null

        @JvmField
        var foundRemoteTv = false

        @JvmStatic
        fun newInstance(): ModelSettingFragment {
            return ModelSettingFragment().setArguments()
        }
    }
}
