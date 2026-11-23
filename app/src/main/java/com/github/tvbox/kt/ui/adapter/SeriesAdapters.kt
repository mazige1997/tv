package com.github.tvbox.kt.ui.adapter

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.VodInfo
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import java.util.ArrayList

/** 选集适配器,Kotlin 移植(与旧版 SeriesAdapter 一致)。 */
class SeriesAdapter(private val mGridLayoutManager: V7GridLayoutManager) :
    BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder>(R.layout.item_series, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: VodInfo.VodSeries) {
        val tvSeries = helper.getView<TextView>(R.id.tvSeries)
        if (item.selected) {
            tvSeries.setTextColor(mContext.resources.getColor(R.color.color_02F8E1))
        } else {
            tvSeries.setTextColor(Color.WHITE)
        }
        helper.setText(R.id.tvSeries, item.name)

        if (data.size == 1 && helper.layoutPosition == 0) {
            helper.itemView.nextFocusUpId = R.id.mGridViewFlag
        }

        val mSeriesGroupTv = (helper.itemView.context as Activity).findViewById<View>(R.id.mSeriesGroupTv)
        if (data.size > 1 && mSeriesGroupTv != null && mSeriesGroupTv.visibility == View.VISIBLE) {
            val spanCount = mGridLayoutManager.spanCount
            val position = helper.layoutPosition
            if (position < spanCount) {
                helper.itemView.nextFocusUpId = R.id.mSeriesSortTv
            }
        }
        val mGridViewQuality = (helper.itemView.context as Activity).findViewById<View>(R.id.mGridViewQuality)
        if (mGridViewQuality != null && mGridViewQuality.visibility == View.VISIBLE &&
            helper.layoutPosition < mGridLayoutManager.spanCount
        ) {
            helper.itemView.nextFocusUpId = R.id.mGridViewQuality
        }
    }
}

/** 选集来源 flag 适配器,Kotlin 移植(与旧版 SeriesFlagAdapter 一致)。 */
class SeriesFlagAdapter : BaseQuickAdapter<VodInfo.VodSeriesFlag, BaseViewHolder>(R.layout.item_series_flag, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: VodInfo.VodSeriesFlag) {
        val tvSeries = helper.getView<TextView>(R.id.tvSeriesFlag)
        val select = helper.getView<View>(R.id.tvSeriesFlagSelect)
        if (item.selected) {
            select.visibility = View.VISIBLE
        } else {
            select.visibility = View.GONE
        }
        helper.setText(R.id.tvSeriesFlag, item.name)
        val mSeriesGroupTv = (helper.itemView.context as Activity).findViewById<View>(R.id.mSeriesGroupTv)
        val mGridViewQuality = (helper.itemView.context as Activity).findViewById<View>(R.id.mGridViewQuality)
        if (mSeriesGroupTv != null && mSeriesGroupTv.visibility == View.VISIBLE) {
            helper.itemView.nextFocusDownId = R.id.mSeriesSortTv
        } else if (mGridViewQuality != null && mGridViewQuality.visibility == View.VISIBLE) {
            helper.itemView.nextFocusDownId = R.id.mGridViewQuality
        } else {
            helper.itemView.nextFocusDownId = R.id.mGridView
        }
        if (helper.layoutPosition == data.size - 1) {
            helper.itemView.id = View.generateViewId()
            helper.itemView.nextFocusRightId = helper.itemView.id
        } else {
            helper.itemView.nextFocusRightId = View.NO_ID
        }
    }
}

/** 通用选择对话框适配器,Kotlin 移植(与旧版 SelectDialogAdapter 一致)。 */
class SelectDialogAdapter<T> : ListAdapter<T, SelectDialogAdapter.SelectViewHolder> {

    class SelectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    interface SelectDialogInterface<T> {
        fun click(value: T, pos: Int)

        fun getDisplay(`val`: T): String
    }

    val data: ArrayList<T> = ArrayList()
    var select = 0
        private set
    private val dialogInterface: SelectDialogInterface<T>

    constructor(dialogInterface: SelectDialogInterface<T>, diffCallback: DiffUtil.ItemCallback<T>) : super(diffCallback) {
        this.dialogInterface = dialogInterface
    }

    companion object {
        /** 与旧版一致的 String DiffCallback */
        @JvmField
        val stringDiff: DiffUtil.ItemCallback<String> = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(newData: List<T>, defaultSelect: Int) {
        data.clear()
        data.addAll(newData)
        select = defaultSelect
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectViewHolder {
        return SelectViewHolder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dialog_select, parent, false)
        )
    }

    override fun onBindViewHolder(holder: SelectViewHolder, @Suppress("UNNECESSARY_NOT_NULL_ASSERTION") position: Int) {
        val value = data[position]
        val name = dialogInterface.getDisplay(value)
        val view = holder.itemView.findViewById<TextView>(R.id.tvName)
        if (position == select) {
            view.setTextColor(0xff02f8e1.toInt())
            view.typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        } else {
            view.setTextColor(Color.WHITE)
            view.typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        }
        view.text = name
        holder.itemView.setOnClickListener { v ->
            if (position == select) return@setOnClickListener
            notifyItemChanged(select)
            select = position
            notifyItemChanged(select)
            dialogInterface.click(value, position)
        }
    }
}

/** 通用按钮列表适配器,Kotlin 移植(与旧版 ButtonAdapter 一致)。 */
class ButtonAdapter<T> : ListAdapter<T, ButtonAdapter.SelectViewHolder> {

    interface SelectDialogInterface<T> {
        fun click(value: T, pos: Int)

        fun getDisplay(`val`: T): String
    }

    class SelectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private val data = ArrayList<T>()
    private val dialogInterface: SelectDialogInterface<T>
    private var select = 0

    constructor(dialogInterface: SelectDialogInterface<T>, diffCallback: DiffUtil.ItemCallback<T>) : super(diffCallback) {
        this.dialogInterface = dialogInterface
    }

    fun setData(newData: List<T>, defaultSelect: Int) {
        data.clear()
        data.addAll(newData)
        select = 0.coerceAtLeast(defaultSelect.coerceAtMost(data.size - 1))
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectViewHolder {
        return SelectViewHolder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_button, parent, false)
        )
    }

    override fun onBindViewHolder(holder: SelectViewHolder, @Suppress("UNNECESSARY_NOT_NULL_ASSERTION") position: Int) {
        val value = data[position]
        val item = holder.itemView.findViewById<TextView>(R.id.tvName)
        item.text = dialogInterface.getDisplay(value)
        if (position == select) {
            item.setTextColor(0xff02f8e1.toInt())
            item.typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        } else {
            item.setTextColor(Color.WHITE)
            item.typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        }
        holder.itemView.setOnClickListener { v ->
            if (position == select) return@setOnClickListener
            val oldSelect = select
            select = position
            notifyItemChanged(oldSelect)
            notifyItemChanged(select)
            dialogInterface.click(value, position)
        }
    }
}

/** 快搜站名热词适配器,Kotlin 移植(与旧版 FastListAdapter 一致)。 */
class FastListAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_search_word_hot, ArrayList()) {
    private var selectedName = "全部"

    override fun convert(helper: BaseViewHolder, item: String) {
        val textView = helper.getView<TextView>(R.id.tvSearchWord)
        textView.text = item
        textView.setBackgroundResource(R.drawable.bg_fast_site_word)
        updateSelection(textView, item)
    }

    fun setSelectedName(selectedName: String?) {
        this.selectedName = if (TextUtils.isEmpty(selectedName)) "全部" else selectedName!!
    }

    fun refreshVisibleSelection(parent: ViewGroup?) {
        if (parent == null) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView) {
                updateSelection(child, child.text.toString())
            }
        }
    }

    private fun updateSelection(textView: TextView, item: String) {
        val selected = TextUtils.equals(item, selectedName)
        textView.isSelected = selected
        textView.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }
}

/** 接口历史对话框适配器,Kotlin 移植(与旧版 ApiHistoryDialogAdapter 一致)。 */
class ApiHistoryDialogAdapter(dialogInterface: SelectDialogInterface) :
    ListAdapter<String, ApiHistoryDialogAdapter.SelectViewHolder>(STRING_DIFF) {

    class SelectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    interface SelectDialogInterface {
        fun click(value: String)

        fun del(value: String, data: ArrayList<String>)
    }

    val data: ArrayList<String> = ArrayList()
    var select: String = ""
        private set
    private val dialogInterface: SelectDialogInterface = dialogInterface

    fun setData(newData: List<String>, defaultSelect: Int) {
        data.clear()
        data.addAll(newData)
        select = data[defaultSelect]
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectViewHolder {
        return SelectViewHolder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dialog_api_history, parent, false)
        )
    }

    override fun onBindViewHolder(holder: SelectViewHolder, position: Int) {
        val value = data[position]
        var name = value
        if (com.github.tvbox.kt.util.HistoryHelper.isApiLineSource(value)) name = "[仓] $name"
        if (select == value) name = "√ $name"
        (holder.itemView.findViewById<View>(R.id.tvName) as TextView).text = name
        holder.itemView.findViewById<View>(R.id.tvName).setOnClickListener { v ->
            if (select == value) return@setOnClickListener
            notifyItemChanged(data.indexOf(select))
            select = value
            notifyItemChanged(data.indexOf(value))
            dialogInterface.click(value)
        }
        holder.itemView.findViewById<View>(R.id.tvDel).setOnClickListener { v ->
            if (select == value) return@setOnClickListener
            notifyItemRemoved(data.indexOf(value))
            data.remove(value)
            dialogInterface.del(value, data)
        }
    }

    companion object {
        private val STRING_DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }
        }
    }
}

/** 搜索源勾选适配器,Kotlin 移植(与旧版 CheckboxSearchAdapter 一致)。 */
class CheckboxSearchAdapter(diffCallback: DiffUtil.ItemCallback<com.github.tvbox.kt.bean.SourceBean>) :
    ListAdapter<com.github.tvbox.kt.bean.SourceBean, CheckboxSearchAdapter.ViewHolder>(diffCallback) {

    val data: ArrayList<com.github.tvbox.kt.bean.SourceBean> = ArrayList()
    var mCheckedSources: HashMap<String, String> = HashMap()
        private set

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val oneSearchSource: android.widget.CheckBox = view.findViewById(R.id.oneSearchSource)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dialog_checkbox_search, parent, false)
        )
    }

    fun setData(newData: List<com.github.tvbox.kt.bean.SourceBean>, checkedSources: HashMap<String, String>?) {
        data.clear()
        data.addAll(newData)
        if (checkedSources != null) mCheckedSources = checkedSources
        notifyDataSetChanged()
    }

    fun setMCheckedSources() {
        com.github.tvbox.kt.util.SearchHelper.putCheckedSources(mCheckedSources, data.size == mCheckedSources.size)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pos = holder.adapterPosition
        val sourceBean = data[pos]
        holder.oneSearchSource.text = sourceBean.name
        holder.oneSearchSource.setOnCheckedChangeListener(null)
        if (mCheckedSources != null) {
            holder.oneSearchSource.isChecked = mCheckedSources.containsKey(sourceBean.key)
        }
        holder.oneSearchSource.tag = sourceBean
        holder.oneSearchSource.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                mCheckedSources[sourceBean.key] = "1"
            } else {
                mCheckedSources.remove(sourceBean.key)
            }
            notifyItemChanged(pos)
        }
    }
}
