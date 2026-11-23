package com.github.tvbox.kt.ui.activity

import android.os.Bundle
import android.view.View
import android.view.animation.BounceInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.adapter.HistoryAdapter
import com.github.tvbox.kt.ui.dialog.ConfirmClearDialog
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.HawkConfig
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 * 历史记录页(含删除模式),Kotlin 移植(与旧版 HistoryActivity 一致)。
 */
class HistoryActivity : BaseActivity() {
    private var tvDelete: ImageView? = null
    private var tvClear: ImageView? = null
    private var tvDelTip: TextView? = null
    private var mGridView: TvRecyclerView? = null
    private var delMode = false

    override val layoutResID: Int
        get() = R.layout.activity_history

    override fun shouldRefreshAutoSize(): Boolean {
        return true
    }

    override fun init() {
        initView()
        initData()
    }

    private fun toggleDelMode() {
        HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete
        historyAdapter?.notifyDataSetChanged()
        delMode = !delMode
        tvDelTip?.visibility = if (delMode) View.VISIBLE else View.GONE
    }

    private fun initView() {
        EventBus.getDefault().register(this)
        tvDelete = findViewById(R.id.tvDelete)
        tvClear = findViewById(R.id.tvClear)
        tvDelTip = findViewById(R.id.tvDelTip)
        mGridView = findViewById(R.id.mGridView)
        mGridView!!.setHasFixedSize(true)
        mGridView!!.layoutManager = V7GridLayoutManager(mContext, if (isBaseOnWidth()) 5 else 6)
        historyAdapter = HistoryAdapter()
        mGridView!!.setAdapter(historyAdapter)
        tvDelete?.setOnClickListener { _ ->
            toggleDelMode()
        }
        tvClear?.setOnClickListener { _ ->
            val dialog = ConfirmClearDialog(mContext!!, "History")
            dialog.show()
        }
        mGridView!!.setOnInBorderKeyEventListener { direction, _ ->
            if (direction == View.FOCUS_UP) {
                tvDelete?.isFocusable = true
                tvClear?.isFocusable = true
                tvDelete?.requestFocus()
            }
            false
        }
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
        historyAdapter?.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            if (position == -1) return@setOnItemClickListener
            val vodInfo: VodInfo? = historyAdapter!!.data[position]

            if (vodInfo != null) {
                if (delMode) {
                    historyAdapter!!.remove(position)
                    RoomDataManger.deleteVodRecord(vodInfo.sourceKey!!, vodInfo)
                } else {
                    val bundle = Bundle()
                    bundle.putString("id", vodInfo.id)
                    bundle.putString("sourceKey", vodInfo.sourceKey)
                    val sourceBean: SourceBean? = ApiConfig.get().getSource(vodInfo.sourceKey)
                    if (sourceBean != null) {
                        bundle.putString("title", vodInfo.name)
                        bundle.putString("picture", vodInfo.pic)
                        jumpActivity(DetailActivity::class.java, bundle)
                    } else {
                        bundle.putString("title", vodInfo.name)
                        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                            jumpActivity(FastSearchActivity::class.java, bundle)
                        } else {
                            jumpActivity(SearchActivity::class.java, bundle)
                        }
                    }
                }
            }
        }
        historyAdapter?.setOnItemLongClickListener { _, view, position ->
            val vodInfo: VodInfo? = historyAdapter!!.data[position]
            if (vodInfo != null) {
                val bundle = Bundle()
                bundle.putString("title", vodInfo.name)
                if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                    jumpActivity(FastSearchActivity::class.java, bundle)
                } else {
                    jumpActivity(SearchActivity::class.java, bundle)
                }
            }
            true
        }
    }

    private fun initData() {
        val allVodRecord = RoomDataManger.getAllVodRecord(100)
        val vodInfoList = ArrayList<VodInfo>()
        for (vodInfo in allVodRecord) {
            if (vodInfo.playNote.isNotEmpty()) vodInfo.note = "上次看到" + vodInfo.playNote
            vodInfoList.add(vodInfo)
        }
        historyAdapter?.setNewData(vodInfoList)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) {
            initData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        // P2-1:解除静态 adapter 对 Activity 的持有(点击 lambda 捕获了本 Activity);
        // 仅当静态引用仍指向本实例绑定的 adapter 时置空,避免误杀 Activity 重建后的新实例
        if (historyAdapter != null && historyAdapter === mGridView?.adapter) {
            historyAdapter = null
        }
    }

    override fun onBackPressed() {
        if (delMode) {
            toggleDelMode()
            return
        }
        super.onBackPressed()
    }

    companion object {
        @JvmField
        var historyAdapter: HistoryAdapter? = null
    }
}
