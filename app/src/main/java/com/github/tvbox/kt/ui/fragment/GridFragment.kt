package com.github.tvbox.kt.ui.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.kt.R
import com.github.tvbox.kt.base.BaseLazyFragment
import com.github.tvbox.kt.bean.AbsXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.activity.DetailActivity
import com.github.tvbox.kt.ui.activity.FastSearchActivity
import com.github.tvbox.kt.ui.activity.SearchActivity
import com.github.tvbox.kt.ui.adapter.GridAdapter
import com.github.tvbox.kt.ui.adapter.GridFilterKVAdapter
import com.github.tvbox.kt.ui.dialog.GridFilterDialog
import com.github.tvbox.kt.ui.tv.widget.LoadMoreView
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.ImgUtil
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.util.Stack

/**
 * 首页分类网格 Fragment(含文件夹模式/筛选/下拉刷新),Kotlin 移植(与旧版 GridFragment 一致)。
 */
class GridFragment : BaseLazyFragment() {
    private var sortData: MovieSort.SortData? = null
    private var mGridView: TvRecyclerView? = null
    private var sourceViewModel: SourceViewModel? = null
    private var gridFilterDialog: GridFilterDialog? = null
    private var gridAdapter: GridAdapter? = null
    private var page = 1
    private var maxPage = 1
    private var isLoad = false
    private var isRequesting = false
    private var hasActionItems = false
    private var isTop = true
    private var focusedView: View? = null
    private var pullRefreshStartX = 0f
    private var pullRefreshStartY = 0f
    private var pullRefreshStartAtTop = false
    private var pullRefreshReady = false
    private var pullRefreshThreshold = 0
    private var loadSirView: View? = null
    private var style: ImgUtil.Style? = null
    private val mGrids = Stack<GridInfo>() // ui 栈

    private class GridInfo {
        var sortID: String = ""
        var mGridView: TvRecyclerView? = null
        var gridAdapter: GridAdapter? = null
        var page = 1
        var maxPage = 1
        var isLoad = false
        var hasActionItems = false
        var focusedView: View? = null
    }

    fun setArguments(sortData: MovieSort.SortData?): GridFragment {
        this.sortData = sortData
        return this
    }

    override val layoutResID: Int
        get() = R.layout.fragment_grid

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val gridView = view.findViewById<TvRecyclerView>(R.id.mGridView)
        if (gridView != null && gridView.layoutManager == null) {
            gridView.layoutManager = V7LinearLayoutManager(mContext, 1, false)
        }
    }

    override fun init() {
        initView()
        initViewModel()
        initData()
    }

    private fun changeView(id: String, isFolder: Boolean) {
        sortData!!.flag = if (isFolder) {
            if (style == null) "1" else "2"
        } else {
            "2"
        } // 修改 sortData.flag
        initView()
        sortData!!.id = id // 修改 sortData.id 为新的 ID
        initViewModel()
        initData()
    }

    fun isFolederMode(): Boolean = getUITag() == '1'

    // 获取当前页面 UI 的显示模式 '0' 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    fun getUITag(): Char {
        return if (sortData == null || sortData!!.flag == null || sortData!!.flag!!.isEmpty() || style != null)
            '0'
        else
            sortData!!.flag!![0]
    }

    // 是否允许聚合搜索 sortData.flag 的第二个字符为 '1' 时允许聚搜
    fun enableFastSearch(): Boolean {
        return sortData!!.flag == null || sortData!!.flag!!.length < 2 || sortData!!.flag!![1] == '1'
    }

    // 保存当前页面
    private fun saveCurrentView() {
        if (mGridView == null) return
        val info = GridInfo()
        info.sortID = sortData!!.id ?: ""
        info.mGridView = mGridView
        info.gridAdapter = gridAdapter
        info.page = page
        info.maxPage = maxPage
        info.isLoad = isLoad
        info.hasActionItems = hasActionItems
        info.focusedView = focusedView
        mGrids.push(info)
    }

    // 丢弃当前页面,将页面还原成上一个保存的页面
    fun restoreView(): Boolean {
        if (mGrids.isEmpty()) return false
        showSuccess()
        (mGridView!!.parent as ViewGroup).removeView(mGridView) // 从父窗口移除当前控件
        val info = mGrids.pop() // 还原上次保存的控件
        sortData!!.id = info.sortID
        mGridView = info.mGridView
        gridAdapter = info.gridAdapter
        page = info.page
        maxPage = info.maxPage
        isLoad = info.isLoad
        hasActionItems = info.hasActionItems
        focusedView = info.focusedView
        mGridView!!.visibility = View.VISIBLE
        mGridView?.requestFocus()
        return true
    }

    // 更改当前页面
    private fun createView() {
        saveCurrentView() // 保存当前页面
        if (mGridView == null) { // 从 layout 中拿 view
            mGridView = findViewById(R.id.mGridView)
        } else { // 复制当前 view
            val v3 = TvRecyclerView(mContext)
            v3.setSpacingWithMargins(10, 10)
            v3.layoutParams = mGridView!!.layoutParams
            v3.setPadding(
                mGridView!!.paddingLeft, mGridView!!.paddingTop,
                mGridView!!.paddingRight, mGridView!!.paddingBottom
            )
            v3.clipToPadding = mGridView!!.clipToPadding
            (mGridView!!.parent as ViewGroup).addView(v3)
            mGridView!!.visibility = View.GONE
            mGridView = v3
            mGridView!!.visibility = View.VISIBLE
        }
        mGridView!!.setHasFixedSize(true)
        style = ImgUtil.initStyle()
        gridAdapter = GridAdapter(isFolederMode(), style)
        page = 1
        maxPage = 1
        isLoad = false
    }

    private fun initView() {
        createView()
        if (isFolederMode()) {
            mGridView!!.layoutManager = V7LinearLayoutManager(mContext, 1, false)
        } else {
            var spanCount = if (isBaseOnWidth()) 5 else 6
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style!!, spanCount)
            }
            if (spanCount == 1) {
                mGridView!!.layoutManager = V7LinearLayoutManager(mContext, spanCount, false)
            } else {
                mGridView!!.layoutManager = V7GridLayoutManager(mContext, spanCount)
            }
        }
        mGridView!!.setAdapter(gridAdapter)

        gridAdapter!!.setOnLoadMoreListener({
            gridAdapter!!.setEnableLoadMore(true)
            sourceViewModel!!.getList(sortData, page)
        }, mGridView)
        mGridView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                itemView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(300)?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                itemView?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(300)?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
            }
        })
        mGridView!!.setOnInBorderKeyEventListener { direction, _ -> false }
        gridAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = gridAdapter!!.data[position]
            if (video != null) {
                if (video.action != null) {
                    sourceViewModel!!.action(video.sourceKey ?: "", video.action)
                    return@setOnItemClickListener
                }
                val bundle = Bundle()
                bundle.putString("id", video.id)
                bundle.putString("sourceKey", video.sourceKey)
                bundle.putString("title", video.name)
                if (video.tag != null && (video.tag == "folder" || video.tag == "cover")) {
                    focusedView = view
                    if ("12".indexOf(getUITag()) != -1) {
                        changeView(video.id ?: "", video.tag == "folder")
                    } else {
                        changeView(video.id ?: "", false)
                    }
                } else {
                    if (video.id != null && video.id!!.startsWith("msearch:")) {
                        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true) && enableFastSearch()) {
                            jumpActivity(FastSearchActivity::class.java, bundle)
                        } else {
                            jumpActivity(SearchActivity::class.java, bundle)
                        }
                    } else {
                        bundle.putString("picture", video.pic)
                        jumpActivity(DetailActivity::class.java, bundle)
                    }
                }
            }
        }
        gridAdapter!!.setOnItemLongClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = gridAdapter!!.data[position]
            if (video != null) {
                val bundle = Bundle()
                bundle.putString("id", video.id)
                bundle.putString("sourceKey", video.sourceKey)
                bundle.putString("title", video.name)
                if (video.id != null && video.id!!.startsWith("msearch:")) {
                    bundle.putString("picture", video.pic)
                    jumpActivity(DetailActivity::class.java, bundle)
                } else {
                    jumpActivity(FastSearchActivity::class.java, bundle)
                }
            }
            true
        }
        gridAdapter!!.setLoadMoreView(LoadMoreView())
        setLoadSir2(mGridView!!)
        initPullRefresh()
    }

    private fun initPullRefresh() {
        pullRefreshThreshold = ViewConfiguration.get(mContext).scaledTouchSlop * 6
        mGridView!!.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                return handlePullRefreshTouch(rv, event)
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
        loadSirView = mGridView!!.parent as? View
        loadSirView?.setOnTouchListener { v, event ->
            handlePullRefreshTouch(v, event)
        }
    }

    private fun bindPullRefreshTouch(view: View?) {
        if (view == null || view === mGridView) return
        view.setOnTouchListener { v, event ->
            handlePullRefreshTouch(v, event)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                bindPullRefreshTouch(view.getChildAt(i))
            }
        }
    }

    private fun handlePullRefreshTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pullRefreshStartX = event.x
                pullRefreshStartY = event.y
                pullRefreshStartAtTop = !view.canScrollVertically(-1)
                pullRefreshReady = false
            }
            MotionEvent.ACTION_MOVE -> {
                val diffX = Math.abs(event.x - pullRefreshStartX)
                val diffY = event.y - pullRefreshStartY
                pullRefreshReady = pullRefreshStartAtTop && diffY > pullRefreshThreshold && diffY > diffX
            }
            MotionEvent.ACTION_UP -> {
                if (pullRefreshReady) {
                    pullRefreshReady = false
                    forceRefresh()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pullRefreshReady = false
            }
        }
        return false
    }

    override fun showEmpty() {
        super.showEmpty()
        bindPullRefreshTouch(loadSirView)
    }

    private fun initViewModel() {
        if (sourceViewModel != null) {
            return
        }
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel!!.listResult.observe(this) { absXml ->
            isRequesting = false
            if (absXml != null && absXml.movie != null && absXml.movie!!.videoList != null && absXml.movie!!.videoList!!.isNotEmpty()) {
                if (page == 1) {
                    showSuccess()
                    isLoad = true
                    hasActionItems = hasActionVideo(absXml.movie!!.videoList)
                    gridAdapter!!.setNewData(absXml.movie!!.videoList)
                    // TV 焦点死区恢复:全量刷新后若没有任何 item 持有焦点,把焦点落到第一个 item
                    mGridView?.post {
                        val gv = mGridView ?: return@post
                        if (gv.focusedChild == null && gv.childCount > 0) {
                            val first = gv.getChildAt(0)
                            if (first != null && first.isFocusable) first.requestFocus()
                        }
                    }
                } else {
                    hasActionItems = hasActionItems or hasActionVideo(absXml.movie!!.videoList)
                    gridAdapter!!.addData(absXml.movie!!.videoList!!)
                }
                page++
                maxPage = absXml.movie!!.pagecount
                if (maxPage > 0 && page > maxPage) {
                    gridAdapter!!.loadMoreEnd()
                    gridAdapter!!.setEnableLoadMore(false)
                    if (page > 2) Toast.makeText(context, "没有更多了", Toast.LENGTH_SHORT).show()
                } else {
                    gridAdapter!!.loadMoreComplete()
                    gridAdapter!!.setEnableLoadMore(true)
                }
            } else {
                if (page == 1) {
                    hasActionItems = false
                    showEmpty()
                } else if (page > 2) { // 只有一页数据时不提示
                    Toast.makeText(context, "没有更多了", Toast.LENGTH_SHORT).show()
                }
                gridAdapter!!.loadMoreEnd()
                gridAdapter!!.setEnableLoadMore(false)
            }
        }
        sourceViewModel!!.actionResult.observe(this) { jsonObject ->
            if (jsonObject == null) return@observe
            val msg = jsonObject.optString("msg")
            if (msg.isNotEmpty()) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                forceRefresh()
            }
        }
    }

    fun isLoaded(): Boolean {
        return isLoad || mGrids.isNotEmpty() // 如果有缓存页的话也可以认为是加载了数据的
    }

    fun shouldReloadOnSelect(): Boolean {
        return !isRequesting && mGrids.isEmpty() && (hasActionItems || !isLoad)
    }

    private fun initData() {
        showLoading()
        isRequesting = true
        isLoad = false
        hasActionItems = false
        scrollTop()
        toggleFilterColor()
        sourceViewModel!!.getList(sortData, page)
    }

    private fun hasActionVideo(videos: List<Movie.Video>?): Boolean {
        if (videos == null) return false
        for (video in videos) {
            if (video != null && video.action != null) return true
        }
        return false
    }

    private fun toggleFilterColor() {
        if (sortData != null && sortData!!.filters != null && sortData!!.filters!!.isNotEmpty()) {
            val count = sortData!!.filterSelectCount()
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count))
        }
    }

    fun isTop0(): Boolean {
        return isTop
    }

    fun scrollTop() {
        isTop = true
        if (mGridView == null) return
        mGridView!!.scrollToPosition(0)
    }

    fun showFilter() {
        if (sortData!!.filters!!.isNotEmpty() && gridFilterDialog == null) {
            gridFilterDialog = GridFilterDialog(mContext)
            setFilterDialogData()
        }
        gridFilterDialog?.show()
    }

    fun setFilterDialogData() {
        val context = context
        val inflater = LayoutInflater.from(context)
        val defaultColor = ContextCompat.getColor(context!!, R.color.color_FFFFFF)
        val selectedColor = ContextCompat.getColor(context, R.color.color_02F8E1)
        // 遍历过滤条件数据
        for (filter in sortData!!.filters!!) {
            val line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog!!.filterRoot, false)
            val filterNameTv = line.findViewById<TextView>(R.id.filterName)
            filterNameTv.text = filter.name
            val gridView = line.findViewById<TvRecyclerView>(R.id.mFilterKv)
            gridView.setHasFixedSize(true)
            gridView.layoutManager = V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            val adapter = GridFilterKVAdapter()
            gridView.setAdapter(adapter)
            val key = filter.key!!
            val values = ArrayList(filter.values?.keys ?: ArrayList())
            val keys = ArrayList(filter.values?.values ?: ArrayList())
            adapter.setOnItemClickListener(object : BaseQuickAdapter.OnItemClickListener {
                // 用于记录上一次选中的 view
                var previousSelectedView: View? = null

                override fun onItemClick(adapter0: BaseQuickAdapter<*, *>, view: View, position: Int) {
                    val currentSelection = sortData!!.filterSelect[key]
                    val newSelection = keys[position]
                    if (currentSelection == null || currentSelection != newSelection) {
                        // 更新选中状态
                        sortData!!.filterSelect[key] = newSelection
                        updateViewStyle(view, selectedColor, true)
                        previousSelectedView?.let { updateViewStyle(it, defaultColor, false) }
                        previousSelectedView = view
                    } else {
                        // 取消选中
                        sortData!!.filterSelect.remove(key)
                        previousSelectedView?.let { updateViewStyle(it, defaultColor, false) }
                        previousSelectedView = null
                    }
                    forceRefresh()
                }

                private fun updateViewStyle(view: View, color: Int, isBold: Boolean) {
                    val valueTv = view.findViewById<TextView>(R.id.filterValue)
                    valueTv.paint.isFakeBoldText = isBold
                    valueTv.setTextColor(color)
                }
            })
            adapter.setNewData(values)
            gridFilterDialog!!.filterRoot.addView(line)
        }
    }

    fun forceRefresh() {
        if (mGridView == null || gridAdapter == null || sourceViewModel == null) return
        if (isRequesting) return
        page = 1
        initData()
    }

    companion object {
        fun newInstance(sortData: MovieSort.SortData): GridFragment {
            return GridFragment().setArguments(sortData)
        }
    }
}
