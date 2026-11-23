package com.github.tvbox.kt.ui.activity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearSmoothScroller
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.AbsXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.adapter.SeriesAdapter
import com.github.tvbox.kt.ui.adapter.SeriesFlagAdapter
import com.github.tvbox.kt.ui.dialog.DescDialog
import com.github.tvbox.kt.ui.dialog.QuickSearchDialog
import com.github.tvbox.kt.ui.fragment.PlayFragment
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.ImgUtil
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.SearchHelper
import com.github.tvbox.kt.util.SubtitleHelper
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.lzy.okgo.OkGo
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern
import me.jessyan.autosize.utils.AutoSizeUtils

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 * 详情页(预览播放/选集/聚合搜索/换源 fallback),Kotlin 移植(与旧版 DetailActivity 一致)。
 * playFragment 属性对应旧版 getPlayFragment():RemoteServer.kt 以 act.playFragment 访问,
 * Java/Kotlin 调用方 detail.getPlayFragment() 由属性自动生成。
 */
class DetailActivity : BaseActivity() {
    private var llLayout: LinearLayout? = null
    private var llPlayerFragmentContainer: FragmentContainerView? = null
    private var llPlayerFragmentContainerBlock: View? = null
    private var llPlayerPlace: View? = null

    /** 对应旧版 getPlayFragment()(公开属性,JVM getter 为 getPlayFragment()) */
    var playFragment: PlayFragment? = null
    private var thumbContainer: View? = null
    private var ivThumb: ImageView? = null
    private var tvName: TextView? = null
    private var tvYear: TextView? = null
    private var tvSite: TextView? = null
    private var tvArea: TextView? = null
    private var tvLang: TextView? = null
    private var tvType: TextView? = null
    private var tvActor: TextView? = null
    private var tvDirector: TextView? = null
    private var tvPlayUrl: TextView? = null
    private var tvDes: TextView? = null
    private var tvPlay: TextView? = null
    //    private TextView tvSort;
    private var tvDesc: TextView? = null
    private var tvSeriesSort: TextView? = null
    private var tvQuickSearch: TextView? = null
    private var tvChangeSource: TextView? = null
    private var tvCollect: TextView? = null
    private var mGridViewFlag: TvRecyclerView? = null
    private var mGridViewQuality: TvRecyclerView? = null
    private var mGridView: TvRecyclerView? = null
    private var mSeriesGroupView: TvRecyclerView? = null
    private var mEmptyPlayList: LinearLayout? = null
    private var tvSeriesGroup: LinearLayout? = null
    private var sourceViewModel: SourceViewModel? = null
    private var mVideo: Movie.Video? = null
    private var vodInfo: VodInfo? = null
    private var seriesFlagAdapter: SeriesFlagAdapter? = null
    private var qualityAdapter: BaseQuickAdapter<String, BaseViewHolder>? = null
    private var seriesGroupAdapter: BaseQuickAdapter<String, BaseViewHolder>? = null
    private var seriesAdapter: SeriesAdapter? = null
    var vodId: String = ""
    var sourceKey: String = ""
    var firstsourceKey: String = ""
    private var fromCollect = false
    private var seriesSelect = false
    private var seriesFlagFocus: View? = null
    private var isReverse = false
    private var preFlag = ""
    private var routeSwitchSeries: VodInfo.VodSeries? = null
    private var firstReverse = false
    private var mGridViewLayoutMgr: V7GridLayoutManager? = null
    private var mCheckSources: HashMap<String, String>? = null
    private val seriesGroupOptions = ArrayList<String>()
    private val qualityOptions = ArrayList<String>()
    private var currentSeriesGroupView: View? = null
    private var selectedSeriesGroupPosition = 0
    private var GroupCount = 0
    private var qualityPosition = 0
    private var showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true) // true 开启 false 关闭

    private var smoothScroller: LinearSmoothScroller? = null

    private var pauseRunnable: MutableList<Runnable>? = null
    private var searchTitle = ""
    private var hadQuickStart = false
    private var quickSearchVodKey = ""
    private val quickSearchData = ArrayList<Movie.Video>()
    private val quickSearchWord = ArrayList<String>()
    private var searchExecutorService: ExecutorService? = null
    private var detailFallbackSearchExecutor: ExecutorService? = null
    private val detailFallbackSourceOrder = ArrayList<String>()
    private val detailFallbackCandidates = ArrayList<Movie.Video>()
    private val detailFallbackCache = HashMap<String, MutableList<Movie.Video>>()
    private val detailFallbackUsedSourceKeys = HashMap<String, HashSet<String?>>()
    private val detailFallbackPendingSources = HashSet<String?>()
    private val detailFallbackCandidateKeys = HashSet<String?>()
    private val detailFallbackTriedKeys = HashSet<String?>()
    private var detailFallbackEpisode: VodInfo.VodSeries? = null
    private var detailFallbackEpisodeIndex = -1
    private var detailFallbackActive = false
    private var detailFallbackSearching = false
    private var detailFallbackSearchCollecting = false
    private var detailFallbackSearchTimedOut = false
    private var detailFallbackDetailTimedOut = false
    private var detailFallbackSearchTimeoutScheduled = false
    private var detailFallbackKeepCurrentDetail = false
    private var detailFallbackLoadingCandidate = false
    private var detailFallbackRequestIndex = 0
    private var detailFallbackBatchIndex = 0
    private var detailFallbackNextSourceIndex = 0
    private var detailFallbackToken = ""
    private var detailFallbackBatchToken = ""
    private var detailFallbackTitle = ""
    private var detailFallbackExcludedSourceKey = ""
    private val detailFallbackTimeout = Runnable {
        finishDetailFallbackSearchOnTimeout()
    }
    private val detailFallbackDetailTimeout = Runnable {
        finishDetailFallbackDetailOnTimeout()
    }
    private var isFirstLoad = true
    private var vod_picture = ""
    private var vod_name = ""

    // preview
    private var previewVodInfo: VodInfo? = null
    private var fullWindows = false
    private var previewOrientation = 0
    private var previewOrientationChanged = false
    private var windowsPreview: ViewGroup.LayoutParams? = null
    private var windowsFull: ViewGroup.LayoutParams? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            fullWindows = savedInstanceState.getBoolean(STATE_FULL_WINDOWS, false)
        }
        super.onCreate(savedInstanceState)
    }

    override val layoutResID: Int
        get() = R.layout.activity_detail

    override fun init() {
        EventBus.getDefault().register(this)
        initView()
        initViewModel()
        initData()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initView() {
        llLayout = findViewById(R.id.llLayout)
        llPlayerPlace = findViewById(R.id.previewPlayerPlace)
        llPlayerFragmentContainer = findViewById(R.id.previewPlayer)
        llPlayerFragmentContainerBlock = findViewById(R.id.previewPlayerBlock)
        applyPreviewRoundCorners()
        thumbContainer = findViewById(R.id.thumbContainer)
        ivThumb = findViewById(R.id.ivThumb)
        applyThumbPreviewStyle()
        tvName = findViewById(R.id.tvName)
        tvYear = findViewById(R.id.tvYear)
        tvSite = findViewById(R.id.tvSite)
        tvArea = findViewById(R.id.tvArea)
        tvLang = findViewById(R.id.tvLang)
        tvType = findViewById(R.id.tvType)
        tvActor = findViewById(R.id.tvActor)
        tvDirector = findViewById(R.id.tvDirector)
        tvPlayUrl = findViewById(R.id.tvPlayUrl)
        tvDes = findViewById(R.id.tvDes)
        tvPlay = findViewById(R.id.tvPlay)
        //        tvSort = findViewById(R.id.tvSort);
        tvDesc = findViewById(R.id.tvDesc)
        tvSeriesSort = findViewById(R.id.mSeriesSortTv)
        tvCollect = findViewById(R.id.tvCollect)
        tvQuickSearch = findViewById(R.id.tvQuickSearch)
        tvChangeSource = findViewById(R.id.tvChangeSource)
        mEmptyPlayList = findViewById(R.id.mEmptyPlaylist)
        mGridView = findViewById(R.id.mGridView)
        mGridView!!.setHasFixedSize(false)
        this.mGridViewLayoutMgr = V7GridLayoutManager(this.mContext, 6)
        mGridView!!.layoutManager = this.mGridViewLayoutMgr
        //        mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));

        smoothScroller = object : LinearSmoothScroller(mContext) {
            override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                return 100f / displayMetrics.densityDpi
            }

            override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
                return mGridViewLayoutMgr!!.computeScrollVectorForPosition(targetPosition)
            }
        }

        seriesAdapter = SeriesAdapter(this.mGridViewLayoutMgr!!)
        mGridView!!.setAdapter(seriesAdapter)
        mGridViewFlag = findViewById(R.id.mGridViewFlag)
        mGridViewFlag!!.setHasFixedSize(true)
        mGridViewFlag!!.layoutManager = V7LinearLayoutManager(this.mContext, 0, false)
        seriesFlagAdapter = SeriesFlagAdapter()
        mGridViewFlag!!.setAdapter(seriesFlagAdapter)
        mGridViewQuality = findViewById(R.id.mGridViewQuality)
        mGridViewQuality!!.setHasFixedSize(true)
        mGridViewQuality!!.layoutManager = V7LinearLayoutManager(this.mContext, 0, false)
        qualityAdapter = object : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_flag, qualityOptions) {
            override fun convert(helper: BaseViewHolder, item: String) {
                helper.setText(R.id.tvSeriesFlag, item)
                helper.getView<View>(R.id.tvSeriesFlagSelect).visibility =
                    if (helper.layoutPosition == qualityPosition) View.VISIBLE else View.GONE
                helper.itemView.nextFocusUpId =
                    if (tvSeriesGroup!!.visibility == View.VISIBLE) R.id.mSeriesSortTv else R.id.mGridViewFlag
                helper.itemView.nextFocusDownId = R.id.mGridView
            }
        }
        mGridViewQuality!!.setAdapter(qualityAdapter)
        mGridViewQuality!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (playFragment == null) return
                if (position == qualityPosition) {
                    if (showPreview && !fullWindows && playFragment!!.getPlayer()!!.isPlaying) enterFullPreview()
                    return
                }
                if (playFragment!!.selectQuality(position)) {
                    qualityPosition = position
                    qualityAdapter!!.notifyDataSetChanged()
                }
            }
        })
        isReverse = false
        firstReverse = false
        preFlag = ""
        if (showPreview) {
            ensurePlayFragment()
            tvPlay!!.text = "全屏"
        }
        llPlayerFragmentContainerBlock!!.isFocusable = showPreview

        mSeriesGroupView = findViewById(R.id.mSeriesGroupView)
        tvSeriesGroup = findViewById(R.id.mSeriesGroupTv)
        mSeriesGroupView!!.setHasFixedSize(true)
        mSeriesGroupView!!.layoutManager = V7LinearLayoutManager(this.mContext, 0, false)
        seriesGroupAdapter = object : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_flag, seriesGroupOptions) {
            override fun convert(helper: BaseViewHolder, item: String) {
                val tvSeries = helper.getView<TextView>(R.id.tvSeriesFlag)
                tvSeries.text = item
                helper.getView<View>(R.id.tvSeriesFlagSelect).visibility =
                    if (helper.layoutPosition == selectedSeriesGroupPosition) View.VISIBLE else View.GONE
                helper.itemView.nextFocusUpId = R.id.mGridViewFlag
                if (helper.layoutPosition == data.size - 1) {
                    helper.itemView.id = View.generateViewId()
                    helper.itemView.nextFocusRightId = helper.itemView.id
                } else {
                    helper.itemView.nextFocusRightId = View.NO_ID
                }
            }
        }
        mSeriesGroupView!!.setAdapter(seriesGroupAdapter)

        llPlayerFragmentContainerBlock!!.setOnClickListener { v ->
            enterFullPreview()
            if (firstReverse) {
                jumpToPlay()
                firstReverse = false
            }
        }

        tvPlay!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            if (showPreview) {
                enterFullPreview()
                if (firstReverse) {
                    jumpToPlay()
                    firstReverse = false
                }
            } else {
                jumpToPlay()
            }
        }

        tvQuickSearch!!.setOnClickListener { v ->
            startQuickSearch()
            val quickSearchDialog = QuickSearchDialog(this@DetailActivity)
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData))
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord))
            quickSearchDialog.show()
            if (pauseRunnable != null && pauseRunnable!!.size > 0) {
                searchExecutorService = Executors.newFixedThreadPool(5)
                for (runnable in pauseRunnable!!) {
                    searchExecutorService!!.execute(runnable)
                }
                pauseRunnable!!.clear()
                pauseRunnable = null
            }
            quickSearchDialog.setOnDismissListener {
                try {
                    if (searchExecutorService != null) {
                        pauseRunnable = searchExecutorService!!.shutdownNow()
                        searchExecutorService = null
                    }
                } catch (th: Throwable) {
                    th.printStackTrace()
                }
            }
        }
        tvChangeSource!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            startDetailFallbackFromMenu()
        }
        tvCollect!!.setOnClickListener { v ->
            val text = tvCollect!!.text.toString()
            if ("加入收藏" == text) {
                RoomDataManger.insertVodCollect(sourceKey, vodInfo!!)
                Toast.makeText(this@DetailActivity, "已加入收藏夹", Toast.LENGTH_SHORT).show()
                tvCollect!!.text = "取消收藏"
            } else {
                RoomDataManger.deleteVodCollect(sourceKey, vodInfo!!)
                Toast.makeText(this@DetailActivity, "已移除收藏夹", Toast.LENGTH_SHORT).show()
                tvCollect!!.text = "加入收藏"
            }
        }
        tvPlayUrl!!.setOnClickListener { v ->
            //获取剪切板管理器
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            //设置内容到剪切板
            cm.setPrimaryClip(ClipData.newPlainText(null, tvPlayUrl!!.text.toString().replace("播放地址：", "")))
            Toast.makeText(this@DetailActivity, "已复制", Toast.LENGTH_SHORT).show()
        }

        tvSeriesSort!!.setOnClickListener { v ->
            if (vodInfo != null && vodInfo!!.seriesMap!!.size > 0) {
                vodInfo!!.reverseSort = !vodInfo!!.reverseSort
                isReverse = !isReverse
                tvSeriesSort!!.text = if (isReverse) "倒序" else "正序"
                vodInfo!!.reverse()
                if (vodInfo!!.playIndex >= 0) {
                    vodInfo!!.playIndex =
                        (vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size - 1) - vodInfo!!.playIndex
                }
                firstReverse = !firstReverse
                setSeriesGroupOptions()
                seriesAdapter!!.notifyDataSetChanged()

                if (vodInfo!!.playIndex >= 0) customSeriesScrollPos(vodInfo!!.playIndex)
                if (currentSeriesGroupView != null) {
                    val txtView = currentSeriesGroupView!!.findViewById<TextView>(R.id.tvSeriesFlag)
                    txtView.setTextColor(Color.WHITE)
                }
            }
        }
        tvDesc!!.setOnClickListener { v ->
            runOnUiThread {
                FastClickCheckUtil.check(v)
                val dialog = DescDialog(this@DetailActivity)
                dialog.setDescribe(removeHtmlTag(mVideo!!.des))
                dialog.show()
            }
        }

        mGridView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                seriesSelect = false
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                seriesSelect = true
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
        })
        mGridViewFlag!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            private fun refresh(itemView: View, position: Int) {
                val newFlag = seriesFlagAdapter!!.data[position].name
                if (vodInfo != null && vodInfo!!.playFlag != newFlag) {
                    val oldFlag = vodInfo!!.playFlag
                    val oldIndex = vodInfo!!.playIndex
                    var currentSeries = getPlayingSeries(previewVodInfo, previewVodInfo?.playFlag)
                    val oldSeriesList = vodInfo!!.seriesMap!![oldFlag ?: ""]
                    if (currentSeries == null && previewVodInfo == null && oldIndex >= 0 && oldSeriesList != null && oldSeriesList.isNotEmpty()) {
                        val safeOldIndex = Math.max(0, Math.min(oldIndex, oldSeriesList.size - 1))
                        currentSeries = oldSeriesList[safeOldIndex]
                    }
                    if (currentSeries == null) {
                        currentSeries = routeSwitchSeries
                    }
                    for (i in vodInfo!!.seriesFlags!!.indices) {
                        val flag = vodInfo!!.seriesFlags!![i]
                        if (flag.name == oldFlag) {
                            flag.selected = false
                            val oldItemView = mGridViewFlag!!.layoutManager?.findViewByPosition(i)
                            if (oldItemView != null) oldItemView.findViewById<View>(R.id.tvSeriesFlagSelect).visibility = View.GONE
                            break
                        }
                    }
                    val flag = vodInfo!!.seriesFlags!![position]
                    flag.selected = true
                    itemView.findViewById<View>(R.id.tvSeriesFlagSelect).visibility = View.VISIBLE
                    // clean pre flag select status
                    if (oldSeriesList != null && oldIndex >= 0 && oldSeriesList.size > oldIndex) {
                        oldSeriesList[oldIndex].selected = false
                    }
                    vodInfo!!.playFlag = newFlag
                    val newSeriesList = vodInfo!!.seriesMap!![newFlag ?: ""]
                    if (newSeriesList != null && newSeriesList.isNotEmpty()) {
                        vodInfo!!.playIndex = findMatchingEpisodeIndex(currentSeries, newSeriesList)
                        for (series in newSeriesList) {
                            series.selected = false
                        }
                        if (vodInfo!!.playIndex >= 0) {
                            newSeriesList[vodInfo!!.playIndex].selected = true
                            routeSwitchSeries = newSeriesList[vodInfo!!.playIndex]
                        } else if (currentSeries != null) {
                            routeSwitchSeries = currentSeries
                        }
                    }
                    refreshList()
                }
                seriesFlagFocus = itemView
            }

            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                //                seriesSelect = false;
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (itemView != null) refresh(itemView, position)
                //                if(isReverse)vodInfo.reverse();
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (itemView != null) refresh(itemView, position)
                //                if(isReverse)vodInfo.reverse();
            }
        })
        seriesAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            if (vodInfo != null && vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size > 0) {
                var reload = false
                for (j in 0 until vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size) {
                    seriesAdapter!!.data[j].selected = false
                    seriesAdapter!!.notifyItemChanged(j)
                }
                //解决倒叙不刷新
                if (vodInfo!!.playIndex != position) {
                    seriesAdapter!!.data[position].selected = true
                    seriesAdapter!!.notifyItemChanged(position)
                    vodInfo!!.playIndex = position
                    routeSwitchSeries = seriesAdapter!!.data[position]

                    reload = true
                }
                //解决当前集不刷新的BUG
                if (preFlag.isNotEmpty() && vodInfo!!.playFlag != preFlag) {
                    reload = true
                }
                val isCurrentPlaying = !showPreview || isCurrentPreviewPlaying(position)
                if (showPreview && !isCurrentPlaying) {
                    reload = true
                }

                seriesAdapter!!.data[vodInfo!!.playIndex].selected = true
                seriesAdapter!!.notifyItemChanged(vodInfo!!.playIndex)
                //选集全屏 想选集不全屏的注释下面一行
                if (showPreview && !fullWindows && previewVodInfo != null && TextUtils.equals(vodInfo!!.playFlag, previewVodInfo!!.playFlag) && (playFragment!!.getPlayer()!!.isPlaying || isCurrentPlaying)) enterFullPreview()
                if (!showPreview || reload) {
                    jumpToPlay()
                    firstReverse = false
                }
            }
        }

        mSeriesGroupView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                val txtView = itemView!!.findViewById<TextView>(R.id.tvSeriesFlag)
                txtView.setTextColor(Color.WHITE)
                //                currentSeriesGroupView = null;
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                selectSeriesGroup(itemView!!, position)
                if (vodInfo != null && vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size > 0) {
                    val targetPos = position * GroupCount
                    //                    mGridView.smoothScrollToPosition(targetPos);
                    customSeriesScrollPos(targetPos)
                }
                currentSeriesGroupView = itemView
                currentSeriesGroupView!!.isSelected
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
        })
        tvSeriesSort!!.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                if (vodInfo != null && vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size > 0) {
                    val firstVisible = mGridView!!.firstVisiblePosition
                    val lastVisible = mGridView!!.lastVisiblePosition
                    if (vodInfo!!.playIndex >= 0 && (vodInfo!!.playIndex < firstVisible || vodInfo!!.playIndex > lastVisible)) {
                        customSeriesScrollPos(vodInfo!!.playIndex)
                    }
                }
            } else {
                tvSeriesSort!!.setTextColor(Color.WHITE)
            }
        }
        seriesGroupAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            selectSeriesGroup(view, position)
            if (vodInfo != null && vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size > 0) {
                val targetPos = position * GroupCount + 1

                customSeriesScrollPos(targetPos)
            }
            if (currentSeriesGroupView != null) {
                val txtView = currentSeriesGroupView!!.findViewById<TextView>(R.id.tvSeriesFlag)
                txtView.setTextColor(Color.WHITE)
            }
            currentSeriesGroupView = view
            currentSeriesGroupView!!.isSelected
        }

        if (showPreview) {
            llPlayerFragmentContainerBlock!!.requestFocus()
        } else {
            tvPlay!!.requestFocus()
        }
        setLoadSir(llLayout!!)
        if (fullWindows) {
            setFullPreview(true)
        }
    }

    //解决类似海贼王的超长动漫 焦点滚动失败的问题
    private fun selectSeriesGroup(selectedView: View, position: Int) {
        if (selectedSeriesGroupPosition == position) return
        val previousView = mSeriesGroupView!!.layoutManager?.findViewByPosition(selectedSeriesGroupPosition)
        if (previousView != null) previousView.findViewById<View>(R.id.tvSeriesFlagSelect).visibility = View.GONE
        selectedSeriesGroupPosition = position
        selectedView.findViewById<View>(R.id.tvSeriesFlagSelect).visibility = View.VISIBLE
    }

    private fun customSeriesScrollPos(targetPos: Int) {
        if (targetPos < 0) return
        mGridViewLayoutMgr!!.scrollToPositionWithOffset(if (targetPos > 10) targetPos - 10 else 0, 0)
        mGridView!!.postDelayed({
            this.smoothScroller!!.setTargetPosition(targetPos)
            mGridViewLayoutMgr!!.startSmoothScroll(smoothScroller)
            mGridView!!.smoothScrollToPosition(targetPos)
        }, 50)
    }

    private fun initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch()
    }

    private fun jumpToPlay() {
        if (vodInfo != null && vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size > 0) {
            preFlag = vodInfo!!.playFlag!!
            //更新播放地址
            setTextShow(tvPlayUrl!!, "播放地址：", vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!![vodInfo!!.playIndex].url)
            val bundle = Bundle()
            //保存历史
            insertVod(firstsourceKey, vodInfo!!)
            //   insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey)
            //            bundle.putSerializable("VodInfo", vodInfo);
            KtApp.instance().setVodInfo(vodInfo)
            if (showPreview) {
                ensurePlayFragment()
                updatePreviewVodInfo()
                KtApp.instance().setVodInfo(previewVodInfo)
                if (playFragment != null) playFragment!!.setData(bundle)
            } else {
                ensurePlayFragment()
                if (playFragment != null) playFragment!!.setData(bundle)
                enterFullPreview()
            }
        }
    }

    private fun updatePreviewVodInfo() {
        if (previewVodInfo == null) {
            previewVodInfo = VodInfo()
        }
        previewVodInfo!!.id = vodInfo!!.id
        previewVodInfo!!.name = vodInfo!!.name
        previewVodInfo!!.pic = vodInfo!!.pic
        previewVodInfo!!.sourceKey = vodInfo!!.sourceKey
        previewVodInfo!!.playNote = vodInfo!!.playNote
        previewVodInfo!!.seriesFlags = vodInfo!!.seriesFlags
        previewVodInfo!!.seriesMap = vodInfo!!.seriesMap
        previewVodInfo!!.playerCfg = vodInfo!!.playerCfg
        previewVodInfo!!.playFlag = vodInfo!!.playFlag
        previewVodInfo!!.playIndex = vodInfo!!.playIndex
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() {
        if (vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size <= vodInfo!!.playIndex) {
            vodInfo!!.playIndex = 0
        }

        if (vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""] != null) {
            var canSelect = true
            for (j in 0 until vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size) {
                if (vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!![j].selected) {
                    canSelect = false
                    break
                }
            }
            if (canSelect && vodInfo!!.playIndex >= 0 && vodInfo!!.playIndex < vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size) {
                vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!![vodInfo!!.playIndex].selected = true
            }
        }

        val pFont = Paint()
        //        pFont.setTypeface(Typeface.DEFAULT );
        val rect = Rect()

        val list = vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!
        val listSize = list.size
        var w = 1
        for (i in 0 until listSize) {
            val name = list[i].name
            pFont.getTextBounds(name, 0, name!!.length, rect)
            if (w < rect.width()) {
                w = rect.width()
            }
        }
        w += 32
        val screenWidth = windowManager.defaultDisplay.width / 3
        var offset = screenWidth / w
        if (offset <= 2) offset = 2
        if (offset > 6) offset = 6
        mGridViewLayoutMgr!!.spanCount = offset
        seriesAdapter!!.setNewData(vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""])

        setSeriesGroupOptions()

        mGridView!!.postDelayed({
            //                mGridView.smoothScrollToPosition(vodInfo.playIndex);
            if (vodInfo!!.playIndex >= 0) customSeriesScrollPos(vodInfo!!.playIndex)
        }, 100)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setSeriesGroupOptions() {
        val list = vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!
        val listSize = list.size
        val offset = mGridViewLayoutMgr!!.spanCount
        seriesGroupOptions.clear()
        GroupCount = if (offset == 3 || offset == 6) 30 else 20
        if (listSize > 100 && listSize <= 400) GroupCount = 60
        if (listSize > 400) GroupCount = 120
        if (listSize > 1) {
            tvSeriesGroup!!.visibility = View.VISIBLE
            val remainedOptionSize = listSize % GroupCount
            val optionSize = listSize / GroupCount

            for (i in 0 until optionSize) {
                if (vodInfo!!.reverseSort)
                //                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + GroupCount, i * GroupCount + 1));
                    seriesGroupOptions.add(
                        String.format(
                            "%d - %d",
                            listSize - (i * GroupCount + 1) + 1,
                            listSize - (i * GroupCount + GroupCount) + 1
                        )
                    )
                else
                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + 1, i * GroupCount + GroupCount))
            }
            if (remainedOptionSize > 0) {
                if (vodInfo!!.reverseSort)
                //                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + remainedOptionSize, optionSize * GroupCount + 1));
                    seriesGroupOptions.add(
                        String.format(
                            "%d - %d",
                            listSize - (optionSize * GroupCount + 1) + 1,
                            listSize - (optionSize * GroupCount + remainedOptionSize) + 1
                        )
                    )
                else
                    seriesGroupOptions.add(
                        String.format(
                            "%d - %d",
                            optionSize * GroupCount + 1,
                            optionSize * GroupCount + remainedOptionSize
                        )
                    )
            }
            //            if(vodInfo.reverseSort) Collections.reverse(seriesGroupOptions);

            selectedSeriesGroupPosition = Math.max(0, Math.min(vodInfo!!.playIndex / GroupCount, seriesGroupOptions.size - 1))
            seriesGroupAdapter!!.notifyDataSetChanged()
        } else {
            tvSeriesGroup!!.visibility = View.GONE
        }
        if (!mGridViewFlag!!.hasFocus()) seriesFlagAdapter!!.notifyDataSetChanged()
        mGridViewQuality!!.nextFocusUpId =
            if (tvSeriesGroup!!.visibility == View.VISIBLE) R.id.mSeriesSortTv else R.id.mGridViewFlag
    }

    private fun updateQualityOptions(result: JSONObject?) {
        val options = ArrayList<String>()
        try {
            val value: Any? = result?.opt("url")
            val urls: JSONArray? = when (value) {
                is JSONArray -> value
                is String -> JSONArray(value)
                else -> null
            }
            if (urls != null) {
                var i = 0
                while (i + 1 < urls.length()) {
                    options.add(urls.optString(i))
                    i += 2
                }
            }
        } catch (th: Throwable) {
        }
        if (qualityOptions == options) {
            if (qualityPosition == 0) return
            qualityPosition = 0
            qualityAdapter!!.notifyDataSetChanged()
            return
        }
        qualityOptions.clear()
        qualityOptions.addAll(options)
        qualityPosition = 0
        val visible = showPreview && options.size > 1
        mGridViewQuality!!.visibility = if (visible) View.VISIBLE else View.GONE
        seriesFlagAdapter!!.notifyDataSetChanged()
        seriesAdapter!!.notifyDataSetChanged()
        qualityAdapter!!.setNewData(ArrayList(qualityOptions))
        val up = if (tvSeriesGroup!!.visibility == View.VISIBLE) R.id.mSeriesSortTv else R.id.mGridViewFlag
        val down = if (visible) R.id.mGridViewQuality else R.id.mGridView
        mGridViewQuality!!.nextFocusUpId = up
        mGridViewQuality!!.nextFocusDownId = R.id.mGridView
        tvSeriesSort!!.nextFocusDownId = down
        mSeriesGroupView!!.nextFocusDownId = down
    }

    private fun setTextShow(view: TextView, tag: String, info: String?) {
        if (info == null || info.trim().isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = Html.fromHtml(getHtml(tag, info))
    }

    private fun removeHtmlTag(info: String?): String {
        if (TextUtils.isEmpty(info))
            return ""
        var text = info!!.replace(Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])\\/](.*?)\\[\\/a]"), "$1")
        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        else
            Html.fromHtml(text).toString()
        return text.replace(Regex("\\s"), "")
    }

    private fun applyPreviewRoundCorners() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        val radius = resources.getDimension(R.dimen.preview_player_radius)
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        llPlayerFragmentContainer!!.clipToOutline = true
        llPlayerFragmentContainer!!.outlineProvider = provider
        llPlayerFragmentContainerBlock!!.clipToOutline = true
        llPlayerFragmentContainerBlock!!.outlineProvider = provider
    }

    private fun applyThumbPreviewStyle() {
        thumbContainer!!.visibility = if (showPreview) View.GONE else View.VISIBLE
        llPlayerPlace!!.visibility = if (showPreview) View.VISIBLE else View.GONE
        ivThumb!!.visibility = if (!showPreview) View.VISIBLE else View.GONE
        thumbContainer!!.setBackgroundResource(
            if (showPreview) R.drawable.shape_detail_thumb_bg else R.drawable.shape_detail_thumb_idle_bg
        )
    }

    private fun setPreviewRoundClip(enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        llPlayerFragmentContainer!!.clipToOutline = enable
        llPlayerFragmentContainerBlock!!.clipToOutline = enable
        llPlayerFragmentContainer!!.setBackgroundResource(if (enable) R.drawable.preview_player_round else android.R.color.black)
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel!!.detailResult.observe(this) { absXml ->
            if (detailFallbackActive && !detailFallbackLoadingCandidate) {
                return@observe
            }
            if (absXml != null && !TextUtils.isEmpty(absXml.sourceKey)
                && !TextUtils.equals(absXml.sourceKey, sourceKey)
                && !("push_fallback" == absXml.sourceKey && "push_agent" == sourceKey)
            ) {
                return@observe
            }
            if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.size > 0) {
                val fallbackResult = detailFallbackLoadingCandidate
                if (detailFallbackLoadingCandidate) {
                    detailFallbackLoadingCandidate = false
                    llLayout!!.removeCallbacks(detailFallbackDetailTimeout)
                }
                if (fallbackResult) {
                    val fallbackSource = ApiConfig.get().getSource(sourceKey)
                    val fallbackName = fallbackSource?.name ?: sourceKey
                    Toast.makeText(this@DetailActivity, "站点切换至" + fallbackName, Toast.LENGTH_SHORT).show()
                }
                showSuccess()
                if (!TextUtils.isEmpty(absXml.msg) && absXml.msg != "数据列表") {
                    resetDetailFallback()
                    Toast.makeText(this@DetailActivity, absXml.msg, Toast.LENGTH_SHORT).show()
                    showEmpty()
                    return@observe
                }
                mVideo = absXml.movie!!.videoList!![0]
                mVideo!!.id = vodId
                if (TextUtils.isEmpty(mVideo!!.name)) mVideo!!.name = vod_name
                if (TextUtils.isEmpty(mVideo!!.name)) mVideo!!.name = "TVBox"
                vodInfo = VodInfo()
                routeSwitchSeries = null
                if ((mVideo!!.pic == null || mVideo!!.pic!!.isEmpty()) && vod_picture.isNotEmpty()) {
                    mVideo!!.pic = vod_picture
                }
                vodInfo!!.setVideo(mVideo!!)
                vodInfo!!.sourceKey = mVideo!!.sourceKey
                sourceKey = mVideo!!.sourceKey ?: ""

                tvName!!.text = mVideo!!.name
                var displaySource = ApiConfig.get().getSource(firstsourceKey)
                if (displaySource == null) {
                    displaySource = ApiConfig.get().getSource(sourceKey)
                }
                setTextShow(tvSite!!, "来源：", displaySource?.name ?: "")
                setTextShow(tvYear!!, "年份：", if (mVideo!!.year == 0) "" else mVideo!!.year.toString())
                setTextShow(tvArea!!, "地区：", mVideo!!.area)
                setTextShow(tvLang!!, "语言：", mVideo!!.lang)
                if (firstsourceKey != sourceKey) {
                    setTextShow(tvType!!, "类型：", "[" + ApiConfig.get().getSource(sourceKey)!!.name + "] 解析")
                } else {
                    setTextShow(tvType!!, "类型：", mVideo!!.type)
                }
                setTextShow(tvActor!!, "演员：", removeHtmlTag(mVideo!!.actor))
                setTextShow(tvDirector!!, "导演：", removeHtmlTag(mVideo!!.director))
                setTextShow(tvDes!!, "内容简介：", removeHtmlTag(mVideo!!.des))
                if (!TextUtils.isEmpty(mVideo!!.pic)) {
                    ImgUtil.loadCenterCrop(
                        DefaultConfig.checkReplaceProxy(mVideo!!.pic!!), ivThumb!!,
                        AutoSizeUtils.mm2px(mContext, 10f), AutoSizeUtils.mm2px(mContext, 300f),
                        AutoSizeUtils.mm2px(mContext, 400f), mVideo!!.name
                    )
                } else {
                    ivThumb!!.setImageDrawable(ImgUtil.createTextDrawable(mVideo!!.name!!))
                }

                if (vodInfo!!.seriesMap != null && vodInfo!!.seriesMap!!.size > 0) {
                    mGridViewFlag!!.visibility = View.VISIBLE
                    mGridView!!.visibility = View.VISIBLE
                    tvPlay!!.visibility = View.VISIBLE
                    mEmptyPlayList!!.visibility = View.GONE

                    val vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId)
                    // 读取历史记录
                    if (vodInfoRecord != null) {
                        vodInfo!!.playIndex = Math.max(vodInfoRecord.playIndex, 0)
                        vodInfo!!.playFlag = vodInfoRecord.playFlag
                        vodInfo!!.playerCfg = vodInfoRecord.playerCfg
                        vodInfo!!.reverseSort = vodInfoRecord.reverseSort
                    } else {
                        vodInfo!!.playIndex = 0
                        vodInfo!!.playFlag = null
                        vodInfo!!.playerCfg = ""
                        vodInfo!!.reverseSort = false
                    }

                    if (vodInfo!!.reverseSort) {
                        vodInfo!!.reverse()
                    }

                    if (vodInfo!!.playFlag == null || !vodInfo!!.seriesMap!!.containsKey(vodInfo!!.playFlag!!))
                        vodInfo!!.playFlag = vodInfo!!.seriesMap!!.keys.toTypedArray()[0]

                    restoreDetailFallbackEpisode()
                    resetDetailFallback()
                    val playingSeriesList = vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!
                    vodInfo!!.playIndex = Math.max(0, Math.min(vodInfo!!.playIndex, playingSeriesList.size - 1))

                    var flagScrollTo = 0
                    for (j in vodInfo!!.seriesFlags!!.indices) {
                        val flag = vodInfo!!.seriesFlags!![j]
                        if (flag.name == vodInfo!!.playFlag) {
                            flagScrollTo = j
                            flag.selected = true
                        } else
                            flag.selected = false
                    }
                    //设置播放地址
                    setTextShow(tvPlayUrl!!, "播放地址：", playingSeriesList[vodInfo!!.playIndex].url)
                    seriesFlagAdapter!!.setNewData(vodInfo!!.seriesFlags)
                    mGridViewFlag!!.scrollToPosition(flagScrollTo)

                    refreshList()
                    if (showPreview) {
                        jumpToPlay()
                        llPlayerFragmentContainer!!.visibility = View.VISIBLE
                        llPlayerFragmentContainerBlock!!.visibility = View.VISIBLE
                        toggleSubtitleTextSize()
                    }
                    // startQuickSearch();
                } else {
                    mGridViewFlag!!.visibility = View.GONE
                    mGridView!!.visibility = View.GONE
                    tvSeriesGroup!!.visibility = View.GONE
                    tvPlay!!.visibility = View.GONE
                    mEmptyPlayList!!.visibility = View.VISIBLE
                    handleNoPlayableDetail()
                }
            } else {
                if (detailFallbackLoadingCandidate) {
                    detailFallbackLoadingCandidate = false
                    detailFallbackDetailTimedOut = true
                    llLayout!!.removeCallbacks(detailFallbackDetailTimeout)
                    loadNextDetailFallbackSource()
                    return@observe
                }
                handleEmptyDetail(absXml)
            }
        }
        sourceViewModel!!.detailFallbackSearchResult.observe(this) { absXml ->
            onDetailFallbackSearchResult(absXml)
        }
    }

    private fun getHtml(label: String, content: String?): String {
        val content0 = content ?: ""
        return label + "<font color=\"#FFFFFF\">" + content0 + "</font>"
    }

    private fun initData() {
        val intent = getIntent()
        if (intent != null && intent.extras != null) {
            val bundle = intent.extras!!
            vod_name = bundle.getString("title", "")
            vod_picture = bundle.getString("picture", "")
            fromCollect = bundle.getBoolean("collect", false)
            val fallbackCandidates = bundle.getSerializable(EXTRA_DETAIL_FALLBACK_CANDIDATES)
            if (fallbackCandidates is ArrayList<*>) {
                @Suppress("UNCHECKED_CAST")
                cacheDetailFallbackCandidates(vod_name, fallbackCandidates as ArrayList<Movie.Video>)
            }
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""))
        }
    }

    private fun loadDetail(vid: String?, key: String?) {
        loadDetail(vid, key, false)
    }

    private fun loadDetail(vid: String?, key: String?, fallback: Boolean) {
        if (!fallback) {
            resetDetailFallback()
        }
        vodId = vid ?: ""
        sourceKey = key ?: ""
        firstsourceKey = key ?: ""
        if (TextUtils.isEmpty(vid) || vid!!.startsWith("msearch:") || ApiConfig.get().getSource(sourceKey) == null) {
            LOG.i("echo-loadDetail invalid param: vid=$vid sourceKey=$sourceKey fromCollect=$fromCollect")
            if (!detailFallbackActive && !fromCollect) {
                Toast.makeText(this, "资源或站点未加载，请检查配置与网络", Toast.LENGTH_SHORT).show()
            }
            handleNoPlayableDetail()
            return
        }
        if (!fallback) {
            showLoading()
        }
        if (fallback && detailFallbackActive) {
            llLayout!!.removeCallbacks(detailFallbackDetailTimeout)
            llLayout!!.postDelayed(detailFallbackDetailTimeout, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS)
        }
        sourceViewModel!!.getDetail(sourceKey, vodId, fallback && detailFallbackActive)
        val isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId)
        if (isVodCollect) {
            tvCollect!!.text = "取消收藏"
        } else {
            tvCollect!!.text = "加入收藏"
        }
    }

    private fun handleEmptyDetail(data: AbsXml?) {
        val shouldFinish = data != null && !TextUtils.isEmpty(data.msg)
        if (shouldFinish || fromCollect) {
            resetDetailFallback()
            if (shouldFinish) {
                Toast.makeText(this, data!!.msg, Toast.LENGTH_SHORT).show()
            }
            finish()
            return
        }
        handleNoPlayableDetail()
    }

    private fun handleNoPlayableDetail() {
        if (detailFallbackActive) {
            detailFallbackLoadingCandidate = false
            loadNextDetailFallbackSource()
            return
        }
        startDetailFallback()
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean {
        return startDetailFallback(false)
    }

    private fun startDetailFallbackFromMenu() {
        if (!startDetailFallback(true)) {
            Toast.makeText(this, "暂无可切换的片源", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDetailFallback(manual: Boolean): Boolean {
        val currentSource = ApiConfig.get().getSource(sourceKey)
        if (isFinishing || currentSource == null || !currentSource.isChangeable) {
            return false
        }
        if (detailFallbackActive) {
            return true
        }
        detailFallbackKeepCurrentDetail = mVideo != null && vodInfo != null
                && vodInfo!!.seriesMap != null && !vodInfo!!.seriesMap!!.isEmpty()
        llLayout!!.removeCallbacks(detailFallbackDetailTimeout)
        captureDetailFallbackEpisode()
        if (mVideo != null && !TextUtils.isEmpty(mVideo!!.name)) {
            vod_name = mVideo!!.name!!
        }
        if (TextUtils.isEmpty(vod_name)) {
            return false
        }
        detailFallbackExcludedSourceKey = sourceKey
        detailFallbackTitle = vod_name.trim()
        addDetailFallbackUsedSource(sourceKey)
        if (loadDetailFallbackCache()) {
            return true
        }
        LOG.i("echo-detail fallback " + (if (manual) "manual" else "after lines exhausted") + ": " + vod_name)
        startDetailFallback()
        return detailFallbackActive
    }

    private fun startDetailFallback() {
        detailFallbackTitle = vod_name.trim()
        if (TextUtils.isEmpty(detailFallbackTitle)) {
            showDetailEmpty()
            return
        }

        for (bean in ApiConfig.get().getSourceBeanList()) {
            if (bean.isSearchable && bean.isChangeable && !TextUtils.equals(bean.key, detailFallbackExcludedSourceKey) && !isDetailFallbackSourceUsed(bean.key)) {
                detailFallbackSourceOrder.add(bean.key)
            }
        }
        if (detailFallbackSourceOrder.isEmpty()) {
            if (!detailFallbackKeepCurrentDetail) {
                showDetailEmpty()
            }
            return
        }

        detailFallbackActive = true
        detailFallbackSearching = true
        detailFallbackSearchCollecting = true
        detailFallbackSearchTimedOut = false
        detailFallbackDetailTimedOut = false
        detailFallbackSearchTimeoutScheduled = false
        detailFallbackLoadingCandidate = false
        detailFallbackBatchIndex = 0
        detailFallbackNextSourceIndex = 0
        detailFallbackRequestIndex++
        detailFallbackToken = "detail_fallback_" + detailFallbackRequestIndex
        detailFallbackTriedKeys.add(getDetailFallbackKey(sourceKey, vodId))
        LOG.i("echo-detail fallback search: " + detailFallbackTitle + ", sources=" + detailFallbackSourceOrder.size)
        scheduleDetailFallbackSearch()
    }

    private fun captureDetailFallbackEpisode() {
        detailFallbackEpisode = null
        detailFallbackEpisodeIndex = -1
        if (vodInfo == null || vodInfo!!.seriesMap == null || TextUtils.isEmpty(vodInfo!!.playFlag)) {
            return
        }
        val seriesList = vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]
        if (seriesList == null || seriesList.isEmpty()) {
            return
        }
        detailFallbackEpisodeIndex = Math.max(0, Math.min(vodInfo!!.playIndex, seriesList.size - 1))
        detailFallbackEpisode = seriesList[detailFallbackEpisodeIndex]
    }

    private fun restoreDetailFallbackEpisode() {
        if (detailFallbackEpisode == null || detailFallbackEpisodeIndex < 0 || vodInfo == null || vodInfo!!.seriesMap == null) {
            return
        }
        val preferredFlag = vodInfo!!.playFlag
        val preferredList = vodInfo!!.seriesMap!![preferredFlag ?: ""]
        var matchedIndex = findMatchingEpisodeIndex(detailFallbackEpisode, preferredList)
        if (matchedIndex >= 0) {
            vodInfo!!.playIndex = matchedIndex
            return
        }
        if (vodInfo!!.seriesFlags != null) {
            for (seriesFlag in vodInfo!!.seriesFlags!!) {
                if (seriesFlag == null || TextUtils.isEmpty(seriesFlag.name) || TextUtils.equals(preferredFlag, seriesFlag.name)) {
                    continue
                }
                val seriesList = vodInfo!!.seriesMap!![seriesFlag.name ?: ""]
                matchedIndex = findMatchingEpisodeIndex(detailFallbackEpisode, seriesList)
                if (matchedIndex >= 0) {
                    vodInfo!!.playFlag = seriesFlag.name
                    vodInfo!!.playIndex = matchedIndex
                    return
                }
            }
        }
        for (flag in vodInfo!!.seriesMap!!.keys) {
            if (TextUtils.equals(preferredFlag, flag) || containsSeriesFlag(flag)) {
                continue
            }
            val seriesList = vodInfo!!.seriesMap!![flag]
            matchedIndex = findMatchingEpisodeIndex(detailFallbackEpisode, seriesList)
            if (matchedIndex >= 0) {
                vodInfo!!.playFlag = flag
                vodInfo!!.playIndex = matchedIndex
                return
            }
        }
        if (preferredList != null && preferredList.isNotEmpty()) {
            vodInfo!!.playIndex = Math.max(0, Math.min(detailFallbackEpisodeIndex, preferredList.size - 1))
        }
    }

    private fun containsSeriesFlag(name: String?): Boolean {
        if (vodInfo == null || vodInfo!!.seriesFlags == null) {
            return false
        }
        for (flag in vodInfo!!.seriesFlags!!) {
            if (flag != null && TextUtils.equals(flag.name, name)) {
                return true
            }
        }
        return false
    }

    private fun onDetailFallbackSearchResult(data: AbsXml?) {
        if (!detailFallbackActive || !detailFallbackSearchCollecting || data == null || detailFallbackBatchToken != data.searchToken) {
            return
        }
        detailFallbackPendingSources.remove(data.sourceKey)
        if (data.movie != null && data.movie!!.videoList != null) {
            for (video in data.movie!!.videoList!!) {
                if (video == null || TextUtils.isEmpty(video.id) || isDetailFallbackSourceUsed(video.sourceKey) || detailFallbackTitle != (video.name?.trim() ?: "")) {
                    continue
                }
                val candidateKey = getDetailFallbackKey(video.sourceKey, video.id)
                if (!detailFallbackTriedKeys.contains(candidateKey) && detailFallbackCandidateKeys.add(candidateKey)) {
                    detailFallbackCandidates.add(video)
                    cacheDetailFallbackCandidate(video)
                }
            }
        }
        if (!detailFallbackLoadingCandidate && detailFallbackCandidates.isNotEmpty()
            && (!detailFallbackSearchTimedOut || detailFallbackDetailTimedOut)
        ) {
            LOG.i("echo-detail fallback candidates: " + detailFallbackCandidates.size)
            loadNextDetailFallbackSource()
        }
        if (!detailFallbackLoadingCandidate) {
            if (detailFallbackSearching) {
                scheduleDetailFallbackSearch()
            } else if (detailFallbackPendingSources.isEmpty()) {
                finishDetailFallbackSearchCollection()
            }
        }
    }

    private fun scheduleDetailFallbackSearch() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return
        }
        if (detailFallbackPendingSources.isNotEmpty() || detailFallbackLoadingCandidate) {
            return
        }
        if (detailFallbackNextSourceIndex >= detailFallbackSourceOrder.size) {
            detailFallbackSearching = false
            llLayout!!.removeCallbacks(detailFallbackTimeout)
            stopDetailFallbackSearchExecutor()
            LOG.i("echo-detail fallback candidates: " + detailFallbackCandidates.size)
            loadNextDetailFallbackSource()
            return
        }

        stopDetailFallbackSearchExecutor()
        detailFallbackSearchExecutor = Executors.newFixedThreadPool(DETAIL_FALLBACK_MAX_SEARCH)
        detailFallbackBatchIndex++
        detailFallbackBatchToken = detailFallbackToken + "_batch_" + detailFallbackBatchIndex
        val batchEnd = Math.min(detailFallbackNextSourceIndex + DETAIL_FALLBACK_MAX_SEARCH, detailFallbackSourceOrder.size)
        while (detailFallbackNextSourceIndex < batchEnd) {
            val searchKey = detailFallbackSourceOrder[detailFallbackNextSourceIndex]
            detailFallbackNextSourceIndex++
            val searchTitle = detailFallbackTitle
            val searchToken = detailFallbackBatchToken
            detailFallbackPendingSources.add(searchKey)
            detailFallbackSearchExecutor!!.execute {
                sourceViewModel!!.getDetailFallbackSearch(searchKey, searchTitle, searchToken)
            }
        }
        if (!detailFallbackSearchTimeoutScheduled) {
            detailFallbackSearchTimeoutScheduled = true
            llLayout!!.postDelayed(detailFallbackTimeout, DETAIL_FALLBACK_SEARCH_TIMEOUT_MS)
        }
    }

    private fun finishDetailFallbackSearchOnTimeout() {
        if (!detailFallbackActive || !detailFallbackSearching) {
            return
        }
        detailFallbackSearchTimeoutScheduled = false
        LOG.i("echo-detail fallback search timeout: " + detailFallbackBatchToken)
        // Keep the current 20 searches alive so late results can be used by the next fallback source.
        detailFallbackSearching = false
        detailFallbackSearchTimedOut = true
        detailFallbackNextSourceIndex = detailFallbackSourceOrder.size
        if (!detailFallbackLoadingCandidate) {
            LOG.i("echo-detail fallback candidates: " + detailFallbackCandidates.size)
            if (detailFallbackCandidates.isNotEmpty()) {
                loadNextDetailFallbackSource()
            } else {
                showDetailFallbackEmptyIfNeeded()
            }
        }
    }

    private fun loadNextDetailFallbackSource() {
        while (detailFallbackCandidates.isNotEmpty()) {
            val video = detailFallbackCandidates.removeAt(0)
            val candidateKey = getDetailFallbackKey(video.sourceKey, video.id)
            if (isDetailFallbackSourceUsed(video.sourceKey) || !detailFallbackTriedKeys.add(candidateKey)) {
                continue
            }
            LOG.i("echo-detail fallback source: " + video.sourceKey + ", id=" + video.id)
            detailFallbackLoadingCandidate = true
            detailFallbackDetailTimedOut = false
            addDetailFallbackUsedSource(video.sourceKey)
            vod_name = video.name ?: ""
            vod_picture = video.pic ?: ""
            loadDetail(video.id, video.sourceKey, true)
            return
        }
        if (detailFallbackSearching || detailFallbackSearchCollecting) {
            if (detailFallbackPendingSources.isEmpty()) {
                if (detailFallbackSearching) {
                    scheduleDetailFallbackSearch()
                } else {
                    finishDetailFallbackSearchCollection()
                }
            }
            return
        }
        finishDetailFallbackWithoutResult()
    }

    private fun finishDetailFallbackSearchCollection() {
        if (!detailFallbackSearchCollecting) {
            return
        }
        detailFallbackSearchCollecting = false
        stopDetailFallbackSearchExecutor()
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG)
        if (!detailFallbackLoadingCandidate) {
            finishDetailFallbackWithoutResult()
        }
    }

    private fun showDetailFallbackEmptyIfNeeded() {
        if (!detailFallbackKeepCurrentDetail) {
            showDetailEmpty()
        }
    }

    private fun finishDetailFallbackWithoutResult() {
        val keepCurrentDetail = detailFallbackKeepCurrentDetail
        resetDetailFallback()
        if (!keepCurrentDetail) {
            showDetailEmpty()
        }
    }

    private fun finishDetailFallbackDetailOnTimeout() {
        if (!detailFallbackActive || !detailFallbackLoadingCandidate) {
            return
        }
        LOG.i("echo-detail fallback detail timeout: " + sourceKey)
        detailFallbackLoadingCandidate = false
        detailFallbackDetailTimedOut = true
        OkGo.getInstance().cancelTag("detail")
        loadNextDetailFallbackSource()
    }

    private fun getDetailFallbackKey(key: String?, id: String?): String {
        return (key ?: "") + "|" + (id ?: "")
    }

    private fun loadDetailFallbackCache(): Boolean {
        val cachedCandidates = detailFallbackCache[detailFallbackTitle]
        if (cachedCandidates == null || cachedCandidates.isEmpty()) {
            return false
        }
        for (video in cachedCandidates) {
            val source = if (video == null) null else ApiConfig.get().getSource(video.sourceKey)
            if (video == null || source == null || !source.isChangeable
                || TextUtils.equals(video.sourceKey, detailFallbackExcludedSourceKey) || isDetailFallbackSourceUsed(video.sourceKey)
            ) {
                continue
            }
            val candidateKey = getDetailFallbackKey(video.sourceKey, video.id)
            if (detailFallbackCandidateKeys.add(candidateKey)) {
                detailFallbackCandidates.add(video)
            }
        }
        if (detailFallbackCandidates.isEmpty()) {
            return false
        }
        detailFallbackActive = true
        detailFallbackLoadingCandidate = false
        detailFallbackTriedKeys.add(getDetailFallbackKey(sourceKey, vodId))
        LOG.i("echo-detail fallback cache: " + detailFallbackTitle + ", candidates=" + detailFallbackCandidates.size)
        loadNextDetailFallbackSource()
        return true
    }

    private fun cacheDetailFallbackCandidates(title0: String?, candidates: List<Movie.Video>?) {
        val title = title0?.trim() ?: ""
        if (TextUtils.isEmpty(title) || candidates == null || candidates.isEmpty()) {
            return
        }
        var cachedCandidates = detailFallbackCache[title]
        if (cachedCandidates == null) {
            cachedCandidates = ArrayList()
            detailFallbackCache[title] = cachedCandidates
        }
        for (video in candidates) {
            if (cachedCandidates.size >= DETAIL_FALLBACK_MAX_SEARCH || video == null
                || TextUtils.isEmpty(video.id) || title != (video.name?.trim() ?: "")
            ) {
                continue
            }
            val candidateKey = getDetailFallbackKey(video.sourceKey, video.id)
            var exists = false
            for (cachedVideo in cachedCandidates) {
                if (candidateKey == getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id)) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                cachedCandidates.add(video)
            }
        }
    }

    private fun cacheDetailFallbackCandidate(video: Movie.Video) {
        var cachedCandidates = detailFallbackCache[detailFallbackTitle]
        if (cachedCandidates == null) {
            cachedCandidates = ArrayList()
            detailFallbackCache[detailFallbackTitle] = cachedCandidates
        }
        val candidateKey = getDetailFallbackKey(video.sourceKey, video.id)
        for (cachedVideo in cachedCandidates) {
            if (candidateKey == getDetailFallbackKey(cachedVideo.sourceKey, cachedVideo.id)) {
                return
            }
        }
        cachedCandidates.add(video)
    }

    private fun addDetailFallbackUsedSource(sourceKey: String?) {
        if (TextUtils.isEmpty(detailFallbackTitle) || TextUtils.isEmpty(sourceKey)) {
            return
        }
        val usedSources = detailFallbackUsedSourceKeys.getOrPut(detailFallbackTitle) { HashSet() }
        usedSources.add(sourceKey)
    }

    private fun isDetailFallbackSourceUsed(sourceKey: String?): Boolean {
        val usedSources = detailFallbackUsedSourceKeys[detailFallbackTitle]
        return usedSources != null && usedSources.contains(sourceKey)
    }

    private fun showDetailEmpty() {
        showEmpty()
        llPlayerFragmentContainer!!.visibility = View.GONE
        llPlayerFragmentContainerBlock!!.visibility = View.GONE
    }

    private fun resetDetailFallback() {
        detailFallbackActive = false
        detailFallbackSearching = false
        detailFallbackSearchCollecting = false
        detailFallbackSearchTimedOut = false
        detailFallbackDetailTimedOut = false
        detailFallbackSearchTimeoutScheduled = false
        detailFallbackKeepCurrentDetail = false
        detailFallbackLoadingCandidate = false
        detailFallbackBatchIndex = 0
        detailFallbackNextSourceIndex = 0
        detailFallbackToken = ""
        detailFallbackBatchToken = ""
        detailFallbackTitle = ""
        detailFallbackExcludedSourceKey = ""
        detailFallbackSourceOrder.clear()
        detailFallbackCandidates.clear()
        detailFallbackPendingSources.clear()
        detailFallbackCandidateKeys.clear()
        detailFallbackTriedKeys.clear()
        detailFallbackEpisode = null
        detailFallbackEpisodeIndex = -1
        if (llLayout != null) {
            llLayout!!.removeCallbacks(detailFallbackTimeout)
            llLayout!!.removeCallbacks(detailFallbackDetailTimeout)
        }
        stopDetailFallbackSearchExecutor()
        OkGo.getInstance().cancelTag(DETAIL_FALLBACK_SEARCH_TAG)
    }

    private fun stopDetailFallbackSearchExecutor() {
        if (detailFallbackSearchExecutor != null) {
            detailFallbackSearchExecutor!!.shutdownNow()
            detailFallbackSearchExecutor = null
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj is VodInfo) {
                    syncPlayingVodInfo(event.obj as VodInfo)
                } else if (event.obj is Int) {
                    val index = event.obj as Int
                    for (j in 0 until vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]!!.size) {
                        seriesAdapter!!.data[j].selected = false
                        seriesAdapter!!.notifyItemChanged(j)
                    }
                    seriesAdapter!!.data[index].selected = true
                    seriesAdapter!!.notifyItemChanged(index)
                    if (!isFirstLoad) mGridView!!.setSelection(index)
                    vodInfo!!.playIndex = index
                    routeSwitchSeries = seriesAdapter!!.data[index]
                    //保存历史
                    insertVod(firstsourceKey, vodInfo!!)
                    isFirstLoad = false
                } else if (event.obj is JSONObject) {
                    vodInfo!!.playerCfg = event.obj.toString()
                    //保存历史
                    insertVod(firstsourceKey, vodInfo!!)
                } else if (event.obj is String) {
                    val url = event.obj.toString()
                    //设置更新播放地址
                    setTvPlayUrl(url)
                }

            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_QUALITY) {
            updateQualityOptions(if (event.obj is JSONObject) event.obj as JSONObject else null)
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                val video = event.obj as Movie.Video
                vod_name = video.name ?: ""
                vod_picture = video.pic ?: ""
                loadDetail(video.id, video.sourceKey)
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                val word = event.obj as String
                switchSearchWord(word)
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(if (event.obj == null) null else event.obj as AbsXml)
            } catch (e: Exception) {
                searchData(null)
            }
        }
    }

    private fun switchSearchWord(word: String) {
        OkGo.getInstance().cancelTag("quick_search")
        quickSearchData.clear()
        searchTitle = word
        searchResult()
    }

    private fun startQuickSearch() {
        initCheckedSourcesForSearch()
        val currentVodKey = sourceKey + ":" + vodId
        if (hadQuickStart && TextUtils.equals(quickSearchVodKey, currentVodKey))
            return
        hadQuickStart = true
        quickSearchVodKey = currentVodKey
        pauseRunnable = null
        OkGo.getInstance().cancelTag("quick_search")
        quickSearchWord.clear()
        searchTitle = mVideo!!.name!!
        quickSearchData.clear()
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle))

        searchResult()
    }

    private fun searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        searchExecutorService = Executors.newFixedThreadPool(5)
        val searchRequestList = ArrayList<SourceBean>()
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList())
        val home = ApiConfig.get().homeSourceBean
        searchRequestList.remove(home)
        searchRequestList.add(0, home)

        val siteKey = ArrayList<String>()
        for (bean in searchRequestList) {
            if (!bean.isSearchable || !bean.isQuickSearch) {
                continue
            }
            if (mCheckSources != null && !mCheckSources!!.containsKey(bean.key)) {
                continue
            }
            siteKey.add(bean.key)
        }
        for (key in siteKey) {
            searchExecutorService!!.execute {
                sourceViewModel!!.getQuickSearch(key, searchTitle)
            }
        }
    }

    private fun searchData(absXml: AbsXml?) {
        if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.size > 0) {
            val data = ArrayList<Movie.Video>()
            for (video in absXml.movie!!.videoList!!) {
                // 去除当前相同的影片
                if (video.sourceKey == sourceKey && video.id == vodId)
                    continue
                data.add(video)
            }
            quickSearchData.addAll(data)
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data))
        }
    }

    private fun syncPlayingVodInfo(playingVodInfo: VodInfo?) {
        if (playingVodInfo == null || vodInfo == null || vodInfo!!.seriesMap == null) {
            return
        }
        val newFlag = playingVodInfo.playFlag
        if (TextUtils.isEmpty(newFlag) || !vodInfo!!.seriesMap!!.containsKey(newFlag!!)) {
            return
        }
        val newSeriesList = vodInfo!!.seriesMap!![newFlag]
        if (newSeriesList == null || newSeriesList.isEmpty()) {
            return
        }

        val oldFlag = vodInfo!!.playFlag
        val oldIndex = vodInfo!!.playIndex
        val sameFlag = TextUtils.equals(oldFlag, newFlag)
        val playingSeries = getPlayingSeries(playingVodInfo, newFlag)
        val newIndex = findMatchingEpisodeIndex(playingSeries, newSeriesList)
        vodInfo!!.playFlag = newFlag
        vodInfo!!.playIndex = newIndex
        if (playingVodInfo.playerCfg != null) {
            vodInfo!!.playerCfg = playingVodInfo.playerCfg
        }

        for (flag in vodInfo!!.seriesFlags!!) {
            flag.selected = flag.name == newFlag
        }
        for (seriesList in vodInfo!!.seriesMap!!.values) {
            if (seriesList == null) {
                continue
            }
            for (series in seriesList) {
                series.selected = false
            }
        }
        if (newIndex >= 0) {
            newSeriesList[newIndex].selected = true
            routeSwitchSeries = newSeriesList[newIndex]
        }

        seriesFlagAdapter!!.notifyDataSetChanged()
        if (sameFlag && newIndex >= 0 && oldIndex >= 0 && oldIndex < newSeriesList.size) {
            if (oldIndex != newIndex) {
                seriesAdapter!!.notifyItemChanged(oldIndex)
                seriesAdapter!!.notifyItemChanged(newIndex)
            }
        } else {
            refreshList()
        }
        if (newIndex >= 0) {
            setTvPlayUrl(newSeriesList[newIndex].url)
        }

        var flagIndex = -1
        for (i in vodInfo!!.seriesFlags!!.indices) {
            if (vodInfo!!.seriesFlags!![i].name == newFlag) {
                flagIndex = i
                break
            }
        }
        if (flagIndex >= 0) {
            mGridViewFlag!!.scrollToPosition(flagIndex)
            if (mGridViewFlag!!.hasFocus()) {
                mGridViewFlag!!.setSelection(flagIndex)
            }
        }
        if (!isFirstLoad && newIndex >= 0) {
            mGridView!!.setSelection(newIndex)
        }

        insertVod(firstsourceKey, vodInfo!!)
        isFirstLoad = false
    }

    private fun getPlayingSeries(playingVodInfo: VodInfo?, flag: String?): VodInfo.VodSeries? {
        if (playingVodInfo == null || playingVodInfo.seriesMap == null || TextUtils.isEmpty(flag)) {
            return null
        }
        val playingList = playingVodInfo.seriesMap!![flag ?: ""]
        if (playingList == null || playingList.isEmpty()) {
            return null
        }
        val safeIndex = Math.max(0, Math.min(playingVodInfo.playIndex, playingList.size - 1))
        return playingList[safeIndex]
    }

    private fun isCurrentPreviewPlaying(position: Int): Boolean {
        if (!showPreview || previewVodInfo == null || vodInfo == null || vodInfo!!.seriesMap == null || TextUtils.isEmpty(vodInfo!!.playFlag)) {
            return false
        }
        if (!TextUtils.equals(vodInfo!!.playFlag, previewVodInfo!!.playFlag) || previewVodInfo!!.playIndex != position) {
            return false
        }
        val currentList = vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]
        if (currentList == null || position < 0 || position >= currentList.size) {
            return false
        }
        val currentSeries = currentList[position]
        val previewSeries = getPlayingSeries(previewVodInfo, previewVodInfo!!.playFlag)
        return currentSeries != null && previewSeries != null && TextUtils.equals(currentSeries.url, previewSeries.url)
    }

    private fun findSameEpisodeIndex(currentSeries: VodInfo.VodSeries?, targetList: List<VodInfo.VodSeries>?, fallbackIndex: Int): Int {
        if (targetList == null || targetList.isEmpty()) {
            return 0
        }
        val matchedIndex = findMatchingEpisodeIndex(currentSeries, targetList)
        return if (matchedIndex >= 0) matchedIndex else Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
    }

    private fun findMatchingEpisodeIndex(currentSeries: VodInfo.VodSeries?, targetList: List<VodInfo.VodSeries>?): Int {
        if (targetList == null || targetList.isEmpty()) {
            return -1
        }
        if (targetList.size == 1) {
            return 0
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return -1
        }
        val currentEpisode = extractEpisodeNumber(currentSeries.name)
        var matchedIndex = -1
        var bestScore = 0
        for (i in targetList.indices) {
            val targetSeries = targetList[i]
            val score = getEpisodeMatchScore(currentSeries.name, currentEpisode, targetSeries?.name)
            if (score > bestScore) {
                bestScore = score
                matchedIndex = i
            }
        }
        return matchedIndex
    }

    private fun getEpisodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(targetName)) {
            return 0
        }
        if (targetName!!.equals(currentName!!, ignoreCase = true)) {
            return 100
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80
        }
        val currentLower = currentName.toLowerCase(Locale.ROOT)
        val targetLower = targetName.toLowerCase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) {
            return 70
        }
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) {
            return 60
        }
        return 0
    }

    private fun extractEpisodeNumber(name: String?): Int {
        if (TextUtils.isEmpty(name)) {
            return -1
        }
        try {
            var text = name!!.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            text = text.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            text = text.toLowerCase(Locale.ROOT).replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")
            val matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text)
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1))
            }
            val number = text.replace(Regex("\\D+"), "")
            if (!TextUtils.isEmpty(number)) {
                return Integer.parseInt(number)
            }
        } catch (ignored: Exception) {
        }
        return -1
    }

    private fun insertVod(sourceKey: String, vodInfo: VodInfo) {
        try {
            vodInfo.playNote = vodInfo.seriesMap!![vodInfo.playFlag ?: ""]!![vodInfo.playIndex].name ?: ""
        } catch (th: Throwable) {
            vodInfo.playNote = ""
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo)
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    override fun onDestroy() {
        resetDetailFallback()
        super.onDestroy()
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        OkGo.getInstance().cancelTag("fenci")
        OkGo.getInstance().cancelTag("detail")
        OkGo.getInstance().cancelTag("quick_search")
        releasePlayFragment()
        EventBus.getDefault().unregister(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FULL_WINDOWS, fullWindows)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (fullWindows) {
            if (playFragment!!.onBackPressed())
                return
            exitFullPreview()
            return
        }
        if (mGridView != null && mGridView!!.hasFocus()
            && mGridViewFlag != null && mGridViewFlag!!.visibility == View.VISIBLE
        ) {
            try {
                if (seriesFlagFocus != null && seriesFlagFocus!!.isShown
                    && seriesFlagFocus!!.requestFocus()
                ) {
                    return
                }
                if (mGridViewFlag!!.requestFocus()) {
                    return
                }
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }
        if (showPreview && playFragment != null) {
            try {
                playFragment!!.setPlayTitle(false)
                playFragment!!.setExitingPreview(true)
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event != null && !fullWindows && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_MENU) {
            startDetailFallbackFromMenu()
            return true
        }
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment!!.dispatchKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment!!.onKeyDown(keyCode, event)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment!!.onKeyUp(keyCode, event)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun toggleFullPreview() {
        setFullPreview(!fullWindows)
    }

    private fun enterFullPreview() {
        setFullPreview(true)
    }

    private fun exitFullPreview() {
        val needRefreshSeries = previewOrientationChanged
        setFullPreview(false)
        previewOrientationChanged = false
        if (needRefreshSeries) {
            refreshSeriesAfterFullPreview()
        } else {
            syncSeriesSelectionAfterFullPreview()
        }
    }

    private fun refreshSeriesAfterFullPreview() {
        if (seriesAdapter == null || vodInfo == null || vodInfo!!.seriesMap == null || TextUtils.isEmpty(vodInfo!!.playFlag)) return
        if (vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""] == null) return
        mGridView!!.post {
            mGridView!!.recycledViewPool.clear()
            mGridView!!.setAdapter(seriesAdapter)
            refreshList()
        }
    }

    private fun syncSeriesSelectionAfterFullPreview() {
        if (seriesAdapter == null || vodInfo == null || vodInfo!!.seriesMap == null || TextUtils.isEmpty(vodInfo!!.playFlag)) return
        val list = vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]
        if (list == null || list.isEmpty()) return
        if (vodInfo!!.playIndex >= list.size) {
            vodInfo!!.playIndex = list.size - 1
        }
        setSeriesGroupOptions()
        mGridView!!.post {
            val firstVisible = mGridView!!.firstVisiblePosition
            val lastVisible = mGridView!!.lastVisiblePosition
            if (vodInfo!!.playIndex >= 0 && (vodInfo!!.playIndex < firstVisible || vodInfo!!.playIndex > lastVisible)) {
                customSeriesScrollPos(vodInfo!!.playIndex)
            }
        }
        mSeriesGroupView!!.post {
            if (mSeriesGroupView!!.visibility == View.VISIBLE) {
                mSeriesGroupView!!.scrollToPosition(selectedSeriesGroupPosition)
            }
        }
    }

    private fun setFullPreview(full: Boolean) {
        if (windowsPreview == null) {
            windowsPreview = llPlayerFragmentContainer!!.layoutParams
        }
        if (windowsFull == null) {
            windowsFull = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (full) {
            previewOrientation = resources.configuration.orientation
            previewOrientationChanged = false
        }
        fullWindows = full
        if (playFragment != null) {
            playFragment!!.setAutoSwitchLineEnabled(!fullWindows)
            playFragment!!.setPreviewMode(!fullWindows)
        }
        llPlayerFragmentContainer!!.visibility = if (fullWindows || showPreview) View.VISIBLE else View.GONE
        llPlayerFragmentContainer!!.layoutParams = if (fullWindows) windowsFull else windowsPreview
        setPreviewRoundClip(!fullWindows)
        llPlayerFragmentContainerBlock!!.visibility = if (!fullWindows && showPreview) View.VISIBLE else View.GONE
        mGridView!!.visibility = if (fullWindows) View.GONE else View.VISIBLE
        mGridViewFlag!!.visibility = if (fullWindows) View.GONE else View.VISIBLE
        if (fullWindows) {
            tvSeriesGroup!!.visibility = View.GONE
        } else {
            val list =
                if (vodInfo == null || vodInfo!!.seriesMap == null || TextUtils.isEmpty(vodInfo!!.playFlag)) null else vodInfo!!.seriesMap!![vodInfo!!.playFlag ?: ""]
            tvSeriesGroup!!.visibility = if (list != null && list.size > 1) View.VISIBLE else View.GONE
            seriesFlagAdapter!!.notifyDataSetChanged()
            if (showPreview) mGridView!!.requestFocus()
            else {
                if (playFragment != null) playFragment!!.pauseForHidden()
                mGridView!!.requestFocus()
            }
        }
        toggleSubtitleTextSize()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullWindows && newConfig.orientation != previewOrientation) {
            previewOrientation = newConfig.orientation
            previewOrientationChanged = true
        }
    }

    private fun ensurePlayFragment() {
        if (playFragment != null) return
        playFragment = PlayFragment()
        supportFragmentManager.beginTransaction().add(R.id.previewPlayer, playFragment!!).commitNowAllowingStateLoss()
        playFragment!!.setPreviewMode(!fullWindows)
    }

    private fun releasePlayFragment() {
        if (playFragment == null) return
        supportFragmentManager.beginTransaction().remove(playFragment!!).commitNowAllowingStateLoss()
        playFragment = null
    }

    private fun toggleSubtitleTextSize() {
        var subtitleTextSize = SubtitleHelper.getTextSize(this)
        if (!fullWindows) {
            subtitleTextSize = (subtitleTextSize * 0.6).toInt()
        }
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize))
    }

    private fun setTvPlayUrl(url: String?) {
        setTextShow(tvPlayUrl!!, "播放地址：", url)
    }

    companion object {
        private const val STATE_FULL_WINDOWS = "detail_full_windows"
        private const val DETAIL_FALLBACK_SEARCH_TAG = "detail_fallback_search"
        const val EXTRA_DETAIL_FALLBACK_CANDIDATES = "detailFallbackCandidates"
        private const val DETAIL_FALLBACK_MAX_SEARCH = 20
        private const val DETAIL_FALLBACK_SEARCH_TIMEOUT_MS = 8000L
        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L
    }
}
