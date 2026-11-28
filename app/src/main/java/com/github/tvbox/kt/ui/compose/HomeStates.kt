package com.github.tvbox.kt.ui.compose

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.util.ImgUtil
import com.github.tvbox.kt.util.HawkConfig
import com.orhanobut.hawk.Hawk

/**
 * 首页各分类页的状态容器(普通对象,由 Activity 持有)。
 * 目的:替代原 ViewPager + Fragment 的 hide/show 保活——Compose 切页只渲染当前页,
 * 但滚动位置/分页/文件夹栈都保存在这里,切走再切回不丢失。
 */
class HomeGridPageState(val sortData: MovieSort.SortData) {
    val videos = mutableStateListOf<Movie.Video>()
    val folderStack = ArrayDeque<HomeGridSnapshot>()
    var page = 1
    var maxPage = 1
    var isLoad = false
    var isRequesting by mutableStateOf(false)
    var hasActionItems = false
    var isFolderMode by mutableStateOf(false)
    var style by mutableStateOf<ImgUtil.Style?>(null)
    var focusIndex = 0
    var pendingScrollIndex: Int? = null
    val gridState = LazyGridState()

    fun uiTag(): Char {
        val flag = sortData.flag
        return if (flag.isNullOrEmpty() || style != null) '0' else flag[0]
    }

    fun enableFastSearch(): Boolean {
        val flag = sortData.flag
        return flag == null || flag.length < 2 || flag[1] == '1'
    }

    fun shouldReloadOnSelect(): Boolean =
        !isRequesting && folderStack.isEmpty() && (hasActionItems || !isLoad)

    fun isLoaded(): Boolean = isLoad || folderStack.isNotEmpty()

    fun hasFilters(): Boolean = !sortData.filters.isNullOrEmpty()

    fun pushFolder() {
        folderStack.addLast(
            HomeGridSnapshot(
                sortId = sortData.id ?: "",
                videos = ArrayList(videos),
                page = page,
                maxPage = maxPage,
                isLoad = isLoad,
                hasActionItems = hasActionItems,
                isFolderMode = isFolderMode,
                focusIndex = focusIndex,
                firstVisibleLine = gridState.firstVisibleItemIndex
            )
        )
    }

    fun popFolder(): Boolean {
        val snap = folderStack.removeLastOrNull() ?: return false
        sortData.id = snap.sortId
        videos.clear()
        videos.addAll(snap.videos)
        page = snap.page
        maxPage = snap.maxPage
        isLoad = snap.isLoad
        hasActionItems = snap.hasActionItems
        isFolderMode = snap.isFolderMode
        focusIndex = snap.focusIndex
        pendingScrollIndex = snap.firstVisibleLine
        return true
    }
}

/** 文件夹快照:进入子目录时保存父级列表与分页状态。 */
data class HomeGridSnapshot(
    val sortId: String,
    val videos: List<Movie.Video>,
    val page: Int,
    val maxPage: Int,
    val isLoad: Boolean,
    val hasActionItems: Boolean,
    val isFolderMode: Boolean,
    val focusIndex: Int,
    val firstVisibleLine: Int
)

/** “主页”(my0)页状态:热播/推荐/历史列表 + 删除模式。 */
class HomeUserPageState {
    val videos = mutableStateListOf<Movie.Video>()
    var tvRate by mutableStateOf("")
    var deleteMode by mutableStateOf(HawkConfig.hotVodDelete)
    var useGridStyle by mutableStateOf(Hawk.get(HawkConfig.HOME_REC_STYLE, false))
    val catalogState = LazyListState()
    val gridState = LazyGridState()
}

/** 首页 UI 快照:状态栏 + 分类条 + 页面加载态。 */
data class HomeUiSnapshot(
    val siteName: String = "",
    val tabs: List<HomeTabUi> = emptyList(),
    val selected: Int = 0,
    val topHidden: Boolean = false,
    val screenLoading: Boolean = false,
    val tabFocusToken: Int = 0,
    val pageGeneration: Int = 0
)

data class HomeTabUi(
    val id: String,
    val name: String,
    val filterCount: Int = 0
)
