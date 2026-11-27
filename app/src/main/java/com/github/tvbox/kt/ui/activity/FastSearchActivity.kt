package com.github.tvbox.kt.ui.activity

import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.widget.LinearLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.AbsXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.core.spider.JsLoader
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.event.ServerEvent
import com.github.tvbox.kt.ui.compose.FastSearchUiSnapshot
import com.github.tvbox.kt.ui.compose.FastSearchScreen
import com.github.tvbox.kt.ui.compose.FastVideoUi
import com.github.tvbox.kt.ui.compose.TvTheme
import com.github.tvbox.kt.util.HistoryHelper
import com.github.tvbox.kt.util.SearchHelper
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.lzy.okgo.OkGo
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 * 快捷聚合搜索页(多源并行搜索 + 结果合并/过滤),Kotlin 移植(与旧版 FastSearchActivity 一致)。
 */
class FastSearchActivity : BaseActivity() {
    private var llLayout: LinearLayout? = null
    private var sourceViewModel: SourceViewModel? = null

    private var searchTitle = ""
    private lateinit var spNames: HashMap<String, String>
    private var isFilterMode = false
    private var searchFilterKey: String? = ""    // 过滤的key
    private lateinit var resultVods: HashMap<String, ArrayList<Movie.Video>> // 搜索结果
    private val quickSearchWord = ArrayList<String>()
    private val wordListNames = HashSet<String>()
    private var wordListVersion = 0
    private var selectedWordName = ""
    private var mCheckSources: HashMap<String, String>? = null

    // ===== Compose 状态:UI 由快照驱动,业务逻辑保持不变 =====
    private val uiState = mutableStateOf(FastSearchUiSnapshot())
    private val mainResults = ArrayList<FastVideoUi>()
    private val filterResults = ArrayList<FastVideoUi>()
    private val siteWords = ArrayList<String>()
    private val fenciWords = ArrayList<String>()
    private var showMainGrid = true
    private var showResults = true
    private var statusLine1 = "准备搜索"
    private var statusLine2 = "结果 0"
    private var siteFocusToken = 0

    override val layoutResID: Int
        get() = R.layout.activity_fast_search_compose

    override fun init() {
        spNames = HashMap()
        resultVods = HashMap()
        initView()
        initViewModel()
        initData()
    }

    override fun onResume() {
        super.onResume()
        if (searchPaused) {
            resumePausedSearches()
        }
    }

    private fun initView() {
        EventBus.getDefault().register(this)
        llLayout = findViewById(R.id.llLayout)
        setLoadSir(llLayout!!)

        val composeView = findViewById<ComposeView>(R.id.fastSearchComposeView)
        composeView.setContent {
            TvTheme {
                FastSearchScreen(
                    state = uiState.value,
                    onWordClick = { word -> search(word) },
                    onSiteFocus = { name -> selectWord(name) },
                    onSiteClick = { name -> selectWord(name) },
                    onResultClick = { index, filterMode -> onResultClicked(index, filterMode) }
                )
            }
        }
        pushUiState()
    }

    /** 站名列/结果点击:站名由 onSiteFocus/onSiteClick 处理;这里处理结果海报点击。 */
    private fun onResultClicked(position: Int, filterMode: Boolean) {
        val source = if (filterMode) resultVideosForClick(true) else resultVideosForClick(false)
        if (position in source.indices) {
            openSearchVideo(source[position], filterMode)
        }
    }

    /** 供点击取数:返回当前主/过滤列表对应的原始视频对象。 */
    private val currentMainVideos = ArrayList<Movie.Video>()
    private val currentFilterVideos = ArrayList<Movie.Video>()
    private fun resultVideosForClick(filterMode: Boolean): List<Movie.Video> =
        if (filterMode) currentFilterVideos else currentMainVideos

    /** 把原始视频列表转换成 Compose 快照数据(站点名 + 角标)。 */
    private fun videosToUi(list: List<Movie.Video>): List<FastVideoUi> = list.map { video ->
        FastVideoUi(
            name = video.name ?: "",
            pic = video.pic,
            siteName = ApiConfig.get().getSource(video.sourceKey)?.name ?: "",
            note = video.note
        )
    }

    /** 统一推送 UI 快照,驱动 Compose 重组。 */
    private fun pushUiState() {
        uiState.value = FastSearchUiSnapshot(
            statusLine1 = statusLine1,
            statusLine2 = statusLine2,
            words = ArrayList(fenciWords),
            sites = ArrayList(siteWords),
            selectedSite = selectedWordName.ifEmpty { SEARCH_ALL_NAME },
            mainResults = ArrayList(mainResults),
            filterResults = ArrayList(filterResults),
            showMainGrid = showMainGrid,
            showResults = showResults,
            siteFocusToken = siteFocusToken
        )
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel!!.listResult.observe(this) { data ->
            if (!folderLoading) return@observe
            folderLoading = false
            if (data == null || data.movie == null || data.movie!!.videoList == null) {
                showEmpty()
                return@observe
            }
            showSuccess()
            val list = data.movie!!.videoList ?: ArrayList()
            if (folderFilterMode) {
                showMainGrid = false
                currentFilterVideos.clear()
                currentFilterVideos.addAll(list)
                filterResults.clear()
                filterResults.addAll(videosToUi(list))
            } else {
                showMainGrid = true
                currentMainVideos.clear()
                currentMainVideos.addAll(list)
                mainResults.clear()
                mainResults.addAll(videosToUi(list))
            }
            showResults = true
            pushUiState()
        }
    }

    private fun openSearchVideo(video: Movie.Video, filterMode: Boolean) {
        pauseSearchTasks()
        if (TextUtils.equals("folder", video.tag)) {
            folderHistory.add(ArrayList(if (filterMode) currentFilterVideos else currentMainVideos))
            folderHistoryFilter.add(filterMode)
            folderHistorySiteKeys.add(selectedSearchSiteKey)
            folderFilterMode = filterMode
            folderLoading = true
            showLoading()
            sourceViewModel!!.getList(video.sourceKey ?: "", video.id ?: "")
            return
        }
        val bundle = Bundle()
        bundle.putString("id", video.id)
        bundle.putString("sourceKey", video.sourceKey)
        bundle.putString("title", video.name)
        bundle.putString("picture", video.pic)
        putDetailFallbackCandidates(bundle, video)
        jumpActivity(DetailActivity::class.java, bundle)
    }

    override fun onBackPressed() {
        if (folderHistory.isNotEmpty()) {
            val currentSiteKey = selectedSearchSiteKey
            val folderSiteKey = folderHistorySiteKeys[folderHistorySiteKeys.size - 1]
            if (!TextUtils.equals(currentSiteKey, folderSiteKey)) {
                folderLoading = false
                folderHistory.clear()
                folderHistoryFilter.clear()
                folderHistorySiteKeys.clear()
                super.onBackPressed()
                return
            }
            folderLoading = false
            val previous = folderHistory.removeAt(folderHistory.size - 1)
            val filterMode = folderHistoryFilter.removeAt(folderHistoryFilter.size - 1)
            folderHistorySiteKeys.removeAt(folderHistorySiteKeys.size - 1)
            folderFilterMode = filterMode
            showSuccess()
            if (filterMode) {
                showMainGrid = false
                currentFilterVideos.clear()
                currentFilterVideos.addAll(previous)
                filterResults.clear()
                filterResults.addAll(videosToUi(previous))
            } else {
                showMainGrid = true
                currentMainVideos.clear()
                currentMainVideos.addAll(previous)
                mainResults.clear()
                mainResults.addAll(videosToUi(previous))
            }
            showResults = true
            pushUiState()
            return
        }
        super.onBackPressed()
    }

    private val selectedSearchSiteKey: String
        get() {
            if (TextUtils.isEmpty(selectedWordName) || TextUtils.equals(selectedWordName, SEARCH_ALL_NAME)) {
                return SEARCH_ALL_NAME
            }
            val key = spNames[selectedWordName]
            return if (TextUtils.isEmpty(key)) selectedWordName else key!!
        }

    private fun filterResult(spName: String?) {
        if (TextUtils.isEmpty(spName)) return
        selectedWordName = spName!!
        setSelectedWordName(spName)
        if (TextUtils.equals(spName, SEARCH_ALL_NAME)) {
            showMainGrid = true
            pushUiState()
            return
        }
        showMainGrid = false
        val key = spNames[spName]
        if (TextUtils.isEmpty(key)) {
            pushUiState()
            return
        }

        if (TextUtils.equals(searchFilterKey, key)) {
            pushUiState()
            return
        }
        searchFilterKey = key

        var list = resultVods[key]
        if (list == null) {
            list = ArrayList()
        }
        currentFilterVideos.clear()
        currentFilterVideos.addAll(list)
        filterResults.clear()
        filterResults.addAll(videosToUi(list))
        showResults = true
        pushUiState()
    }

    private fun selectWord(spName: String?) {
        if (TextUtils.isEmpty(spName) || TextUtils.equals(selectedWordName, spName)) return
        filterResult(spName)
    }

    private fun setSelectedWordName(spName: String?) {
        // Compose 下选中态由快照的 selectedSite 驱动,无需刷新可见 View
        pushUiState()
    }

    private fun setWordListData(data: List<String>) {
        wordListVersion += 1
        siteWords.clear()
        siteWords.addAll(data)
        wordListNames.clear()
        wordListNames.addAll(data)
        if (data.isNotEmpty() && TextUtils.equals(data[0], SEARCH_ALL_NAME)) {
            requestWordListFirstFocus()
        }
        pushUiState()
    }

    private fun requestWordListFirstFocus() {
        siteFocusToken++
        pushUiState()
    }

    private fun addWordListDataIfAbsent(name: String?) {
        if (TextUtils.isEmpty(name) || !wordListNames.add(name!!)) return
        siteWords.add(name)
        pushUiState()
    }

    private fun fenci() {
        if (!quickSearchWord.isEmpty()) return // 如果经有分词了，不再进行二次分词
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle))
        val words = ArrayList(LinkedHashSet(quickSearchWord))
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words))
        // 分词
        //        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
        //                .tag("fenci")
        //                .execute(new AbsCallback<String>() {
        //                    @Override
        //                    public String convertResponse(okhttp3.Response response) throws Throwable {
        //                        if (response.body() != null) {
        //                            return response.body().string();
        //                        } else {
        //                            throw new IllegalStateException("网络请求错误");
        //                        }
        //                    }
        //
        //                    @Override
        //                    public void onSuccess(Response<String> response) {
        //                        String json = response.body();
        //                        quickSearchWord.clear();
        //                        try {
        //                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
        //                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
        //                            }
        //                        } catch (Throwable th) {
        //                            th.printStackTrace();
        //                        }
        //                        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        //                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
        //                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
        //                    }
        //
        //                    @Override
        //                    public void onError(Response<String> response) {
        //                        super.onError(response);
        //                    }
        //                });
    }

    private fun initData() {
        initCheckedSourcesForSearch()
        val intent = getIntent()
        if (intent != null && intent.hasExtra("title")) {
            val title = intent.getStringExtra("title")
            showLoading()
            search(title)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun server(event: ServerEvent) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            val title = event.obj as String
            showLoading()
            search(title)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(if (event.obj == null) null else event.obj as AbsXml)
            } catch (e: Exception) {
                searchData(null)
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                val data = event.obj as List<String>
                fenciWords.clear()
                fenciWords.addAll(data)
            }
        }
        updateSearchStatus()
    }

    private fun initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch()
    }

    private fun search(title: String?) {
        cancel()
        showLoading()
        this.searchTitle = title ?: ""
        fenci()
        showResults = false
        mainResults.clear()
        filterResults.clear()
        currentMainVideos.clear()
        currentFilterVideos.clear()

        selectedWordName = ""
        filterResult(SEARCH_ALL_NAME)
        resultVods.clear()
        searchFilterKey = ""
        isFilterMode = false
        spNames.clear()
        totalSearchCount.set(0)
        timedOutSearchCount.set(0)
        updateSearchStatus()

        //写入历史记录
        HistoryHelper.setSearchHistory(title ?: "")

        searchResult()
    }

    private var searchExecutorService: ExecutorService? = null
    private var searchTimeoutExecutor: ScheduledExecutorService? = null
    private val allRunCount = AtomicInteger(0)
    private val pendingSearchKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val waitingSearchTasks: MutableList<SearchTask> = Collections.synchronizedList(ArrayList<SearchTask>())
    private val startedSearchKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val releasedSearchKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private var startedSearchCountOffset = 0
    private val searchTokenSeq = AtomicInteger(0)
    private val totalSearchCount = AtomicInteger(0)
    private val timedOutSearchCount = AtomicInteger(0)
    private var currentSearchToken = ""
    private var searchPaused = false
    private val detailFallbackSearchResults = ArrayList<Movie.Video>()
    private var folderFilterMode = false
    private val folderHistory = ArrayList<List<Movie.Video>>()
    private val folderHistoryFilter = ArrayList<Boolean>()
    private val folderHistorySiteKeys = ArrayList<String>()
    private var folderLoading = false

    private fun searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor!!.shutdownNow()
                searchTimeoutExecutor = null
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        } finally {
            mainResults.clear()
            filterResults.clear()
            currentMainVideos.clear()
            currentFilterVideos.clear()
            showResults = false
            pushUiState()
            allRunCount.set(0)
            pendingSearchKeys.clear()
            waitingSearchTasks.clear()
            startedSearchKeys.clear()
            releasedSearchKeys.clear()
            startedSearchCountOffset = 0
            currentSearchToken = searchTokenSeq.incrementAndGet().toString()
            searchPaused = false
            totalSearchCount.set(0)
            timedOutSearchCount.set(0)
            detailFallbackSearchResults.clear()
            folderHistory.clear()
            folderHistoryFilter.clear()
            folderHistorySiteKeys.clear()
            updateSearchStatus()
        }
        val searchRequestList = ArrayList<SourceBean>()
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList())
        val home = ApiConfig.get().homeSourceBean
        searchRequestList.remove(home)
        searchRequestList.add(0, home)


        val searchTasks = ArrayList<SearchTask>()
        val hots = ArrayList<String>()
        hots.add(SEARCH_ALL_NAME)

        setWordListData(hots)
        for (bean in searchRequestList) {
            if (!bean.isSearchable) {
                continue
            }
            if (mCheckSources != null && !mCheckSources!!.containsKey(bean.key)) {
                continue
            }
            searchTasks.add(SearchTask(bean.key, searchTitle, currentSearchToken))
            this.spNames[bean.name] = bean.key
        }

        if (searchTasks.size <= 0) {
            showEmpty()
            setSearchStatusText("无搜索源", "结果 0")
            return
        }
        for (task in searchTasks) {
            pendingSearchKeys.add(task.sourceKey)
        }
        allRunCount.set(searchTasks.size)
        totalSearchCount.set(searchTasks.size)
        updateSearchStatus()
        searchExecutorService = createSearchExecutor()
        searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        waitingSearchTasks.addAll(searchTasks)
        startNextSearchBatch(currentSearchToken)
        startSearchPump(currentSearchToken)
        updateSearchStatus()
    }

    // 向过滤栏添加有结果的spname
    private fun addWordAdapterIfNeed(key: String): String {
        return try {
            var name = ""
            for (n in spNames.keys) {
                if (TextUtils.equals(spNames[n], key)) {
                    name = n
                }
            }
            if (TextUtils.isEmpty(name)) return key

            addWordListDataIfAbsent(name)
            key
        } catch (e: Exception) {
            key
        }
    }

    private fun addMainSearchResults(data: List<Movie.Video>?) {
        if (data == null || data.isEmpty()) return
        if (currentMainVideos.isNotEmpty()) {
            currentMainVideos.addAll(data)
            mainResults.addAll(videosToUi(data))
        } else {
            showSuccess()
            if (!isFilterMode) showMainGrid = true
            currentMainVideos.addAll(data)
            mainResults.clear()
            mainResults.addAll(videosToUi(data))
        }
        showResults = true
        pushUiState()
    }

    private fun searchData(absXml: AbsXml?) {
        if (!isCurrentSearchResult(absXml)) {
            return
        }
        val sourceKey = absXml?.sourceKey ?: ""
        if (!markSearchFinished(sourceKey, absXml!!.searchToken)) {
            return
        }
        releaseSearchSlotAndStartNext(sourceKey, absXml.searchToken!!)
        var lastSourceKey = ""

        if (absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.isNotEmpty()) {
            val data = SearchHelper.sortSearchResults(absXml.movie!!.videoList!!, searchTitle)
            for (video in data) {
                detailFallbackSearchResults.add(video)
                val videoKey = video.sourceKey ?: ""
                if (!resultVods.containsKey(videoKey)) {
                    resultVods[videoKey] = ArrayList()
                }
                resultVods[videoKey]!!.add(video)
                if (!TextUtils.equals(videoKey, lastSourceKey)) {
                    lastSourceKey = addWordAdapterIfNeed(videoKey)
                }
            }
            addMainSearchResults(data)
        }

        finishSearchIfDone()
    }

    private fun putDetailFallbackCandidates(bundle: Bundle?, selectedVideo: Movie.Video?) {
        if (bundle == null || selectedVideo == null || TextUtils.isEmpty(selectedVideo.name)) {
            return
        }
        val title = selectedVideo.name!!.trim()
        val candidates = ArrayList<Movie.Video>()
        val keys = HashSet<String>()
        for (video in detailFallbackSearchResults) {
            if (video == null || TextUtils.isEmpty(video.id)
                || title != (video.name?.trim() ?: "")
            ) {
                continue
            }
            val key = (video.sourceKey ?: "") + "|" + video.id
            if (keys.add(key)) {
                candidates.add(video)
                if (candidates.size >= 20) {
                    break
                }
            }
        }
        if (!candidates.isEmpty()) {
            bundle.putSerializable(DetailActivity.EXTRA_DETAIL_FALLBACK_CANDIDATES, candidates)
        }
    }

    private fun scheduleSearchAdvance(sourceKey: String, searchToken: String) {
        if (searchTimeoutExecutor == null) return
        searchTimeoutExecutor!!.schedule({
            if (!isCurrentSearchToken(searchToken)) return@schedule
            if (isSearchPending(sourceKey, searchToken) && releaseSearchSlot(sourceKey, searchToken)) {
                startNextSearchTask(searchToken)
            }
        }, SEARCH_NEXT_BATCH_SECONDS.toLong(), TimeUnit.SECONDS)
    }

    private fun startSearchPump(searchToken: String) {
        if (searchTimeoutExecutor == null) return
        searchTimeoutExecutor!!.scheduleWithFixedDelay({
            try {
                if (!isCurrentSearchToken(searchToken) || allRunCount.get() <= 0) return@scheduleWithFixedDelay
                if (getWaitingSearchCount() > 0) {
                    startNextSearchBatch(searchToken)
                    updateSearchStatusOnUiThread()
                }
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }, SEARCH_PUMP_SECONDS.toLong(), SEARCH_PUMP_SECONDS.toLong(), TimeUnit.SECONDS)
    }

    private fun updateSearchStatusOnUiThread() {
        runOnUiThread {
            updateSearchStatus()
        }
    }

    private fun scheduleSearchTimeout(sourceKey: String, searchToken: String) {
        if (searchTimeoutExecutor == null) return
        searchTimeoutExecutor!!.schedule({
            if (!isCurrentSearchToken(searchToken)) return@schedule
            if (markSearchFinished(sourceKey, searchToken)) {
                timedOutSearchCount.incrementAndGet()
                releaseSearchSlotAndStartNext(sourceKey, searchToken)
                runOnUiThread {
                    updateSearchStatus()
                    finishSearchIfDone()
                }
            }
        }, SEARCH_SITE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
    }

    private fun submitSearchTask(task: SearchTask): Boolean {
        if (!isSearchPending(task.sourceKey, task.searchToken)) return false
        if (searchExecutorService == null || searchExecutorService!!.isShutdown) return false
        try {
            searchExecutorService!!.execute(task)
        } catch (e: RejectedExecutionException) {
            return false
        }
        scheduleSearchAdvance(task.sourceKey, task.searchToken)
        scheduleSearchTimeout(task.sourceKey, task.searchToken)
        updateSearchStatusOnUiThread()
        return true
    }

    private fun createSearchExecutor(): ExecutorService {
        return ThreadPoolExecutor(0, SEARCH_MAX_THREAD_COUNT, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    }

    private fun startNextSearchBatch(searchToken: String) {
        for (i in 0 until SEARCH_THREAD_COUNT) {
            if (!startNextSearchTask(searchToken)) {
                return
            }
        }
    }

    private fun startNextSearchTask(searchToken: String): Boolean {
        if (!isCurrentSearchToken(searchToken)) return false
        val task = takeNextSearchTask(searchToken) ?: return false
        if (!submitSearchTask(task)) {
            startedSearchKeys.remove(task.sourceKey)
            synchronized(waitingSearchTasks) {
                waitingSearchTasks.add(0, task)
            }
            return false
        }
        return true
    }

    private fun takeNextSearchTask(searchToken: String): SearchTask? {
        synchronized(waitingSearchTasks) {
            while (!waitingSearchTasks.isEmpty()) {
                val task = waitingSearchTasks.removeAt(0)
                if (!isSearchPending(task.sourceKey, searchToken) || !startedSearchKeys.add(task.sourceKey)) {
                    continue
                }
                return task
            }
        }
        return null
    }

    private fun resumePausedSearches() {
        if (!searchPaused) {
            return
        }
        searchPaused = false
        val sourceKeys = pendingSearchKeysSnapshot()
        if (sourceKeys.isEmpty()) {
            finishSearchIfDone()
            return
        }
        currentSearchToken = searchTokenSeq.incrementAndGet().toString()
        waitingSearchTasks.clear()
        startedSearchCountOffset = Math.max(0, totalSearchCount.get() - allRunCount.get())
        startedSearchKeys.clear()
        releasedSearchKeys.clear()
        for (sourceKey in sourceKeys) {
            waitingSearchTasks.add(SearchTask(sourceKey, searchTitle, currentSearchToken))
        }
        if (searchExecutorService == null || searchExecutorService!!.isShutdown) {
            searchExecutorService = createSearchExecutor()
        }
        if (searchTimeoutExecutor == null || searchTimeoutExecutor!!.isShutdown) {
            searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        startNextSearchBatch(currentSearchToken)
        startSearchPump(currentSearchToken)
        updateSearchStatus()
    }

    private fun pauseSearchTasks() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor!!.shutdownNow()
                searchTimeoutExecutor = null
            }
            searchPaused = allRunCount.get() > 0
            if (searchPaused) {
                cancel()
                currentSearchToken = ""
            }
            updateSearchStatus()
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    private fun isCurrentSearchResult(absXml: AbsXml?): Boolean {
        return absXml != null && isCurrentSearchToken(absXml.searchToken)
    }

    private fun isCurrentSearchToken(searchToken: String?): Boolean {
        return !TextUtils.isEmpty(searchToken) && searchToken == currentSearchToken
    }

    private fun markSearchFinished(sourceKey: String, searchToken: String?): Boolean {
        if (!isCurrentSearchToken(searchToken)) return false
        synchronized(pendingSearchKeys) {
            if (TextUtils.isEmpty(sourceKey)) {
                return false
            }
            if (!pendingSearchKeys.remove(sourceKey)) {
                return false
            }
            allRunCount.set(pendingSearchKeys.size)
            return true
        }
    }

    private fun releaseSearchSlot(sourceKey: String, searchToken: String): Boolean {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false
        return releasedSearchKeys.add(sourceKey)
    }

    private fun releaseSearchSlotAndStartNext(sourceKey: String, searchToken: String) {
        if (releaseSearchSlot(sourceKey, searchToken)) {
            startNextSearchTask(searchToken)
        }
    }

    private fun isSearchPending(sourceKey: String, searchToken: String): Boolean {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false
        synchronized(pendingSearchKeys) {
            return pendingSearchKeys.contains(sourceKey)
        }
    }

    private fun pendingSearchKeysSnapshot(): List<String> {
        synchronized(pendingSearchKeys) {
            return ArrayList(pendingSearchKeys)
        }
    }

    private fun getWaitingSearchCount(): Int {
        synchronized(waitingSearchTasks) {
            return waitingSearchTasks.size
        }
    }

    private fun finishSearchIfDone() {
        if (allRunCount.get() > 0) return
        searchPaused = false
        updateSearchStatus()
        if (currentMainVideos.isEmpty() && resultVods.isEmpty()) {
            showEmpty()
        }
        cancel()
        if (searchTimeoutExecutor != null) {
            searchTimeoutExecutor!!.shutdownNow()
            searchTimeoutExecutor = null
        }
    }

    private fun getStartedSearchCount(): Int {
        synchronized(startedSearchKeys) {
            return startedSearchCountOffset + startedSearchKeys.size
        }
    }

    private fun getResultCount(): Int {
        return currentMainVideos.size
    }

    private fun updateSearchStatus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            updateSearchStatusOnUiThread()
            return
        }
        val total = totalSearchCount.get()
        val pending = allRunCount.get()
        val finished = Math.max(0, total - pending)
        val started = Math.min(total, getStartedSearchCount())
        val results = getResultCount()
        val timeouts = timedOutSearchCount.get()

        val firstLine: String
        var secondLine: String
        if (total <= 0) {
            firstLine = "准备搜索"
            secondLine = "结果 0"
        } else if (searchPaused && pending > 0) {
            firstLine = "已暂停 " + finished + "/" + total
            secondLine = "结果 " + results + " · 待 " + pending
        } else if (pending <= 0) {
            firstLine = "搜索完成 " + results
            secondLine = "源 " + total + "/" + total
            if (timeouts > 0) {
                secondLine += " · 超时 " + timeouts
            }
        } else if (started >= total) {
            firstLine = if (finished > 0) "等慢源 " + pending else "等待返回 " + pending
            secondLine = "结果 " + results + " · 完成 " + finished + "/" + total
        } else {
            firstLine = "搜源 " + started + "/" + total
            secondLine = "结果 " + results + " · 待 " + pending
        }
        setSearchStatusText(firstLine, secondLine)
    }

    private fun setSearchStatusText(firstLine: String, secondLine: String) {
        statusLine1 = firstLine
        statusLine2 = secondLine
        pushUiState()
    }

    private inner class SearchTask(val sourceKey: String, val title: String, val searchToken: String) : Runnable {
        override fun run() {
            if (!isSearchPending(sourceKey, searchToken)) return
            try {
                sourceViewModel!!.getSearch(sourceKey, title, searchToken)
            } catch (th: Throwable) {
                th.printStackTrace()
                if (markSearchFinished(sourceKey, searchToken)) {
                    runOnUiThread {
                        updateSearchStatus()
                        finishSearchIfDone()
                    }
                }
            }
        }
    }

    private fun cancel() {
        OkGo.getInstance().cancelTag("search")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
            if (searchTimeoutExecutor != null) {
                searchTimeoutExecutor!!.shutdownNow()
                searchTimeoutExecutor = null
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        EventBus.getDefault().unregister(this)
    }

    companion object {
        private const val SEARCH_THREAD_COUNT = 6
        private val SEARCH_MAX_THREAD_COUNT = if (Build.VERSION.SDK_INT >= 35) 24 else if (Build.VERSION.SDK_INT >= 30) 18 else 12
        private const val SEARCH_PUMP_SECONDS = 2
        private const val SEARCH_NEXT_BATCH_SECONDS = 3
        private const val SEARCH_SITE_TIMEOUT_SECONDS = 15
        private const val SEARCH_ALL_NAME = "全部"
    }
}
