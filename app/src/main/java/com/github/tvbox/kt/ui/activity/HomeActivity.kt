package com.github.tvbox.kt.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.AbsSortXml
import com.github.tvbox.kt.bean.AbsXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter
import com.github.tvbox.kt.ui.compose.HomeGridPageState
import com.github.tvbox.kt.ui.compose.HomeScreen
import com.github.tvbox.kt.ui.compose.HomeTabUi
import com.github.tvbox.kt.ui.compose.HomeUiSnapshot
import com.github.tvbox.kt.ui.compose.HomeUserPageState
import com.github.tvbox.kt.ui.compose.TvTheme
import com.github.tvbox.kt.ui.dialog.GridFilterDialog
import com.github.tvbox.kt.ui.dialog.SelectDialog
import com.github.tvbox.kt.ui.dialog.TipDialog
import com.github.tvbox.kt.util.AppManager
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.MD5
import com.github.tvbox.kt.util.ScreenUtils
import com.github.tvbox.kt.util.UA
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import me.jessyan.autosize.utils.AutoSizeUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

/**
 * 首页 Activity(Compose 整页):状态栏 + 横排分类 + 当前分类网格/主页。
 * ViewPager + Fragment 体系整体替换为「状态容器保活 + Compose 单页渲染」。
 * 数据源切换/筛选 Dialog、双击退出、MENU 键、EventBus 等 Activity 级逻辑保留原生。
 */
class HomeActivity : BaseActivity() {
    private var contentLayout: LinearLayout? = null
    private var sourceViewModel: SourceViewModel? = null

    private var currentSelected = 0
    private var sortFocused = 0
    private var sortChange = false
    private var topHide = 0
    private var loadingSourceKey: String? = null
    private var previousHomeName: String? = null
    private var previousHomeSource: SourceBean? = null
    private var homeSortLoading = false
    private var refreshHomeRec = false
    private val mHandler = Handler()
    private var mExitTime = 0L
    private var eventBusRegistered = false
    private var skipNextUpdate = false
    private var dataInitOk = false
    private var jarInitOk = false
    private var useCacheConfig = false
    private var menuKeyDownTime = 0L
    private var mConfigErrorDialog: TipDialog? = null
    private var mSiteSwitchDialog: SelectDialog<SourceBean>? = null
    private var gridFilterDialog: GridFilterDialog? = null

    // ===== Compose 状态容器 =====
    private val uiState = mutableStateOf(HomeUiSnapshot())
    private val sortList = ArrayList<MovieSort.SortData>()          // 分类数据(与 pageStates 一一对应)
    private val pageStates = ArrayList<HomeGridPageState>()         // 每个分类页的状态(含 my0 占位)
    private val userPage = HomeUserPageState()                      // 主页(my0)状态
    private var siteName = ""
    private var dateTime = ""
    private var tabFocusToken = 0
    private var requestingPage: HomeGridPageState? = null           // 当前正在请求列表的页面(分发 listResult)

    private val mRunnable: Runnable = object : Runnable {
        override fun run() {
            val timeFormat = SimpleDateFormat("yyyy/MM/dd  E  HH:mm", Locale.CHINA)
            dateTime = timeFormat.format(Date())
            pushUiState()
            mHandler.postDelayed(this, 1000)
        }
    }

    override val layoutResID: Int
        get() = R.layout.activity_home_compose

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun init() {
        EventBus.getDefault().register(this)
        eventBusRegistered = true
        ControlManager.get().startServer()
        initView()
        initViewModel()
        useCacheConfig = true
        val intent = intent
        if (intent != null && intent.extras != null && intent.extras!!.containsKey("useCache")) {
            useCacheConfig = intent.extras!!.getBoolean("useCache", true)
        }
        initData()
    }

    private fun initView() {
        contentLayout = findViewById(R.id.contentLayout)
        setLoadSir(contentLayout!!)
        val composeView = findViewById<ComposeView>(R.id.homeComposeView)
        composeView.setContent {
            TvTheme {
                HomeScreen(
                    ui = uiState.value,
                    gridPage = pageStates.getOrNull(currentSelected),
                    userPage = userPage,
                    showUserPage = isUserPage(currentSelected),
                    onTabFocus = { onTabFocused(it) },
                    onTabClick = { onTabClicked(it) },
                    onTabUp = { onTabUp() },
                    onSiteClick = { onSiteClicked() },
                    onSiteLongClick = { jumpActivity(SettingActivity::class.java) },
                    onGridItemClick = { onGridVideoClick(it) },
                    onGridItemLongClick = { onGridVideoLongClick(it) },
                    onLoadMore = { loadMore() },
                    onEntryClick = { onEntryClicked(it) },
                    onHotItemClick = { onHotVideoClick(it) },
                    onHotItemLongClick = { onHotVideoLongClick(it) }
                )
            }
        }
        pushUiState()
    }

    private fun isUserPage(position: Int): Boolean {
        return position in sortList.indices && sortList[position].id == "my0"
    }

    /** 统一推送首页 UI 快照。 */
    private fun pushUiState() {
        val tabs = sortList.mapIndexed { index, data ->
            HomeTabUi(
                id = data.id ?: "",
                name = data.name ?: "",
                filterCount = if (data.filters.isNullOrEmpty()) 0 else data.filterSelectCount()
            )
        }
        uiState.value = HomeUiSnapshot(
            siteName = siteName,
            dateTime = dateTime,
            tabs = tabs,
            selected = currentSelected,
            topHidden = topHide != 0,
            screenLoading = isLoading(),
            tabFocusToken = tabFocusToken
        )
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        // 分类(sort)结果:一次 observe,构建全部页面状态
        sourceViewModel!!.sortResult.observe(this) { absXml ->
            if (skipNextUpdate) {
                skipNextUpdate = false
                return@observe
            }
            if (!homeSortLoading && loadingSourceKey == null) return@observe
            if (absXml != null && absXml.sourceKey != null && loadingSourceKey != null &&
                loadingSourceKey != absXml.sourceKey
            ) return@observe
            val home = ApiConfig.get().homeSourceBean
            showSuccess()
            clearHomePages()
            val newSortData: List<MovieSort.SortData> =
                if (absXml != null && absXml.classes != null && absXml.classes!!.sortList != null) {
                    DefaultConfig.adjustSort(home.key, absXml.classes!!.sortList, true)
                } else {
                    DefaultConfig.adjustSort(home.key, ArrayList(), true)
                }
            buildPages(newSortData, absXml)
            updateHomeRec(absXml)
            if (home.name.isNotEmpty()) siteName = home.name
            homeSortLoading = false
            loadingSourceKey = null
            previousHomeName = null
            previousHomeSource = null
            pushUiState()
            tabFocusToken++
            pushUiState()
        }
        // 列表结果:分发给当前请求页(原各 Fragment 各自 observe,这里统一分发)
        sourceViewModel!!.listResult.observe(this) { absXml ->
            val state = requestingPage ?: return@observe
            requestingPage = null
            state.isRequesting = false
            if (absXml != null && absXml.movie != null && !absXml.movie!!.videoList.isNullOrEmpty()) {
                val list = absXml.movie!!.videoList!!
                if (state.page == 1) {
                    showSuccess()
                    state.isLoad = true
                    state.hasActionItems = hasActionVideo(list)
                    state.videos.clear()
                    state.videos.addAll(list)
                } else {
                    state.hasActionItems = state.hasActionItems or hasActionVideo(list)
                    state.videos.addAll(list)
                }
                state.page++
                state.maxPage = absXml.movie!!.pagecount
            } else {
                if (state.page == 1) {
                    state.hasActionItems = false
                    showEmpty()
                } else if (state.page > 2) {
                    Toast.makeText(this@HomeActivity, "没有更多了", Toast.LENGTH_SHORT).show()
                }
            }
            pushUiState()
        }
        // action 结果(播放源动作 / 筛选):
        sourceViewModel!!.actionResult.observe(this) { jsonObject ->
            if (jsonObject == null) return@observe
            val msg = jsonObject.optString("msg")
            if (msg.isNotEmpty()) {
                Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show()
            }
            forceGridRefresh()
        }
    }

    private fun initData() {
        if (dataInitOk && jarInitOk) {
            loadHomeSort(false)
            if (!useCacheConfig && Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                jumpActivity(LivePlayActivity::class.java)
            }
            return
        }
        showLoading()
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isNullOrEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), object : ApiConfig.LoadConfigCallback {
                    override fun success() {
                        jarInitOk = true
                        mHandler.postDelayed({ initData() }, 50)
                    }

                    override fun notice(msg: String) {
                        mHandler.post { Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show() }
                    }

                    override fun error(msg: String) {
                        jarInitOk = true
                        dataInitOk = true
                        mHandler.postDelayed({
                            Toast.makeText(this@HomeActivity, msg + " jar load err", Toast.LENGTH_SHORT).show()
                            initData()
                        }, 50)
                    }
                })
            }
            return
        }
        ApiConfig.get().loadConfig(useCacheConfig, object : ApiConfig.LoadConfigCallback {
            override fun notice(msg: String) {
                mHandler.post { Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show() }
            }

            override fun success() {
                LOG.i("DIAG config=OK sites=" + ApiConfig.get().sourceBeanList.size +
                    " lives=" + ApiConfig.get().liveChannelGroupList.size +
                    " spider=" + (ApiConfig.get().getSpider() ?: ""))
                dataInitOk = true
                if (ApiConfig.get().getSpider().isNullOrEmpty()) jarInitOk = true
                mHandler.postDelayed({ initData() }, 50)
            }

            override fun error(msg: String) {
                LOG.i("DIAG config=ERROR msg=$msg")
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                    return
                }
                mHandler.post {
                    if (isActivityUnavailable()) return@post
                    if (mConfigErrorDialog == null) {
                        mConfigErrorDialog = TipDialog(this@HomeActivity, msg, "重试", "取消", object : TipDialog.OnListener {
                            override fun left() {
                                mHandler.post { dismissConfigErrorDialog(); initData() }
                            }

                            override fun right() {
                                dataInitOk = true
                                jarInitOk = true
                                mHandler.post { dismissConfigErrorDialog(); initData() }
                            }

                            override fun cancel() {
                                dataInitOk = true
                                jarInitOk = true
                                mHandler.post { dismissConfigErrorDialog(); initData() }
                            }
                        })
                    }
                    if (!mConfigErrorDialog!!.isShowing) mConfigErrorDialog!!.show()
                }
            }
        }, this)
    }

    private fun loadHomeSort(keepCurrentContent: Boolean) {
        val home = ApiConfig.get().homeSourceBean
        homeSortLoading = keepCurrentContent
        if (keepCurrentContent && home.name.isNotEmpty()) {
            previousHomeName = siteName
            siteName = home.name
            pushUiState()
        }
        loadingSourceKey = home.key
        if (!keepCurrentContent) showLoading()
        sourceViewModel!!.getSort(loadingSourceKey)
    }

    /** 依据分类数据构建页面状态(含 my0 占位,保证 pageStates 与 sortList 索引一致)。 */
    private fun buildPages(newSortData: List<MovieSort.SortData>, absXml: AbsSortXml?) {
        sortList.clear()
        sortList.addAll(newSortData)
        pageStates.clear()
        for (data in sortList) {
            val state = HomeGridPageState(data)
            state.style = ImgUtil.initStyle()
            val isFolder = state.uiTag() == '1'
            state.isFolderMode = isFolder
            state.columns = if (isFolder) 1 else gridSpanCount(state)
            pageStates.add(state)
            if (data.id == "my0") prepareUserPage(absXml)
        }
    }

    private fun gridSpanCount(state: HomeGridPageState): Int {
        var span = if (isBaseOnWidth()) 5 else 6
        if (state.style != null) span = ImgUtil.spanCountByStyle(state.style!!, span)
        return span.coerceAtLeast(1)
    }

    /** 主页热播数据:站点推荐 / 历史 / 豆瓣。 */
    private fun prepareUserPage(absXml: AbsSortXml?) {
        val recMode = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)
        userPage.useGridStyle = Hawk.get(HawkConfig.HOME_REC_STYLE, false)
        userPage.deleteMode = HawkConfig.hotVodDelete
        userPage.columns = if (isBaseOnWidth()) 5 else 6
        userPage.videos.clear()
        if (recMode == 1) {
            userPage.tvRate = "站点推荐"
            if (absXml?.videoList != null && absXml.videoList!!.isNotEmpty()) {
                userPage.videos.addAll(absXml.videoList!!)
                return
            }
            userPage.tvRate = "豆瓣热播"
            loadDouBanData()
        } else if (recMode == 2) {
            userPage.tvRate = "观看历史"
            for (vodInfo in RoomDataManger.getAllVodRecord(20)) {
                val vod = Movie.Video()
                vod.id = vodInfo.id
                vod.sourceKey = vodInfo.sourceKey
                vod.name = vodInfo.name
                vod.pic = vodInfo.pic
                if (!vodInfo.playNote.isNullOrEmpty()) vod.note = "上次看到" + vodInfo.playNote
                userPage.videos.add(vod)
            }
        } else {
            userPage.tvRate = "豆瓣热播"
            loadDouBanData()
        }
    }

    /** 豆瓣热播(原 UserFragment.setDouBanData/loadHots)。 */
    private fun loadDouBanData() {
        try {
            val cal = java.util.Calendar.getInstance()
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DATE)
            val today = String.format("%d%d%d", year, month, day)
            if (Hawk.get("home_hot_day", "") == today) {
                val cached = Hawk.get("home_hot", "")
                if (cached.isNotEmpty()) {
                    val hot = loadHots(cached)
                    if (hot.isNotEmpty()) {
                        userPage.videos.clear()
                        userPage.videos.addAll(hot)
                        pushUiState()
                        return
                    }
                }
            }
            val url = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=$year,$year"
            OkGo.get<String>(url)
                .headers("User-Agent", UA.randomOne())
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        val netJson = response.body() ?: return
                        Hawk.put("home_hot_day", today)
                        Hawk.put("home_hot", netJson)
                        runOnUiThread {
                            userPage.videos.clear()
                            userPage.videos.addAll(loadHots(netJson))
                            pushUiState()
                        }
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        return response.body()!!.string()
                    }
                })
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    private fun loadHots(json: String?): ArrayList<Movie.Video> {
        val result = ArrayList<Movie.Video>()
        try {
            val infoJson = Gson().fromJson(json, JsonObject::class.java)
            val array = infoJson.getAsJsonArray("data")
            val limit = minOf(array.size(), 25)
            for (i in 0 until limit) {
                val obj = array[i].asJsonObject
                val vod = Movie.Video()
                vod.name = obj.get("title").asString
                vod.note = obj.get("rate").asString
                if (!vod.note.isNullOrEmpty()) vod.note += " 分"
                vod.pic = obj.get("cover").asString + "@User-Agent=" + UA.randomOne() + "@Referer=https://www.douban.com/"
                result.add(vod)
            }
        } catch (ignored: Throwable) {
        }
        return result
    }

    private fun clearHomePages() {
        mHandler.removeCallbacks(mDataRunnable)
        currentSelected = 0
        sortFocused = 0
        sortChange = false
        sortList.clear()
        pageStates.clear()
        requestingPage = null
        userPage.videos.clear()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onBackPressed() {
        if (homeSortLoading) {
            cancelHomeSortLoading()
            return
        }
        if (isLoading()) {
            refreshEmpty()
            return
        }
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false
            userPage.deleteMode = false
            pushUiState()
            return
        }
        if (sortList.isEmpty() || (sortFocused !in pageStates.indices && !isUserPage(sortFocused))) {
            doExit()
            return
        }
        if (!isUserPage(sortFocused)) {
            val state = pageStates[sortFocused]
            if (state.popFolder()) {
                pushUiState()
                return
            }
            if (sortFocused != 0) {
                tabFocusToken++
                pushUiState()
            } else {
                doExit()
            }
        } else {
            doExit()
        }
    }

    private fun doExit() {
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.instance.finishAllActivity()
            unregisterEventBus()
            ControlManager.get().stopServer()
            finish()
            Process.killProcess(Process.myPid())
            System.exit(0)
        } else {
            mExitTime = System.currentTimeMillis()
            Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mHandler.removeCallbacks(mRunnable)
        mHandler.post(mRunnable)
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacks(mRunnable)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                val newIntent = Intent(this, DetailActivity::class.java)
                newIntent.putExtra("id", event.obj as String?)
                newIntent.putExtra("sourceKey", "push_agent")
                newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(newIntent)
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            pushUiState()   // filterCount 依据 sortData.filterSelectCount() 重算
        } else if (event.type == RefreshEvent.TYPE_HOME_SOURCE_CHANGE) {
            refreshHome(false)
        }
    }

    // ===== 分类条回调 =====
    private fun onTabFocused(index: Int) {
        if (index !in sortList.indices) return
        sortFocused = index
        sortChange = true
        mHandler.removeCallbacks(mDataRunnable)
        mHandler.postDelayed(mDataRunnable, 200)
    }

    private val mDataRunnable = Runnable {
        if (!sortChange) return@Runnable
        sortChange = false
        if (sortFocused == currentSelected) {
            if (!isUserPage(sortFocused)) {
                val state = pageStates.getOrNull(sortFocused)
                if (state != null && state.shouldReloadOnSelect()) loadGridPage(state, reset = true)
            }
            return@Runnable
        }
        currentSelected = sortFocused
        topHide = if (isUserPage(currentSelected)) 0 else 1
        pushUiState()
        if (!isUserPage(currentSelected)) {
            val state = pageStates.getOrNull(currentSelected)
            if (state != null && state.shouldReloadOnSelect()) loadGridPage(state, reset = true)
        }
    }

    private fun onTabClicked(index: Int) {
        if (index !in sortList.indices) return
        if (index == currentSelected) {
            if (isUserPage(index)) {
                showSiteSwitch()
            } else {
                showFilter()
            }
        }
    }

    /** 分类条按上键:主页刷新数据源,分类页强制刷新(原 setOnInBorderKeyEventListener FOCUS_UP)。 */
    private fun onTabUp() {
        if (isUserPage(sortFocused)) {
            refreshHomeSort()
        } else {
            val state = pageStates.getOrNull(sortFocused)
            if (state != null) forceGridRefresh()
        }
    }

    /** 站点名点击:清缓存(原 tvName.setOnClickListener)。 */
    private fun onSiteClicked() {
        FastClickCheckUtil.check(contentLayout)
        if (dataInitOk && jarInitOk) {
            val jar = ApiConfig.get().homeSourceBean.jar
            val jarUrl = if (!jar.isNullOrEmpty()) jar else ApiConfig.get().getSpider() ?: ""
            val jarSource = jarUrl.split(";md5;")[0]
            val cspCacheDir = File(FileUtils.getFilePath() + "/csp/" + MD5.string2MD5(jarSource) + ".jar")
            val jarCacheDir = File(FileUtils.getCachePath() + "/jar/" + MD5.string2MD5(jarSource) + ".jar")
            val jarFullCacheDir = File(FileUtils.getCachePath() + "/jar/" + MD5.string2MD5(jarUrl) + ".jar")
            Toast.makeText(this, "缓存已清除", Toast.LENGTH_LONG).show()
            Thread {
                try {
                    FileUtils.deleteFile(cspCacheDir)
                    FileUtils.deleteFile(jarCacheDir)
                    FileUtils.deleteFile(jarFullCacheDir)
                    FileUtils.clearSpiderCacheFiles()
                    ApiConfig.get().clearSpiderCache()
                    refreshHome()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        } else {
            jumpActivity(SettingActivity::class.java)
        }
    }

    // ===== 网格页回调 =====
    private fun loadGridPage(state: HomeGridPageState, reset: Boolean) {
        if (state.isRequesting) return
        val wasLoaded = state.isLoad
        if (reset) {
            state.page = 1
            state.maxPage = 1
            state.isLoad = false
            state.hasActionItems = false
            state.videos.clear()
        }
        if (!wasLoaded) showLoading()
        requestingPage = state
        state.isRequesting = true
        sourceViewModel!!.getList(state.sortData, state.page)
    }

    private fun forceGridRefresh() {
        val state = pageStates.getOrNull(currentSelected) ?: return
        if (state.isRequesting) return
        loadGridPage(state, reset = true)
    }

    private fun loadMore() {
        val state = pageStates.getOrNull(currentSelected) ?: return
        if (state.isRequesting || !state.isLoad) return
        if (state.maxPage > 0 && state.page > state.maxPage) return
        requestingPage = state
        state.isRequesting = true
        sourceViewModel!!.getList(state.sortData, state.page)
    }

    private fun hasActionVideo(videos: List<Movie.Video>?): Boolean {
        if (videos == null) return false
        for (v in videos) if (v.action != null) return true
        return false
    }

    private fun onGridVideoClick(video: Movie.Video) {
        val state = pageStates.getOrNull(currentSelected) ?: return
        if (video.action != null) {
            sourceViewModel!!.action(video.sourceKey ?: "", video.action)
            return
        }
        val bundle = Bundle()
        bundle.putString("id", video.id)
        bundle.putString("sourceKey", video.sourceKey)
        bundle.putString("title", video.name)
        if (!video.tag.isNullOrEmpty() && (video.tag == "folder" || video.tag == "cover")) {
            // 进入文件夹:保存快照,改写 flag/id,重载
            state.pushFolder()
            state.sortData.flag = if (state.style == null) {
                if (video.tag == "folder") "1" else "2"
            } else {
                "2"
            }
            state.sortData.id = video.id
            state.isFolderMode = state.uiTag() == '1'
            state.columns = if (state.isFolderMode) 1 else gridSpanCount(state)
            loadGridPage(state, reset = true)
        } else if (!video.id.isNullOrEmpty() && video.id!!.startsWith("msearch:")) {
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) && state.enableFastSearch()) {
                jumpActivity(FastSearchActivity::class.java, bundle)
            } else {
                jumpActivity(SearchActivity::class.java, bundle)
            }
        } else {
            bundle.putString("picture", video.pic)
            jumpActivity(DetailActivity::class.java, bundle)
        }
    }

    private fun onGridVideoLongClick(video: Movie.Video) {
        val bundle = Bundle()
        bundle.putString("id", video.id)
        bundle.putString("sourceKey", video.sourceKey)
        bundle.putString("title", video.name)
        if (!video.id.isNullOrEmpty() && video.id!!.startsWith("msearch:")) {
            bundle.putString("picture", video.pic)
            jumpActivity(DetailActivity::class.java, bundle)
        } else {
            jumpActivity(FastSearchActivity::class.java, bundle)
        }
    }

    /** 筛选 Dialog(原生保留:GridFilterDialog 内部构建筛选项,关闭后 forceRefresh)。 */
    private fun showFilter() {
        if (isUserPage(currentSelected)) return
        val state = pageStates.getOrNull(currentSelected) ?: return
        if (!state.hasFilters()) return
        if (gridFilterDialog == null) {
            gridFilterDialog = GridFilterDialog(this)
            gridFilterDialog!!.setData(state.sortData)
            gridFilterDialog!!.setOnDismiss(object : GridFilterDialog.Callback {
                override fun change() {
                    forceGridRefresh()
                }
            })
        }
        if (!gridFilterDialog!!.isShowing) gridFilterDialog!!.show()
    }

    // ===== 主页(my0)回调 =====
    private fun onEntryClicked(key: String) {
        HawkConfig.hotVodDelete = false
        when (key) {
            "live" -> jumpActivity(LivePlayActivity::class.java)
            "search" -> jumpActivity(SearchActivity::class.java)
            "setting" -> jumpActivity(SettingActivity::class.java)
            "history" -> jumpActivity(HistoryActivity::class.java)
            "push" -> jumpActivity(PushActivity::class.java)
            "favorite" -> jumpActivity(CollectActivity::class.java)
            "transfer" -> if (ScreenUtils.isTv(this)) jumpActivity(TransferActivity::class.java)
        }
    }

    private fun jumpSearch(vod: Movie.Video) {
        val bundle = Bundle()
        bundle.putString("title", vod.name)
        val target = if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) FastSearchActivity::class.java else SearchActivity::class.java
        jumpActivity(target, bundle)
    }

    private fun onHotVideoClick(vod: Movie.Video) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) return
        val recMode = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)
        if (recMode == 1 && vod.action != null) {
            sourceViewModel!!.action(vod.sourceKey ?: "", vod.action)
            return
        }
        if (!vod.id.isNullOrEmpty() && recMode == 2 && HawkConfig.hotVodDelete) {
            val idx = userPage.videos.indexOf(vod)
            if (idx >= 0) userPage.videos.removeAt(idx)
            val info = RoomDataManger.getVodInfo(vod.sourceKey ?: "", vod.id ?: "")
            if (info != null) RoomDataManger.deleteVodRecord(vod.sourceKey ?: "", info)
            Toast.makeText(this, "已删除当前记录", Toast.LENGTH_SHORT).show()
            return
        }
        if (!vod.id.isNullOrEmpty() && vod.id!!.startsWith("msearch:")) {
            jumpSearch(vod)
        } else {
            val bundle = Bundle()
            bundle.putString("id", vod.id)
            bundle.putString("sourceKey", vod.sourceKey)
            bundle.putString("title", vod.name)
            bundle.putString("picture", vod.pic)
            jumpActivity(DetailActivity::class.java, bundle)
        }
    }

    private fun onHotVideoLongClick(vod: Movie.Video) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) return
        val recMode = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC)
        if (!vod.id.isNullOrEmpty() && vod.id!!.startsWith("msearch:")) {
            val bundle = Bundle()
            bundle.putString("id", vod.id)
            bundle.putString("sourceKey", vod.sourceKey)
            bundle.putString("title", vod.name)
            bundle.putString("picture", vod.pic)
            jumpActivity(DetailActivity::class.java, bundle)
        } else if (!vod.id.isNullOrEmpty() && recMode == 2) {
            HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete
            userPage.deleteMode = HawkConfig.hotVodDelete
            pushUiState()
        } else {
            val bundle = Bundle()
            bundle.putString("title", vod.name)
            jumpActivity(FastSearchActivity::class.java, bundle)
        }
    }

    // ===== 数据源切换 / 刷新 =====
    private var menuKeyDownTime2 = 0L
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (topHide < 0) return false
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                menuKeyDownTime2 = System.currentTimeMillis()
            } else if (event.action == KeyEvent.ACTION_UP) {
                val duration = System.currentTimeMillis() - menuKeyDownTime2
                if (duration >= LONG_PRESS_THRESHOLD) jumpActivity(SettingActivity::class.java)
                else showSiteSwitch()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSiteSwitch() {
        if (isActivityUnavailable()) return
        val sites = ApiConfig.get().getSwitchSourceBeanList()
        if (sites.isEmpty()) return
        var select = sites.indexOf(ApiConfig.get().homeSourceBean)
        if (select < 0 || select >= sites.size) select = 0
        if (mSiteSwitchDialog == null) {
            mSiteSwitchDialog = SelectDialog<SourceBean>(this)
            val tvRecyclerView = mSiteSwitchDialog!!.findViewById<TvRecyclerView>(R.id.list)
            var spanCount = Math.floor(sites.size / 20.0).toInt()
            spanCount = Math.min(spanCount, 2)
            tvRecyclerView.layoutManager = V7GridLayoutManager(mSiteSwitchDialog!!.context, spanCount + 1)
            val clRoot = mSiteSwitchDialog!!.findViewById<ConstraintLayout>(R.id.cl_root)
            val clp = clRoot.layoutParams
            clp.width = AutoSizeUtils.mm2px(mSiteSwitchDialog!!.context, (380 + 200 * spanCount).toFloat())
            mSiteSwitchDialog!!.setTip("请选择首页数据源")
        }
        mSiteSwitchDialog!!.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<SourceBean> {
            override fun click(value: SourceBean, pos: Int) {
                dismissSiteSwitchDialog()
                previousHomeSource = ApiConfig.get().homeSourceBean
                ApiConfig.get().setSourceBean(value)
                refreshHome(false)
            }

            override fun getDisplay(`val`: SourceBean): String = `val`.name
        }, object : DiffUtil.ItemCallback<SourceBean>() {
            override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean = oldItem === newItem
            override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean = oldItem.key == newItem.key
        }, sites, select)
        if (!mSiteSwitchDialog!!.isShowing) mSiteSwitchDialog!!.show()
    }

    private fun refreshHome() = refreshHome(true)

    private fun refreshHomeSort() {
        refreshHomeRec = true
        userPage.videos.clear()
        pushUiState()
        val home = ApiConfig.get().homeSourceBean
        SourceViewModel.clearSortCache(home.key)
        refreshHome(false)
    }

    private fun updateHomeRec(absXml: AbsSortXml?) {
        if (!refreshHomeRec) return
        refreshHomeRec = false
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) != 1) return
        if (absXml == null || absXml.videoList == null) return
        userPage.tvRate = "站点推荐"
        userPage.videos.clear()
        userPage.videos.addAll(absXml.videoList!!)
        pushUiState()
    }

    private fun refreshHome(restart: Boolean) {
        if (Thread.currentThread() != Looper.getMainLooper().thread) {
            mHandler.post { refreshHome(restart) }
            return
        }
        if (isActivityUnavailable()) return
        dismissHomeDialogs()
        if (!restart) {
            loadHomeSort(true)
            return
        }
        val intent = Intent(applicationContext, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val bundle = Bundle()
        bundle.putBoolean("useCache", true)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    private fun refreshEmpty() {
        skipNextUpdate = true
        showSuccess()
        cancelHomeSortLoading()
        clearHomePages()
        sortList.addAll(DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true))
        for (data in sortList) pageStates.add(HomeGridPageState(data))
        pushUiState()
    }

    private fun cancelHomeSortLoading() {
        homeSortLoading = false
        loadingSourceKey = null
        if (previousHomeSource != null) ApiConfig.get().setSourceBean(previousHomeSource!!)
        if (!previousHomeName.isNullOrEmpty()) {
            siteName = previousHomeName!!
            pushUiState()
        }
        previousHomeSource = null
        previousHomeName = null
    }

    private fun isActivityUnavailable(): Boolean {
        return isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)
    }

    private fun dismissHomeDialogs() {
        dismissConfigErrorDialog()
        dismissSiteSwitchDialog()
        if (gridFilterDialog != null) {
            if (gridFilterDialog!!.isShowing) gridFilterDialog!!.dismiss()
            gridFilterDialog = null
        }
    }

    private fun dismissConfigErrorDialog() {
        if (mConfigErrorDialog != null) {
            if (mConfigErrorDialog!!.isShowing) mConfigErrorDialog!!.dismiss()
            mConfigErrorDialog = null
        }
    }

    private fun dismissSiteSwitchDialog() {
        if (mSiteSwitchDialog != null) {
            if (mSiteSwitchDialog!!.isShowing) mSiteSwitchDialog!!.dismiss()
            mSiteSwitchDialog = null
        }
    }

    override fun onDestroy() {
        dismissHomeDialogs()
        mHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        unregisterEventBus()
        if (isFinishing) ControlManager.get().stopServer()
    }

    private fun unregisterEventBus() {
        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this)
            eventBusRegistered = false
        }
    }

    companion object {
        private const val LONG_PRESS_THRESHOLD = 2000L
    }
}
