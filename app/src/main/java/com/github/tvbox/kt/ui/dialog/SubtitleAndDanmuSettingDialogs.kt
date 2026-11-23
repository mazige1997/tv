package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.recyclerview.widget.DiffUtil
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.bean.DanmuSearchResult
import com.github.tvbox.kt.bean.Subtitle
import com.github.tvbox.kt.bean.SubtitleData
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.adapter.ButtonAdapter
import com.github.tvbox.kt.ui.adapter.SearchDanmuAdapter
import com.github.tvbox.kt.ui.adapter.SearchSubtitleAdapter
import com.github.tvbox.kt.util.DanmuHelper
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.viewmodel.SubtitleViewModel
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

/** 在线字幕搜索对话框,Kotlin 移植(与旧版 SearchSubtitleDialog 一致)。 */
class SearchSubtitleDialog(context: Context) : BaseDialog(context) {
    private var mContext: Context? = null
    private var mGridView: TvRecyclerView? = null
    private var searchAdapter: SearchSubtitleAdapter? = null

    private var subtitleSearchBtn: TextView? = null
    private var subtitleSearchEt: EditText? = null
    private var mSubtitleLoader: SubtitleLoader? = null
    private var loadingBar: ProgressBar? = null
    private var subtitleViewModel: SubtitleViewModel? = null
    private var page = 1
    private val maxPage = 5
    private var searchWord = ""

    private var zipSubtitles = ArrayList<Subtitle>()
    private var isSearchPag = true

    interface SubtitleLoader {
        fun loadSubtitle(subtitle: Subtitle?)
    }

    init {
        mContext = context
        if (context is Activity) {
            setOwnerActivity(context)
        }
        setContentView(R.layout.dialog_search_subtitle)
        initView(context)
        initViewModel()
    }

    protected fun initView(context: Context) {
        loadingBar = findViewById(R.id.loadingBar)
        mGridView = findViewById(R.id.mGridView)
        subtitleSearchEt = findViewById(R.id.input)
        subtitleSearchBtn = findViewById(R.id.inputSubmit)
        searchAdapter = SearchSubtitleAdapter()
        mGridView!!.setHasFixedSize(true)
        mGridView!!.layoutManager = V7LinearLayoutManager(getContext(), 1, false)
        mGridView!!.setAdapter(searchAdapter)
        searchAdapter!!.setOnItemClickListener { adapter, view, position ->
            FastClickCheckUtil.check(view)
            val subtitle = searchAdapter!!.data[position]
            // 加载字幕
            if (mSubtitleLoader != null) {
                if (subtitle.getIsZip()) {
                    isSearchPag = false
                    loadingBar?.visibility = View.VISIBLE
                    mGridView?.visibility = View.GONE
                    subtitleViewModel!!.getSearchResultSubtitleUrls(subtitle)
                } else {
                    loadSubtitle(subtitle)
                    dismiss()
                }
            }
        }

        searchAdapter!!.setOnLoadMoreListener({
            if (searchAdapter!!.data[0].getIsZip()) {
                subtitleViewModel!!.searchResult(searchWord, page)
            }
        }, mGridView)

        subtitleSearchBtn!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val wd = subtitleSearchEt!!.text.toString().trim()
            search(wd)
        }
        searchAdapter!!.setNewData(ArrayList())
    }

    fun setSearchWord(wd0: String) {
        var wd = wd0.replace("(?:（|\\(|\\[|【|\\.mp4|\\.mkv|\\.avi|\\.MP4|\\.MKV|\\.AVI)".toRegex(), "")
        wd = wd.replace("(?:：|\\:|）|\\)|\\]|】|\\.)".toRegex(), " ")
        val len = wd.length
        val finalLen = if (len >= 36) 36 else len
        wd = wd.substring(0, finalLen).trim()
        subtitleSearchEt!!.setText(wd)
        subtitleSearchEt!!.setSelection(wd.length)
        subtitleSearchEt!!.requestFocus()
        search(wd)
    }

    fun search(wd: String) {
        isSearchPag = true
        searchAdapter!!.setNewData(ArrayList())
        if (!TextUtils.isEmpty(wd)) {
            loadingBar?.visibility = View.VISIBLE
            mGridView?.visibility = View.GONE
            searchWord = wd
            page = 1
            subtitleViewModel!!.searchResult(wd, page)
        } else {
            Toast.makeText(context, "输入内容不能为空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initViewModel() {
        subtitleViewModel = ViewModelProvider(mContext as ViewModelStoreOwner).get(SubtitleViewModel::class.java)
        subtitleViewModel!!.searchResultData.observe(mContext as LifecycleOwner, Observer { subtitleData ->
            val data = subtitleData?.getSubtitleList()
            loadingBar?.visibility = View.GONE
            mGridView?.visibility = View.VISIBLE
            if (data == null) {
                mGridView?.post {
                    Toast.makeText(context, "未查询到匹配字幕", Toast.LENGTH_SHORT).show()
                }
                return@Observer
            }

            if (data.isNotEmpty()) {
                mGridView!!.requestFocus()
                if (subtitleData.getIsZip() == true) {
                    if (subtitleData.getIsNew() == true) {
                        searchAdapter!!.setNewData(data)
                        zipSubtitles = ArrayList(data)
                    } else {
                        searchAdapter!!.addData(data)
                        zipSubtitles.addAll(data)
                    }
                    page++
                    if (page > maxPage) {
                        searchAdapter!!.loadMoreEnd()
                        searchAdapter!!.setEnableLoadMore(false)
                    } else {
                        searchAdapter!!.loadMoreComplete()
                        searchAdapter!!.setEnableLoadMore(true)
                    }
                } else {
                    searchAdapter!!.loadMoreComplete()
                    searchAdapter!!.setNewData(data)
                    searchAdapter!!.setEnableLoadMore(false)
                }
            } else {
                if (page > maxPage) {
                    searchAdapter!!.loadMoreEnd()
                } else {
                    searchAdapter!!.loadMoreComplete()
                }
                searchAdapter!!.setEnableLoadMore(false)
            }
        })
    }

    private fun loadSubtitle(subtitle: Subtitle) {
        subtitleViewModel!!.getSubtitleUrl(subtitle, mSubtitleLoader!!)
    }

    fun setSubtitleLoader(subtitleLoader: SubtitleLoader?) {
        mSubtitleLoader = subtitleLoader
    }

    override fun onBackPressed() {
        if (!isSearchPag) {
            isSearchPag = true
            loadingBar?.visibility = View.GONE
            mGridView?.visibility = View.VISIBLE
            searchAdapter!!.setNewData(zipSubtitles)
            searchAdapter!!.setEnableLoadMore(page < maxPage)
            return
        }
        dismiss()
    }
}

/** 弹幕设置对话框,Kotlin 移植(与旧版 DanmuSettingDialog 一致)。 */
class DanmuSettingDialog(context: Context) : BaseDialog(context) {
    private var danmuSearchListener: DanmuSearchListener? = null

    interface DanmuSearchListener {
        fun openSearchDanmuDialog()
    }

    init {
        setContentView(R.layout.dialog_danmu_setting)
        initDanmuSearch()
        initColor()
        initSpeed()
        initSize()
        initLine()
        initAlpha()
    }

    private fun initDanmuSearch() {
        val danmuSearch = findViewById<TextView>(R.id.danmuSearch)
        danmuSearch.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            if (danmuSearchListener == null) return@setOnClickListener
            dismiss()
            danmuSearchListener!!.openSearchDanmuDialog()
        }
        danmuSearch.post { danmuSearch.requestFocus() }
    }

    private fun initColor() {
        val data = Arrays.asList(false, true)
        setButtonAdapter(
            R.id.trv_color, data, if (DanmuHelper.useRandomColor()) 1 else 0,
            object : ButtonAdapter.SelectDialogInterface<Boolean> {
                override fun click(value: Boolean, pos: Int) {
                    DanmuHelper.setRandomColor(value)
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, true))
                }

                override fun getDisplay(`val`: Boolean): String {
                    return if (`val`) "随机" else "默认"
                }
            }
        )
    }

    private fun initSpeed() {
        val speeds = Arrays.asList(2.4f, 1.8f, 1.5f, 1.0f)
        val defaultPos = maxOf(0, speeds.indexOf(DanmuHelper.getSpeed()))
        setButtonAdapter(R.id.speed, speeds, defaultPos, object : ButtonAdapter.SelectDialogInterface<Float> {
            override fun click(value: Float, pos: Int) {
                DanmuHelper.setSpeed(value)
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
            }

            override fun getDisplay(`val`: Float): String {
                val idx = speeds.indexOf(`val`)
                return if (idx < 0) "适中" else arrayOf("超慢", "慢", "适中", "快")[idx]
            }
        })
    }

    private fun initSize() {
        val sizeText = findViewById<TextView>(R.id.size)
        val sizeSub = findViewById<TextView>(R.id.sizeSub)
        val sizeAdd = findViewById<TextView>(R.id.sizeAdd)
        val size = AtomicInteger(Math.round(DanmuHelper.getSizeScale() * 10))
        renderSize(sizeText, size.get())
        sizeSub.setOnClickListener { v ->
            if (size.get() <= 6) return@setOnClickListener
            size.decrementAndGet()
            DanmuHelper.setSizeScale(size.get() / 10f)
            renderSize(sizeText, size.get())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
        }
        sizeAdd.setOnClickListener { v ->
            if (size.get() >= 20) return@setOnClickListener
            size.incrementAndGet()
            DanmuHelper.setSizeScale(size.get() / 10f)
            renderSize(sizeText, size.get())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
        }
    }

    private fun initLine() {
        val lineText = findViewById<TextView>(R.id.line)
        val lineSub = findViewById<TextView>(R.id.lineSub)
        val lineAdd = findViewById<TextView>(R.id.lineAdd)
        val line = AtomicInteger(DanmuHelper.getMaxLine())
        renderLine(lineText, line.get())
        lineSub.setOnClickListener { v ->
            if (line.get() <= 1) return@setOnClickListener
            line.decrementAndGet()
            DanmuHelper.setMaxLine(line.get())
            renderLine(lineText, line.get())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
        }
        lineAdd.setOnClickListener { v ->
            if (line.get() >= 15) return@setOnClickListener
            line.incrementAndGet()
            DanmuHelper.setMaxLine(line.get())
            renderLine(lineText, line.get())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
        }
    }

    private fun initAlpha() {
        val alphaText = findViewById<TextView>(R.id.alpha)
        val alphaSub = findViewById<TextView>(R.id.alphaSub)
        val alphaAdd = findViewById<TextView>(R.id.alphaAdd)
        val alpha = AtomicInteger(Math.round(DanmuHelper.getAlpha() * 100))
        renderAlpha(alphaText, alpha.get())
        alphaSub.setOnClickListener { v ->
            if (alpha.get() <= 10) return@setOnClickListener
            alpha.addAndGet(-10)
            DanmuHelper.setAlpha(alpha.get() / 100f)
            renderAlpha(alphaText, alpha.get())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
        }
        alphaAdd.setOnClickListener { v ->
            if (alpha.get() >= 100) return@setOnClickListener
            alpha.addAndGet(10)
            DanmuHelper.setAlpha(alpha.get() / 100f)
            renderAlpha(alphaText, alpha.get())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, false))
        }
    }

    private fun renderSize(view: TextView, value: Int) {
        view.text = "$value" + "档"
    }

    private fun renderLine(view: TextView, value: Int) {
        view.text = "$value" + "行"
    }

    private fun renderAlpha(view: TextView, value: Int) {
        view.text = "$value%"
    }

    private fun <T : Any> setButtonAdapter(rid: Int, data: List<T>, select: Int, dialogInterface: ButtonAdapter.SelectDialogInterface<T>) {
        val adapter = ButtonAdapter(dialogInterface, object : DiffUtil.ItemCallback<T>() {
            override fun areItemsTheSame(oldItem: T, newItem: T): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
                return oldItem == newItem
            }
        })
        adapter.setData(data, select)
        val tvRecyclerView = findViewById<TvRecyclerView>(rid)
        tvRecyclerView.layoutManager = V7LinearLayoutManager(context, 0, false)
        tvRecyclerView.setAdapter(adapter)
        tvRecyclerView.setSelectedPosition(select)
    }

    fun setDanmuSearchListener(danmuSearchListener: DanmuSearchListener?) {
        this.danmuSearchListener = danmuSearchListener
    }
}
