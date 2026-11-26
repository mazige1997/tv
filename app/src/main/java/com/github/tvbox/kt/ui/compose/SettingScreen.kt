package com.github.tvbox.kt.ui.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.activity.HomeActivity
import com.github.tvbox.kt.ui.activity.SettingActivity
import com.github.tvbox.kt.ui.dialog.AboutDialog
import com.github.tvbox.kt.ui.dialog.ApiDialog
import com.github.tvbox.kt.ui.dialog.ApiHistoryDialog
import com.github.tvbox.kt.ui.dialog.BackupDialog
import com.github.tvbox.kt.ui.dialog.DanmuApiDialog
import com.github.tvbox.kt.ui.dialog.UpdateDialog
import com.github.tvbox.kt.util.DanmuHelper
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.OkGoHelper
import com.github.tvbox.kt.util.PlayerHelper
import com.github.tvbox.kt.util.UpdateChecker
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.EventBus
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File

/**
 * 设置页(Compose 版):数据/逻辑全部复用原 ModelSettingFragment 的实现,
 * 仅把 UI 从 XML 换成声明式 Compose。6 个分组 + 分组折叠 + TV 焦点导航。
 */
@Composable
fun SettingScreen(activity: BaseActivity) {
    val context = LocalContext.current

    // ---- 每项的显示值:初值来自 Hawk,点击后写回并刷新 ----
    val debugOpen = remember { mutableStateOf(if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) "已打开" else "已关闭") }
    val debugVisible = remember { mutableStateOf(false) }
    val api = remember { mutableStateOf(Hawk.get(HawkConfig.API_URL, "")) }
    val apiLine = remember { mutableStateOf("") }
    val homeApi = remember { mutableStateOf("") }
    val homeLive = remember { mutableStateOf(if (Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) "直播" else "点播") }
    val homeRec = remember { mutableStateOf("") }
    val recStyle = remember { mutableStateOf(if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) "是" else "否") }
    val searchView = remember { mutableStateOf("") }
    val fastSearch = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.FAST_SEARCH_MODE, true))) }
    val play = remember { mutableStateOf(PlayerHelper.getPlayerName(Hawk.get(HawkConfig.PLAY_TYPE, 2))) }
    val mediaCodec = remember { mutableStateOf(Hawk.get(HawkConfig.IJK_CODEC, "硬解码")) }
    val purify = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.M3U8_PURIFY, false))) }
    val render = remember { mutableStateOf(PlayerHelper.getRenderName(Hawk.get(HawkConfig.PLAY_RENDER, 0))) }
    val autoSwitch = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true))) }
    val dns = remember { mutableStateOf(currentDns()) }
    val parseWeb = remember { mutableStateOf(if (Hawk.get(HawkConfig.PARSE_WEBVIEW, true)) "系统自带" else "XWalkView") }
    val historyMerge = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.HISTORY_MERGE, false))) }
    val historyNum = remember { mutableStateOf(HistoryHelper.getHistoryNumName(Hawk.get(HawkConfig.HISTORY_NUM, 0))) }
    val danmuOpen = remember { mutableStateOf(boolText(DanmuHelper.isOpen())) }
    val danmuApiText = remember { mutableStateOf(readDanmuApi()) }
    val scale = remember { mutableStateOf(PlayerHelper.getScaleName(Hawk.get(HawkConfig.PLAY_SCALE, 0))) }
    val preview = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.SHOW_PREVIEW, true))) }
    val ijkCache = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.IJK_CACHE_PLAY, false))) }
    val playCache = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.PLAY_CACHE, false))) }
    val logDisk = remember { mutableStateOf(boolText(Hawk.get(HawkConfig.LOG_DISK, true))) }

    // 线路选择行是否显示 + 折叠状态
    val showLine = remember { mutableStateOf(HistoryHelper.isApiLineUrl(Hawk.get(HawkConfig.API_URL, ""))) }
    homeApi.value = runCatching { ApiConfig.get().homeSourceBean.name }.getOrDefault("")
    homeRec.value = homeRecName(Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC))
    searchView.value = searchViewName(Hawk.get(HawkConfig.SEARCH_VIEW, 0))
    refreshApiLine(apiLine, showLine)

    // 开发者模式回调:原 Fragment 在 init 里挂 SettingActivity.callback
    DisposableEffect(Unit) {
        SettingActivity.callback = object : SettingActivity.DevModeCallback {
            override fun onChange() { debugVisible.value = true }
        }
        onDispose { SettingActivity.callback = null }
    }

    val sections = remember {
        listOf("config", "display", "play", "danmu", "advanced", "tools")
    }
    val collapsed = remember {
        mutableStateMapOf<String, Boolean>().apply {
            sections.forEach {
                put(it, Hawk.get(HawkConfig.SETTING_GROUP_COLLAPSED_PREFIX + it, false))
            }
        }
    }

    fun toggleSection(key: String) {
        val next = !(collapsed[key] ?: false)
        collapsed[key] = next
        Hawk.put(HawkConfig.SETTING_GROUP_COLLAPSED_PREFIX + key, next)
    }

    val handler = remember { Handler() }
    fun restartAfterConfig() {
        Toast.makeText(context, "配置已切换,即将重新加载!", Toast.LENGTH_SHORT).show()
        try {
            com.github.tvbox.kt.viewmodel.SourceViewModel.clearRuntimeCache()
        } catch (e: Exception) {
            LOG.i("echo-clear-config-switch-cache-error:" + e.message)
        }
        handler.postDelayed({
            if (!activity.isFinishing) activity.onBackPressed()
        }, 2500)
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(dimensionResource(R.dimen.vs_20))
    ) {
        // ===== 配置与数据源 =====
        SettingGroupHeader("配置与数据源", collapsed["config"] == true) { toggleSection("config") }
        if (collapsed["config"] != true) {
            SettingRowGroup {
                SettingRow("调试模式", debugOpen.value, visible = debugVisible.value) {
                    val next = !Hawk.get(HawkConfig.DEBUG_OPEN, false)
                    Hawk.put(HawkConfig.DEBUG_OPEN, next)
                    debugOpen.value = if (next) "已打开" else "已关闭"
                }
            }
            SettingRowGroup {
                SettingRow("配置地址", api.value, weight = 3.08f) {
                    showApiDialog(context, activity, api, showLine, apiLine, ::restartAfterConfig)
                }
                SettingRow("配置历史") { showApiHistory(context, api, apiLine, showLine, ::restartAfterConfig) }
                SettingRow("线路选择", apiLine.value, visible = showLine.value) {
                    showApiLine(context, api, apiLine, showLine, ::restartAfterConfig)
                }
            }
            SettingRowGroup {
                SettingRow("首页站源", homeApi.value, weight = 3.08f) {
                    showHomeSource(context) { homeApi.value = it }
                }
                SettingRow("下次进入", homeLive.value) {
                    val next = !Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)
                    Hawk.put(HawkConfig.DEFAULT_LOAD_LIVE, next)
                    homeLive.value = if (next) "直播" else "点播"
                }
            }
        }

        // ===== 界面与显示 =====
        SettingGroupHeader("界面与显示", collapsed["display"] == true) { toggleSection("display") }
        if (collapsed["display"] != true) {
            SettingRowGroup {
                SettingRow("首页推荐", homeRec.value) {
                    showSingleChoice(context, "请选择首页列表数据", listOf(0, 1, 2),
                        Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC), SettingDiff.int,
                        { homeRecName(it) }) { v ->
                        Hawk.put(HawkConfig.HOME_REC, v)
                        homeRec.value = homeRecName(v)
                    }
                }
                SettingRow("首页多行", recStyle.value) {
                    val next = !Hawk.get(HawkConfig.HOME_REC_STYLE, false)
                    Hawk.put(HawkConfig.HOME_REC_STYLE, next)
                    recStyle.value = if (next) "是" else "否"
                }
            }
            SettingRowGroup {
                SettingRow("搜索展示", searchView.value) {
                    showSingleChoice(context, "请选择搜索视图", listOf(0, 1),
                        Hawk.get(HawkConfig.SEARCH_VIEW, 0), SettingDiff.int,
                        { searchViewName(it) }) { v ->
                        Hawk.put(HawkConfig.SEARCH_VIEW, v)
                        searchView.value = searchViewName(v)
                    }
                }
                SettingRow("聚合搜索", fastSearch.value) {
                    val next = !Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)
                    Hawk.put(HawkConfig.FAST_SEARCH_MODE, next)
                    fastSearch.value = boolText(next)
                }
            }
        }

        // ===== 播放与网络 =====
        SettingGroupHeader("播放与网络", collapsed["play"] == true) { toggleSection("play") }
        if (collapsed["play"] != true) {
            SettingRowGroup {
                SettingRow("播放器", play.value) {
                    val types = PlayerHelper.getExistPlayerTypes()
                    val cur = Hawk.get(HawkConfig.PLAY_TYPE, 2)
                    showSingleChoice(context, "请选择默认播放器", types, types.indexOf(cur), SettingDiff.int,
                        { PlayerHelper.getPlayerName(it) }) { v ->
                        Hawk.put(HawkConfig.PLAY_TYPE, v)
                        play.value = PlayerHelper.getPlayerName(v)
                        PlayerHelper.init()
                    }
                }
                SettingRow("IJK解码", mediaCodec.value) {
                    val codes = ApiConfig.get().getIjkCodes().orEmpty()
                    if (codes.isEmpty()) return@SettingRow
                    val sel = Hawk.get(HawkConfig.IJK_CODEC, "硬解码")
                    showSingleChoice(context, "请选择IJK解码", codes,
                        codes.indexOfFirst { it.name == sel }.coerceAtLeast(0), SettingDiff.ijk,
                        { it.name ?: "" }) { v ->
                        v.selected(true)
                        mediaCodec.value = v.name ?: ""
                    }
                }
                SettingRow("去广告", purify.value) {
                    val next = !Hawk.get(HawkConfig.M3U8_PURIFY, false)
                    Hawk.put(HawkConfig.M3U8_PURIFY, next)
                    purify.value = boolText(next)
                }
            }
            SettingRowGroup {
                SettingRow("渲染方式", render.value) {
                    showSingleChoice(context, "请选择默认渲染方式", listOf(0, 1),
                        Hawk.get(HawkConfig.PLAY_RENDER, 0), SettingDiff.int,
                        { PlayerHelper.getRenderName(it) }) { v ->
                        Hawk.put(HawkConfig.PLAY_RENDER, v)
                        render.value = PlayerHelper.getRenderName(v)
                        PlayerHelper.init()
                    }
                }
                SettingRow("自动换线", autoSwitch.value) {
                    val next = !Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true)
                    Hawk.put(HawkConfig.AUTO_SWITCH_LINE, next)
                    autoSwitch.value = boolText(next)
                }
                SettingRow("安全DNS", dns.value) {
                    showDns(context, dns)
                }
            }
            SettingRowGroup {
                SettingRow("嗅探Webview", parseWeb.value) {
                    val useSystem = !Hawk.get(HawkConfig.PARSE_WEBVIEW, true)
                    Hawk.put(HawkConfig.PARSE_WEBVIEW, useSystem)
                    parseWeb.value = if (useSystem) "系统自带" else "XWalkView"
                    if (!useSystem) {
                        Toast.makeText(context, "注意: XWalkView只适用于部分低Android版本，Android5.0以上推荐使用系统自带", Toast.LENGTH_LONG).show()
                        com.github.tvbox.kt.ui.dialog.XWalkInitDialog(context).show()
                    }
                }
                SettingRow("历史合并", historyMerge.value) {
                    val next = !Hawk.get(HawkConfig.HISTORY_MERGE, false)
                    Hawk.put(HawkConfig.HISTORY_MERGE, next)
                    historyMerge.value = boolText(next)
                }
                SettingRow("历史记录", historyNum.value) {
                    showSingleChoice(context, "保留历史记录数量", listOf(0, 1, 2),
                        Hawk.get(HawkConfig.HISTORY_NUM, 0), SettingDiff.int,
                        { HistoryHelper.getHistoryNumName(it) }) { v ->
                        Hawk.put(HawkConfig.HISTORY_NUM, v)
                        historyNum.value = HistoryHelper.getHistoryNumName(v)
                    }
                }
            }
        }

        // ===== 弹幕设置 =====
        SettingGroupHeader("弹幕设置", collapsed["danmu"] == true) { toggleSection("danmu") }
        if (collapsed["danmu"] != true) {
            SettingRowGroup {
                SettingRow("弹幕", danmuOpen.value) {
                    val next = !DanmuHelper.isOpen()
                    DanmuHelper.setOpen(next)
                    danmuOpen.value = boolText(next)
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, next))
                }
                SettingRow("弹幕地址", danmuApiText.value) {
                    val dialog = DanmuApiDialog(context)
                    dialog.setOnListener(object : DanmuApiDialog.OnListener {
                        override fun onChange(api: String) { danmuApiText.value = readDanmuApi() }
                    })
                    dialog.show()
                }
            }
        }

        // ===== 界面高级 =====
        SettingGroupHeader("界面高级", collapsed["advanced"] == true) { toggleSection("advanced") }
        if (collapsed["advanced"] != true) {
            SettingRowGroup {
                SettingRow("换张壁纸") { changeWallpaper(activity) }
                SettingRow("重置壁纸") { resetWallpaper(activity) }
            }
            SettingRowGroup {
                SettingRow("画面缩放", scale.value) {
                    showSingleChoice(context, "请选择默认画面缩放", listOf(0, 1, 2, 3, 4, 5),
                        Hawk.get(HawkConfig.PLAY_SCALE, 0), SettingDiff.int,
                        { PlayerHelper.getScaleName(it) }) { v ->
                        Hawk.put(HawkConfig.PLAY_SCALE, v)
                        scale.value = PlayerHelper.getScaleName(v)
                    }
                }
                SettingRow("窗口预览", preview.value) {
                    val next = !Hawk.get(HawkConfig.SHOW_PREVIEW, true)
                    Hawk.put(HawkConfig.SHOW_PREVIEW, next)
                    preview.value = boolText(next)
                }
            }
        }

        // ===== 缓存与工具 =====
        SettingGroupHeader("缓存与工具", collapsed["tools"] == true) { toggleSection("tools") }
        if (collapsed["tools"] != true) {
            SettingRowGroup {
                SettingRow("IJK缓存", ijkCache.value) {
                    val next = !Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)
                    Hawk.put(HawkConfig.IJK_CACHE_PLAY, next)
                    ijkCache.value = boolText(next)
                }
                SettingRow("清空缓存") { clearCache(activity) }
            }
            SettingRowGroup {
                SettingRow("数据备份") { BackupDialog(context).show() }
                SettingRow("关于") { AboutDialog(context).show() }
            }
            SettingRowGroup {
                SettingRow("边下边播", playCache.value) {
                    val next = !Hawk.get(HawkConfig.PLAY_CACHE, false)
                    Hawk.put(HawkConfig.PLAY_CACHE, next)
                    playCache.value = boolText(next)
                    Toast.makeText(context, if (next) "边下边播已开启,下次播放生效" else "边下边播已关闭", Toast.LENGTH_SHORT).show()
                }
                SettingRow("日志落盘", logDisk.value) {
                    val next = !Hawk.get(HawkConfig.LOG_DISK, true)
                    Hawk.put(HawkConfig.LOG_DISK, next)
                    LOG.diskEnabled = next
                    logDisk.value = boolText(next)
                    LOG.i("DIAG log_disk=${if (next) "on" else "off"}")
                }
                SettingRow("检查更新") { checkUpdate(context, activity) }
            }
        }
    }
}

// ---------------- 纯逻辑辅助(与原 Fragment 一致) ----------------

private fun boolText(on: Boolean) = if (on) "开启" else "关闭"

private fun homeRecName(type: Int) = when (type) {
    1 -> "站点推荐"
    2 -> "观看历史"
    else -> "豆瓣热播"
}

private fun searchViewName(type: Int) = if (type == 0) "文字列表" else "缩略图"

private fun currentDns(): String {
    val list = OkGoHelper.dnsHttpsList
    val idx = Hawk.get(HawkConfig.DOH_URL, 0)
    return list.getOrElse(idx) { "关闭" }
}

private fun readDanmuApi(): String {
    if (DanmakuApi.isUseDefault()) return "默认"
    val custom: String = Hawk.get(HawkConfig.DANMU_API, "")
    if (custom.isNotEmpty()) return "自定义"
    val config = ApiConfig.get().getDanmaku()
    return if (config.isEmpty()) "默认" else "接口"
}

private fun refreshApiLine(apiLine: androidx.compose.runtime.MutableState<String>, showLine: androidx.compose.runtime.MutableState<Boolean>) {
    val current = Hawk.get(HawkConfig.API_URL, "")
    val visible = HistoryHelper.isApiLineUrl(current)
    showLine.value = visible
    if (!visible) { apiLine.value = ""; return }
    val lines = Hawk.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
    apiLine.value = lines.firstOrNull { current == HistoryHelper.getApiLineUrl(it) }
        ?.let { HistoryHelper.getApiLineName(it) } ?: ""
}

private fun <T> showSingleChoice(
    context: Context,
    tip: String,
    data: List<T>,
    select: Int,
    diff: androidx.recyclerview.widget.DiffUtil.ItemCallback<T>,
    display: (T) -> String,
    onPicked: (T) -> Unit
) {
    showSelect(context, tip, data, select.coerceAtLeast(0), diff, display, onPicked)
}

private fun showHomeSource(context: Context, onPicked: (String) -> Unit) {
    val sites = ApiConfig.get().getSwitchSourceBeanList()
    if (sites.isEmpty()) return
    val current = ApiConfig.get().homeSourceBean
    var select = sites.indexOf(current)
    if (select < 0) select = 0
    showSelect(context, "请选择首页数据源", sites, select, SettingDiff.source,
        { it.name }) { value, _ ->
        ApiConfig.get().setSourceBean(value)
        onPicked(ApiConfig.get().homeSourceBean.name)
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HOME_SOURCE_CHANGE))
    }
}

private fun showApiLine(context: Context, api: androidx.compose.runtime.MutableState<String>, apiLine: androidx.compose.runtime.MutableState<String>, showLine: androidx.compose.runtime.MutableState<Boolean>, restart: () -> Unit) {
    val lines = Hawk.get(HawkConfig.API_LINE_LIST, ArrayList<String>())
    if (lines.isEmpty()) { Toast.makeText(context, "线路列表为空", Toast.LENGTH_SHORT).show(); return }
    val current = Hawk.get(HawkConfig.API_URL, "")
    var idx = 0
    for (i in lines.indices) {
        if (current == HistoryHelper.getApiLineUrl(lines[i])) { idx = i; break }
    }
    showSelect(context, "线路选择", lines, idx, SettingDiff.string,
        { HistoryHelper.getApiLineName(it) }) { value, _ ->
        val newApi = HistoryHelper.getApiLineUrl(value)
        val oldApi = Hawk.get(HawkConfig.API_URL, "")
        if (newApi.isEmpty()) return@showSelect
        Hawk.put(HawkConfig.API_URL, newApi)
        Hawk.put(HawkConfig.LIVE_API_URL, newApi)
        HistoryHelper.setLiveApiHistory(newApi)
        api.value = newApi
        refreshApiLine(apiLine, showLine)
        if (oldApi != newApi) restart()
    }
}

private fun showApiHistory(context: Context, api: androidx.compose.runtime.MutableState<String>, apiLine: androidx.compose.runtime.MutableState<String>, showLine: androidx.compose.runtime.MutableState<Boolean>, restart: () -> Unit) {
    val history = Hawk.get(HawkConfig.API_HISTORY, ArrayList<String>())
    if (history.isEmpty()) return
    val current = Hawk.get(HawkConfig.API_URL, "")
    var idx = 0
    if (history.contains(current)) idx = history.indexOf(current)
    val dialog = ApiHistoryDialog(context)
    dialog.setTip("历史配置列表")
    dialog.setAdapter(object : com.github.tvbox.kt.ui.adapter.ApiHistoryDialogAdapter.SelectDialogInterface {
        override fun click(value: String) {
            val oldApi = Hawk.get(HawkConfig.API_URL, "")
            if (!HistoryHelper.isApiLineHistory(value)) HistoryHelper.clearApiLineList()
            Hawk.put(HawkConfig.API_URL, value)
            Hawk.put(HawkConfig.LIVE_API_URL, value)
            HistoryHelper.setLiveApiHistory(value)
            api.value = value
            refreshApiLine(apiLine, showLine)
            dialog.dismiss()
            if (oldApi != value) restart()
        }
        override fun del(value: String, data: ArrayList<String>) {
            Hawk.put(HawkConfig.API_HISTORY, data)
        }
    }, history, idx)
    dialog.show()
}

private fun showApiDialog(context: Context, activity: BaseActivity, api: androidx.compose.runtime.MutableState<String>, showLine: androidx.compose.runtime.MutableState<Boolean>, apiLine: androidx.compose.runtime.MutableState<String>, restart: () -> Unit) {
    val dialog = ApiDialog(context)
    EventBus.getDefault().register(dialog)
    dialog.setOnListener(object : ApiDialog.OnListener {
        override fun onchange(newApi: String) {
            val oldApi = Hawk.get(HawkConfig.API_URL, "")
            Hawk.put(HawkConfig.API_URL, newApi)
            if (!HistoryHelper.isApiLineHistory(newApi)) HistoryHelper.clearApiLineList()
            api.value = newApi
            refreshApiLine(apiLine, showLine)
            if (oldApi != newApi) restart()
        }
        override fun onLocalConfig(live: Boolean) {
            Toast.makeText(context, "请在原版设置中使用本地配置导入", Toast.LENGTH_SHORT).show()
        }
    })
    dialog.setOnDismissListener {
        (activity as? BaseActivity)?.hideSysBar()
        EventBus.getDefault().unregister(it)
    }
    dialog.show()
}

private fun showDns(context: Context, dns: androidx.compose.runtime.MutableState<String>) {
    val list = OkGoHelper.dnsHttpsList
    val cur = Hawk.get(HawkConfig.DOH_URL, 0)
    showSelect(context, "请选择安全DNS", list, cur, SettingDiff.string,
        { it }) { _, pos ->
        dns.value = OkGoHelper.dnsHttpsList[pos]
        Hawk.put(HawkConfig.DOH_URL, pos)
        OkGoHelper.reloadDns()
        IjkMediaPlayer.toggleDotPort(pos > 0)
    }
}

private fun checkUpdate(context: Context, activity: BaseActivity) {
    Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()
    UpdateChecker.check { release ->
        if (release == null) {
            Toast.makeText(context, "检查更新失败,请稍后重试", Toast.LENGTH_SHORT).show()
            return@check
        }
        UpdateDialog(activity, release, DefaultConfig.getAppVersionName(context)).show()
    }
}

private fun changeWallpaper(activity: BaseActivity) {
    val url = ApiConfig.get().wallpaper
    if (url.isEmpty()) return
    com.lzy.okgo.OkGo.get<java.io.File>(url).execute(object : com.lzy.okgo.callback.FileCallback(activity.filesDir.absolutePath, "wp") {
        override fun onSuccess(response: com.lzy.okgo.model.Response<java.io.File>) {
            activity.changeWallpaper(true)
        }
    })
}

private fun resetWallpaper(activity: BaseActivity) {
    val wp = File(activity.filesDir.absolutePath + "/wp")
    if (wp.exists()) wp.delete()
    activity.changeWallpaper(true)
}

private fun clearCache(activity: BaseActivity) {
    val cachePath = FileUtils.getCachePath()
    val cacheDir = File(cachePath)
    Thread {
        try {
            ApiConfig.get().clearSpiderCache()
            if (cacheDir.exists()) FileUtils.cleanDirectory(cacheDir)
            FileUtils.clearSpiderCacheFiles()
            for (url in listOf(Hawk.get(HawkConfig.API_URL, ""), Hawk.get(HawkConfig.LIVE_API_URL, ""))) {
                if (url.isEmpty()) continue
                try {
                    File(KtApp.instance().filesDir.absolutePath + "/" + com.github.tvbox.kt.util.MD5.encode(url)).delete()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activity.runOnUiThread {
                Toast.makeText(activity, "缓存已清空,即将回到主页!", Toast.LENGTH_LONG).show()
                Handler().postDelayed({
                    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        activity.startActivity(intent)
                        java.lang.System.exit(0)
                    }
                }, 2500)
            }
        }
    }.start()
}
