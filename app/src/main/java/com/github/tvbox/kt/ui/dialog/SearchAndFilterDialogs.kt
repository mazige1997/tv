package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.adapter.GridFilterKVAdapter
import com.github.tvbox.kt.ui.adapter.QuickSearchAdapter
import com.github.tvbox.kt.ui.adapter.SearchWordAdapter
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList

/** 快速搜索对话框,Kotlin 移植(与旧版 QuickSearchDialog 一致)。 */
class QuickSearchDialog(context: Context) : BaseDialog(context, R.style.CustomDialogStyleDim) {
    private var searchWordAdapter: SearchWordAdapter? = null
    private var searchAdapter: QuickSearchAdapter? = null
    private var mGridView: TvRecyclerView? = null
    private var mGridViewWord: TvRecyclerView? = null

    init {
        setCanceledOnTouchOutside(false)
        setCancelable(true)
        setContentView(R.layout.dialog_quick_search)
        init(context)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_QUICK_SEARCH) {
            if (event.obj != null) {
                val data = event.obj as List<Movie.Video>
                searchAdapter?.addData(data)
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                val data = event.obj as List<String>
                searchWordAdapter?.setNewData(data)
            }
        }
    }

    private fun init(context: Context) {
        EventBus.getDefault().register(this)
        setOnDismissListener { EventBus.getDefault().unregister(this) }
        mGridView = findViewById(R.id.mGridView)
        searchAdapter = QuickSearchAdapter()
        mGridView!!.setHasFixedSize(true)
        // lite
        mGridView!!.layoutManager = V7LinearLayoutManager(context, 1, false)
        mGridView!!.setAdapter(searchAdapter)
        searchAdapter!!.setOnItemClickListener { adapter, _, position ->
            val video = searchAdapter!!.data[position]
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_SELECT, video))
            dismiss()
        }
        searchAdapter!!.setNewData(ArrayList())
        searchWordAdapter = SearchWordAdapter()
        mGridViewWord = findViewById(R.id.mGridViewWord)
        mGridViewWord!!.setAdapter(searchWordAdapter)
        mGridViewWord!!.layoutManager = V7LinearLayoutManager(context, 0, false)
        searchWordAdapter!!.setOnItemClickListener { adapter, _, position ->
            searchAdapter!!.data.clear()
            searchAdapter!!.notifyDataSetChanged()
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE, searchWordAdapter!!.data[position]))
        }
        searchWordAdapter!!.setNewData(ArrayList())
    }
}

/** 筛选对话框,Kotlin 移植(与旧版 GridFilterDialog 一致)。 */
class GridFilterDialog(context: Context) : BaseDialog(context) {
    lateinit var filterRoot: LinearLayout
    private var selectChange = false

    interface Callback {
        fun change()
    }

    init {
        setCanceledOnTouchOutside(false)
        setCancelable(true)
        setContentView(R.layout.dialog_grid_filter)
        filterRoot = findViewById(R.id.filterRoot)
        bindOutsideTouchDismiss()
    }

    fun setOnDismiss(callback: Callback) {
        setOnDismissListener {
            if (selectChange) {
                callback.change()
            }
        }
    }

    fun setData(sortData: MovieSort.SortData) {
        val filters = sortData.filters
        for (filter in filters) {
            val line = LayoutInflater.from(context).inflate(R.layout.item_grid_filter, null)
            (line.findViewById<View>(R.id.filterName) as TextView).text = filter.name
            val gridView = line.findViewById<TvRecyclerView>(R.id.mFilterKv)
            gridView.setHasFixedSize(true)
            gridView.layoutManager = V7LinearLayoutManager(context, 0, false)
            val filterKVAdapter = GridFilterKVAdapter()
            gridView.setAdapter(filterKVAdapter)
            val key = filter.key!!
            val values = ArrayList(filter.values?.keys ?: ArrayList())
            val keys = ArrayList(filter.values?.values ?: ArrayList())
            filterKVAdapter.setOnItemClickListener(object : BaseQuickAdapter.OnItemClickListener {
                var pre: View? = null

                override fun onItemClick(adapter: BaseQuickAdapter<*, *>, view: View, position: Int) {
                    selectChange = true
                    val filterSelect = sortData.filterSelect[key]
                    if (filterSelect == null || filterSelect != keys[position]) {
                        sortData.filterSelect[key] = keys[position]
                        pre?.let { p ->
                            val `val` = p.findViewById<TextView>(R.id.filterValue)
                            `val`.paint.isFakeBoldText = false
                            `val`.setTextColor(context.resources.getColor(R.color.color_FFFFFF))
                        }
                        val `val` = view.findViewById<TextView>(R.id.filterValue)
                        `val`.paint.isFakeBoldText = true
                        `val`.setTextColor(context.resources.getColor(R.color.color_02F8E1))
                        pre = view
                    } else {
                        sortData.filterSelect.remove(key)
                        pre?.let { p ->
                            val `val` = p.findViewById<TextView>(R.id.filterValue)
                            `val`.paint.isFakeBoldText = false
                            `val`.setTextColor(context.resources.getColor(R.color.color_FFFFFF))
                        }
                        pre = null
                    }
                }
            })
            filterKVAdapter.setNewData(values)
            filterRoot.addView(line)
        }
    }

    override fun show() {
        selectChange = false
        super.show()
        val layoutParams = window!!.attributes
        layoutParams.gravity = Gravity.BOTTOM
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.dimAmount = 0f
        window!!.decorView.setPadding(0, 0, 0, 0)
        window!!.attributes = layoutParams
    }

    private fun bindOutsideTouchDismiss() {
        val rootView = findViewById<View>(R.id.root)
        rootView.setOnTouchListener { v, event ->
            if (isTouchInsideFilter(event)) {
                return@setOnTouchListener false
            }
            if (event.action == MotionEvent.ACTION_UP) {
                dismiss()
                return@setOnTouchListener true
            }
            event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_MOVE ||
                event.action == MotionEvent.ACTION_CANCEL
        }
    }

    private fun isTouchInsideFilter(event: MotionEvent): Boolean {
        return event.x >= filterRoot.left &&
            event.x <= filterRoot.right &&
            event.y >= filterRoot.top &&
            event.y <= filterRoot.bottom
    }
}

/** 选集对话框,Kotlin 移植(与旧版 EpisodeDialog 一致)。 */
class EpisodeDialog(
    context: Context,
    title: String,
    episodes: List<VodInfo.VodSeries>?,
    selectedPosition: Int,
    private val listener: EpisodeSelectListener?
) : BaseDialog(context) {

    interface EpisodeSelectListener {
        fun selectEpisode(position: Int)
    }

    private val episodes: List<VodInfo.VodSeries>
    private val selectedPosition: Int

    init {
        if (context is Activity) setOwnerActivity(context)
        this.episodes = if (episodes == null) ArrayList() else ArrayList(episodes)
        this.selectedPosition = 0.coerceAtLeast(selectedPosition.coerceAtMost(0.coerceAtLeast(this.episodes.size - 1)))
        setContentView(R.layout.dialog_episode)
        init0(title)
    }

    private fun init0(title: String) {
        val episodeTitle = findViewById<TextView>(R.id.episode_title)
        episodeTitle.text = title
        findViewById<View>(R.id.episode_dismiss).setOnClickListener { dismiss() }

        val episodeList = findViewById<TvRecyclerView>(R.id.episode_list)
        val adapter = EpisodeAdapter(selectedPosition)
        episodeList.setHasFixedSize(true)
        val layoutManager = V7GridLayoutManager(context, 1)
        episodeList.layoutManager = layoutManager
        episodeList.setAdapter(adapter)
        adapter.setNewData(episodes)
        val initialGridWidth = context.resources.displayMetrics.widthPixels -
            context.resources.getDimensionPixelSize(R.dimen.vs_30) * 2
        if (initialGridWidth > 0) {
            layoutManager.spanCount = getSpanCount(initialGridWidth)
            scrollToSelectedPosition(layoutManager)
            episodeList.setSelectedPosition(selectedPosition)
        }
        adapter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            dismiss()
            listener?.selectEpisode(position)
        }
        episodeList.post(object : Runnable {
            override fun run() {
                if (episodeList.width <= 0) {
                    episodeList.postDelayed(this, 50)
                    return
                }
                val spanCount = getSpanCount(episodeList.width)
                if (layoutManager.spanCount != spanCount) {
                    layoutManager.spanCount = spanCount
                    scrollToSelectedPosition(layoutManager)
                }
                episodeList.setSelectedPosition(selectedPosition)
            }
        })
    }

    private fun getSpanCount(gridWidth: Int): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = context.resources.getDimension(R.dimen.ts_20)
        val bounds = Rect()
        var maxTextWidth = 1
        for (episode in episodes) {
            val name = episode?.name ?: ""
            paint.getTextBounds(name, 0, name.length, bounds)
            maxTextWidth = maxOf(maxTextWidth, bounds.width())
        }
        val itemPadding = context.resources.getDimensionPixelSize(R.dimen.vs_10) * 2
        val itemMargin = context.resources.getDimensionPixelSize(R.dimen.vs_5) * 2
        val itemWidth = maxTextWidth + itemPadding + itemMargin
        return maxOf(1, minOf(4, gridWidth / maxOf(1, itemWidth)))
    }

    override fun show() {
        super.show()
        if (!isShowing) return
        val window: Window? = window
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun scrollToSelectedPosition(layoutManager: V7GridLayoutManager) {
        // 当前集上方预留两排,避免贴在顶部时遥控上键找不到可移动的焦点项
        val rowsBeforeSelected = 2
        val startPosition = maxOf(0, selectedPosition - layoutManager.spanCount * rowsBeforeSelected)
        layoutManager.scrollToPositionWithOffset(startPosition, 0)
    }

    private class EpisodeAdapter(private val selectedPosition: Int) :
        BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder>(R.layout.item_series, ArrayList()) {

        override fun convert(helper: BaseViewHolder, item: VodInfo.VodSeries) {
            val series = helper.getView<TextView>(R.id.tvSeries)
            series.text = item?.name ?: ""
            series.setTextColor(
                if (helper.layoutPosition == selectedPosition)
                    mContext.resources.getColor(R.color.color_02F8E1)
                else Color.WHITE
            )
        }
    }
}
