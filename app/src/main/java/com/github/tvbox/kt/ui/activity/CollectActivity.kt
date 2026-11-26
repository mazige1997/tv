package com.github.tvbox.kt.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.cache.VodCollect
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

/** 收藏列表页(含删除模式),UI 由 Jetpack Compose 承载。 */
class CollectActivity : BaseActivity() {
    private val items = mutableStateListOf<GridMediaItem>()
    private val sourceKeys = mutableStateListOf<String?>()
    private val vodIds = mutableStateListOf<String?>()
    private val dbIds = mutableStateListOf<Int>()
    private val delMode = mutableStateOf(false)

    override val layoutResID: Int
        get() = R.layout.activity_grid_compose

    override fun init() {
        EventBus.getDefault().register(this)
        delMode.value = HawkConfig.hotVodDelete
        loadData()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeView).setContent {
            TvTheme {
                GridMediaScreen(
                    title = "收藏夹",
                    mediaItems = items,
                    delMode = delMode.value,
                    columnCount = if (isBaseOnWidth()) 5 else 6,
                    onToggleDelMode = ::toggleDelMode,
                    onClear = {
                        ConfirmClearDialog(mContext, "Collect", ::loadData).show()
                    },
                    onItemClick = ::onItemClick,
                    onItemLongClick = ::onItemLongClick,
                    onItemDelete = ::onItemDelete
                )
            }
        }
    }

    private fun loadData() {
        val all = RoomDataManger.getAllVodCollect()
        val newItems = ArrayList<GridMediaItem>()
        val newKeys = ArrayList<String?>()
        val newVodIds = ArrayList<String?>()
        val newDbIds = ArrayList<Int>()
        for (v in all) {
            val sourceName = v.sourceKey?.let { ApiConfig.get().getSource(it)?.name } ?: ""
            newItems.add(GridMediaItem(v.name ?: "", v.pic, sourceName))
            newKeys.add(v.sourceKey)
            newVodIds.add(v.vodId)
            newDbIds.add(v.id)
        }
        items.clear(); items.addAll(newItems)
        sourceKeys.clear(); sourceKeys.addAll(newKeys)
        vodIds.clear(); vodIds.addAll(newVodIds)
        dbIds.clear(); dbIds.addAll(newDbIds)
    }

    private fun toggleDelMode() {
        HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete
        delMode.value = HawkConfig.hotVodDelete
    }

    private fun onItemDelete(position: Int) {
        if (position !in dbIds.indices) return
        val dbId = dbIds[position]
        RoomDataManger.deleteVodCollect(dbId)
        items.removeAt(position)
        sourceKeys.removeAt(position)
        vodIds.removeAt(position)
        dbIds.removeAt(position)
    }

    private fun onItemClick(position: Int) {
        if (position !in dbIds.indices) return
        val key = sourceKeys[position]
        if (key != null && ApiConfig.get().getSource(key) != null) {
            val bundle = Bundle()
            bundle.putString("id", vodIds[position])
            bundle.putString("sourceKey", key)
            bundle.putString("title", items[position].name)
            bundle.putString("picture", items[position].pic)
            bundle.putBoolean("collect", true)
            jumpActivity(DetailActivity::class.java, bundle)
        } else {
            val intent = Intent(mContext, SearchActivity::class.java)
            intent.putExtra("title", items[position].name)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
    }

    private fun onItemLongClick(position: Int) {
        if (position !in items.indices) return
        val bundle = Bundle()
        bundle.putString("title", items[position].name)
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
