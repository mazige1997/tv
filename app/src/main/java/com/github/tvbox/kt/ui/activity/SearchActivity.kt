package com.github.tvbox.kt.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.AbsXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.core.spider.JsLoader
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.event.ServerEvent
import com.github.tvbox.kt.ui.adapter.PinyinAdapter
import com.github.tvbox.kt.ui.adapter.SearchAdapter
import com.github.tvbox.kt.ui.dialog.RemoteDialog
import com.github.tvbox.kt.ui.dialog.SearchCheckboxDialog
import com.github.tvbox.kt.ui.tv.widget.SearchKeyboard
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.github.tvbox.kt.util.SearchHelper
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
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
 * 搜索页,Kotlin 移植(与旧版 SearchActivity 一致)。
 * TODO: FastSearchActivity 与 SearchKeyboard(ui/tv/widget)由并行任务移植,此处按原 Java 签名引用。
 */
class SearchActivity : BaseActivity() {
    private var llLayout: LinearLayout? = null
    private var llHistoryWord: LinearLayout? = null
    private var mGridView: TvRecyclerView? = null
    private var mGridViewWord: TvRecyclerView? = null
    private var historyWordGrid: GridLayout? = null
    private var sourceViewModel: SourceViewModel? = null
    private var remoteDialog: RemoteDialog? = null
    private var etSearch: EditText? = null
    private var tvSearch: TextView? = null
    private var tvClear: TextView? = null
    private var tvHistoryClear: ImageView? = null
    private var keyboard: SearchKeyboard? = null
    private var searchAdapter: SearchAdapter? = null
    private var wordAdapter: PinyinAdapter? = null
    private var hotWordAdapter: PinyinAdapter? = null
    private var searchTitle = ""
    private var tvSearchCheckboxBtn: TextView? = null
    private var mSearchCheckboxDialog: SearchCheckboxDialog? = null
    private var wordsSwitch: TextView? = null
    private var aggregateSearchMode = false
    private var aggregateSearchModeInited = false

    private var searchExecutorService: ExecutorService? = null
    private var searchTimeoutExecutor: ScheduledExecutorService? = null
    private val allRunCount = AtomicInteger(0)
    private val pendingSearchKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val waitingSearchTasks: MutableList<SearchTask> = Collections.synchronizedList(ArrayList<SearchTask>())
    private val startedSearchKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val releasedSearchKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val searchTokenSeq = AtomicInteger(0)
    private var currentSearchToken = ""
    private var searchPaused = false
    private val detailFallbackSearchResults = ArrayList<Movie.Video>()
    private val folderHistory = ArrayList<List<Movie.Video>>()
    private var folderLoading = false

    override val layoutResID: Int
        get() = R.layout.activity_search

    override fun init() {
        initView()
        initViewModel()
        initData()
        hasKeyBoard = true
        isSearchBack = false
    }

    override fun onResume() {
        super.onResume()
        if (searchPaused) {
            resumePausedSearches()
        }
        requestSearchFocusWhenReady()
        applySearchWordMode()
        if (aggregateSearchMode) {
            refreshSearchHistoryWords()
            val hotsNow = hots
            if (hotsNow != null && hotsNow.isNotEmpty()) {
                hotWordAdapter!!.setNewData(hotsNow)
            }
        }
    }

    private fun requestSearchFocusWhenReady() {
        val focusView = if (hasKeyBoard == true || isSearchBack == true) tvSearch else etSearch
        if (focusView == null) return
        focusView.post {
            if (isFinishing) return@post
            focusView.requestFocus()
            focusView.requestFocusFromTouch()
        }
    }

    private fun initView() {
        EventBus.getDefault().register(this)
        llLayout = findViewById(R.id.llLayout)
        llHistoryWord = findViewById(R.id.llHistoryWord)
        etSearch = findViewById(R.id.etSearch)
        tvSearch = findViewById(R.id.tvSearch)
        tvSearchCheckboxBtn = findViewById(R.id.tvSearchCheckboxBtn)
        tvClear = findViewById(R.id.tvClear)
        mGridView = findViewById(R.id.mGridView)
        keyboard = findViewById(R.id.keyBoardRoot)
        mGridViewWord = findViewById(R.id.mGridViewWord)
        historyWordGrid = findViewById(R.id.historyWordGrid)
        tvHistoryClear = findViewById(R.id.tvHistoryClear)
        mGridViewWord!!.setHasFixedSize(true)
        wordAdapter = PinyinAdapter()
        hotWordAdapter = PinyinAdapter()
        wordsSwitch = findViewById(R.id.wordSwitch)
        applySearchWordMode()
        wordAdapter!!.setOnItemClickListener { _, _, position ->
            startSearch(wordAdapter!!.getItem(position))
        }
        hotWordAdapter!!.setOnItemClickListener { _, _, position ->
            startSearch(hotWordAdapter!!.getItem(position))
        }
        mGridView!!.setHasFixedSize(true)
        // lite
        if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0)
            mGridView!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)
        // with preview
        else
            mGridView!!.layoutManager = V7GridLayoutManager(this.mContext, 3)
        searchAdapter = SearchAdapter()
        mGridView!!.setAdapter(searchAdapter)
        searchAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = searchAdapter!!.data[position]
            if (video != null) {
                openSearchVideo(video)
            }
        }
        wordsSwitch!!.setOnClickListener { v ->
            if (aggregateSearchMode) {
                return@setOnClickListener
            }
            FastClickCheckUtil.check(v)
            val wd = wordsSwitch!!.text.toString().trim()
            if (wd.contains("热词")) {
                val hisWord = Hawk.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
                if (hisWord.isEmpty()) {
                    Toast.makeText(mContext, "暂无历史搜索", Toast.LENGTH_SHORT).show()
                } else {
                    wordsSwitch!!.text = "历史 搜索"
                    wordAdapter!!.setNewData(hisWord)
                }
            }
            if (wd == "历史 搜索") {
                wordsSwitch!!.text = "热词 搜索"
                val hotsNow = hots
                if (hotsNow != null && hotsNow.isNotEmpty()) {
                    wordAdapter!!.setNewData(hotsNow)
                }
            }
        }
        tvHistoryClear!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            HistoryHelper.clearSearchHistory()
            refreshSearchHistoryWords()
            Toast.makeText(mContext, "已清空搜索历史", Toast.LENGTH_SHORT).show()
        }
        tvSearch!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            hasKeyBoard = true
            val wd = etSearch!!.text.toString().trim()
            if (!TextUtils.isEmpty(wd)) {
                if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                    val bundle = Bundle()
                    bundle.putString("title", wd)
                    jumpActivity(FastSearchActivity::class.java, bundle)
                } else {
                    search(wd)
                }
            } else {
                Toast.makeText(mContext, "输入内容不能为空", Toast.LENGTH_SHORT).show()
            }
        }
        tvClear!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            initData()
            etSearch!!.setText("")
        }

        //软键盘

        etSearch!!.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val wd = etSearch!!.text.toString().trim()
                if (!TextUtils.isEmpty(wd)) {
                    if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                        val bundle = Bundle()
                        bundle.putString("title", wd)
                        jumpActivity(FastSearchActivity::class.java, bundle)
                    } else {
                        hiddenImm()
                        search(wd)
                    }
                } else {
                    Toast.makeText(mContext, "输入内容不能为空", Toast.LENGTH_SHORT).show()
                }
                true
            } else {
                false
            }
        }

        // 监听遥控器
        etSearch!!.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                val wd = etSearch!!.text.toString().trim()
                if (!TextUtils.isEmpty(wd)) {
                    if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                        val bundle = Bundle()
                        bundle.putString("title", wd)
                        jumpActivity(FastSearchActivity::class.java, bundle)
                    } else {
                        hiddenImm()
                        search(wd)
                    }
                } else {
                    Toast.makeText(mContext, "输入内容不能为空", Toast.LENGTH_SHORT).show()
                }
                true
            } else {
                false
            }
        }
        keyboard!!.setOnSearchKeyListener(object : SearchKeyboard.OnSearchKeyListener {
            override fun onSearchKey(pos: Int, key: String?) {
                if (pos > 1) {
                    var text = etSearch!!.text.toString().trim()
                    text += key
                    etSearch!!.setText(text)
                    if (text.length > 0) {
                        loadRec(text)
                    }
                } else if (pos == 1) {
                    var text = etSearch!!.text.toString().trim()
                    if (text.length > 0) {
                        text = text.substring(0, text.length - 1)
                        etSearch!!.setText(text)
                    }
                    if (text.length > 0) {
                        loadRec(text)
                    }
                } else if (pos == 0) {
                    remoteDialog = RemoteDialog(this@SearchActivity)
                    remoteDialog!!.show()
                }
            }
        })
        setLoadSir(llLayout!!)
        tvSearchCheckboxBtn!!.setOnClickListener { view ->
            val searchAbleSource = ApiConfig.get().getSearchSourceBeanList()
            if (mSearchCheckboxDialog == null) {
                mSearchCheckboxDialog = SearchCheckboxDialog(this, searchAbleSource, mCheckSources)
            } else {
                if (searchAbleSource.size != mSearchCheckboxDialog!!.mSourceList!!.size) {
                    mSearchCheckboxDialog!!.setMSourceList(searchAbleSource)
                }
            }
            mSearchCheckboxDialog!!.setOnDismissListener { dialog -> dialog.dismiss() }
            mSearchCheckboxDialog!!.show()
        }
    }

    private fun startSearch(wd: String?) {
        if (TextUtils.isEmpty(wd)) {
            return
        }
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            val bundle = Bundle()
            bundle.putString("title", wd)
            jumpActivity(FastSearchActivity::class.java, bundle)
        } else {
            search(wd)
        }
    }

    private fun isAggregateSearchMode(): Boolean {
        return Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)
    }

    private fun setAggregateHotTitle() {
        wordsSwitch!!.text = "热  门"
        wordsSwitch!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ts_22))
        wordsSwitch!!.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wordsSwitch!!.letterSpacing = 0.08f
        }
    }

    private fun setNormalWordTitle() {
        wordsSwitch!!.text = "热词 | 历史"
        wordsSwitch!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ts_20))
        wordsSwitch!!.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wordsSwitch!!.letterSpacing = 0f
        }
    }

    private fun applySearchWordMode() {
        val aggregateMode = isAggregateSearchMode()
        if (aggregateSearchModeInited && aggregateSearchMode == aggregateMode) {
            return
        }
        aggregateSearchModeInited = true
        aggregateSearchMode = aggregateMode
        if (aggregateSearchMode) {
            llHistoryWord!!.visibility = View.VISIBLE
            llLayout!!.visibility = View.GONE
            mGridView!!.visibility = View.GONE
            setAggregateHotTitle()
            wordsSwitch!!.isFocusable = false
            wordsSwitch!!.background = null
            mGridViewWord!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)
            mGridViewWord!!.setAdapter(hotWordAdapter)
            refreshSearchHistoryWords()
        } else {
            llHistoryWord!!.visibility = View.GONE
            llLayout!!.visibility = View.VISIBLE
            if (mGridView!!.visibility == View.GONE) {
                mGridView!!.visibility = View.INVISIBLE
            }
            setNormalWordTitle()
            wordsSwitch!!.isFocusable = true
            wordsSwitch!!.setBackgroundResource(R.drawable.shape_search_word)
            mGridViewWord!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)
            mGridViewWord!!.setAdapter(wordAdapter)
        }
    }

    private fun setHotWordsData(data: ArrayList<String>?) {
        if (aggregateSearchMode) {
            hotWordAdapter!!.setNewData(data)
        } else {
            wordAdapter!!.setNewData(data)
        }
    }

    private fun refreshSearchHistoryWords() {
        historyWordGrid!!.post {
            if (!aggregateSearchMode) return@post
            val history = Hawk.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())
            historyWordGrid!!.removeAllViews()
            val itemHeight = resources.getDimensionPixelSize(R.dimen.vs_50)
            val itemMargin = resources.getDimensionPixelSize(R.dimen.vs_5)
            val paddingH = resources.getDimensionPixelSize(R.dimen.vs_10)
            val minWidth = resources.getDimensionPixelSize(R.dimen.vs_80)
            var availableWidth = historyWordGrid!!.width
            if (availableWidth <= 0) availableWidth = llHistoryWord!!.width
            val textSize = resources.getDimension(R.dimen.ts_22)
            val textColor = resources.getColor(R.color.color_FFFFFF)
            var row: LinearLayout? = null
            var rowWidth = 0
            for (i in history.indices) {
                val word = history[i]
                val item = TextView(this@SearchActivity)
                item.text = word
                item.setSingleLine(true)
                item.gravity = Gravity.CENTER
                item.includeFontPadding = false
                item.isFocusable = true
                item.setTextColor(textColor)
                item.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                item.minWidth = minWidth
                item.setPadding(paddingH, 0, paddingH, 0)
                item.setBackgroundResource(R.drawable.shape_search_word)
                item.stateListAnimator = android.animation.AnimatorInflater.loadAnimator(
                    this@SearchActivity, R.animator.focus_scale
                ) as android.animation.StateListAnimator
                item.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(itemHeight, View.MeasureSpec.EXACTLY)
                )
                val itemWidth = Math.max(minWidth, item.measuredWidth)
                val rowItemWidth = itemWidth + itemMargin * 2
                if (row == null || (rowWidth > 0 && rowWidth + rowItemWidth > availableWidth)) {
                    row = LinearLayout(this@SearchActivity)
                    row.orientation = LinearLayout.HORIZONTAL
                    val rowParams = GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED)
                    )
                    rowParams.width = GridLayout.LayoutParams.MATCH_PARENT
                    rowParams.height = GridLayout.LayoutParams.WRAP_CONTENT
                    historyWordGrid!!.addView(row, rowParams)
                    rowWidth = 0
                }
                item.setOnClickListener { startSearch(word) }
                val params = LinearLayout.LayoutParams(itemWidth, itemHeight)
                params.setMargins(itemMargin, itemMargin, itemMargin, itemMargin)
                row!!.addView(item, params)
                rowWidth += rowItemWidth
            }
        }
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
            mGridView!!.visibility = View.VISIBLE
            searchAdapter!!.setNewData(data.movie!!.videoList)
        }
    }

    private fun openSearchVideo(video: Movie.Video) {
        pauseSearchTasks()
        hasKeyBoard = false
        if (TextUtils.equals("folder", video.tag)) {
            folderHistory.add(ArrayList(searchAdapter!!.data))
            folderLoading = true
            showLoading()
            sourceViewModel!!.getList(video.sourceKey ?: "", video.id ?: "")
            return
        }
        isSearchBack = true
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
            folderLoading = false
            val previous = folderHistory.removeAt(folderHistory.size - 1)
            showSuccess()
            mGridView!!.visibility = View.VISIBLE
            searchAdapter!!.setNewData(previous)
            return
        }
        super.onBackPressed()
    }

    /**
     * 拼音联想
     */
    private fun loadRec(key: String?) {
        OkGo.get<String>("https://tv.aiseet.atianqi.com/i-tvbin/qtv_video/search/get_search_smart_box")
            .params("format", "json")
            .params("page_num", 0)
            .params("page_size", 20)
            .params("key", key)
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: Response<String>) {
                    try {
                        val hots = ArrayList<String>()
                        val result = response.body()
                        val gson = Gson()
                        val json = gson.fromJson(result, JsonElement::class.java)
                        val groupDataArr = json.asJsonObject
                            .get("data").asJsonObject
                            .get("search_data").asJsonObject
                            .get("vecGroupData").asJsonArray
                            .get(0).asJsonObject
                            .get("group_data").asJsonArray
                        for (groupDataElement in groupDataArr) {
                            val groupData = groupDataElement.asJsonObject
                            val keywordTxt = groupData.getAsJsonObject("dtReportInfo")
                                .getAsJsonObject("reportData")
                                .get("keyword_txt").asString
                            hots.add(keywordTxt.trim())
                        }
                        wordsSwitch!!.text = "猜你 想搜"
                        setHotWordsData(hots)
                        mGridViewWord!!.smoothScrollToPosition(0)
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }

                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    return response.body()!!.string()
                }
            })
    }

    private fun useDefaultHotWords() {
        val data = ArrayList<String>()
        for (word in DEFAULT_HOT_WORDS) {
            data.add(word)
        }
        cacheHotWords(data)
    }

    private fun cacheHotWords(data: ArrayList<String>) {
        hots = data
        setHotWordsData(hots)
    }

    private fun cleanHotWord(title: String?): String {
        if (TextUtils.isEmpty(title)) return ""
        return title!!.trim().replace(Regex("<|>|《|》|-"), "").split(" ")[0]
    }

    private fun addHotWord(data: ArrayList<String>, title: String?) {
        val word = cleanHotWord(title)
        if (!TextUtils.isEmpty(word) && !data.contains(word)) {
            data.add(word)
        }
    }

    private fun initData() {
        initCheckedSourcesForSearch()
        applySearchWordMode()
        val intent = getIntent()
        if (intent != null && intent.hasExtra("title")) {
            val title = intent.getStringExtra("title")
            showLoading()
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                val bundle = Bundle()
                bundle.putString("title", title)
                jumpActivity(FastSearchActivity::class.java, bundle)
            } else {
                search(title)
            }
        }
        if (aggregateSearchMode) {
            setAggregateHotTitle()
            refreshSearchHistoryWords()
        } else {
            setNormalWordTitle()
        }
        val hotsNow = hots
        if (hotsNow != null && hotsNow.isNotEmpty()) {
            setHotWordsData(hotsNow)
            return
        }
        if (hotWordsRequested) {
            return
        }
        hotWordsRequested = true
        // 加载热词
        OkGo.get<String>(HOT_SEARCH_URL)
            //        OkGo.<String>get("https://api.web.360kan.com/v1/rank")
            //                .params("cat", "1")
            .headers("User-Agent", "Mozilla/5.0")
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: Response<String>) {
                    try {
                        val data = ArrayList<String>()
                        val itemList = JsonParser.parseString(response.body()).asJsonObject.get("subjects").asJsonArray
                        //                            JsonArray itemList = JsonParser.parseString(response.body()).getAsJsonObject().get("data").getAsJsonArray();
                        for (ele in itemList) {
                            val obj = ele as JsonObject
                            if (obj.has("title")) {
                                addHotWord(data, obj.get("title").asString)
                            }
                        }
                        if (data.isEmpty()) {
                            useDefaultHotWords()
                            return
                        }
                        cacheHotWords(data)
                    } catch (th: Throwable) {
                        th.printStackTrace()
                        useDefaultHotWords()
                    }
                }

                override fun onError(response: Response<String>) {
                    super.onError(response)
                    useDefaultHotWords()
                }

                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    return response.body()!!.string()
                }
            })
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun server(event: ServerEvent) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            val title = event.obj as String?
            showLoading()
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                val bundle = Bundle()
                bundle.putString("title", title)
                jumpActivity(FastSearchActivity::class.java, bundle)
            } else {
                search(title)
            }
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
        }
    }

    private fun initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch()
    }

    private fun search(title: String?) {
        cancel()
        if (remoteDialog != null) {
            remoteDialog!!.dismiss()
            remoteDialog = null
        }
        showLoading()
        etSearch!!.setText(title)

        //写入历史记录
        HistoryHelper.setSearchHistory(title ?: "")

        this.searchTitle = title ?: ""
        mGridView!!.visibility = View.INVISIBLE
        searchAdapter!!.setNewData(ArrayList())
        searchResult()
    }

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
            searchAdapter!!.setNewData(ArrayList())
            allRunCount.set(0)
            pendingSearchKeys.clear()
            waitingSearchTasks.clear()
            startedSearchKeys.clear()
            releasedSearchKeys.clear()
            detailFallbackSearchResults.clear()
            folderHistory.clear()
            currentSearchToken = searchTokenSeq.incrementAndGet().toString()
            searchPaused = false
        }
        val searchRequestList = ArrayList<SourceBean>()
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList())
        val home = ApiConfig.get().homeSourceBean
        searchRequestList.remove(home)
        searchRequestList.add(0, home)

        val searchTasks = ArrayList<SearchTask>()
        for (bean in searchRequestList) {
            if (!bean.isSearchable) {
                continue
            }
            if (mCheckSources != null && !mCheckSources!!.containsKey(bean.key)) {
                continue
            }
            searchTasks.add(SearchTask(bean.key, searchTitle, currentSearchToken))
        }
        if (searchTasks.size <= 0) {
            Toast.makeText(mContext, "没有指定搜索源", Toast.LENGTH_SHORT).show()
            showEmpty()
            return
        }
        for (task in searchTasks) {
            pendingSearchKeys.add(task.sourceKey)
        }
        allRunCount.set(searchTasks.size)
        searchExecutorService = createSearchExecutor()
        searchTimeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        waitingSearchTasks.addAll(searchTasks)
        startNextSearchBatch(currentSearchToken)
    }

    private fun addSearchResults(data: List<Movie.Video>?) {
        if (data == null || data.isEmpty()) return
        if (searchAdapter!!.data.size > 0) {
            searchAdapter!!.addData(data)
        } else {
            showSuccess()
            mGridView!!.visibility = View.VISIBLE
            searchAdapter!!.setNewData(data)
        }
    }

    private fun searchData(absXml: AbsXml?) {
        if (!isCurrentSearchResult(absXml)) {
            return
        }
        val sourceKey = absXml?.sourceKey ?: ""
        if (!markSearchFinished(sourceKey, absXml!!.searchToken)) {
            return
        }
        releaseSearchSlotAndStartNext(sourceKey, absXml.searchToken)
        if (absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.size > 0) {
            val data = SearchHelper.sortSearchResults(absXml.movie!!.videoList!!, searchTitle)
            detailFallbackSearchResults.addAll(data)
            addSearchResults(data)
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
        if (candidates.isNotEmpty()) {
            bundle.putSerializable(DetailActivity.EXTRA_DETAIL_FALLBACK_CANDIDATES, candidates)
        }
    }

    private fun scheduleSearchAdvance(sourceKey: String, searchToken: String?) {
        if (searchTimeoutExecutor == null) return
        searchTimeoutExecutor!!.schedule({
            if (!isCurrentSearchToken(searchToken)) return@schedule
            if (isSearchPending(sourceKey, searchToken) && releaseSearchSlot(sourceKey, searchToken)) {
                startNextSearchTask(searchToken)
            }
        }, SEARCH_NEXT_BATCH_SECONDS, TimeUnit.SECONDS)
    }

    private fun scheduleSearchTimeout(sourceKey: String, searchToken: String?) {
        if (searchTimeoutExecutor == null) return
        searchTimeoutExecutor!!.schedule({
            if (!isCurrentSearchToken(searchToken)) return@schedule
            if (markSearchFinished(sourceKey, searchToken)) {
                releaseSearchSlotAndStartNext(sourceKey, searchToken)
                runOnUiThread {
                    finishSearchIfDone()
                }
            }
        }, SEARCH_SITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
        return true
    }

    private fun createSearchExecutor(): ExecutorService {
        return ThreadPoolExecutor(0, SEARCH_MAX_THREAD_COUNT, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    }

    private fun startNextSearchBatch(searchToken: String?) {
        for (i in 0 until SEARCH_THREAD_COUNT) {
            if (!startNextSearchTask(searchToken)) {
                return
            }
        }
    }

    private fun startNextSearchTask(searchToken: String?): Boolean {
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

    private fun takeNextSearchTask(searchToken: String?): SearchTask? {
        synchronized(waitingSearchTasks) {
            while (waitingSearchTasks.isNotEmpty()) {
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
        val sourceKeys = getPendingSearchKeys()
        if (sourceKeys.isEmpty()) {
            finishSearchIfDone()
            return
        }
        currentSearchToken = searchTokenSeq.incrementAndGet().toString()
        waitingSearchTasks.clear()
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

    private fun releaseSearchSlot(sourceKey: String, searchToken: String?): Boolean {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false
        return releasedSearchKeys.add(sourceKey)
    }

    private fun releaseSearchSlotAndStartNext(sourceKey: String, searchToken: String?) {
        if (releaseSearchSlot(sourceKey, searchToken)) {
            startNextSearchTask(searchToken)
        }
    }

    private fun isSearchPending(sourceKey: String, searchToken: String?): Boolean {
        if (!isCurrentSearchToken(searchToken) || TextUtils.isEmpty(sourceKey)) return false
        synchronized(pendingSearchKeys) {
            return pendingSearchKeys.contains(sourceKey)
        }
    }

    private fun getPendingSearchKeys(): List<String> {
        synchronized(pendingSearchKeys) {
            return ArrayList(pendingSearchKeys)
        }
    }

    private fun finishSearchIfDone() {
        if (allRunCount.get() > 0) return
        searchPaused = false
        if (searchAdapter!!.data.size <= 0) {
            showEmpty()
        }
        cancel()
        if (searchTimeoutExecutor != null) {
            searchTimeoutExecutor!!.shutdownNow()
            searchTimeoutExecutor = null
        }
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

    private fun hiddenImm() {
        val imm = mContext?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch!!.windowToken, 0)
        }
    }

    companion object {
        private const val HOT_SEARCH_URL =
            "https://movie.douban.com/j/search_subjects?type=tv&tag=%E7%83%AD%E9%97%A8&sort=recommend&page_limit=20&page_start=0"
        private const val SEARCH_THREAD_COUNT = 6
        private val SEARCH_MAX_THREAD_COUNT =
            if (Build.VERSION.SDK_INT >= 35) 24 else if (Build.VERSION.SDK_INT >= 30) 18 else 12
        private const val SEARCH_NEXT_BATCH_SECONDS = 3L
        private const val SEARCH_SITE_TIMEOUT_SECONDS = 15L
        private val DEFAULT_HOT_WORDS = arrayOf(
            "家业",
            "主角",
            "低智商犯罪",
            "苏超",
            "书卷一梦",
            "美人余",
            "藏海传",
            "长安的荔枝",
            "庆余年",
            "凡人修仙传"
        )

        private var mCheckSources: HashMap<String, String>? = null
        private var hots: ArrayList<String>? = null
        private var hotWordsRequested = false
        private var hasKeyBoard: Boolean? = null
        private var isSearchBack: Boolean? = null

        @JvmStatic
        fun setCheckedSourcesForSearch(checkedSources: HashMap<String, String>?) {
            mCheckSources = checkedSources
        }
    }
}
