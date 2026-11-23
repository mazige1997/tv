package com.github.tvbox.kt.ui.tv.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.BaseMultiItemQuickAdapter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.chad.library.adapter.base.entity.MultiItemEntity
import com.github.tvbox.kt.R
import java.util.ArrayList
import java.util.Arrays

/**
 * 软键盘组件,Kotlin 移植(与旧版 SearchKeyboard 一致)。
 * 接口约定:onSearchKey(pos: Int, key: String?)(SearchActivity/FastSearchActivity 引用)。
 */
class SearchKeyboard : FrameLayout {

    private var mRecyclerView: RecyclerView? = null
    private val keys = Arrays.asList(
        "远程搜索", "删除", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N",
        "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "1", "2", "3", "4", "5", "6",
        "7", "8", "9", "0"
    )
    private val keyboardList = ArrayList<Keyboard>()
    private var searchKeyListener: OnSearchKeyListener? = null
    private val focusChangeListener = View.OnFocusChangeListener { itemView, hasFocus ->
        if (null != itemView && itemView !== mRecyclerView) {
            itemView.isSelected = hasFocus
        }
    }

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    ) {
        initView()
    }

    private fun initView() {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_keyborad, this)
        mRecyclerView = view.findViewById(R.id.mRecyclerView) as RecyclerView
        val manager = GridLayoutManager(context, 6)
        mRecyclerView!!.layoutManager = manager
        mRecyclerView!!.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                if (child.isFocusable && null == child.onFocusChangeListener) {
                    child.onFocusChangeListener = focusChangeListener
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
        val size = keys.size
        for (i in 0 until size) {
            keyboardList.add(Keyboard(1, keys[i]))
        }
        val adapter = KeyboardAdapter(keyboardList)
        mRecyclerView!!.setAdapter(adapter)
        adapter.setSpanSizeLookup { _, position ->
            if (position == 0) 3 else if (position == 1) 3 else 1
        }
        adapter.setOnItemClickListener { adapter0, _, position ->
            val keyboard = adapter0.getItem(position) as Keyboard
            searchKeyListener?.onSearchKey(position, keyboard.getKey())
        }
    }

    class Keyboard(private var itemType: Int, private var key: String) : MultiItemEntity {

        override fun getItemType(): Int = itemType

        fun getKey(): String = key

        fun setKey(key: String) {
            this.key = key
        }
    }

    private class KeyboardAdapter(data: List<Keyboard>) :
        BaseMultiItemQuickAdapter<Keyboard, BaseViewHolder>(data) {

        init {
            addItemType(1, R.layout.item_keyboard)
        }

        override fun convert(helper: BaseViewHolder, item: Keyboard) {
            when (helper.itemViewType) {
                1 -> helper.setText(R.id.keyName, item.getKey())
            }
        }
    }

    fun setOnSearchKeyListener(listener: OnSearchKeyListener?) {
        searchKeyListener = listener
    }

    interface OnSearchKeyListener {
        fun onSearchKey(pos: Int, key: String?)
    }
}
