package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.kt.R
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.ui.adapter.CheckboxSearchAdapter
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import me.jessyan.autosize.utils.AutoSizeUtils
import java.util.HashMap

/** 搜索源勾选对话框,Kotlin 移植(与旧版 SearchCheckboxDialog 一致)。 */
class SearchCheckboxDialog(
    context: Context,
    sourceList: List<SourceBean>?,
    checkedSources: HashMap<String, String>?
) : BaseDialog(context) {

    var mGridView: TvRecyclerView? = null
    var checkboxSearchAdapter: CheckboxSearchAdapter? = null
    @JvmField
    var mSourceList: List<SourceBean>? = sourceList
    var checkAll: TextView? = null
    var clearAll: TextView? = null

    var mCheckSourcees: HashMap<String, String>? = checkedSources

    init {
        if (context is Activity) {
            setOwnerActivity(context)
        }
        setCanceledOnTouchOutside(false)
        setCancelable(true)
        setContentView(R.layout.dialog_checkbox_search)
        initView(context)
    }

    override fun dismiss() {
        checkboxSearchAdapter?.setMCheckedSources()
        super.dismiss()
    }

    private fun initView(context: Context) {
        mGridView = findViewById(R.id.mGridView)
        checkAll = findViewById(R.id.checkAll)
        clearAll = findViewById(R.id.clearAll)
        checkboxSearchAdapter = CheckboxSearchAdapter(object : DiffUtil.ItemCallback<SourceBean>() {
            override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                return oldItem.key == newItem.key
            }

            override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                return oldItem.name == newItem.name
            }
        })
        mGridView!!.setHasFixedSize(true)

        val size = mSourceList?.size ?: 0
        var spanCount = (Math.floor((size / 10).toDouble())).toInt()
        if (spanCount <= 0) spanCount = 1
        if (spanCount > 3) spanCount = 3
        mGridView!!.layoutManager = V7GridLayoutManager(context, spanCount)
        val root = findViewById<View>(R.id.root)
        val clp = root.layoutParams
        clp.width = AutoSizeUtils.mm2px(context, (400 + 260 * (spanCount - 1)).toFloat())
        root.layoutParams = clp

        mGridView!!.setAdapter(checkboxSearchAdapter)
        checkboxSearchAdapter!!.setData(mSourceList!!, mCheckSourcees)
        if (mCheckSourcees != null) {
            for (i in mSourceList!!.indices) {
                val key = mSourceList!![i].key
                if (mCheckSourcees!!.containsKey(key)) {
                    break
                }
            }
        }
        checkAll!!.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            mCheckSourcees = HashMap()
            for (sourceBean in mSourceList!!) {
                mCheckSourcees!![sourceBean.key] = "1"
            }
            checkboxSearchAdapter!!.setData(mSourceList!!, mCheckSourcees)
        }
        clearAll!!.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            mCheckSourcees = HashMap()
            checkboxSearchAdapter!!.setData(mSourceList!!, mCheckSourcees)
        }
    }

    fun setMSourceList(sourceBeanList: List<SourceBean>?) {
        mSourceList = sourceBeanList
        checkboxSearchAdapter!!.setData(mSourceList!!, mCheckSourcees)
    }
}
