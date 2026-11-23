package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.bean.DanmuSearchResult
import com.github.tvbox.kt.ui.adapter.SearchDanmuAdapter
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import java.util.ArrayList

/** 弹幕搜索对话框,Kotlin 移植(与旧版 SearchDanmuDialog 一致)。 */
class SearchDanmuDialog(context: Context) : BaseDialog(context) {
    private var gridView: TvRecyclerView? = null
    private var searchAdapter: SearchDanmuAdapter? = null
    private var searchInput: EditText? = null
    private var loadingBar: ProgressBar? = null
    private var danmuLoader: DanmuLoader? = null
    private var episode = ""

    interface DanmuLoader {
        fun loadDanmu(danmu: String)
    }

    init {
        if (context is Activity) {
            setOwnerActivity(context)
        }
        setContentView(R.layout.dialog_search_danmu)
        initView()
    }

    private fun initView() {
        loadingBar = findViewById(R.id.loadingBar)
        gridView = findViewById(R.id.mGridView)
        searchInput = findViewById(R.id.input)
        val searchButton = findViewById<TextView>(R.id.inputSubmit)
        searchAdapter = SearchDanmuAdapter()
        gridView!!.setHasFixedSize(true)
        gridView!!.layoutManager = V7LinearLayoutManager(context, 1, false)
        gridView!!.setAdapter(searchAdapter)
        searchAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            loadDanmu(searchAdapter!!.data[position])
        }
        searchAdapter!!.setOnItemChildClickListener { adapter, view, position ->
            if (view.id != R.id.danmuItem) return@setOnItemChildClickListener
            FastClickCheckUtil.check(view)
            loadDanmu(searchAdapter!!.data[position])
        }
        searchButton.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            search(searchInput!!.text.toString().trim())
        }
        searchAdapter!!.setNewData(ArrayList())
    }

    fun setEpisode(episode: String?) {
        this.episode = episode ?: ""
    }

    fun setSearchWord(word: String?) {
        val searchWord = word?.trim() ?: ""
        searchInput!!.setText(searchWord)
        searchInput!!.setSelection(searchWord.length)
        searchInput!!.requestFocus()
        search(searchWord)
    }

    fun setDanmuLoader(danmuLoader: DanmuLoader?) {
        this.danmuLoader = danmuLoader
    }

    private fun search(word: String) {
        searchAdapter!!.setNewData(ArrayList())
        if (TextUtils.isEmpty(word)) {
            Toast.makeText(context, "输入内容不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        showLoading()
        DanmakuApi.searchList(word, episode, object : DanmakuApi.SearchListCallback {
            override fun onSuccess(results: List<DanmuSearchResult>) {
                showResults(results)
            }

            override fun onError(message: String) {
                showResults(ArrayList())
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadDanmu(result: DanmuSearchResult) {
        showLoading()
        DanmakuApi.loadSearchResult(result, object : DanmakuApi.SearchResultCallback {
            override fun onSuccess(danmu: String) {
                danmuLoader?.loadDanmu(danmu)
                dismiss()
            }

            override fun onError(message: String) {
                loadingBar?.visibility = View.GONE
                gridView?.visibility = View.VISIBLE
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showLoading() {
        loadingBar?.visibility = View.VISIBLE
        gridView?.visibility = View.GONE
    }

    private fun showResults(results0: List<DanmuSearchResult>?) {
        var results = results0
        if (results == null) results = ArrayList()
        loadingBar?.visibility = View.GONE
        gridView?.visibility = View.VISIBLE
        searchAdapter!!.setNewData(results)
        if (results.isEmpty()) {
            Toast.makeText(context, "未查询到匹配弹幕", Toast.LENGTH_SHORT).show()
            return
        }
        gridView!!.requestFocus()
    }

    override fun onBackPressed() {
        DanmakuApi.cancel()
        dismiss()
    }
}
