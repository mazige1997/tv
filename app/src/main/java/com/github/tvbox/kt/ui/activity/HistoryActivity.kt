package com.github.tvbox.kt.ui.activity

import android.os.Bundle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.ui.compose.GridMediaItem
import com.github.tvbox.kt.ui.compose.GridMediaScreen
import com.github.tvbox.kt.ui.compose.TvTheme
import com.github.tvbox.kt.ui.dialog.ConfirmClearDialog
import com.github.tvbox.kt.util.HawkConfig
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** 历史记录页(含删除模式),UI 由 Jetpack Compose 承载。 */
class HistoryActivity : BaseActivity() {
    private val items = mutableStateListOf<GridMediaItem>()
    private val vodRecords = ArrayList<VodInfo>()
    private val delMode = mutableStateOf(false)

    override val layoutResID: Int
        get() = R.layout.activity_grid_compose

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun init() {
        EventBus.getDefault().register(this)
        delMode.value = HawkConfig.hotVodDelete
        loadData()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeView).setContent {
            TvTheme {
                GridMediaScreen(
                    title = "历史记录",
                    mediaItems = items,
                    delMode = delMode.value,
                    columnCount = if (isBaseOnWidth()) 5 else 6,
                    onToggleDelMode = ::toggleDelMode,
                    onClear = {
                        ConfirmClearDialog(mContext, "History", ::loadData).show()
                    },
                    onItemClick = ::onItemClick,
                    onItemLongClick = ::onItemLongClick,
                    onItemDelete = ::onItemDelete
                )
            }
        }
    }

    private fun loadData() {
        val all = RoomDataManger.getAllVodRecord(100)
        val newItems = ArrayList<GridMediaItem>()
        vodRecords.clear()
        for (v in all) {
            if (v.playNote.isNotEmpty()) v.note = "上次看到" + v.playNote
            val sourceName = v.sourceKey?.let { ApiConfig.get().getSource(it)?.name } ?: "搜"
            newItems.add(GridMediaItem(v.name ?: "", v.pic, sourceName, v.note))
            vodRecords.add(v)
        }
        items.clear(); items.addAll(newItems)
    }

    private fun toggleDelMode() {
        HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete
        delMode.value = HawkConfig.hotVodDelete
    }

    private fun onItemDelete(position: Int) {
        if (position !in vodRecords.indices) return
        val record = vodRecords[position]
        val key = record.sourceKey
        if (key != null) RoomDataManger.deleteVodRecord(key, record)
        items.removeAt(position)
        vodRecords.removeAt(position)
    }

    private fun onItemClick(position: Int) {
        if (position !in vodRecords.indices) return
        val record = vodRecords[position]
        val bundle = Bundle()
        bundle.putString("id", record.id)
        bundle.putString("sourceKey", record.sourceKey)
        val sourceBean: SourceBean? = record.sourceKey?.let { ApiConfig.get().getSource(it) }
        if (sourceBean != null) {
            bundle.putString("title", record.name)
            bundle.putString("picture", record.pic)
            jumpActivity(DetailActivity::class.java, bundle)
        } else {
            bundle.putString("title", record.name)
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
                jumpActivity(FastSearchActivity::class.java, bundle)
            } else {
                jumpActivity(SearchActivity::class.java, bundle)
            }
        }
    }

    private fun onItemLongClick(position: Int) {
        if (position !in vodRecords.indices) return
        val bundle = Bundle()
        bundle.putString("title", vodRecords[position].name)
        if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            jumpActivity(FastSearchActivity::class.java, bundle)
        } else {
            jumpActivity(SearchActivity::class.java, bundle)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) {
            loadData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    override fun onBackPressed() {
        if (delMode.value) {
            toggleDelMode()
            return
        }
        super.onBackPressed()
    }
}
