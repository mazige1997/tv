package com.github.tvbox.kt.ui.adapter

import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.bean.ParseBean
import java.util.ArrayList

/** 首页顶部分类条适配器,Kotlin 移植(与旧版 SortAdapter 一致)。 */
class SortAdapter : BaseQuickAdapter<MovieSort.SortData, BaseViewHolder>(R.layout.item_home_sort, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: MovieSort.SortData) {
        helper.setText(R.id.tvTitle, item.name)
    }
}

/** 播放页解析适配器,Kotlin 移植(与旧版 ParseAdapter 一致)。 */
class ParseAdapter : BaseQuickAdapter<ParseBean, BaseViewHolder>(R.layout.item_play_parse, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: ParseBean) {
        val tvParse = helper.getView<TextView>(R.id.tvParse)
        tvParse.visibility = View.VISIBLE
        if (item.isDefault) {
            tvParse.setTextColor(mContext.resources.getColor(R.color.color_02F8E1))
        } else {
            tvParse.setTextColor(Color.WHITE)
        }
        tvParse.text = item.name
        if (helper.layoutPosition == 0) {
            helper.itemView.nextFocusLeftId = R.id.screen_display
        }
    }
}

/** 搜索热词适配器,Kotlin 移植(与旧版 SearchWordAdapter 一致)。 */
class SearchWordAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_search_word_split, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: String) {
        helper.setText(R.id.tvSearchWord, item)
    }
}

/** 拼音适配器,Kotlin 移植(与旧版 PinyinAdapter 一致)。 */
class PinyinAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_search_word_hot, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: String) {
        helper.setText(R.id.tvSearchWord, item)
    }
}

/** 字幕搜索结果适配器,Kotlin 移植(与旧版 SearchSubtitleAdapter 一致)。 */
class SearchSubtitleAdapter : BaseQuickAdapter<com.github.tvbox.kt.bean.Subtitle, BaseViewHolder>(
    R.layout.item_search_subtitle_result, ArrayList()
) {

    override fun convert(helper: BaseViewHolder, item: com.github.tvbox.kt.bean.Subtitle) {
        helper.setText(R.id.subtitleName, item.name)
        helper.setText(R.id.subtitleNameInfo, if (item.getIsZip()) "压缩包" else "文件")
    }
}

/** 筛选值适配器,Kotlin 移植(与旧版 GridFilterKVAdapter 一致)。 */
class GridFilterKVAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_grid_filter_value, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: String) {
        helper.setText(R.id.filterValue, item)
    }
}

/** 设置菜单适配器,Kotlin 移植(与旧版 SettingMenuAdapter 一致)。 */
class SettingMenuAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_setting_menu, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: String) {
        helper.setText(R.id.tvName, item)
        helper.addOnClickListener(R.id.tvName)
    }
}

/** 备份适配器,Kotlin 移植(与旧版 BackupAdapter 一致)。 */
class BackupAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_dialog_backup, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: String) {
        helper.setText(R.id.tvName, item)
        helper.addOnClickListener(R.id.tvName)
        helper.addOnClickListener(R.id.tvDel)
    }
}
