package com.github.tvbox.kt.ui.tv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.Scroller
import android.widget.TextView
import androidx.viewpager.widget.ViewPager
import com.chad.library.adapter.base.loadmore.LoadMoreView
import com.github.tvbox.kt.R

/** 禁止滑动/按键切换的 ViewPager,Kotlin 移植(与旧版 NoScrollViewPager 一致)。 */
class NoScrollViewPager : ViewPager {

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    /** 禁止 viewpager 里面内容导致页面切换 */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }
}

/** 跑马灯 TextView,Kotlin 移植(与旧版 MarqueeTextView 一致)。 */
@SuppressLint("AppCompatCustomView")
class MarqueeTextView : TextView {

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setSelected(true)
        setSingleLine(true)
        marqueeRepeatLimit = -1
        ellipsize = TextUtils.TruncateAt.MARQUEE
    }

    override fun isFocused(): Boolean {
        return true
    }
}

/** 固定时长 Scroller,Kotlin 移植(与旧版 FixedSpeedScroller 一致)。 */
class FixedSpeedScroller : Scroller {
    private var mDuration = 0

    fun setmDuration(mDuration: Int) {
        this.mDuration = mDuration
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, interpolator: Interpolator?) : super(context, interpolator)

    constructor(context: Context, interpolator: Interpolator?, flywheel: Boolean) : super(context, interpolator, flywheel)

    override fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, duration: Int) {
        super.startScroll(startX, startY, dx, dy, mDuration)
    }

    override fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int) {
        super.startScroll(startX, startY, dx, dy, mDuration)
    }
}

/** 加载更多布局,Kotlin 移植(与旧版 LoadMoreView 一致)。 */
class LoadMoreView : LoadMoreView() {
    override fun getLayoutId(): Int {
        return R.layout.item_view_load_more
    }

    override fun getLoadingViewId(): Int {
        return R.id.load_more_loading_view
    }

    override fun getLoadFailViewId(): Int {
        return R.id.load_more_load_fail_view
    }

    override fun getLoadEndViewId(): Int {
        return R.id.load_more_load_end_view
    }
}

/** 默认页面变换器,Kotlin 移植(与旧版 DefaultTransformer 一致)。 */
class DefaultTransformer : ViewPager.PageTransformer {
    override fun transformPage(page: View, position: Float) {
    }
}

/** View 边距/尺寸动画辅助,Kotlin 移植(与旧版 ViewObj 一致)。 */
class ViewObj(private val view: View, private val params: ViewGroup.MarginLayoutParams) {
    fun setMarginLeft(left: Int) {
        params.leftMargin = left
        view.layoutParams = params
    }

    fun setMarginTop(top: Int) {
        params.topMargin = top
        view.layoutParams = params
    }

    fun setMarginRight(right: Int) {
        params.rightMargin = right
        view.layoutParams = params
    }

    fun setMarginBottom(bottom: Int) {
        params.bottomMargin = bottom
        view.layoutParams = params
    }

    fun setWidth(width: Int) {
        params.width = width
        view.layoutParams = params
    }

    fun setHeight(height: Int) {
        params.height = height
        view.layoutParams = params
    }
}
