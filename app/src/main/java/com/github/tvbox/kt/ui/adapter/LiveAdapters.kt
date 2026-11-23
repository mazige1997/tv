package com.github.tvbox.kt.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.DanmuSearchResult
import com.github.tvbox.kt.bean.Epginfo
import com.github.tvbox.kt.bean.LiveChannelGroup
import com.github.tvbox.kt.bean.LiveChannelItem
import com.github.tvbox.kt.bean.LiveEpgDate
import com.github.tvbox.kt.bean.LiveSettingGroup
import com.github.tvbox.kt.bean.LiveSettingItem
import com.github.tvbox.kt.ui.tv.widget.AudioWaveView
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date

/** 直播 EPG 适配器,Kotlin 移植(与旧版 LiveEpgAdapter 一致)。 */
class LiveEpgAdapter : BaseQuickAdapter<Epginfo, BaseViewHolder>(R.layout.epglist_item, ArrayList()) {
    var selectedEpgIndex = -1
        private set
    var focusedEpgIndex = -1
        private set
    private var shiyiSelection = false
    private var shiyiDate: String? = null
    private var source_include_back = false
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd")

    fun canBack(source_include_back: Boolean) {
        this.source_include_back = source_include_back
    }

    @SuppressLint("SetTextI18n")
    override fun convert(holder: BaseViewHolder, value: Epginfo) {
        val textview = holder.getView<TextView>(R.id.tv_epg_name)
        val timeview = holder.getView<TextView>(R.id.tv_epg_time)
        val shiyi = holder.getView<TextView>(R.id.shiyi)
        val wqddg_AudioWaveView = holder.getView<AudioWaveView>(R.id.wqddg_AudioWaveView)
        holder.itemView.isSelected = value.index == selectedEpgIndex || value.index == focusedEpgIndex
        textview.isSelected = true
        timeview.isSelected = true
        wqddg_AudioWaveView.visibility = View.GONE
        if (value.index == selectedEpgIndex && value.index != focusedEpgIndex &&
            (value.currentEpgDate == shiyiDate || value.currentEpgDate == timeFormat.format(Date()))
        ) {
            textview.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
            timeview.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
        } else {
            textview.setTextColor(Color.WHITE)
            timeview.setTextColor(Color.WHITE)
        }
        val now = Date()
        if (now >= value.startdateTime!! && now <= value.enddateTime!!) {
            shiyi.visibility = View.VISIBLE
            shiyi.setBackgroundColor(Color.YELLOW)
            shiyi.text = "直播中"
            shiyi.setTextColor(Color.RED)
        } else if (now > value.enddateTime!! && source_include_back) {
            shiyi.visibility = View.VISIBLE
            shiyi.setBackgroundColor(Color.BLUE)
            shiyi.setTextColor(Color.WHITE)
            shiyi.text = "回看"
        } else if (now < value.startdateTime!!) {
            shiyi.visibility = View.GONE
        } else {
            shiyi.visibility = View.GONE
        }
        textview.text = value.title
        timeview.text = value.start + "--" + value.end
        if (!shiyiSelection) {
            if (now >= value.startdateTime!! && now <= value.enddateTime!!) {
                wqddg_AudioWaveView.visibility = View.VISIBLE
                textview.freezesText = true
                timeview.freezesText = true
            } else {
                wqddg_AudioWaveView.visibility = View.GONE
            }
        } else {
            if (value.index == selectedEpgIndex && value.currentEpgDate == shiyiDate) {
                wqddg_AudioWaveView.visibility = View.VISIBLE
                textview.freezesText = true
                timeview.freezesText = true
                shiyi.text = "回看中"
                shiyi.setTextColor(Color.RED)
                shiyi.setBackgroundColor(Color.rgb(12, 255, 0))
                if (now >= value.startdateTime!! && now <= value.enddateTime!!) {
                    shiyi.visibility = View.VISIBLE
                    shiyi.setBackgroundColor(Color.YELLOW)
                    shiyi.text = "直播中"
                    shiyi.setTextColor(Color.RED)
                }
            } else {
                wqddg_AudioWaveView.visibility = View.GONE
            }
        }
    }

    fun setShiyiSelection(i: Int, t: Boolean, currentEpgDate: String?) {
        selectedEpgIndex = i
        shiyiDate = if (t) currentEpgDate else null
        shiyiSelection = t
        notifyItemChanged(selectedEpgIndex)
    }

    fun setSelectedEpgIndex(selectedEpgIndex: Int) {
        if (selectedEpgIndex == this.selectedEpgIndex) {
            if (this.selectedEpgIndex != -1) notifyItemChanged(this.selectedEpgIndex)
            return
        }
        val preSelectedEpgIndex = this.selectedEpgIndex
        this.selectedEpgIndex = selectedEpgIndex
        if (preSelectedEpgIndex != -1) notifyItemChanged(preSelectedEpgIndex)
        if (this.selectedEpgIndex != -1) notifyItemChanged(this.selectedEpgIndex)
    }

    fun setFocusedEpgIndex(focusedEpgIndex: Int) {
        this.focusedEpgIndex = focusedEpgIndex
    }
}

/** 直播 EPG 旧版 ListView 适配器,Kotlin 移植(与旧版 MyEpgAdapter 一致)。 */
class MyEpgAdapter(private val data: List<Epginfo>, private val context: android.content.Context, i: Int) : BaseAdapter() {
    private var defaultSelection = i
    var fontSize = 20f
        private set

    fun setSelection(i: Int) {
        defaultSelection = i
        notifyDataSetChanged()
    }

    fun setFontSize(f: Float) {
        fontSize = f
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return data.size
    }

    override fun getItem(i: Int): Any? {
        return null
    }

    override fun getItemId(i: Int): Long {
        return i.toLong()
    }

    override fun getView(i: Int, view0: View?, viewGroup: ViewGroup): View {
        var view = view0
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.epglist_item, viewGroup, false)
        }
        val textview = view!!.findViewById<TextView>(R.id.tv_epg_name)
        val timeview = view.findViewById<TextView>(R.id.tv_epg_time)
        val wqddg_AudioWaveView = view.findViewById<AudioWaveView>(R.id.wqddg_AudioWaveView)
        wqddg_AudioWaveView.visibility = View.GONE
        if (i < data.size) {
            textview.text = data[i].title
            timeview.text = data[i].start + "--" + data[i].end
            textview.setTextColor(Color.WHITE)
            timeview.setTextColor(Color.WHITE)
            if (i == defaultSelection) {
                wqddg_AudioWaveView.visibility = View.VISIBLE
                textview.setTextColor(Color.rgb(0, 153, 255))
                timeview.setTextColor(Color.rgb(0, 153, 255))
                textview.freezesText = true
                timeview.freezesText = true
            } else {
                wqddg_AudioWaveView.visibility = View.GONE
            }
        }
        return view
    }
}

/** 直播 EPG 日期适配器,Kotlin 移植(与旧版 LiveEpgDateAdapter 一致)。 */
class LiveEpgDateAdapter : BaseQuickAdapter<LiveEpgDate, BaseViewHolder>(R.layout.item_live_channel_group, ArrayList()) {
    var selectedIndex = -1
        private set
    private var focusedIndex = -1

    override fun convert(holder: BaseViewHolder, item: LiveEpgDate) {
        val tvGroupName = holder.getView<TextView>(R.id.tvChannelGroupName)
        tvGroupName.text = item.datePresented
        tvGroupName.setBackgroundColor(Color.TRANSPARENT)
        if (item.index == selectedIndex && item.index != focusedIndex) {
            tvGroupName.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
        } else {
            tvGroupName.setTextColor(mContext.resources.getColor(R.color.color_CCFFFFFF))
        }
    }

    fun setSelectedIndex(selectedIndex: Int) {
        if (selectedIndex == this.selectedIndex) return
        val preSelectedIndex = this.selectedIndex
        this.selectedIndex = selectedIndex
        if (preSelectedIndex != -1) notifyItemChanged(preSelectedIndex)
        if (this.selectedIndex != -1) notifyItemChanged(this.selectedIndex)
    }

    fun setFocusedIndex(focusedIndex: Int) {
        val preSelectedIndex = this.selectedIndex
        this.focusedIndex = focusedIndex
        if (preSelectedIndex != -1) notifyItemChanged(preSelectedIndex)
        if (this.focusedIndex != -1) notifyItemChanged(this.focusedIndex)
    }
}

/** 直播分组适配器,Kotlin 移植(与旧版 LiveChannelGroupAdapter 一致)。 */
class LiveChannelGroupAdapter : BaseQuickAdapter<LiveChannelGroup, BaseViewHolder>(R.layout.item_live_channel_group, ArrayList()) {
    private var selectedGroupIndex = -1
    private var focusedGroupIndex = -1

    override fun convert(holder: BaseViewHolder, item: LiveChannelGroup) {
        val tvGroupName = holder.getView<TextView>(R.id.tvChannelGroupName)
        tvGroupName.text = item.groupName
        tvGroupName.isSelected = true
        val groupIndex = item.groupIndex
        holder.itemView.isSelected = groupIndex == selectedGroupIndex
        if (groupIndex == selectedGroupIndex && groupIndex != focusedGroupIndex) {
            tvGroupName.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
        } else {
            tvGroupName.setTextColor(Color.WHITE)
        }
    }

    fun setSelectedGroupIndex(selectedGroupIndex: Int) {
        if (selectedGroupIndex == this.selectedGroupIndex) return
        val preSelectedGroupIndex = this.selectedGroupIndex
        this.selectedGroupIndex = selectedGroupIndex
        notifyGroupChanged(preSelectedGroupIndex)
        notifyGroupChanged(this.selectedGroupIndex)
    }

    fun getSelectedGroupIndex(): Int {
        return selectedGroupIndex
    }

    fun setFocusedGroupIndex(focusedGroupIndex: Int) {
        this.focusedGroupIndex = focusedGroupIndex
        if (this.focusedGroupIndex != -1) {
            notifyGroupChanged(this.focusedGroupIndex)
        } else if (this.selectedGroupIndex != -1) {
            notifyGroupChanged(this.selectedGroupIndex)
        }
    }

    fun clearGroupState() {
        selectedGroupIndex = -1
        focusedGroupIndex = -1
    }

    private fun notifyGroupChanged(position: Int) {
        if (position in 0 until itemCount) {
            notifyItemChanged(position)
        }
    }
}

/** 直播频道适配器,Kotlin 移植(与旧版 LiveChannelItemAdapter 一致)。 */
class LiveChannelItemAdapter : BaseQuickAdapter<LiveChannelItem, BaseViewHolder>(R.layout.item_live_channel, ArrayList()) {
    private var selectedChannelIndex = -1
    private var focusedChannelIndex = -1

    override fun convert(holder: BaseViewHolder, item: LiveChannelItem) {
        val tvChannelNum = holder.getView<TextView>(R.id.tvChannelNum)
        val tvChannel = holder.getView<TextView>(R.id.tvChannelName)
        tvChannelNum.text = String.format("%s", item.channelNum)
        tvChannel.text = item.getChannelName()
        tvChannelNum.isSelected = true
        tvChannel.isSelected = true
        val channelIndex = item.channelIndex
        holder.itemView.isSelected = channelIndex == selectedChannelIndex
        if (channelIndex == selectedChannelIndex && channelIndex != focusedChannelIndex) {
            tvChannelNum.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
            tvChannel.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
        } else {
            tvChannelNum.setTextColor(Color.WHITE)
            tvChannel.setTextColor(Color.WHITE)
        }
    }

    fun setSelectedChannelIndex(selectedChannelIndex: Int) {
        if (selectedChannelIndex == this.selectedChannelIndex) return
        val preSelectedChannelIndex = this.selectedChannelIndex
        this.selectedChannelIndex = selectedChannelIndex
        if (preSelectedChannelIndex != -1) notifyItemChanged(preSelectedChannelIndex)
        if (this.selectedChannelIndex != -1) notifyItemChanged(this.selectedChannelIndex)
    }

    fun setFocusedChannelIndex(focusedChannelIndex: Int) {
        val preFocusedChannelIndex = this.focusedChannelIndex
        this.focusedChannelIndex = focusedChannelIndex
        if (preFocusedChannelIndex != -1) notifyItemChanged(preFocusedChannelIndex)
        if (this.focusedChannelIndex != -1) {
            notifyItemChanged(this.focusedChannelIndex)
        } else if (this.selectedChannelIndex != -1) {
            notifyItemChanged(this.selectedChannelIndex)
        }
    }
}

/** 直播设置分组适配器,Kotlin 移植(与旧版 LiveSettingGroupAdapter 一致)。 */
class LiveSettingGroupAdapter : BaseQuickAdapter<LiveSettingGroup, BaseViewHolder>(R.layout.item_live_setting_group, ArrayList()) {
    var selectedGroupIndex = -1
        private set
    private var focusedGroupIndex = -1

    override fun convert(holder: BaseViewHolder, group: LiveSettingGroup) {
        val tvGroupName = holder.getView<TextView>(R.id.tvSettingGroupName)
        tvGroupName.text = group.groupName
        tvGroupName.isSelected = true
        val groupIndex = group.groupIndex
        holder.itemView.isSelected = groupIndex == selectedGroupIndex
        if (groupIndex == selectedGroupIndex && groupIndex != focusedGroupIndex) {
            tvGroupName.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
        } else {
            tvGroupName.setTextColor(Color.WHITE)
        }
    }

    fun setSelectedGroupIndex(selectedGroupIndex: Int) {
        val preSelectedGroupIndex = this.selectedGroupIndex
        this.selectedGroupIndex = if (findPositionByGroupIndex(selectedGroupIndex) != -1) selectedGroupIndex else -1
        val preSelectedPosition = findPositionByGroupIndex(preSelectedGroupIndex)
        if (preSelectedPosition != -1) notifyItemChanged(preSelectedPosition)
        val selectedPosition = findPositionByGroupIndex(this.selectedGroupIndex)
        if (selectedPosition != -1) notifyItemChanged(selectedPosition)
    }

    fun setFocusedGroupIndex(focusedGroupIndex: Int) {
        this.focusedGroupIndex = if (findPositionByGroupIndex(focusedGroupIndex) != -1) focusedGroupIndex else -1
        val focusedPosition = findPositionByGroupIndex(this.focusedGroupIndex)
        if (focusedPosition != -1) {
            notifyItemChanged(focusedPosition)
        } else {
            val selectedPosition = findPositionByGroupIndex(this.selectedGroupIndex)
            if (selectedPosition != -1) notifyItemChanged(selectedPosition)
        }
    }

    fun findPositionByGroupIndex(groupIndex: Int): Int {
        for (i in data.indices) {
            if (data[i].groupIndex == groupIndex) return i
        }
        return -1
    }
}

/** 直播设置项适配器,Kotlin 移植(与旧版 LiveSettingItemAdapter 一致)。 */
class LiveSettingItemAdapter : BaseQuickAdapter<LiveSettingItem, BaseViewHolder>(R.layout.item_live_setting, ArrayList()) {
    private var focusedItemIndex = -1

    override fun convert(holder: BaseViewHolder, item: LiveSettingItem) {
        val tvItemName = holder.getView<TextView>(R.id.tvSettingItemName)
        tvItemName.text = item.itemName
        tvItemName.isSelected = true
        val itemIndex = item.itemIndex
        holder.itemView.isSelected = item.isItemSelected()
        if (item.isItemSelected() && itemIndex != focusedItemIndex) {
            tvItemName.setTextColor(mContext.resources.getColor(R.color.color_1890FF))
        } else {
            tvItemName.setTextColor(Color.WHITE)
        }
    }

    fun selectItem(selectedItemIndex: Int, select: Boolean, unselectPreItemIndex: Boolean) {
        if (unselectPreItemIndex) {
            val preSelectedItemIndex = getSelectedItemIndex()
            if (preSelectedItemIndex != -1) {
                data[preSelectedItemIndex].setItemSelected(false)
                notifyItemChanged(preSelectedItemIndex)
            }
        }
        if (selectedItemIndex != -1) {
            data[selectedItemIndex].setItemSelected(select)
            notifyItemChanged(selectedItemIndex)
        }
    }

    fun setFocusedItemIndex(focusedItemIndex: Int) {
        val preFocusItemIndex = this.focusedItemIndex
        this.focusedItemIndex = focusedItemIndex
        if (preFocusItemIndex != -1) notifyItemChanged(preFocusItemIndex)
        if (this.focusedItemIndex != -1) notifyItemChanged(this.focusedItemIndex)
    }

    fun getSelectedItemIndex(): Int {
        for (item in data) {
            if (item.isItemSelected()) return item.itemIndex
        }
        return -1
    }
}

/** 弹幕搜索结果适配器,Kotlin 移植(与旧版 SearchDanmuAdapter 一致)。 */
class SearchDanmuAdapter : BaseQuickAdapter<DanmuSearchResult, BaseViewHolder>(R.layout.item_search_danmu_result, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: DanmuSearchResult) {
        helper.setText(R.id.danmuName, item.getName())
        helper.addOnClickListener(R.id.danmuItem)
        helper.getView<View>(R.id.danmuItem).setOnKeyListener { view, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_A)
            ) {
                view.performClick()
                return@setOnKeyListener true
            }
            false
        }
    }
}
