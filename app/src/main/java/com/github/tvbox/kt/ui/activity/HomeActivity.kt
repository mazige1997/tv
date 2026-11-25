package com.github.tvbox.kt.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.IntEvaluator
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.base.BaseLazyFragment
import com.github.tvbox.kt.bean.AbsSortXml
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.MovieSort
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.adapter.HomePageAdapter
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter
import com.github.tvbox.kt.ui.adapter.SortAdapter
import com.github.tvbox.kt.ui.dialog.SelectDialog
import com.github.tvbox.kt.ui.dialog.TipDialog
import com.github.tvbox.kt.ui.fragment.GridFragment
import com.github.tvbox.kt.ui.fragment.UserFragment
import com.github.tvbox.kt.ui.tv.widget.DefaultTransformer
import com.github.tvbox.kt.ui.tv.widget.FixedSpeedScroller
import com.github.tvbox.kt.ui.tv.widget.NoScrollViewPager
import com.github.tvbox.kt.ui.tv.widget.ViewObj
import com.github.tvbox.kt.util.AppManager
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.MD5
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import me.jessyan.autosize.utils.AutoSizeUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

/** 首页 Activity(ViewPager 分类 + 数据源切换 + 双击退出),Kotlin 移植(与旧版 HomeActivity 一致)。 */
class HomeActivity : BaseActivity() {
    private var topLayout: LinearLayout? = null
    private var contentLayout: LinearLayout? = null
    private var tvDate: TextView? = null
    private var tvName: TextView? = null
    private var mGridView: TvRecyclerView? = null
    private var mViewPager: NoScrollViewPager? = null
    private var sourceViewModel: SourceViewModel? = null
    private var sortAdapter: SortAdapter? = null
    private var pageAdapter: HomePageAdapter? = null
    private var currentView: View? = null
    private val fragments = ArrayList<BaseLazyFragment>()
    private var isDownOrUp = false
    private var sortChange = false
    private var currentSelected = 0
    private var sortFocused = 0
    var sortFocusView: View? = null
    private var loadingSourceKey: String? = null
    private var previousHomeName: String? = null
    private var previousHomeSource: SourceBean? = null
    private var homeSortLoading = false
    private var refreshHomeRec = false
    private val mHandler = Handler()
    private var mExitTime = 0L
    private var eventBusRegistered = false
    // 焦点切换时避免每次都走 Resources.getColor 查找。
    private var focusTextColor = 0
    private var normalTextColor = 0
    private val mRunnable: Runnable = object : Runnable {
        @SuppressLint("SetTextI18n")
        override fun run() {
            val date = Date()
            val timeFormat = SimpleDateFormat("yyyy/MM/dd  E  HH:mm", Locale.CHINA)
            tvDate!!.text = timeFormat.format(date)
            mHandler.postDelayed(this, 1000)
        }
    }
    private val refreshTopInfoTextSizeRunnable = Runnable {
        refreshTopInfoTextSize()
    }
    private val refreshTopLayoutRunnable = Runnable {
        if (topLayout == null || isActivityUnavailable() || currentSelected != 0 || topHide != 0) {
            return@Runnable
        }
        // OnePlus devices may finish applying immersive mode after the first measure.
        // Re-apply the visible top state once the final display metrics are available.
        hideSysBar()
        val params = topLayout!!.layoutParams as ViewGroup.MarginLayoutParams
        params.topMargin = AutoSizeUtils.mm2px(this@HomeActivity, 10.0f)
        params.height = AutoSizeUtils.mm2px(this@HomeActivity, 50.0f)
        topLayout!!.layoutParams = params
        topLayout!!.alpha = 1.0f
        refreshTopInfoTextSize()
        topLayout!!.requestLayout()
    }

    override val layoutResID: Int
        get() = R.layout.activity_home

    override fun shouldRefreshAutoSize(): Boolean {
        return true
    }

    private var useCacheConfig = false

    override fun init() {
        EventBus.getDefault().register(this)
        eventBusRegistered = true
        ControlManager.get().startServer()
        initView()
        initViewModel()
        useCacheConfig = false
        val intent = getIntent()
        if (intent != null && intent.extras != null) {
            val bundle = intent.extras
            useCacheConfig = bundle!!.getBoolean("useCache", false)
        }
        initData()
    }

    private fun initView() {
        this.topLayout = findViewById(R.id.topLayout)
        this.tvDate = findViewById(R.id.tvDate)
        this.tvName = findViewById(R.id.tvName)
        this.contentLayout = findViewById(R.id.contentLayout)
        this.mGridView = findViewById(R.id.mGridView)
        this.mViewPager = findViewById(R.id.mViewPager)
        this.sortAdapter = SortAdapter()
        this.mGridView!!.layoutManager = V7LinearLayoutManager(mContext, 0, false)
        this.mGridView!!.setHasFixedSize(true)
        this.mGridView!!.setSpacingWithMargins(0, AutoSizeUtils.dp2px(mContext, 10.0f))
        this.mGridView!!.setAdapter(this.sortAdapter)
        focusTextColor = resources.getColor(R.color.color_FFFFFF)
        normalTextColor = resources.getColor(R.color.color_BBFFFFFF)
        sortAdapter!!.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                mGridView!!.post {
                    val firstChild = mGridView!!.layoutManager!!.findViewByPosition(0)
                    if (firstChild != null) {
                        mGridView!!.setSelectedPosition(0)
                        firstChild.requestFocus()
                    }
                }
            }
        })
        this.mGridView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                if (view != null && !isDownOrUp) {
                    mHandler.postDelayed(Runnable {
                        val textView = view.findViewById<TextView>(R.id.tvTitle)
                        textView.paint.isFakeBoldText = false
                        if (sortFocused == position) {
                            view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(DecelerateInterpolator()).setDuration(150).start()
                            textView.setTextColor(this@HomeActivity.focusTextColor)
                        } else {
                            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                            textView.setTextColor(this@HomeActivity.normalTextColor)
                            view.findViewById<View>(R.id.tvFilter).visibility = View.GONE
                            view.findViewById<View>(R.id.tvFilterColor).visibility = View.GONE
                        }
                        textView.invalidate()
                    }, 10)
                }
            }

            override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                if (view != null) {
                    currentView = view
                    isDownOrUp = false
                    sortChange = true
                    view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(DecelerateInterpolator()).setDuration(150).start()
                    val textView = view.findViewById<TextView>(R.id.tvTitle)
                    textView.paint.isFakeBoldText = true
                    textView.setTextColor(this@HomeActivity.focusTextColor)
                    textView.invalidate()
                    val sortData = sortAdapter!!.getItem(position)
                    if (!sortData!!.filters.isEmpty()) {
                        showFilterIcon(sortData!!.filterSelectCount())
                    }
                    sortFocusView = view
                    sortFocused = position
                    mHandler.removeCallbacks(mDataRunnable)
                    mHandler.postDelayed(mDataRunnable, 200)
                }
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (itemView != null && currentSelected == position) {
                    val baseLazyFragment = fragments[currentSelected]
                    if (baseLazyFragment is GridFragment && !sortAdapter!!.getItem(position)!!.filters.isEmpty()) {// 弹出筛选
                        baseLazyFragment.showFilter()
                    } else if (baseLazyFragment is UserFragment) {
                        showSiteSwitch()
                    }
                }
            }
        })

        this.mGridView!!.setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
            override fun onInBorderKeyEvent(direction: Int, view: View?): Boolean {
                if (direction == View.FOCUS_UP) {
                    val baseLazyFragment = fragments[sortFocused]
                    if (baseLazyFragment is UserFragment) {
                        refreshHomeSort()
                        return true
                    }
                    if (baseLazyFragment is GridFragment) {
                        baseLazyFragment.forceRefresh()
                        return true
                    }
                }
                if (direction != View.FOCUS_DOWN) {
                    return false
                }
                val baseLazyFragment = fragments[sortFocused]
                if (baseLazyFragment !is GridFragment) {
                    return false
                }
                return !baseLazyFragment.isLoaded()
            }
        })
        tvName!!.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            if (dataInitOk && jarInitOk) {
                val jar = ApiConfig.get().homeSourceBean.jar
                val jarUrl = if (jar.isNotEmpty()) jar else ApiConfig.get().getSpider() ?: ""
                val jarSource = jarUrl.split(";md5;")[0]
                val cspCacheDir = File(FileUtils.getFilePath() + "/csp/" + MD5.string2MD5(jarSource) + ".jar")
                val jarCacheDir = File(FileUtils.getCachePath() + "/jar/" + MD5.string2MD5(jarSource) + ".jar")
                val jarFullCacheDir = File(FileUtils.getCachePath() + "/jar/" + MD5.string2MD5(jarUrl) + ".jar")
                Toast.makeText(mContext, "缓存已清除", Toast.LENGTH_LONG).show()
                Thread {
                    try {
                        FileUtils.deleteFile(cspCacheDir)
                        FileUtils.deleteFile(jarCacheDir)
                        FileUtils.deleteFile(jarFullCacheDir)
                        FileUtils.clearSpiderCacheFiles()
                        ApiConfig.get().clearSpiderCache()
                        refreshHome()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            } else {
                jumpActivity(SettingActivity::class.java)
            }
        }
        tvName!!.setOnLongClickListener {
            jumpActivity(SettingActivity::class.java)
            true
        }
        setLoadSir(this.contentLayout!!)
        //mHandler.postDelayed(mFindFocus, 500);
    }

    private var skipNextUpdate = false

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel!!.sortResult.observe(this) { absXml ->
            if (skipNextUpdate) {
                skipNextUpdate = false
                return@observe
            }
            if (!homeSortLoading && loadingSourceKey == null) {
                return@observe
            }
            if (absXml != null && absXml.sourceKey != null && loadingSourceKey != null && loadingSourceKey != absXml.sourceKey) {
                return@observe
            }
            val home = ApiConfig.get().homeSourceBean
            showSuccess()
            clearHomePages()
            val newSortData: List<MovieSort.SortData>
            if (absXml != null && absXml.classes != null && absXml.classes!!.sortList != null) {
                newSortData = DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, absXml.classes!!.sortList, true)
            } else {
                newSortData = DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
            }
            updateSortData(newSortData)
            initViewPager(absXml)
            updateHomeRec(absXml)
            if (home.name.isNotEmpty()) tvName!!.text = home.name
            tvName!!.clearAnimation()
            homeSortLoading = false
            loadingSourceKey = null
            previousHomeName = null
            previousHomeSource = null
        }
    }

    private var dataInitOk = false
    private var jarInitOk = false
    private var mConfigErrorDialog: TipDialog? = null

    private fun initData() {
        if (dataInitOk && jarInitOk) {
            loadHomeSort(false)
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有")
            } else {
                LOG.e("无")
            }
            if (!useCacheConfig && Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                jumpActivity(LivePlayActivity::class.java)
            }
            return
        }
        tvNameAnimation()
        showLoading()
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider()!!.isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), object : ApiConfig.LoadConfigCallback {
                    override fun success() {
                        jarInitOk = true
                        mHandler.postDelayed(Runnable {
//                                if (!useCacheConfig) Toast.makeText(HomeActivity.this, "自定义jar加载成功", Toast.LENGTH_SHORT).show();
                            initData()
                        }, 50)
                    }

                    override fun notice(msg: String) {
                        mHandler.post {
                            Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun error(msg: String) {
                        jarInitOk = true
                        dataInitOk = true
                        mHandler.postDelayed(Runnable {
                            Toast.makeText(this@HomeActivity, msg + " jar load err", Toast.LENGTH_SHORT).show()
                            initData()
                        }, 50)
                    }
                })
            }
            return
        }
        ApiConfig.get().loadConfig(useCacheConfig, object : ApiConfig.LoadConfigCallback {
            override fun notice(msg: String) {
                mHandler.post {
                    Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }

            override fun success() {
                LOG.i("DIAG config=OK sites=" + ApiConfig.get().sourceBeanList.size +
                    " lives=" + ApiConfig.get().liveChannelGroupList.size +
                    " spider=" + (ApiConfig.get().getSpider() ?: ""))
                dataInitOk = true
                if (ApiConfig.get().getSpider()!!.isEmpty()) {
                    jarInitOk = true
                }
                mHandler.postDelayed(Runnable {
                    initData()
                }, 50)
            }

            override fun error(msg: String) {
                LOG.i("DIAG config=ERROR msg=$msg")
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                    return
                }
                mHandler.post {
                    if (isActivityUnavailable()) {
                        return@post
                    }
                    if (mConfigErrorDialog == null) {
                        mConfigErrorDialog = TipDialog(this@HomeActivity, msg, "重试", "取消", object : TipDialog.OnListener {
                            override fun left() {
                                mHandler.post {
                                    dismissConfigErrorDialog()
                                    initData()
                                }
                            }

                            override fun right() {
                                dataInitOk = true
                                jarInitOk = true
                                mHandler.post {
                                    dismissConfigErrorDialog()
                                    initData()
                                }
                            }

                            override fun cancel() {
                                dataInitOk = true
                                jarInitOk = true
                                mHandler.post {
                                    dismissConfigErrorDialog()
                                    initData()
                                }
                            }
                        })
                    }
                    if (!mConfigErrorDialog!!.isShowing) {
                        mConfigErrorDialog!!.show()
                    }
                }
            }
        }, this)
    }

    private fun loadHomeSort(keepCurrentContent: Boolean) {
        val home = ApiConfig.get().homeSourceBean
        homeSortLoading = keepCurrentContent
        if (keepCurrentContent && home.name.isNotEmpty()) {
            previousHomeName = tvName!!.text?.toString()
            tvName!!.text = home.name
        }
        tvNameAnimation()
        loadingSourceKey = home.key
        if (!keepCurrentContent) {
            showLoading()
        }
        sourceViewModel!!.getSort(loadingSourceKey)
    }

    private fun initViewPager(absXml: AbsSortXml?) {
        if (sortAdapter!!.data.size > 0) {
            for (data in sortAdapter!!.data) {
                if (data.id == "my0") {
                    if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && absXml != null && absXml.videoList != null && absXml.videoList!!.isNotEmpty()) {
                        fragments.add(UserFragment.newInstance(absXml.videoList!!))
                    } else {
                        fragments.add(UserFragment.newInstance())
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data))
                }
            }
            pageAdapter = HomePageAdapter(supportFragmentManager, fragments)
            try {
                val field = ViewPager::class.java.getDeclaredField("mScroller")
                field.isAccessible = true
                val scroller = FixedSpeedScroller(mContext, AccelerateInterpolator())
                field.set(mViewPager, scroller)
                scroller.setmDuration(300)
            } catch (e: Exception) {
            }
            mViewPager!!.setPageTransformer(true, DefaultTransformer())
            mViewPager!!.adapter = pageAdapter
            mViewPager!!.setCurrentItem(currentSelected, false)
        }
    }

    private fun clearHomePages() {
        mHandler.removeCallbacks(mDataRunnable)
        currentSelected = 0
        sortFocused = 0
        sortChange = false
        sortFocusView = null
        currentView = null
        if (pageAdapter != null) {
            mViewPager!!.adapter = null
            pageAdapter!!.removeAll()
            pageAdapter = null
        } else if (fragments.isNotEmpty()) {
            fragments.clear()
        }
    }

    private fun updateSortData(newSortData0: List<MovieSort.SortData>?) {
        var newSortData = newSortData0
        if (newSortData == null) {
            newSortData = ArrayList()
        }
        val oldSortData = sortAdapter!!.data as ArrayList<MovieSort.SortData>
        if (oldSortData.isEmpty()
            || newSortData.isEmpty()
            || oldSortData[0] == null
            || newSortData[0] == null
            || "my0" != oldSortData[0].id
            || "my0" != newSortData[0].id
        ) {
            sortAdapter!!.setNewData(newSortData)
            return
        }
        val oldTailCount = oldSortData.size - 1
        if (oldTailCount > 0) {
            oldSortData.subList(1, oldSortData.size).clear()
            sortAdapter!!.notifyItemRangeRemoved(1, oldTailCount)
        }
        if (newSortData.size > 1) {
            oldSortData.addAll(newSortData.subList(1, newSortData.size))
            sortAdapter!!.notifyItemRangeInserted(1, newSortData.size - 1)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onBackPressed() {
        // 打断加载
        if (homeSortLoading) {
            cancelHomeSortLoading()
            return
        }
        if (isLoading()) {
            refreshEmpty()
            return
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false
            UserFragment.homeHotVodAdapter?.notifyDataSetChanged()
            return
        }

        // 检查 fragments 状态
        if (this.fragments.size <= 0 || this.sortFocused >= this.fragments.size || this.sortFocused < 0) {
            doExit()
            return
        }

        val baseLazyFragment = this.fragments[this.sortFocused]
        if (baseLazyFragment is GridFragment) {
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (baseLazyFragment.restoreView()) {
                return
            }
            // 如果 sortFocusView 存在且没有获取焦点，则请求焦点
            if (this.sortFocusView != null && !this.sortFocusView!!.isFocused) {
                this.sortFocusView!!.requestFocus()
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            else if (this.sortFocused != 0) {
                this.mGridView!!.setSelection(0)
            } else {
                doExit()
            }
        } else if (baseLazyFragment is UserFragment && baseLazyFragment.tvHotList.canScrollVertically(-1)) {
            // 如果 UserFragment 列表可以向上滚动，则滚动到顶部
            baseLazyFragment.tvHotList.scrollToPosition(0)
            this.mGridView!!.setSelection(0)
        } else {
            doExit()
        }
    }

    private fun doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.instance.finishAllActivity()
            unregisterEventBus()
            ControlManager.get().stopServer()
            finish()
            Process.killProcess(Process.myPid())
            System.exit(0)
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis()
            Toast.makeText(mContext, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTopInfoTextSize()
        mHandler.removeCallbacks(refreshTopInfoTextSizeRunnable)
        mHandler.postDelayed(refreshTopInfoTextSizeRunnable, 350)
        mHandler.removeCallbacks(refreshTopLayoutRunnable)
        mHandler.postDelayed(refreshTopLayoutRunnable, 450)
        mHandler.post(mRunnable)
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacks(refreshTopInfoTextSizeRunnable)
        mHandler.removeCallbacks(refreshTopLayoutRunnable)
        mHandler.removeCallbacks(mRunnable)
    }

    private fun refreshTopInfoTextSize() {
        if (tvName == null || tvDate == null) {
            return
        }
        tvName!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ts_30))
        tvDate!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ts_26))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                val newIntent = Intent(mContext, DetailActivity::class.java)
                newIntent.putExtra("id", event.obj as String?)
                newIntent.putExtra("sourceKey", "push_agent")
                newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                this@HomeActivity.startActivity(newIntent)
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null) {
                showFilterIcon(event.obj as Int)
            }
        } else if (event.type == RefreshEvent.TYPE_HOME_SOURCE_CHANGE) {
            refreshHome(false)
        }
    }

    private fun showFilterIcon(count: Int) {
        val visible = count > 0
        currentView!!.findViewById<View>(R.id.tvFilterColor).visibility = if (visible) View.VISIBLE else View.GONE
        currentView!!.findViewById<View>(R.id.tvFilter).visibility = if (visible) View.GONE else View.VISIBLE
    }

    private val mDataRunnable = Runnable {
        if (sortChange) {
            sortChange = false
            val baseLazyFragment = fragments[sortFocused]
            if (sortFocused != currentSelected) {
                currentSelected = sortFocused
                mViewPager!!.setCurrentItem(sortFocused, false)
                changeTop(sortFocused != 0)
                if (baseLazyFragment is GridFragment && baseLazyFragment.shouldReloadOnSelect()) {
                    baseLazyFragment.forceRefresh()
                }
            } else if (baseLazyFragment is GridFragment && baseLazyFragment.shouldReloadOnSelect()) {
                baseLazyFragment.forceRefresh()
            }
        }
    }

    private var menuKeyDownTime = 0L
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (topHide < 0) {
            return false
        }
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                menuKeyDownTime = System.currentTimeMillis()
            } else if (event.action == KeyEvent.ACTION_UP) {
                val pressDuration = System.currentTimeMillis() - menuKeyDownTime
                if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    jumpActivity(SettingActivity::class.java)
                } else {
                    showSiteSwitch()
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var topHide = 0

    private fun changeTop(hide: Boolean) {
        val viewObj = ViewObj(topLayout!!, topLayout!!.layoutParams as ViewGroup.MarginLayoutParams)
        val animatorSet = AnimatorSet()
        animatorSet.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {

            }

            override fun onAnimationEnd(animation: Animator) {
                topHide = if (hide) 1 else 0
            }

            override fun onAnimationCancel(animation: Animator) {

            }

            override fun onAnimationRepeat(animation: Animator) {

            }
        })
        if (hide && topHide == 0) {
            animatorSet.playTogether(
                ObjectAnimator.ofObject(viewObj, "marginTop", IntEvaluator(),
                    AutoSizeUtils.mm2px(mContext, 10.0f),
                    AutoSizeUtils.mm2px(mContext, 0.0f)),
                ObjectAnimator.ofObject(viewObj, "height", IntEvaluator(),
                    AutoSizeUtils.mm2px(mContext, 50.0f),
                    AutoSizeUtils.mm2px(mContext, 1.0f)),
                ObjectAnimator.ofFloat(this.topLayout!!, "alpha", 1.0f, 0.0f)
            )
            animatorSet.duration = 200
            animatorSet.start()
            return
        }
        if (!hide && topHide == 1) {
            animatorSet.playTogether(
                ObjectAnimator.ofObject(viewObj, "marginTop", IntEvaluator(),
                    AutoSizeUtils.mm2px(mContext, 0.0f),
                    AutoSizeUtils.mm2px(mContext, 10.0f)),
                ObjectAnimator.ofObject(viewObj, "height", IntEvaluator(),
                    AutoSizeUtils.mm2px(mContext, 1.0f),
                    AutoSizeUtils.mm2px(mContext, 50.0f)),
                ObjectAnimator.ofFloat(this.topLayout!!, "alpha", 0.0f, 1.0f)
            )
            animatorSet.duration = 200
            animatorSet.start()
        }
    }

    override fun onDestroy() {
        dismissHomeDialogs()
        mHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        unregisterEventBus()
        if (isFinishing) {
            ControlManager.get().stopServer()
        }
    }

    private fun unregisterEventBus() {
        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this)
            eventBusRegistered = false
        }
    }

    private var mSiteSwitchDialog: SelectDialog<SourceBean>? = null

    private fun showSiteSwitch() {
        if (isActivityUnavailable()) return
        val sites = ApiConfig.get().getSwitchSourceBeanList()
        if (sites.isEmpty()) return
        var select = sites.indexOf(ApiConfig.get().homeSourceBean)
        if (select < 0 || select >= sites.size) select = 0
        if (mSiteSwitchDialog == null) {
            mSiteSwitchDialog = SelectDialog<SourceBean>(this)
            val tvRecyclerView = mSiteSwitchDialog!!.findViewById<TvRecyclerView>(R.id.list)
            // 根据 sites 数量动态计算列数
            var spanCount = Math.floor(sites.size / 20.0).toInt()
            spanCount = Math.min(spanCount, 2)
            tvRecyclerView.layoutManager = V7GridLayoutManager(mSiteSwitchDialog!!.context, spanCount + 1)
            // 设置对话框宽度
            val cl_root = mSiteSwitchDialog!!.findViewById<ConstraintLayout>(R.id.cl_root)
            val clp = cl_root.layoutParams
            clp.width = AutoSizeUtils.mm2px(mSiteSwitchDialog!!.context, (380 + 200 * spanCount).toFloat())
            mSiteSwitchDialog!!.setTip("请选择首页数据源")
        }
        mSiteSwitchDialog!!.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<SourceBean> {
            override fun click(value: SourceBean, pos: Int) {
                dismissSiteSwitchDialog()
                previousHomeSource = ApiConfig.get().homeSourceBean
                ApiConfig.get().setSourceBean(value)
                refreshHome(false)
            }

            override fun getDisplay(`val`: SourceBean): String {
                return `val`.name
            }
        }, object : DiffUtil.ItemCallback<SourceBean>() {
            override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                return oldItem === newItem
            }

            override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                return oldItem.key == newItem.key
            }
        }, sites, select)
        if (!mSiteSwitchDialog!!.isShowing) {
            mSiteSwitchDialog!!.show()
        }
    }

    private fun refreshHome() {
        refreshHome(true)
    }

    private fun refreshHomeSort() {
        refreshHomeRec = true
        if (UserFragment.homeHotVodAdapter != null) {
            UserFragment.homeHotVodAdapter!!.setNewData(ArrayList<Movie.Video>())
        }
        val home = ApiConfig.get().homeSourceBean
        SourceViewModel.clearSortCache(home.key)
        refreshHome(false)
    }

    private fun updateHomeRec(absXml: AbsSortXml?) {
        if (!refreshHomeRec) return
        refreshHomeRec = false
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) != 1) return
        if (absXml == null || absXml.videoList == null || UserFragment.homeHotVodAdapter == null) return
        UserFragment.homeHotVodAdapter!!.setNewData(absXml.videoList)
    }

    private fun refreshHome(restart: Boolean) {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            mHandler.post {
                refreshHome(restart)
            }
            return
        }
        if (isActivityUnavailable()) {
            return
        }
        dismissHomeDialogs()
        if (!restart) {
            loadHomeSort(true)
            return
        }
        val intent = Intent(applicationContext, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val bundle = Bundle()
        bundle.putBoolean("useCache", true)
        intent.putExtras(bundle)
        this@HomeActivity.startActivity(intent)
    }

    private fun isActivityUnavailable(): Boolean {
        return isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)
    }

    private fun dismissHomeDialogs() {
        dismissConfigErrorDialog()
        dismissSiteSwitchDialog()
    }

    private fun dismissConfigErrorDialog() {
        if (mConfigErrorDialog != null) {
            if (mConfigErrorDialog!!.isShowing) {
                mConfigErrorDialog!!.dismiss()
            }
            mConfigErrorDialog = null
        }
    }

    private fun dismissSiteSwitchDialog() {
        if (mSiteSwitchDialog != null) {
            if (mSiteSwitchDialog!!.isShowing) {
                mSiteSwitchDialog!!.dismiss()
            }
            mSiteSwitchDialog = null
        }
    }

    private fun refreshEmpty() {
        skipNextUpdate = true
        showSuccess()
        cancelHomeSortLoading()
        clearHomePages()
        sortAdapter!!.setNewData(DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true))
        initViewPager(null)
        tvName!!.clearAnimation()
    }

    private fun cancelHomeSortLoading() {
        homeSortLoading = false
        loadingSourceKey = null
        tvName!!.clearAnimation()
        if (previousHomeSource != null) {
            ApiConfig.get().setSourceBean(previousHomeSource!!)
        }
        if (previousHomeName != null && !previousHomeName!!.isEmpty()) {
            tvName!!.text = previousHomeName
        }
        previousHomeSource = null
        previousHomeName = null
    }

    private fun tvNameAnimation() {
        tvName!!.clearAnimation()
        val blinkAnimation = AlphaAnimation(0.0f, 1.0f)
        blinkAnimation.duration = 500
        blinkAnimation.startOffset = 20
        blinkAnimation.repeatMode = Animation.REVERSE
        blinkAnimation.repeatCount = Animation.INFINITE
        tvName!!.startAnimation(blinkAnimation)
    }

    companion object {
        private const val LONG_PRESS_THRESHOLD = 2000L // 设置长按的阈值，单位是毫秒
    }
}
