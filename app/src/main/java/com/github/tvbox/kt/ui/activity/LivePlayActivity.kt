package com.github.tvbox.kt.ui.activity

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.IntEvaluator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CountDownTimer
import android.os.Handler
import android.text.TextUtils
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseActivity
import com.github.tvbox.kt.bean.Epginfo
import com.github.tvbox.kt.bean.LiveChannelGroup
import com.github.tvbox.kt.bean.LiveChannelItem
import com.github.tvbox.kt.bean.LiveDayListGroup
import com.github.tvbox.kt.bean.LiveEpgDate
import com.github.tvbox.kt.bean.LivePlayerManager
import com.github.tvbox.kt.bean.LiveSettingGroup
import com.github.tvbox.kt.bean.LiveSettingItem
import com.github.tvbox.kt.core.net.OkHttp
import com.github.tvbox.kt.player.controller.LiveController
import com.github.tvbox.kt.ui.adapter.LiveChannelGroupAdapter
import com.github.tvbox.kt.ui.adapter.LiveChannelItemAdapter
import com.github.tvbox.kt.ui.adapter.LiveEpgAdapter
import com.github.tvbox.kt.ui.adapter.LiveEpgDateAdapter
import com.github.tvbox.kt.ui.adapter.LiveSettingGroupAdapter
import com.github.tvbox.kt.ui.adapter.LiveSettingItemAdapter
import com.github.tvbox.kt.ui.adapter.MyEpgAdapter
import com.github.tvbox.kt.ui.dialog.LivePasswordDialog
import com.github.tvbox.kt.ui.tv.widget.ViewObj
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.EpgUtil
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.HistoryHelper
import com.github.tvbox.kt.util.ImgUtil
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.OkGoHelper
import com.github.tvbox.kt.util.PlayerHelper
import com.github.tvbox.kt.util.StringUtils
import com.github.tvbox.kt.util.TxtSubscribe
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.HashMap
import java.util.Locale
import java.util.Objects
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils.safeTimeMs

/**
 * 直播播放页,Kotlin 移植(与旧版 LivePlayActivity 一致)。
 *
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
class LivePlayActivity : BaseActivity() {
    private var mVideoView: VideoView<AbstractPlayer>? = null
    private var switchChannelSnapshotOverlay: View? = null
    private var switchChannelSnapshotImage: ImageView? = null
    private var tvChannelInfo: TextView? = null
    private var tvTime: TextView? = null
    private var tvNetSpeed: TextView? = null
    private var tvTopStatus: View? = null
    private var tvResolution: TextView? = null
    private var tvLeftChannelListLayout: LinearLayout? = null
    private var mChannelGroupView: TvRecyclerView? = null
    private var mLiveChannelView: TvRecyclerView? = null
    private var liveChannelGroupAdapter: LiveChannelGroupAdapter? = null
    private var liveChannelItemAdapter: LiveChannelItemAdapter? = null

    private var tvRightSettingLayout: LinearLayout? = null
    private var mSettingGroupView: TvRecyclerView? = null
    private var mSettingItemView: TvRecyclerView? = null
    private var liveSettingGroupAdapter: LiveSettingGroupAdapter? = null
    private var liveSettingItemAdapter: LiveSettingItemAdapter? = null
    private var liveSettingGroupList: List<LiveSettingGroup> = ArrayList()

    private var mHandler: Handler = Handler()
    private var resolutionInfoRetryCount = 0
    private var resolutionInfoPending = false
    private var exitingLivePlay = false
    private val mLoadEpgRun: Runnable = object : Runnable {
        override fun run() {
            if (channel_Name != null && liveEpgDateAdapter != null && liveEpgDateAdapter!!.selectedIndex >= 0) {
                getEpg(Date())
            }
        }
    }
    private var firstLiveEpgLoad = true

    private val liveChannelGroupList = ArrayList<LiveChannelGroup>()
    private var currentLiveChannelIndex = -1
    private var currentLiveLookBackIndex = -1
    private var currentLiveChangeSourceTimes = 0
    private var allowLiveSwitchPlayer = true
    private var currentLiveChannelItem: LiveChannelItem? = null
    private var pendingLiveRefreshChannelName: String? = null
    private var pendingLiveRefreshSourceIndex = -1
    private var refreshingLiveChannelList = false
    private var liveConfigRequestId = 0
    private val livePlayerManager = LivePlayerManager()
    private val channelGroupPasswordConfirmed = ArrayList<Int>()

    //EPG   by 龍
    private var countDownTimer: CountDownTimer? = null
    //    private CountDownTimer countDownTimerRightTop;
    private var ll_right_top_loading: View? = null
    private var ll_right_top_huikan: View? = null
    private var divLoadEpg: View? = null
    private var divLoadEpgDivider: View? = null
    private var divLoadEpgleft: View? = null
    private var divEpg: LinearLayout? = null
    private var ll_epg: RelativeLayout? = null
    private var tv_channelnum: TextView? = null
    private var tip_chname: TextView? = null
    private var tip_epg1: TextView? = null
    private var tip_epg2: TextView? = null
    private var tv_srcinfo: TextView? = null
    private var tv_curepg_left: TextView? = null
    private var tv_nextepg_left: TextView? = null
    private var myAdapter: MyEpgAdapter? = null
    private var tv_right_top_tipnetspeed: TextView? = null
    private var tv_right_top_channel_name: TextView? = null
    private var tv_right_top_epg_name: TextView? = null
    private var tv_right_top_type: TextView? = null
    private var iv_circle_bg: ImageView? = null
    private var tv_shownum: TextView? = null
    private var txtNoEpg: TextView? = null
    private var iv_back_bg: ImageView? = null

    private var objectAnimator: ObjectAnimator? = null
    var epgStringAddress = ""

    private var mEpgDateGridView: TvRecyclerView? = null
    private var mRightEpgList: TvRecyclerView? = null
    private var liveEpgDateAdapter: LiveEpgDateAdapter? = null
    private var epgListAdapter: LiveEpgAdapter? = null

    private val liveDayList = ArrayList<LiveDayListGroup>()

    //laodao 7day replay
    private var isSHIYI = false
    private var isBack = false
    var timeFormat = SimpleDateFormat("yyyy-MM-dd")

    //kenson
    private var imgLiveIcon: ImageView? = null
    private var liveIconNullBg: FrameLayout? = null
    private var liveIconNullText: TextView? = null
    private var backcontroller: View? = null
    private var countDownTimer3: CountDownTimer? = null
    private val videoWidth = 1920
    private val videoHeight = 1080
    private var tv_currentpos: TextView? = null
    private var tv_duration: TextView? = null
    private var sBar: SeekBar? = null
    private var iv_playpause: View? = null
    private var iv_play: View? = null
    private var show = false

    // 遥控器数字键输入的要切换的频道号码
    private var selectedChannelNumber = 0
    private var tvSelectedChannel: TextView? = null

    //获取EPG并存储 // 百川epg  DIYP epg   51zmt epg ------- 自建EPG格式输出格式请参考 51zmt
    private var epgdata: List<Epginfo> = ArrayList()

    private var mLastChannelGroupIndex = -1
    private var mLastChannelList: List<LiveChannelItem> = ArrayList()

    private val mmHandler = Handler()
    private var mLongPressRunnable: Runnable? = null

    private var catchup: JsonObject? = null
    private var logoUrl: String? = null

    private var loadingLiveConfigOnEnter = false

    private val mPlaySelectedChannel: Runnable = object : Runnable {
        override fun run() {
            val channelNumber = selectedChannelNumber
            selectedChannelNumber = 0
            var currentTotal = 0
            var groupIndex = 0
            var channelIndex = -1
            for (group in liveChannelGroupList) {
                val groupChannelCount = group.getLiveChannels()!!.size
                if (currentTotal + groupChannelCount >= channelNumber) {
                    channelIndex = channelNumber - currentTotal - 1 // 转换为0-based索引
                    break
                }
                currentTotal += groupChannelCount
                groupIndex++
            }
            tvSelectedChannel!!.visibility = View.INVISIBLE
            tvSelectedChannel!!.text = ""
            if (channelIndex >= 0) {
                loadChannelGroupDataAndPlay(groupIndex, channelIndex)
            } else {
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, false)
            }
        }
    }

    private val mFocusCurrentChannelAndShowChannelList: Runnable = object : Runnable {
        override fun run() {
            if (mChannelGroupView!!.isScrolling || mLiveChannelView!!.isScrolling || mChannelGroupView!!.isComputingLayout || mLiveChannelView!!.isComputingLayout) {
                mHandler.postDelayed(this, 100)
            } else {
                tvLeftChannelListLayout!!.visibility = View.VISIBLE
                focusCurrentChannelInMenu()
                val viewObj = ViewObj(tvLeftChannelListLayout!!, tvLeftChannelListLayout!!.layoutParams as ViewGroup.MarginLayoutParams)
                val animator = ObjectAnimator.ofObject(viewObj, "marginLeft", IntEvaluator(), -tvLeftChannelListLayout!!.layoutParams.width, 0)
                animator.duration = 200
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        focusCurrentChannelInMenu()
                        mHandler.removeCallbacks(mHideChannelListRun)
                        mHandler.postDelayed(mHideChannelListRun, postTimeout)
                    }
                })
                animator.start()
            }
        }
    }

    private val mHideChannelListRun: Runnable = object : Runnable {
        override fun run() {
            val params = tvLeftChannelListLayout!!.layoutParams as ViewGroup.MarginLayoutParams
            if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
                val viewObj = ViewObj(tvLeftChannelListLayout!!, params)
                val animator = ObjectAnimator.ofObject(viewObj, "marginLeft", IntEvaluator(), 0, -tvLeftChannelListLayout!!.layoutParams.width)
                animator.duration = 200
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        tvLeftChannelListLayout!!.visibility = View.INVISIBLE
                    }
                })
                animator.start()
            }
        }
    }

    private val mHideChannelInfoRun: Runnable = Runnable {
        tvChannelInfo!!.visibility = View.INVISIBLE
    }

    private val mFocusAndShowSettingGroup: Runnable = object : Runnable {
        override fun run() {
            if (mSettingGroupView!!.isScrolling || mSettingItemView!!.isScrolling || mSettingGroupView!!.isComputingLayout || mSettingItemView!!.isComputingLayout) {
                mHandler.postDelayed(this, 100)
            } else {
                val settingGroupIndex = getDefaultSettingGroupIndex()
                val settingGroupPosition = liveSettingGroupAdapter!!.findPositionByGroupIndex(settingGroupIndex)
                val holder = mSettingGroupView!!.findViewHolderForAdapterPosition(if (settingGroupPosition < 0) 0 else settingGroupPosition)
                if (holder != null) holder.itemView.requestFocus()
                tvRightSettingLayout!!.visibility = View.VISIBLE
                updateTopStatusVisibility()
                val params = tvRightSettingLayout!!.layoutParams as ViewGroup.MarginLayoutParams
                if (tvRightSettingLayout!!.visibility == View.VISIBLE) {
                    val viewObj = ViewObj(tvRightSettingLayout!!, params)
                    val animator = ObjectAnimator.ofObject(viewObj, "marginRight", IntEvaluator(), -tvRightSettingLayout!!.layoutParams.width, livePanelEdgeMargin())
                    animator.duration = 200
                    animator.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            super.onAnimationEnd(animation)
                            mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
                        }
                    })
                    animator.start()
                }
            }
        }
    }

    private val mHideSettingLayoutRun: Runnable = object : Runnable {
        override fun run() {
            val params = tvRightSettingLayout!!.layoutParams as ViewGroup.MarginLayoutParams
            if (tvRightSettingLayout!!.visibility == View.VISIBLE) {
                val viewObj = ViewObj(tvRightSettingLayout!!, params)
                val animator = ObjectAnimator.ofObject(viewObj, "marginRight", IntEvaluator(), params.rightMargin, -tvRightSettingLayout!!.layoutParams.width)
                animator.duration = 200
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        tvRightSettingLayout!!.visibility = View.INVISIBLE
                        updateTopStatusVisibility()
                        liveSettingGroupAdapter!!.setSelectedGroupIndex(-1)
                    }
                })
                animator.start()
            }
        }
    }

    private val mConnectTimeoutChangeSourceRun: Runnable = object : Runnable {
        override fun run() {
            if (switchLivePlayerAndReplay()) {
                return
            }
            currentLiveChangeSourceTimes++
            if (currentLiveChannelItem!!.getSourceNum() == currentLiveChangeSourceTimes) {
                currentLiveChangeSourceTimes = 0
                val groupChannelIndex = getNextChannel(if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) -1 else 1)
                playChannel(groupChannelIndex[0], groupChannelIndex[1], false)
            } else {
                playNextSource()
            }
        }
    }

    private val mHideResolutionInfoRun: Runnable = Runnable {
        if (tvResolution != null) {
            tvResolution!!.visibility = View.GONE
        }
    }

    private val mUpdateResolutionInfoRun: Runnable = object : Runnable {
        override fun run() {
            if (tvResolution == null || mVideoView == null) {
                return
            }
            if (mVideoView!!.currentPlayState != VideoView.STATE_PREPARED
                && mVideoView!!.currentPlayState != VideoView.STATE_BUFFERED
                && mVideoView!!.currentPlayState != VideoView.STATE_PLAYING
            ) {
                retryOrHideResolutionInfo()
                return
            }
            val videoSize = mVideoView!!.videoSize
            if (videoSize != null && videoSize.size >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
                updateResolutionText(videoSize[0], videoSize[1])
                return
            }
            retryOrHideResolutionInfo()
        }
    }

    private val mUpdateTimeRun: Runnable = object : Runnable {
        override fun run() {
            val day = Date()
            @SuppressLint("SimpleDateFormat") val df = SimpleDateFormat("HH:mm")
            tvTime!!.text = df.format(day)
            mHandler.postDelayed(this, 1000)
        }
    }

    private val mUpdateNetSpeedRun: Runnable = object : Runnable {
        override fun run() {
            if (mVideoView == null) return
            val speed = PlayerHelper.getDisplaySpeedBps(mVideoView!!.tcpSpeed, true)
            tvNetSpeed!!.text = speed
            mHandler.postDelayed(this, 1000)
        }
    }

    override val layoutResID: Int
        get() = R.layout.activity_live_play

    override fun init() {
        LivePlayActivity.context = this
        epgStringAddress = getConfiguredEpgAddress()

        setLoadSir(findViewById(R.id.live_root))
        mVideoView = findViewById(R.id.mVideoView)
        switchChannelSnapshotOverlay = findViewById(R.id.switchChannelSnapshotOverlay)
        switchChannelSnapshotImage = findViewById(R.id.switchChannelSnapshotImage)

        tvLeftChannelListLayout = findViewById(R.id.tvLeftChannnelListLayout)
        mChannelGroupView = findViewById(R.id.mGroupGridView)
        mLiveChannelView = findViewById(R.id.mChannelGridView)
        tvRightSettingLayout = findViewById(R.id.tvRightSettingLayout)
        mSettingGroupView = findViewById(R.id.mSettingGroupView)
        mSettingItemView = findViewById(R.id.mSettingItemView)
        tvChannelInfo = findViewById(R.id.tvChannel)
        tvTime = findViewById(R.id.tvTime)
        tvNetSpeed = findViewById(R.id.tvNetSpeed)
        tvTopStatus = findViewById(R.id.tv_top_status)
        tvResolution = findViewById(R.id.tvResolution)

        //EPG  findViewById  by 龍
        tip_chname = findViewById(R.id.tv_channel_bar_name) //底部名称
        tv_channelnum = findViewById(R.id.tv_channel_bottom_number) //底部数字
        tip_epg1 = findViewById(R.id.tv_current_program_time) //底部EPG当前节目信息
        tip_epg2 = findViewById(R.id.tv_next_program_time) //底部EPG当下个节目信息
        tv_srcinfo = findViewById(R.id.tv_source) //线路状态
        tv_curepg_left = findViewById(R.id.tv_current_program) //当前节目
        tv_nextepg_left = findViewById(R.id.tv_next_program) //下一节目
        ll_epg = findViewById(R.id.ll_epg)
        tv_right_top_channel_name = findViewById(R.id.tv_right_top_channel_name)
        tv_right_top_epg_name = findViewById(R.id.tv_right_top_epg_name)
        iv_circle_bg = findViewById(R.id.iv_circle_bg)
        iv_back_bg = findViewById(R.id.iv_back_bg)
        tv_shownum = findViewById(R.id.tv_shownum)
        txtNoEpg = findViewById(R.id.txtNoEpg)
        ll_right_top_loading = findViewById(R.id.ll_right_top_loading)
        ll_right_top_huikan = findViewById(R.id.ll_right_top_huikan)
        divLoadEpg = findViewById(R.id.divLoadEpg)
        divLoadEpgDivider = findViewById(R.id.divLoadEpgDivider)
        divLoadEpgleft = findViewById(R.id.divLoadEpgleft)
        divEpg = findViewById(R.id.divEPG)
        //右上角图片旋转
        objectAnimator = ObjectAnimator.ofFloat(iv_circle_bg!!, "rotation", 360.0f)
        objectAnimator!!.duration = postTimeout
        objectAnimator!!.repeatCount = -1
        objectAnimator!!.start()

        //laodao 7day replay
        mEpgDateGridView = findViewById(R.id.mEpgDateGridView)
        Hawk.put(HawkConfig.NOW_DATE, formatDate.format(Date()))
        day = formatDate.format(Date())
        nowday = Date()

        mRightEpgList = findViewById(R.id.lv_epg)
        //EPG频道名称
        imgLiveIcon = findViewById(R.id.img_live_icon)
        liveIconNullBg = findViewById(R.id.live_icon_null_bg)
        liveIconNullText = findViewById(R.id.live_icon_null_text)
        imgLiveIcon!!.visibility = View.INVISIBLE
        liveIconNullText!!.visibility = View.INVISIBLE
        liveIconNullBg!!.visibility = View.INVISIBLE

        sBar = findViewById(R.id.pb_progressbar)
        tv_currentpos = findViewById(R.id.tv_currentpos)
        backcontroller = findViewById(R.id.backcontroller)
        tv_duration = findViewById(R.id.tv_duration)
        iv_playpause = findViewById(R.id.iv_playpause)
        iv_play = findViewById(R.id.iv_play)

        tvSelectedChannel = findViewById(R.id.tv_selected_channel)

        if (show) {
            backcontroller!!.visibility = View.VISIBLE
            ll_epg!!.visibility = View.GONE
        } else {
            backcontroller!!.visibility = View.GONE
            ll_epg!!.visibility = View.VISIBLE
        }

        iv_play!!.setOnClickListener {
            mVideoView!!.start()
            iv_play!!.visibility = View.INVISIBLE
            countDownTimer!!.start()
            iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
        }

        iv_playpause!!.setOnClickListener {
            if (mVideoView!!.isPlaying) {
                mVideoView!!.pause()
                countDownTimer!!.cancel()
                iv_play!!.visibility = View.VISIBLE
                iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.icon_play) }
            } else {
                mVideoView!!.start()
                iv_play!!.visibility = View.INVISIBLE
                countDownTimer!!.start()
                iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
            }
        }
        sBar!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(arg0: SeekBar?) {}

            override fun onStartTrackingTouch(arg0: SeekBar?) {}

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromuser: Boolean) {
                if (!fromuser) {
                    return
                }
                if (countDownTimer != null) {
                    mVideoView!!.seekTo(progress.toLong())
                    countDownTimer!!.cancel()
                    countDownTimer!!.start()
                }
            }
        })
        sBar!!.setOnKeyListener { _, keycode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keycode == KeyEvent.KEYCODE_DPAD_CENTER || keycode == KeyEvent.KEYCODE_ENTER) {
                    if (mVideoView!!.isPlaying) {
                        mVideoView!!.pause()
                        countDownTimer!!.cancel()
                        iv_play!!.visibility = View.VISIBLE
                        iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.icon_play) }
                    } else {
                        mVideoView!!.start()
                        iv_play!!.visibility = View.INVISIBLE
                        countDownTimer!!.start()
                        iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
                    }
                }
            }
            false
        }
        initEpgDateView()
        initEpgListView()
        initDayList()
        initVideoView()
        initChannelGroupView()
        initLiveChannelView()
        initSettingGroupView()
        initSettingItemView()
        initLiveChannelList()
        initLiveSettingGroupList()
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, true)
    }

    private fun showEpg(date: Date, arrayList: ArrayList<Epginfo>?) {
        val hasEpg = arrayList != null && arrayList.size > 0
        updateEpgPanelState(hasEpg)
        if (hasEpg) {
            epgdata = arrayList!!
            epgListAdapter!!.canBack(currentLiveChannelItem!!.getinclude_back())
            epgListAdapter!!.setNewData(epgdata)
            updateCurrentEpgSelectedIndex()
        }
    }

    private fun findCurrentEpgIndex(epgList: List<Epginfo>?): Int {
        if (epgList == null || epgList.isEmpty()) return -1
        val now = Date()
        for (i in epgList.indices.reversed()) {
            val epgInfo = epgList[i]
            if (epgInfo == null || epgInfo.startdateTime == null || epgInfo.enddateTime == null) {
                continue
            }
            var endDateTime: Date? = epgInfo.enddateTime
            if (!endDateTime!!.after(epgInfo.startdateTime)) {
                val calendar = Calendar.getInstance()
                calendar.time = endDateTime
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                endDateTime = calendar.time
            }
            if (!now.before(epgInfo.startdateTime) && now.before(endDateTime)) {
                return i
            }
        }
        return -1
    }

    private fun getCurrentEpgIndexOrSelected(): Int {
        var epgIndex = findCurrentEpgIndex(epgListAdapter!!.data)
        if (epgIndex >= 0) return epgIndex
        epgIndex = epgListAdapter!!.selectedEpgIndex
        if (epgIndex >= 0 && epgIndex < epgListAdapter!!.data.size) return epgIndex
        return 0
    }

    private fun updateCurrentEpgSelectedIndex() {
        if (epgListAdapter == null || epgListAdapter!!.data == null || epgListAdapter!!.data.isEmpty()) return
        val epgIndex = findCurrentEpgIndex(epgListAdapter!!.data)
        if (epgIndex >= 0) {
            epgListAdapter!!.setSelectedEpgIndex(epgIndex)
        }
    }

    private fun syncCurrentEpgSelection(focus: Boolean) {
        if (mRightEpgList == null || epgListAdapter == null || epgListAdapter!!.data == null || epgListAdapter!!.data.isEmpty()) return
        val epgIndex = getCurrentEpgIndexOrSelected()
        mRightEpgList!!.setSelectedPosition(epgIndex)
        mRightEpgList!!.setSelection(epgIndex)
        epgListAdapter!!.setSelectedEpgIndex(epgIndex)
        if (focus) {
            epgListAdapter!!.setFocusedEpgIndex(epgIndex)
            focusEpgPosition(epgIndex)
        } else {
            mRightEpgList!!.post {
                mRightEpgList!!.smoothScrollToPosition(epgIndex)
            }
        }
    }

    private fun updateEpgPanelState(hasEpg: Boolean) {
        if (hasEpg) {
            txtNoEpg!!.visibility = View.GONE
            mRightEpgList!!.visibility = View.VISIBLE
            divLoadEpgDivider!!.visibility = View.VISIBLE
            if (divEpg!!.visibility != View.VISIBLE) {
                divLoadEpg!!.visibility = View.VISIBLE
                divLoadEpgleft!!.visibility = View.GONE
            }
        } else {
            epgdata = ArrayList()
            epgListAdapter!!.setNewData(epgdata)
            txtNoEpg!!.visibility = View.GONE
            mRightEpgList!!.visibility = View.GONE
            divEpg!!.visibility = View.GONE
            divLoadEpg!!.visibility = View.GONE
            divLoadEpgDivider!!.visibility = View.GONE
            divLoadEpgleft!!.visibility = View.GONE
            mChannelGroupView!!.visibility = View.VISIBLE
        }
    }

    private fun getFirstPartBeforeSpace(str: String?): String? {
        if (str == null || str.isEmpty()) {
            return str
        }
        val spaceIndex = str.indexOf(' ')
        return if (spaceIndex == -1) {
            str
        } else {
            str.substring(0, spaceIndex)
        }
    }

    fun getEpg(date: Date) {
        val channelName = channel_Name!!.getChannelName()
        val channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelName))
        @SuppressLint("SimpleDateFormat") val timeFormat = SimpleDateFormat("yyyy-MM-dd")
        timeFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        var epgTagName = channelNameReal
        if (logoUrl == null || logoUrl!!.isEmpty()) {
            val epgInfo = EpgUtil.getEpgInfo(channelNameReal)
            if (epgInfo != null && !epgInfo[1].isEmpty()) {
                epgTagName = epgInfo[1]
            }
            updateChannelIcon(channelName, if (epgInfo == null) null else epgInfo[0])
        } else if (logoUrl == "false") {
            updateChannelIcon(channelName, null)
        } else {
            val logo = logoUrl!!.replace("{name}", epgTagName)
            updateChannelIcon(channelName, logo)
        }
        val finalEpgTagName = epgTagName
        epgListAdapter!!.canBack(currentLiveChannelItem!!.getinclude_back())
        if (!hasEpgAddress()) {
            updateEpgPanelState(false)
            return
        }
        val epgQueryNames = buildEpgQueryNames(channelName, channelNameReal, finalEpgTagName)
        val url: String
        url = buildEpgUrl(epgStringAddress, epgQueryNames[0], date, timeFormat)

        val savedEpgKey = channelName + "_" + (liveEpgDateAdapter!!.getItem(liveEpgDateAdapter!!.selectedIndex)!!).datePresented
        val cachedEpg = hsEpg.get(savedEpgKey)
        if (cachedEpg != null) {
            showEpg(date, cachedEpg)
            showBottomEpg()
            return
        }
        updateEpgPanelState(false)
        requestEpg(url, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, 0)
    }

    private fun buildEpgUrl(address: String, epgTagName: String, date: Date, timeFormat: SimpleDateFormat): String {
        return if (address.contains("{name}") || address.contains("{date}")) {
            address.replace("{name}", encodeEpgParam(epgTagName)).replace("{date}", timeFormat.format(date))
        } else if (isXmlEpgAddress(address)) {
            address
        } else {
            address + (if (address.contains("?")) "&" else "?") + "ch=" + encodeEpgParam(epgTagName) + "&date=" + timeFormat.format(date)
        }
    }

    private fun encodeEpgParam(value: String?): String {
        return try {
            URLEncoder.encode(value ?: "", "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            value ?: ""
        }
    }

    private fun buildEpgQueryNames(channelName: String, channelNameReal: String, epgTagName: String): ArrayList<String> {
        val queryNames = ArrayList<String>()
        addEpgQueryName(queryNames, epgTagName)
        addEpgQueryName(queryNames, channelNameReal)
        addEpgQueryName(queryNames, normalizeEpgChannelName(getFirstPartBeforeSpace(channelName)))
        addEpgQueryName(queryNames, getFirstPartBeforeSpace(channelName))
        addEpgQueryName(queryNames, channelName)
        if (queryNames.isEmpty()) {
            queryNames.add("")
        }
        return queryNames
    }

    private fun addEpgQueryName(queryNames: ArrayList<String>, name: String?) {
        if (name == null) return
        val trimName = name.trim()
        if (trimName.isEmpty() || queryNames.contains(trimName)) return
        queryNames.add(trimName)
    }

    private fun getConfiguredEpgAddress(): String {
        val userEpgAddress: String? = Hawk.get(HawkConfig.EPG_URL, "")
        if (userEpgAddress != null && userEpgAddress.trim().length >= 5) {
            return userEpgAddress.trim()
        }
        return DEFAULT_EPG_ADDRESS
    }

    private fun hasEpgAddress(): Boolean {
        return epgStringAddress != null && !epgStringAddress.trim().isEmpty()
    }

    private fun requestEpg(
        url: String, date: Date, channelNameReal: String, finalEpgTagName: String, savedEpgKey: String,
        epgQueryNames: ArrayList<String>, timeFormat: SimpleDateFormat, queryIndex: Int
    ) {
        var client = OkGoHelper.getDefaultClient()
        if (client == null) client = OkHttp.client()
        client.newCall(okhttp3.Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                mHandler.post {
                    onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }

            @Throws(IOException::class)
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.code() != 200) {
                    response.close()
                    mHandler.post {
                        onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                    }
                    return
                }
                val body: String
                try {
                    body = if (response.body() != null) response.body()!!.string() else ""
                } finally {
                    response.close()
                }
                mHandler.post {
                    onEpgRequestResponse(body, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }
        })
    }

    private fun onEpgRequestFailure(
        date: Date, channelNameReal: String, finalEpgTagName: String, savedEpgKey: String,
        epgQueryNames: ArrayList<String>, timeFormat: SimpleDateFormat, queryIndex: Int
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        if (requestDefaultEpgOnFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        updateEpgPanelState(false)
        //        showEpg(date, new ArrayList<>());
        //        showBottomEpg();
    }

    private fun onEpgRequestResponse(
        paramString: String?, date: Date, channelNameReal: String, finalEpgTagName: String,
        savedEpgKey: String, epgQueryNames: ArrayList<String>, timeFormat: SimpleDateFormat, queryIndex: Int
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (paramString == null || paramString.trim().isEmpty()) {
            updateEpgPanelState(false)
            return
        }
        LOG.i("echo-epgTagName:" + channelNameReal)
        var arrayList = ArrayList<Epginfo>()
        try {
            if (isXmlEpgResponse(paramString)) {
                arrayList = parseXmlEpg(paramString, finalEpgTagName, date)
            } else if (paramString.contains("epg_data") || paramString.trim().startsWith("{")) {
                arrayList = parseJsonEpg(paramString, date)
            }
        } catch (jSONException: JSONException) {
            jSONException.printStackTrace()
        }
        if (arrayList.isEmpty() && requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        hsEpg.put(savedEpgKey, arrayList)
        if (!isCurrentEpgRequest(savedEpgKey)) return
        showEpg(date, arrayList)
        showBottomEpg()
    }

    private fun requestDefaultEpgOnFailure(
        date: Date, channelNameReal: String, finalEpgTagName: String, savedEpgKey: String,
        epgQueryNames: ArrayList<String>?, timeFormat: SimpleDateFormat, queryIndex: Int
    ): Boolean {
        if (DEFAULT_EPG_ADDRESS == epgStringAddress || epgQueryNames == null || queryIndex >= epgQueryNames.size) {
            return false
        }
        val fallbackUrl = buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames[0], date, timeFormat)
        LOG.i("echo-epg fallback default address")
        requestEpg(fallbackUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, epgQueryNames.size)
        return true
    }

    private fun requestNextEpgQueryName(
        date: Date, channelNameReal: String, finalEpgTagName: String, savedEpgKey: String,
        epgQueryNames: ArrayList<String>?, timeFormat: SimpleDateFormat, queryIndex: Int
    ): Boolean {
        if (!isTemplateEpgAddress(epgStringAddress) || epgQueryNames == null || queryIndex + 1 >= epgQueryNames.size) {
            return false
        }
        val nextIndex = queryIndex + 1
        val nextUrl = buildEpgUrl(epgStringAddress, epgQueryNames[nextIndex], date, timeFormat)
        LOG.i("echo-epg retry query name:" + epgQueryNames[nextIndex])
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex)
        return true
    }

    private fun isTemplateEpgAddress(address: String?): Boolean {
        return address != null && (address.contains("{name}") || address.contains("{date}"))
    }

    private fun isCurrentEpgRequest(savedEpgKey: String): Boolean {
        if (channel_Name == null || liveEpgDateAdapter == null || liveEpgDateAdapter!!.selectedIndex < 0) return false
        val currentEpgKey = channel_Name!!.getChannelName() + "_" + (liveEpgDateAdapter!!.getItem(liveEpgDateAdapter!!.selectedIndex)!!).datePresented
        return savedEpgKey == currentEpgKey
    }

    //显示底部EPG
    private fun isXmlEpgAddress(address: String?): Boolean {
        if (address == null) {
            return false
        }
        var lowerAddress = address.toLowerCase(Locale.ROOT)
        val queryIndex = lowerAddress.indexOf("?")
        if (queryIndex >= 0) {
            lowerAddress = lowerAddress.substring(0, queryIndex)
        }
        return lowerAddress.endsWith(".xml")
    }

    private fun isXmlEpgResponse(response: String?): Boolean {
        if (response == null) {
            return false
        }
        val trimResponse = response.trim()
        return trimResponse.startsWith("<?xml") || trimResponse.startsWith("<tv") || trimResponse.contains("<programme")
    }

    @Throws(JSONException::class)
    private fun parseJsonEpg(response: String, date: Date): ArrayList<Epginfo> {
        val epgList = ArrayList<Epginfo>()
        val jsonObject = JSONObject(response)
        val channelName = jsonObject.optString("channel_name", jsonObject.optString("channel", ""))
        if (isUnavailableEpgText(channelName)) {
            return epgList
        }
        val epgArray = findJsonEpgArray(jsonObject)
        if (epgArray == null) {
            return epgList
        }
        for (i in 0 until epgArray.length()) {
            val item = epgArray.optJSONObject(i)
            if (item == null) {
                continue
            }
            val title = cleanEpgTitle(item.optString("title", item.optString("name", "")))
            if (TextUtils.isEmpty(title) || isUnavailableEpgText(title)) {
                continue
            }
            val startText = item.optString("start", item.optString("start_time", item.optString("starttime", "")))
            val endText = item.optString("end", item.optString("end_time", item.optString("endtime", "")))
            val startDate = parseJsonEpgDate(date, startText)
            var endDate = parseJsonEpgDate(date, endText)
            if (startDate == null || endDate == null) {
                continue
            }
            if (!endDate.after(startDate)) {
                endDate = Date(endDate.time + TimeUnit.DAYS.toMillis(1))
            }
            epgList.add(createXmlEpgInfo(date, title, startDate, endDate, epgList.size))
        }
        return epgList
    }

    private fun findJsonEpgArray(jsonObject: JSONObject): JSONArray? {
        var epgArray = jsonObject.optJSONArray("epg_data")
        if (epgArray != null) return epgArray
        epgArray = jsonObject.optJSONArray("data")
        if (epgArray != null) return epgArray
        epgArray = jsonObject.optJSONArray("list")
        if (epgArray != null) return epgArray
        val dataObject = jsonObject.optJSONObject("data")
        if (dataObject != null) {
            epgArray = dataObject.optJSONArray("epg_data")
            if (epgArray != null) return epgArray
            epgArray = dataObject.optJSONArray("list")
        }
        return epgArray
    }

    private fun parseJsonEpgDate(date: Date, timeText: String?): Date? {
        if (timeText == null || timeText.trim().isEmpty()) {
            return null
        }
        val trimText = timeText.trim()
        val fullPatterns = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        for (pattern in fullPatterns) {
            try {
                val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
                return dateFormat.parse(trimText)
            } catch (ignored: ParseException) {
            }
        }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        dayFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        val dayText = dayFormat.format(date)
        val timePatterns = arrayOf("HH:mm:ss", "HH:mm")
        for (pattern in timePatterns) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd " + pattern, Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
                return dateFormat.parse(dayText + " " + trimText)
            } catch (ignored: ParseException) {
            }
        }
        return null
    }

    private fun cleanEpgTitle(title: String?): String {
        if (title == null) {
            return ""
        }
        return title.replace(" --免费使用", "")
            .replace("--免费使用", "")
            .trim()
    }

    private fun isUnavailableEpgText(text: String?): Boolean {
        return text != null && (text.contains("未提供") || text.contains("暂无"))
    }

    private fun normalizeEpgChannelName(channelName: String?): String {
        if (channelName == null) {
            return ""
        }
        val trimName = channelName.trim()
        val compactName = trimName.replace("-", "").replace(" ", "")
        val cctvMatcher = Pattern.compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|$)").matcher(compactName)
        if (cctvMatcher.matches()) {
            return cctvMatcher.group(1)!!.toUpperCase(Locale.ROOT)
        }
        if (compactName.toUpperCase(Locale.ROOT).startsWith("CCTV")) {
            return compactName.toUpperCase(Locale.ROOT)
        }
        return trimName
    }

    private fun parseXmlEpg(xml: String, channelName: String, date: Date): ArrayList<Epginfo> {
        val epgList = ArrayList<Epginfo>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isIgnoringComments = true
            factory.isCoalescing = true
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (ignored: Exception) {
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(InputSource(StringReader(xml)))
            document.documentElement.normalize()

            val targetName = normalizeEpgChannelName(channelName)
            val channelIds = ArrayList<String>()
            val channelNodes: NodeList = document.getElementsByTagName("channel")
            for (i in 0 until channelNodes.length) {
                val channelNode = channelNodes.item(i)
                if (channelNode.nodeType != Node.ELEMENT_NODE) {
                    continue
                }
                val channelElement = channelNode as Element
                val channelId = channelElement.getAttribute("id")
                if (targetName == normalizeEpgChannelName(channelId)) {
                    channelIds.add(channelId)
                    continue
                }
                val displayNameNodes = channelElement.getElementsByTagName("display-name")
                for (j in 0 until displayNameNodes.length) {
                    val displayName = displayNameNodes.item(j).textContent
                    if (targetName == normalizeEpgChannelName(displayName)) {
                        channelIds.add(channelId)
                        break
                    }
                }
            }

            val dayStart = getDayStart(date)
            val dayEnd = Date(dayStart.time + TimeUnit.DAYS.toMillis(1))
            val programmeNodes: NodeList = document.getElementsByTagName("programme")
            for (i in 0 until programmeNodes.length) {
                val programmeNode = programmeNodes.item(i)
                if (programmeNode.nodeType != Node.ELEMENT_NODE) {
                    continue
                }
                val programmeElement = programmeNode as Element
                val programmeChannel = programmeElement.getAttribute("channel")
                if (!channelIds.contains(programmeChannel) && targetName != normalizeEpgChannelName(programmeChannel)) {
                    continue
                }

                val startDate = parseXmlTvDate(programmeElement.getAttribute("start"))
                val endDate = parseXmlTvDate(programmeElement.getAttribute("stop"))
                if (startDate == null || endDate == null || !endDate.after(startDate)) {
                    continue
                }
                if (!startDate.before(dayEnd) || !endDate.after(dayStart)) {
                    continue
                }

                var title = ""
                val titleNodes = programmeElement.getElementsByTagName("title")
                if (titleNodes.length > 0) {
                    title = titleNodes.item(0).textContent
                }
                epgList.add(createXmlEpgInfo(date, title, startDate, endDate, epgList.size))
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
        return epgList
    }

    @Throws(ParseException::class)
    private fun getDayStart(date: Date): Date {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        dayFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        return dayFormat.parse(dayFormat.format(date))
    }

    private fun parseXmlTvDate(dateText: String?): Date? {
        if (dateText == null || dateText.trim().isEmpty()) {
            return null
        }
        val trimDate = dateText.trim()
        try {
            return SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).parse(trimDate)
        } catch (ignored: ParseException) {
        }
        try {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
            return dateFormat.parse(trimDate)
        } catch (ignored: ParseException) {
        }
        return null
    }

    private fun createXmlEpgInfo(epgDate: Date, title: String, startDate: Date, endDate: Date, index: Int): Epginfo {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val epgInfo = Epginfo(epgDate, title, epgDate, timeFormat.format(startDate), timeFormat.format(endDate), index)
        epgInfo.startdateTime = startDate
        epgInfo.enddateTime = endDate
        epgInfo.start = timeFormat.format(startDate)
        epgInfo.end = timeFormat.format(endDate)
        epgInfo.originStart = epgInfo.start
        epgInfo.originEnd = epgInfo.end
        epgInfo.datestart = Integer.parseInt(epgInfo.start!!.replace(":", ""))
        epgInfo.dateend = Integer.parseInt(epgInfo.end!!.replace(":", ""))
        return epgInfo
    }

    @SuppressLint("SetTextI18n")
    private fun showBottomEpg() {
        if (isSHIYI) {
            return
        }
        if (channel_Name!!.getChannelName() != null) {
            tip_chname!!.text = channel_Name!!.getChannelName()
            tv_channelnum!!.text = "" + channel_Name!!.channelNum
            val tv_current_program_name = findViewById<TextView>(R.id.tv_current_program_name)
            val tv_next_program_name = findViewById<TextView>(R.id.tv_next_program_name)
            setDefaultBottomEpg(tv_current_program_name, tv_next_program_name)
            val savedEpgKey = channel_Name!!.getChannelName() + "_" + (liveEpgDateAdapter!!.getItem(liveEpgDateAdapter!!.selectedIndex)!!).datePresented
            val arrayList = hsEpg.get(savedEpgKey)
            if (arrayList != null) {
                if (arrayList.size > 0) {
                    val date = Date()
                    var size = arrayList.size - 1
                    var hasInfo = false
                    while (size >= 0) {
                        if (date.after(arrayList[size].startdateTime) and date.before(arrayList[size].enddateTime)) {
                            tip_epg1!!.text = arrayList[size].start + "-" + arrayList[size].end
                            tv_current_program_name.text = arrayList[size].title
                            if (size != arrayList.size - 1) {
                                tip_epg2!!.text = arrayList[size + 1].start + "-" + arrayList[size + 1].end
                                tv_next_program_name.text = arrayList[size + 1].title
                            } else {
                                tip_epg2!!.text = arrayList[size].end + "-23:59"
                                tv_next_program_name.text = "精彩节目-暂无节目预告信息"
                            }
                            hasInfo = true
                            break
                        } else {
                            size--
                        }
                    }
                }
                epgListAdapter!!.canBack(currentLiveChannelItem!!.getinclude_back())
                epgListAdapter!!.setNewData(arrayList)
                updateEpgPanelState(arrayList != null && arrayList.size > 0)
            } else {
                updateEpgPanelState(false)
            }

            if (countDownTimer != null) {
                countDownTimer!!.cancel()
            }
            if (tip_epg1!!.text != "暂无信息") {
                ll_right_top_loading!!.visibility = View.VISIBLE
                updateTopStatusVisibility()
                ll_epg!!.visibility = View.VISIBLE
                countDownTimer = object : CountDownTimer(postTimeout, 1000) { //底部epg隐藏时间设定
                    override fun onTick(j: Long) {}

                    override fun onFinish() {
                        ll_right_top_loading!!.visibility = View.GONE
                        ll_right_top_huikan!!.visibility = View.GONE
                        ll_epg!!.visibility = View.GONE
                        updateTopStatusVisibility()
                    }
                }
                countDownTimer!!.start()
            } else {
                ll_right_top_loading!!.visibility = View.GONE
                ll_right_top_huikan!!.visibility = View.GONE
                ll_epg!!.visibility = View.GONE
                updateTopStatusVisibility()
            }
            if (channel_Name == null || channel_Name!!.getSourceNum() <= 0) {
                findViewById<TextView>(R.id.tv_source).text = "1/1"
            } else {
                findViewById<TextView>(R.id.tv_source).text = "线路" + (channel_Name!!.getSourceIndex() + 1) + "/" + channel_Name!!.getSourceNum()
            }
            tv_right_top_channel_name!!.text = channel_Name!!.getChannelName()
            tv_right_top_epg_name!!.text = channel_Name!!.getChannelName()
        }
    }

    private fun setDefaultBottomEpg(currentProgramName: TextView, nextProgramName: TextView) {
        val timeZone = TimeZone.getTimeZone("GMT+8:00")
        val currentStart = Calendar.getInstance(timeZone)
        currentStart.set(Calendar.MINUTE, 0)
        currentStart.set(Calendar.SECOND, 0)
        currentStart.set(Calendar.MILLISECOND, 0)
        val currentEnd = currentStart.clone() as Calendar
        currentEnd.add(Calendar.MINUTE, 59)
        val nextStart = currentEnd.clone() as Calendar
        nextStart.add(Calendar.MINUTE, 1)
        val nextEnd = nextStart.clone() as Calendar
        nextEnd.add(Calendar.MINUTE, 59)

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeFormat.timeZone = timeZone
        tip_epg1!!.text = timeFormat.format(currentStart.time) + "-" + timeFormat.format(currentEnd.time)
        currentProgramName.text = "精彩节目"
        tip_epg2!!.text = timeFormat.format(nextStart.time) + "-" + timeFormat.format(nextEnd.time)
        nextProgramName.text = "暂无节目预告信息"
    }

    private fun updateCurrentChannelIcon() {
        if (channel_Name == null || channel_Name!!.getChannelName() == null) {
            return
        }
        val channelName = channel_Name!!.getChannelName()
        val channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelName))
        var epgTagName = channelNameReal
        var iconUrl: String? = null
        if (channel_Name!!.getChannelLogo().isNotEmpty()) {
            iconUrl = channel_Name!!.getChannelLogo()
        } else if (logoUrl == null || logoUrl!!.isEmpty()) {
            val epgInfo = EpgUtil.getEpgInfo(channelNameReal)
            if (epgInfo != null) {
                iconUrl = epgInfo[0]
                if (!epgInfo[1].isEmpty()) {
                    epgTagName = epgInfo[1]
                }
            }
        } else if (logoUrl != "false") {
            iconUrl = logoUrl!!.replace("{name}", epgTagName)
        }
        updateChannelIcon(channelName, iconUrl)
    }

    @SuppressLint("SetTextI18n")
    private fun updateChannelIcon(channelName: String?, logoUrl: String?) {
        if (channel_Name == null || channel_Name!!.getChannelName() == null || channel_Name!!.getChannelName() != channelName) {
            return
        }
        if (StringUtils.isEmpty(logoUrl)) {
            imgLiveIcon!!.setImageDrawable(null)
            liveIconNullBg!!.visibility = View.VISIBLE
            liveIconNullText!!.visibility = View.VISIBLE
            imgLiveIcon!!.visibility = View.INVISIBLE
            liveIconNullText!!.text = "" + channel_Name!!.channelNum
        } else {
            imgLiveIcon!!.visibility = View.VISIBLE
            ImgUtil.load(DefaultConfig.checkReplaceProxy(logoUrl!!), imgLiveIcon!!, 1, 0, 0, channel_Name!!.getChannelName(), ImageView.ScaleType.CENTER_INSIDE)
            liveIconNullBg!!.visibility = View.INVISIBLE
            liveIconNullText!!.visibility = View.INVISIBLE
        }
    }

    //频道列表
    @SuppressLint("NotifyDataSetChanged")
    fun divLoadEpgRight(view: View?) {
        if (epgListAdapter!!.data == null || epgListAdapter!!.data.isEmpty()) {
            updateEpgPanelState(false)
            return
        }
        mHandler.removeCallbacks(mHideChannelListRun)
        mHandler.postDelayed(mHideChannelListRun, postTimeout)
        mChannelGroupView!!.visibility = View.GONE
        divEpg!!.visibility = View.VISIBLE
        mRightEpgList!!.visibility = View.VISIBLE
        divLoadEpgleft!!.visibility = View.VISIBLE
        divLoadEpg!!.visibility = View.GONE
        liveChannelItemAdapter!!.setFocusedChannelIndex(-1)
        epgListAdapter!!.notifyDataSetChanged()
        mRightEpgList!!.post {
            focusCurrentEpgInMenu()
        }
    }

    //频道列表
    fun divLoadEpgLeft(view: View?) {
        mHandler.removeCallbacks(mHideChannelListRun)
        mHandler.postDelayed(mHideChannelListRun, postTimeout)
        mChannelGroupView!!.visibility = View.VISIBLE
        divEpg!!.visibility = View.GONE
        divLoadEpgleft!!.visibility = View.GONE
        divLoadEpg!!.visibility = View.VISIBLE
        focusCurrentChannelInMenu()
    }

    override fun onBackPressed() {
        if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun)
            mHandler.post(mHideChannelListRun)
        } else if (tvRightSettingLayout!!.visibility == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun)
            mHandler.post(mHideSettingLayoutRun)
        } else if (backcontroller!!.visibility == View.VISIBLE) { //
            backcontroller!!.visibility = View.GONE
        } else if (isBack) {
            isBack = false
            playPreSource()
        } else {
            mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
            mHandler.removeCallbacks(mUpdateNetSpeedRun)
            exitingLivePlay = true
            super.onBackPressed()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun numericKeyDown(digit: Int) {
        selectedChannelNumber = selectedChannelNumber * 10 + digit
        tvSelectedChannel!!.text = Integer.toString(selectedChannelNumber)
        ll_right_top_loading!!.visibility = View.GONE
        ll_right_top_huikan!!.visibility = View.GONE
        tvSelectedChannel!!.visibility = View.VISIBLE

        mHandler.removeCallbacks(mPlaySelectedChannel)
        mHandler.postDelayed(mPlaySelectedChannel, 2500)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(mChannelGroupView)) {
                    focusChannelFromSelectedGroup()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isFocusInView(mLiveChannelView)) {
                    divLoadEpgRight(null)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFocusInView(mRightEpgList)) {
                    divLoadEpgLeft(null)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !isFocusInView(mLiveChannelView) && !isFocusInView(mRightEpgList)) {
                    focusCurrentGroupInMenu()
                    return true
                }
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_HELP) {
                showSettingGroup()
            } else if (!isListOrSettingLayoutVisible()) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP ->
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) playNext() else playPrevious()
                    KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) playPrevious() else playNext()
                    KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (isBack) {
                            showProgressBars(true)
                        } else {
                            playPreSource()
                        }
                    KeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (isBack) {
                            showProgressBars(true)
                        } else {
                            playNextSource()
                        }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {}
                    else -> {
                        val digit = when {
                            keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                            keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                            else -> -1
                        }
                        if (digit >= 0) {
                            numericKeyDown(digit)
                        }
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (!isListOrSettingLayoutVisible()) {
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event.repeatCount == 0) {
                    showChannelList()
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event.repeatCount == 0) {
            mLongPressRunnable = Runnable {
                showSettingGroup() //实现长按调出菜单
            }
            mmHandler.postDelayed(mLongPressRunnable!!, LONG_PRESS_DELAY)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (mLongPressRunnable != null) {
                mmHandler.removeCallbacks(mLongPressRunnable!!)
                mLongPressRunnable = null
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        exitingLivePlay = false
        if (mVideoView != null) {
            mVideoView!!.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (mVideoView != null && !exitingLivePlay) {
            mVideoView!!.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // P2-1:全部使用点都在本 Activity 实例生命周期内(已逐点审计),销毁时置空,解除静态引用对 Activity 的持有
        LivePlayActivity.context = null
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false)
        hideSwitchChannelSnapshot()
        if (mVideoView != null) {
            mVideoView!!.release()
            mVideoView = null
        }
        // 清空全部待处理消息,防止销毁后残留任务(含每秒自续的速度/时间刷新)持有 Activity
        mHandler.removeCallbacksAndMessages(null)
        mmHandler.removeCallbacksAndMessages(null)
    }

    private fun showChannelList() {
        if (tvRightSettingLayout!!.visibility == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun)
            mHandler.post(mHideSettingLayoutRun)
            return
        }
        if (liveChannelGroupList.isEmpty()) return
        if (tvLeftChannelListLayout!!.visibility == View.INVISIBLE) {
            if (currentLiveLookBackIndex > -1) {
                mRightEpgList!!.setSelectedPosition(currentLiveLookBackIndex)
                mRightEpgList!!.post {
                    mRightEpgList!!.smoothScrollToPosition(currentLiveLookBackIndex)
                }
            }
            refreshChannelList(currentChannelGroupIndex)

            mHandler.postDelayed(mFocusCurrentChannelAndShowChannelList, 50)
        } else {
            mHandler.removeCallbacks(mHideChannelListRun)
            mHandler.post(mHideChannelListRun)
        }
    }

    private fun refreshChannelList(currentChannelGroupIndex: Int) {
        val newChannels = getLiveChannels(currentChannelGroupIndex)
        // 2. 判断数据是否变化
        if (currentChannelGroupIndex == mLastChannelGroupIndex
            && isSameData(newChannels, mLastChannelList)
        ) {
            return // 数据未变化，跳过刷新 解决部分直播频道过多时卡顿
        }
        if (currentLiveChannelIndex > -1) {
            mLiveChannelView!!.scrollToPosition(currentLiveChannelIndex)
            mLiveChannelView!!.setSelection(currentLiveChannelIndex)
        }
        mChannelGroupView!!.scrollToPosition(currentChannelGroupIndex)
        mChannelGroupView!!.setSelection(currentChannelGroupIndex)
        mLastChannelGroupIndex = currentChannelGroupIndex
        mLastChannelList = ArrayList(newChannels)
        liveChannelItemAdapter!!.setNewData(newChannels)
    }

    // 对比两个列表内容是否相同
    private fun isSameData(list1: List<LiveChannelItem>?, list2: List<LiveChannelItem>?): Boolean {
        //        return list1.size() == list2.size();
        if (list1 === list2) return true
        if (list1 == null || list2 == null || list1.size != list2.size) return false
        for (i in list1.indices) {
            if (list1[i] != list2[i]) {
                return false
            }
        }
        return true
    }

    private fun isFocusInView(view: View?): Boolean {
        val focused = currentFocus
        return focused != null && view != null && (focused === view || isChildOf(view, focused))
    }

    private fun isChildOf(parent: View, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === parent) return true
            val parentView = current.parent
            if (parentView !is View) return false
            current = parentView
        }
        return false
    }

    private fun focusRecyclerPosition(recyclerView: TvRecyclerView?, position: Int) {
        if (recyclerView == null || position < 0) return
        recyclerView.scrollToPosition(position)
        recyclerView.setSelection(position)
        requestRecyclerItemFocus(recyclerView, position, 0)
    }

    private fun focusCurrentGroupInMenu() {
        if (currentChannelGroupIndex < 0) return
        mChannelGroupView!!.visibility = View.VISIBLE
        divEpg!!.visibility = View.GONE
        divLoadEpgleft!!.visibility = View.GONE
        divLoadEpg!!.visibility = if (epgListAdapter != null && epgListAdapter!!.data != null && !epgListAdapter!!.data.isEmpty()) View.VISIBLE else View.GONE
        liveChannelGroupAdapter!!.setSelectedGroupIndex(currentChannelGroupIndex)
        liveChannelItemAdapter!!.setSelectedChannelIndex(currentLiveChannelIndex)
        liveChannelGroupAdapter!!.setFocusedGroupIndex(currentChannelGroupIndex)
        liveChannelItemAdapter!!.setFocusedChannelIndex(-1)
        epgListAdapter!!.setFocusedEpgIndex(-1)
        mLiveChannelView!!.clearFocus()
        mRightEpgList!!.clearFocus()
        focusRecyclerPosition(mChannelGroupView, currentChannelGroupIndex)
    }

    private fun focusEpgPosition(position: Int) {
        if (mRightEpgList == null || position < 0) return
        val layoutManager = mRightEpgList!!.layoutManager
        if (layoutManager is LinearLayoutManager) {
            val offset = Math.max(0, (mRightEpgList!!.height - resources.getDimensionPixelSize(R.dimen.ts_100)) / 2)
            layoutManager.scrollToPositionWithOffset(position, offset)
        } else {
            mRightEpgList!!.scrollToPosition(position)
        }
        mRightEpgList!!.setSelection(position)
        requestRecyclerItemFocus(mRightEpgList!!, position, 0)
    }

    private fun requestRecyclerItemFocus(recyclerView: TvRecyclerView, position: Int, retryCount: Int) {
        recyclerView.post {
            val holder = recyclerView.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else if (retryCount < 3) {
                requestRecyclerItemFocus(recyclerView, position, retryCount + 1)
            }
        }
    }

    private fun focusCurrentChannelInMenu() {
        if (currentChannelGroupIndex < 0 || currentLiveChannelIndex < 0) return
        if (liveChannelGroupAdapter!!.getSelectedGroupIndex() != currentChannelGroupIndex) {
            liveChannelGroupAdapter!!.setSelectedGroupIndex(currentChannelGroupIndex)
            liveChannelItemAdapter!!.setNewData(getLiveChannels(currentChannelGroupIndex))
            mLastChannelGroupIndex = currentChannelGroupIndex
            mLastChannelList = ArrayList(getLiveChannels(currentChannelGroupIndex))
        }
        liveChannelGroupAdapter!!.setFocusedGroupIndex(-1)
        liveChannelItemAdapter!!.setSelectedChannelIndex(currentLiveChannelIndex)
        liveChannelItemAdapter!!.setFocusedChannelIndex(currentLiveChannelIndex)
        focusRecyclerPosition(mLiveChannelView, currentLiveChannelIndex)
    }

    private fun focusChannelFromSelectedGroup() {
        var groupIndex = liveChannelGroupAdapter!!.getSelectedGroupIndex()
        if (groupIndex < 0) groupIndex = currentChannelGroupIndex
        if (groupIndex < 0 || groupIndex >= liveChannelGroupList.size) return
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1)
            return
        }
        if (mChannelGroupView!!.visibility != View.VISIBLE) return
        val channelIndex = if (groupIndex == currentChannelGroupIndex && currentLiveChannelIndex >= 0) currentLiveChannelIndex else 0
        liveChannelItemAdapter!!.setNewData(getLiveChannels(groupIndex))
        liveChannelGroupAdapter!!.setSelectedGroupIndex(groupIndex)
        liveChannelGroupAdapter!!.setFocusedGroupIndex(-1)
        liveChannelItemAdapter!!.setSelectedChannelIndex(if (groupIndex == currentChannelGroupIndex) currentLiveChannelIndex else -1)
        liveChannelItemAdapter!!.setFocusedChannelIndex(channelIndex)
        focusRecyclerPosition(mLiveChannelView, channelIndex)
    }

    private fun focusCurrentEpgInMenu() {
        if (mRightEpgList == null || epgListAdapter == null || epgListAdapter!!.data == null || epgListAdapter!!.data.isEmpty()) return
        syncCurrentEpgSelection(true)
    }

    private fun showChannelInfo() {
        tvChannelInfo!!.text = String.format(
            Locale.getDefault(), "%d %s %s(%d/%d)", currentLiveChannelItem!!.channelNum,
            currentLiveChannelItem!!.getChannelName(), currentLiveChannelItem!!.getSourceName(),
            currentLiveChannelItem!!.getSourceIndex() + 1, currentLiveChannelItem!!.getSourceNum()
        )

        val lParams = FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (tvRightSettingLayout!!.visibility == View.VISIBLE) {
            lParams.gravity = Gravity.LEFT
            lParams.leftMargin = 60
            lParams.topMargin = 30
        } else {
            lParams.gravity = Gravity.RIGHT
            lParams.rightMargin = 60
            lParams.topMargin = 30
        }
        tvChannelInfo!!.layoutParams = lParams

        tvChannelInfo!!.visibility = View.VISIBLE
        mHandler.removeCallbacks(mHideChannelInfoRun)
        mHandler.postDelayed(mHideChannelInfoRun, 3000)
    }

    private fun initLiveObj() {
        catchup = null
        logoUrl = null
        val position = ApiConfig.get().getLiveGroupIndex()
        val liveGroups: JsonArray? = Hawk.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        if (liveGroups == null || liveGroups.size() == 0 || position < 0 || position >= liveGroups.size()) {
            return
        }
        val livesOBJ = liveGroups[position].asJsonObject
        val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else "0"

        if (livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject) {
            catchup = livesOBJ.getAsJsonObject("catchup")
            LOG.i("echo-catchup :" + catchup.toString())
        }
        if (livesOBJ.has("logo")) {
            logoUrl = livesOBJ.get("logo").asString
        }
        if (type == "3") {
            var pyJar = ""
            if (livesOBJ.has("jar")) {
                pyJar = if (livesOBJ.has("jar")) livesOBJ.get("jar").asString else ""
            } else if (livesOBJ.has("api")) {
                pyJar = if (livesOBJ.has("api")) livesOBJ.get("api").asString else ""
                //                String ext = livesOBJ.has("ext")?livesOBJ.get("ext").getAsJsonObject().toString():"";
                var ext = ""
                ext = if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)) {
                    livesOBJ.get("ext").toString()
                } else {
                    DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                }
                LOG.i("echo-ext:" + ext)
                if (ext.isNotEmpty()) pyJar = pyJar + "?extend=" + ext
            }
            ApiConfig.get().setLiveJar(pyJar)
        }
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return Hawk.get(HawkConfig.LIVE_WEB_HEADER)
    }

    private fun liveChannelHeader(): HashMap<String, String>? {
        if (currentLiveChannelItem == null) return liveWebHeader()
        val header = HashMap<String, String>()
        val liveHeader = liveWebHeader()
        if (liveHeader != null) header.putAll(liveHeader)
        if (currentLiveChannelItem!!.getHeaders() != null) {
            header.putAll(currentLiveChannelItem!!.getHeaders())
        }
        if (currentLiveChannelItem!!.getChannelFormat().isNotEmpty()) {
            header[ExoMediaSourceHelper.HEADER_FORMAT] = currentLiveChannelItem!!.getChannelFormat()
        }
        return if (header.isEmpty()) null else header
    }

    private fun currentChannelHasCatchup(): Boolean {
        return currentLiveChannelItem != null && hasCatchupSource(currentLiveChannelItem!!.getChannelCatchup())
    }

    private fun currentCatchup(): JsonObject? {
        return if (currentChannelHasCatchup()) currentLiveChannelItem!!.getChannelCatchup() else catchup
    }

    private fun getCatchupValue(catchupObj: JsonObject?, key: String): String {
        if (catchupObj == null || !catchupObj.has(key) || catchupObj.get(key).isJsonNull) return ""
        return try {
            catchupObj.get(key).asString
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun hasCatchupSource(catchupObj: JsonObject?): Boolean {
        return !TextUtils.isEmpty(getCatchupValue(catchupObj, "source"))
    }

    private fun canCurrentChannelCatchup(): Boolean {
        if (currentLiveChannelItem == null) return false
        val url = currentLiveChannelItem!!.getUrl()
        val catchupObj = currentCatchup()
        return if (hasCatchupSource(catchupObj)) {
            val regex = getCatchupValue(catchupObj, "regex")
            if (TextUtils.isEmpty(regex)) true
            else try {
                url.contains(regex) || Pattern.compile(regex).matcher(url).find()
            } catch (ignored: Throwable) {
                false
            }
        } else {
            url.contains("/PLTV/")
        }
    }

    private fun buildCatchupUrl(url: String?, epg: Epginfo?): String {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return ""
        val catchupObj = currentCatchup()
        if (hasCatchupSource(catchupObj)) {
            return formatCatchupUrl(url!!, catchupObj, epg)
        }
        if (!url!!.contains("/PLTV/")) return ""
        val source = "?playseek=" + formatCatchupTime(epg.startdateTime, "yyyyMMddHHmmss") +
            "-" + formatCatchupTime(epg.enddateTime, "yyyyMMddHHmmss")
        return appendCatchupUrl(url, "/PLTV/,/TVOD/", source)
    }

    private fun formatCatchupUrl(url: String, catchupObj: JsonObject?, epg: Epginfo): String {
        val source = formatCatchupSource(getCatchupValue(catchupObj, "source"), epg)
        return if ("default".equals(getCatchupValue(catchupObj, "type"), ignoreCase = true)) source
        else appendCatchupUrl(url, getCatchupValue(catchupObj, "replace"), source)
    }

    private fun appendCatchupUrl(url: String, replace: String, sourceIn: String): String {
        var source = sourceIn
        var replayUrl = url
        val parts = replace.split(",".toRegex(), 2).toTypedArray()
        if (parts.size == 2 && !TextUtils.isEmpty(parts[0])) {
            try {
                replayUrl = replayUrl.replace(parts[0].toRegex(), parts[1])
            } catch (ignored: Throwable) {
            }
        }
        val queryIndex = replayUrl.indexOf('?')
        if (queryIndex >= 0 && queryIndex < replayUrl.length - 1) source = source.replace("?", "&")
        return replayUrl + source
    }

    private fun formatCatchupSource(source: String, epg: Epginfo): String {
        val matcher = CATCHUP_TOKEN_PATTERN.matcher(source)
        val result = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(formatCatchupToken(matcher.group(1), epg)))
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun formatCatchupToken(token: String, epg: Epginfo): String {
        val matcher = CATCHUP_TAG_PATTERN.matcher(token)
        if (!matcher.find()) return ""
        val tag = matcher.group(1)
        if (tag!!.startsWith("utcend:")) return (epg.enddateTime!!.time / 1000).toString()
        if (tag.startsWith("utc:")) return (epg.startdateTime!!.time / 1000).toString()
        val bracketIndex = tag.indexOf(')')
        if (tag.startsWith("(b") && bracketIndex >= 0) return formatCatchupTime(epg.startdateTime, tag.substring(bracketIndex + 1))
        if (tag.startsWith("(e") && bracketIndex >= 0) return formatCatchupTime(epg.enddateTime, tag.substring(bracketIndex + 1))
        return ""
    }

    private fun formatCatchupTime(time: Date?, pattern: String): String {
        if ("timestamp" == pattern) return (time!!.time / 1000).toString()
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(time)
        } catch (ignored: IllegalArgumentException) {
            ""
        }
    }

    private fun getCatchupDurationSeconds(epg: Epginfo?): Int {
        if (epg == null || epg.startdateTime == null || epg.enddateTime == null) return 0
        val duration = Math.max(0, epg.enddateTime!!.time - epg.startdateTime!!.time) / 1000
        return if (duration > Integer.MAX_VALUE) Integer.MAX_VALUE else duration.toInt()
    }

    private fun showSwitchChannelSnapshot() {
        if (switchChannelSnapshotImage != null && mVideoView != null) {
            var bitmap: Bitmap? = null
            try {
                bitmap = mVideoView!!.doScreenShot()
            } catch (ignored: Throwable) {
            }
            if (bitmap != null) {
                switchChannelSnapshotImage!!.setImageBitmap(bitmap)
                switchChannelSnapshotImage!!.visibility = View.VISIBLE
            } else {
                switchChannelSnapshotImage!!.setImageBitmap(null)
                switchChannelSnapshotImage!!.visibility = View.GONE
            }
        }
        if (switchChannelSnapshotOverlay != null) {
            switchChannelSnapshotOverlay!!.visibility = View.VISIBLE
        }
    }

    private fun hideSwitchChannelSnapshot() {
        if (switchChannelSnapshotOverlay != null) {
            switchChannelSnapshotOverlay!!.visibility = View.GONE
        }
        if (switchChannelSnapshotImage != null) {
            switchChannelSnapshotImage!!.setImageBitmap(null)
            switchChannelSnapshotImage!!.visibility = View.GONE
        }
    }

    private fun playChannel(channelGroupIndex: Int, liveChannelIndex: Int, changeSource: Boolean): Boolean {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
            || (changeSource && currentLiveChannelItem!!.getSourceNum() == 1)
        ) {
            // showChannelInfo();
            return true
        }
        val groupChannels = getLiveChannels(channelGroupIndex)
        if (groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size) {
            return false
        }
        val showPreviousFrame = currentLiveChannelItem != null && mVideoView != null && mVideoView!!.isPlaying
        val previousLivePlayerType = livePlayerManager.getLivePlayerType()
        allowLiveSwitchPlayer = true
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex
            currentLiveChannelIndex = liveChannelIndex
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex)[currentLiveChannelIndex]
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem!!.getChannelName())
        }

        channel_Name = currentLiveChannelItem
        currentLiveLookBackIndex = -1
        epgListAdapter!!.setSelectedEpgIndex(-1)
        isSHIYI = false
        isBack = false
        if (canCurrentChannelCatchup()) {
            currentLiveChannelItem!!.setinclude_back(true)
        } else {
            currentLiveChannelItem!!.setinclude_back(false)
        }
        updateCurrentChannelIcon()
        showBottomEpg()
        backcontroller!!.visibility = View.GONE
        ll_right_top_huikan!!.visibility = View.GONE
        if (mVideoView != null) {
            if (liveChannelHeader() != null) LOG.i("echo-" + liveChannelHeader().toString())
            val reusePlayer = canReusePlayer(previousLivePlayerType)
            val keepExoFrame = reusePlayer && previousLivePlayerType == 2
            if (showPreviousFrame && !keepExoFrame) {
                showSwitchChannelSnapshot()
            } else {
                hideSwitchChannelSnapshot()
            }
            val liveUrl = currentLiveChannelItem!!.getUrl()
            if (reusePlayer) {
                mVideoView!!.setUrl(liveUrl, liveChannelHeader())
                mVideoView!!.replay(true)
            } else {
                mVideoView!!.release()
                mVideoView!!.setUrl(liveUrl, liveChannelHeader())
                mVideoView!!.start()
            }
            showResolutionAfterChannelSwitch()
        }
        loadEpgAfterChannelStarted()
        return true
    }

    private fun canReusePlayer(previousLivePlayerType: Int): Boolean {
        val currentLivePlayerType = livePlayerManager.getLivePlayerType()
        return mVideoView != null
            && mVideoView!!.currentPlayState != VideoView.STATE_IDLE
            && previousLivePlayerType == currentLivePlayerType
    }

    private fun loadEpgAfterChannelStarted() {
        mHandler.removeCallbacks(mLoadEpgRun)
        if (!hasEpgAddress()) {
            updateEpgPanelState(false)
            return
        }
        if (hasCurrentEpgCache()) {
            firstLiveEpgLoad = false
            return
        }
        if (firstLiveEpgLoad) {
            firstLiveEpgLoad = false
            mHandler.postDelayed(mLoadEpgRun, EPG_LOAD_DELAY)
        } else {
            getEpg(Date())
        }
    }

    private fun hasCurrentEpgCache(): Boolean {
        if (channel_Name == null || liveEpgDateAdapter == null || liveEpgDateAdapter!!.selectedIndex < 0) return false
        val currentEpgKey = channel_Name!!.getChannelName() + "_" + (liveEpgDateAdapter!!.getItem(liveEpgDateAdapter!!.selectedIndex)!!).datePresented
        return hsEpg.get(currentEpgKey) != null
    }

    private fun playNext() {
        if (!isCurrentLiveChannelValid()) return
        val groupChannelIndex = getNextChannel(1)
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false)
    }

    private fun playPrevious() {
        if (!isCurrentLiveChannelValid()) return
        val groupChannelIndex = getNextChannel(-1)
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false)
    }

    fun playPreSource() {
        if (!isCurrentLiveChannelValid()) return
        currentLiveChannelItem!!.preSource()
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    fun playNextSource() {
        if (!isCurrentLiveChannelValid()) return
        currentLiveChannelItem!!.nextSource()
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    //显示设置列表
    private fun showSettingGroup() {
        if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun)
            mHandler.post(mHideChannelListRun)
        }
        if (tvRightSettingLayout!!.visibility == View.INVISIBLE) {
            //重新载入默认状态
            ApiConfig.get().refreshLiveApiHistoryItems()
            loadCurrentSourceList()
            liveSettingGroupAdapter!!.setNewData(getVisibleLiveSettingGroupList())
            liveSettingGroupAdapter!!.setSelectedGroupIndex(-1)
            val settingGroupIndex = getDefaultSettingGroupIndex()
            selectSettingGroup(settingGroupIndex, false)
            val settingGroupPosition = liveSettingGroupAdapter!!.findPositionByGroupIndex(settingGroupIndex)
            mSettingGroupView!!.scrollToPosition(if (settingGroupPosition < 0) 0 else settingGroupPosition)
            var settingItemIndex = if (currentLiveChannelItem == null) 0 else currentLiveChannelItem!!.getSourceIndex()
            if (liveSettingItemAdapter!!.data.isEmpty() || settingItemIndex < 0 || settingItemIndex >= liveSettingItemAdapter!!.data.size) {
                settingItemIndex = 0
            }
            mSettingItemView!!.scrollToPosition(settingItemIndex)
            mHandler.postDelayed(mFocusAndShowSettingGroup, 50)
        } else {
            mHandler.removeCallbacks(mHideSettingLayoutRun)
            mHandler.post(mHideSettingLayoutRun)
        }
    }

    private fun livePanelEdgeMargin(): Int {
        return 0
    }

    //laodao 7天Epg数据绑定和展示
    private fun initEpgListView() {
        mRightEpgList!!.setHasFixedSize(true)
        mRightEpgList!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)
        epgListAdapter = LiveEpgAdapter()
        mRightEpgList!!.setAdapter(epgListAdapter)

        mRightEpgList!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, postTimeout)
            }
        })
        //电视
        mRightEpgList!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                epgListAdapter!!.setFocusedEpgIndex(-1)
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, postTimeout)
                epgListAdapter!!.setFocusedEpgIndex(position)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position == currentLiveLookBackIndex) return
                val date = if (liveEpgDateAdapter!!.selectedIndex < 0) Date() else liveEpgDateAdapter!!.data[liveEpgDateAdapter!!.selectedIndex].dateParamVal
                val selectedData: Epginfo? = epgListAdapter!!.getItem(position)
                if (selectedData == null || selectedData.startdateTime == null || selectedData.enddateTime == null) return
                val now = Date()
                if (Date().compareTo(selectedData.startdateTime) < 0) {
                    return
                }
                if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return
                currentLiveLookBackIndex = position
                epgListAdapter!!.setSelectedEpgIndex(position)
                if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
                    mVideoView!!.release()
                    isSHIYI = false
                    mVideoView!!.setUrl(currentLiveChannelItem!!.getUrl(), liveChannelHeader())
                    mVideoView!!.start()
                    epgListAdapter!!.setShiyiSelection(-1, false, timeFormat.format(date))
                    epgListAdapter!!.notifyDataSetChanged()
                    showProgressBars(false)
                    return
                }
                var shiyiUrl = currentLiveChannelItem!!.getUrl()
                if (now.compareTo(selectedData.startdateTime) < 0) {

                } else if (canCurrentChannelCatchup()) {
                    mHandler.removeCallbacks(mHideChannelListRun)
                    mHandler.postDelayed(mHideChannelListRun, 100)
                    mVideoView!!.release()
                    isSHIYI = true
                    //mCanSeek=true;
                    shiyiUrl = buildCatchupUrl(shiyiUrl, selectedData)
                    if (TextUtils.isEmpty(shiyiUrl)) return
                    LOG.i("echo-回看地址playUrl :" + shiyiUrl)
                    playUrl = shiyiUrl

                    mVideoView!!.setUrl(playUrl, liveChannelHeader())
                    mVideoView!!.start()
                    epgListAdapter!!.setShiyiSelection(position, true, timeFormat.format(date))
                    epgListAdapter!!.notifyDataSetChanged()
                    mRightEpgList!!.setSelectedPosition(position)
                    mRightEpgList!!.post {
                        mRightEpgList!!.smoothScrollToPosition(position)
                    }
                    shiyi_time_c = getCatchupDurationSeconds(selectedData)
                    val lp = iv_play!!.layoutParams
                    lp.width = videoHeight / 7
                    lp.height = videoHeight / 7
                    sBar = findViewById(R.id.pb_progressbar)
                    sBar!!.max = safeTimeMs(shiyi_time_c * 1000L)
                    sBar!!.progress = safeTimeMs(mVideoView!!.currentPosition.toLong())
                    tv_currentpos!!.text = durationToString(safeTimeMs(mVideoView!!.currentPosition.toLong()))
                    tv_duration!!.text = durationToString(safeTimeMs(shiyi_time_c * 1000L))
                    showProgressBars(true)
                    isBack = true
                }
            }
        })

        //手机/模拟器
        epgListAdapter!!.setOnItemClickListener { _, _, position ->
            if (position == currentLiveLookBackIndex) return@setOnItemClickListener
            val date = if (liveEpgDateAdapter!!.selectedIndex < 0) Date() else liveEpgDateAdapter!!.data[liveEpgDateAdapter!!.selectedIndex].dateParamVal
            val selectedData: Epginfo? = epgListAdapter!!.getItem(position)
            if (selectedData == null || selectedData.startdateTime == null || selectedData.enddateTime == null) return@setOnItemClickListener
            val now = Date()
            if (Date().compareTo(selectedData.startdateTime) < 0) {
                return@setOnItemClickListener
            }
            if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return@setOnItemClickListener
            currentLiveLookBackIndex = position
            epgListAdapter!!.setSelectedEpgIndex(position)
            if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
                mVideoView!!.release()
                isSHIYI = false
                mVideoView!!.setUrl(currentLiveChannelItem!!.getUrl(), liveChannelHeader())
                mVideoView!!.start()
                epgListAdapter!!.setShiyiSelection(-1, false, timeFormat.format(date))
                epgListAdapter!!.notifyDataSetChanged()
                showProgressBars(false)
                return@setOnItemClickListener
            }
            var shiyiUrl = currentLiveChannelItem!!.getUrl()
            if (now.compareTo(selectedData.startdateTime) < 0) {

            } else if (canCurrentChannelCatchup()) {
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, 100)
                mVideoView!!.release()
                isSHIYI = true
                //mCanSeek=true;
                shiyiUrl = buildCatchupUrl(shiyiUrl, selectedData)
                if (TextUtils.isEmpty(shiyiUrl)) return@setOnItemClickListener
                LOG.i("echo-回看地址playUrl :" + shiyiUrl)
                playUrl = shiyiUrl
                if (liveChannelHeader() != null) LOG.i("echo-liveWebHeader :" + liveChannelHeader().toString())
                mVideoView!!.setUrl(playUrl, liveChannelHeader())
                mVideoView!!.start()
                epgListAdapter!!.setShiyiSelection(position, true, timeFormat.format(date))
                epgListAdapter!!.notifyDataSetChanged()
                mRightEpgList!!.setSelectedPosition(position)
                mRightEpgList!!.post {
                    mRightEpgList!!.smoothScrollToPosition(position)
                }
                shiyi_time_c = getCatchupDurationSeconds(selectedData)
                val lp = iv_play!!.layoutParams
                lp.width = videoHeight / 7
                lp.height = videoHeight / 7
                sBar = findViewById(R.id.pb_progressbar)
                sBar!!.max = safeTimeMs(shiyi_time_c * 1000L)
                sBar!!.progress = safeTimeMs(mVideoView!!.currentPosition.toLong())
                // long dd = mVideoView.getDuration();
                tv_currentpos!!.text = durationToString(safeTimeMs(mVideoView!!.currentPosition.toLong()))
                tv_duration!!.text = durationToString(safeTimeMs(shiyi_time_c * 1000L))
                showProgressBars(true)
                isBack = true
            }
        }
    }

    //laoda 生成7天回放日期列表数据
    private fun initDayList() {
        liveDayList.clear()
        //        Date firstday = new Date(nowday.getTime() - 2 * 24 * 60 * 60 * 1000);
        //        for (int i = 0; i < 1; i++) {
        //            LiveDayListGroup daylist = new LiveDayListGroup();
        //            Date newday= new Date(firstday.getTime() + i * 24 * 60 * 60 * 1000);
        //            String day = formatDate1.format(newday);
        //            LOG.i("echo-date"+day);
        //            daylist.setGroupIndex(i);
        //            daylist.setGroupName(day);
        //            liveDayList.add(daylist);
        //        }

        val daylist = LiveDayListGroup()
        val newday = Date(nowday.time)
        val day = formatDate1.format(newday)
        LOG.i("echo-date" + day)
        daylist.groupIndex = 0
        daylist.groupName = day
        liveDayList.add(daylist)
    }

    //kens 7天回放数据绑定和展示
    private fun initEpgDateView() {
        //        return;
        mEpgDateGridView!!.setHasFixedSize(true)
        mEpgDateGridView!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)
        liveEpgDateAdapter = LiveEpgDateAdapter()
        val calendar = Calendar.getInstance()
        calendar.time = Date()
        @SuppressLint("SimpleDateFormat") val datePresentFormat = SimpleDateFormat("MM-dd")
        calendar.add(Calendar.DAY_OF_MONTH, 0)
        for (i in 0 until 1) {
            val dateIns = calendar.time
            val epgDate = LiveEpgDate()
            epgDate.index = i
            epgDate.datePresented = datePresentFormat.format(dateIns)
            epgDate.dateParamVal = dateIns
            liveEpgDateAdapter!!.addData(epgDate)
            //            calendar.add(Calendar.DAY_OF_MONTH, -1);
        }
        mEpgDateGridView!!.setAdapter(liveEpgDateAdapter)
        mEpgDateGridView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, postTimeout)
            }
        })

        //        //电视
        //        mEpgDateGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
        //            @Override
        //            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
        //                liveEpgDateAdapter.setFocusedIndex(-1);
        //            }
        //
        //            @Override
        //            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
        //                mHandler.removeCallbacks(mHideChannelListRun);
        //                mHandler.postDelayed(mHideChannelListRun, postTimeout);
        //                liveEpgDateAdapter.setFocusedIndex(position);
        //            }
        //
        //            @Override
        //            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
        //                mHandler.removeCallbacks(mHideChannelListRun);
        //                mHandler.postDelayed(mHideChannelListRun, postTimeout);
        //                liveEpgDateAdapter.setSelectedIndex(position);
        //                getEpg(liveEpgDateAdapter.getData().get(position).getDateParamVal());
        //            }
        //        });
        //
        //        //手机/模拟器
        //        liveEpgDateAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
        //            @Override
        //            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
        //                FastClickCheckUtil.check(view);
        //                mHandler.removeCallbacks(mHideChannelListRun);
        //                mHandler.postDelayed(mHideChannelListRun, postTimeout);
        //                liveEpgDateAdapter.setSelectedIndex(position);
        //                getEpg(liveEpgDateAdapter.getData().get(position).getDateParamVal());
        //            }
        //        });
        liveEpgDateAdapter!!.setSelectedIndex(0)
        mEpgDateGridView!!.visibility = View.GONE
    }

    private fun initVideoView() {
        val controller = LiveController(this)
        controller.setListener(object : LiveController.LiveControlListener {
            override fun singleTap(): Boolean {
                showChannelList()
                return true
            }

            override fun longPress() {
                if (isBack) { //手机换源和显示时移控制栏
                    showProgressBars(true)
                } else {
                    showSettingGroup()
                }
            }

            override fun playStateChanged(playState: Int) {
                mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
                when (playState) {
                    VideoView.STATE_IDLE -> {
                        // 空闲状态：播放器处于空闲，尚未开始播放。一般不需要自动换源。
                    }
                    VideoView.STATE_PAUSED -> {
                        // 暂停状态：播放被暂停，通常是用户操作，不触发自动换源
                    }
                    VideoView.STATE_PREPARED -> {
                        // 准备就绪：播放器已经加载好媒体数据，但尚未开始播放。
                    }
                    VideoView.STATE_BUFFERED, VideoView.STATE_PLAYING -> {
                        // 播放状态：当播放器缓冲完成或正在正常播放时，表明当前源是可用的，
                        hideSwitchChannelSnapshot()
                        if (resolutionInfoPending) {
                            resolutionInfoRetryCount = 0
                            mHandler.removeCallbacks(mUpdateResolutionInfoRun)
                            mHandler.post(mUpdateResolutionInfoRun)
                        }
                        currentLiveChangeSourceTimes = 0
                        allowLiveSwitchPlayer = true
                    }
                    VideoView.STATE_ERROR, VideoView.STATE_PLAYBACK_COMPLETED -> {
                        // 错误或播放结束状态：播放器遇到错误或播放完毕时，
                        // 启动自动换源任务，等待3秒后尝试切换至备选源
                        hideSwitchChannelSnapshot()
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, 3500)
                    }
                    VideoView.STATE_PREPARING, VideoView.STATE_BUFFERING -> {
                        // 正在准备或缓冲状态：表示当前源正在加载中
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L)
                    }
                    else -> {
                        LOG.i("echo-Unexpected live_play state: " + playState)
                    }
                }
            }

            override fun changeSource(direction: Int) {
                if (direction > 0) {
                    if (isBack) { //手机换源和显示时移控制栏
                        showProgressBars(true)
                    } else {
                        playNextSource()
                    }
                } else {
                    playPreSource()
                }
            }
        })
        controller.setCanChangePosition(false)
        controller.setEnableInNormal(true)
        controller.setGestureEnabled(true)
        controller.setDoubleTapTogglePlayEnabled(false)
        mVideoView!!.setVideoController(controller)
        mVideoView!!.setProgressManager(null)
    }

    private fun switchLivePlayerAndReplay(): Boolean {
        if (!allowLiveSwitchPlayer || currentLiveChannelItem == null || mVideoView == null) {
            return false
        }
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        mVideoView!!.release()
        if (!livePlayerManager.switchLivePlayer(mVideoView!!)) {
            allowLiveSwitchPlayer = false
            return false
        }
        //        LOG.i("echo-liveAutoRetry switch player and replay current stream");
        allowLiveSwitchPlayer = false
        val retryUrl = if (isSHIYI && !TextUtils.isEmpty(playUrl)) playUrl else currentLiveChannelItem!!.getUrl()
        mVideoView!!.setUrl(retryUrl, liveChannelHeader())
        mVideoView!!.start()
        return true
    }

    private fun initChannelGroupView() {
        mChannelGroupView!!.setHasFixedSize(true)
        mChannelGroupView!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)

        liveChannelGroupAdapter = LiveChannelGroupAdapter()
        mChannelGroupView!!.setAdapter(liveChannelGroupAdapter)
        mChannelGroupView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, postTimeout)
            }
        })

        //电视
        mChannelGroupView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                selectChannelGroup(position, true, -1)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (isNeedInputPassword(position)) {
                    showPasswordDialog(position, -1)
                }
            }
        })

        //手机/模拟器
        liveChannelGroupAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            selectChannelGroup(position, false, -1)
        }
    }

    private fun selectChannelGroup(groupIndex: Int, focus: Boolean, liveChannelIndex: Int) {
        if (focus && tvLeftChannelListLayout!!.visibility != View.VISIBLE) return
        mLastChannelGroupIndex = groupIndex
        if (focus) {
            liveChannelGroupAdapter!!.setFocusedGroupIndex(groupIndex)
            clearFocusedChannelInMenu()
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter!!.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter!!.setSelectedGroupIndex(groupIndex)
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex)
                return
            }
            if (focus && liveChannelIndex < 0) {
                loadChannelGroupData(groupIndex)
            } else {
                loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
            }
        }
        if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun)
            mHandler.postDelayed(mHideChannelListRun, postTimeout)
        }
    }

    private fun clearFocusedChannelInMenu() {
        if (tvLeftChannelListLayout!!.visibility != View.VISIBLE) return
        if (mLiveChannelView!!.isComputingLayout || mLiveChannelView!!.isScrolling) {
            mLiveChannelView!!.postDelayed({
                clearFocusedChannelInMenu()
            }, 50)
            return
        }
        liveChannelItemAdapter!!.setFocusedChannelIndex(-1)
    }

    private fun initLiveChannelView() {
        mLiveChannelView!!.setHasFixedSize(true)
        mLiveChannelView!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)

        liveChannelItemAdapter = LiveChannelItemAdapter()
        mLiveChannelView!!.setAdapter(liveChannelItemAdapter)
        mLiveChannelView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, postTimeout)
            }
        })

        //电视
        mLiveChannelView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position < 0) return
                mHandler.removeCallbacks(mHideChannelListRun)
                mHandler.postDelayed(mHideChannelListRun, postTimeout)
                liveChannelGroupAdapter!!.setFocusedGroupIndex(-1)
                liveChannelItemAdapter!!.setFocusedChannelIndex(position)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                clickLiveChannel(position)
            }
        })

        //手机/模拟器
        liveChannelItemAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            liveChannelItemAdapter!!.setSelectedChannelIndex(position)
            clickLiveChannel(position)
        }
    }

    private fun clickLiveChannel(position: Int) {
        if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun)
            mHandler.postDelayed(mHideChannelListRun, postTimeout)
        }
        playChannel(liveChannelGroupAdapter!!.getSelectedGroupIndex(), position, false)
    }

    private fun initSettingGroupView() {
        mSettingGroupView!!.setHasFixedSize(true)
        mSettingGroupView!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)

        liveSettingGroupAdapter = LiveSettingGroupAdapter()
        mSettingGroupView!!.setAdapter(liveSettingGroupAdapter)
        mSettingGroupView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mHandler.removeCallbacks(mHideSettingLayoutRun)
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
            }
        })

        //电视
        mSettingGroupView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                mHandler.removeCallbacks(mHideSettingLayoutRun)
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
                selectVisibleSettingGroup(position, true)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
        })

        //手机/模拟器
        liveSettingGroupAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            selectVisibleSettingGroup(position, false)
        }
    }

    private fun selectVisibleSettingGroup(position: Int, focus: Boolean) {
        if (position < 0 || position >= liveSettingGroupAdapter!!.data.size) return
        selectSettingGroup(liveSettingGroupAdapter!!.data[position].groupIndex, focus)
    }

    private fun selectSettingGroup(position: Int, focus: Boolean) {
        if (focus) {
            liveSettingGroupAdapter!!.setFocusedGroupIndex(position)
            liveSettingItemAdapter!!.setFocusedItemIndex(-1)
        }
        if (position == liveSettingGroupAdapter!!.selectedGroupIndex || position < 0 || position >= liveSettingGroupList.size) return

        liveSettingGroupAdapter!!.setSelectedGroupIndex(position)
        liveSettingItemAdapter!!.setNewData(liveSettingGroupList[position].liveSettingItems)

        when (position) {
            0 -> if (currentLiveChannelItem != null
                && currentLiveChannelItem!!.getSourceIndex() >= 0
                && currentLiveChannelItem!!.getSourceIndex() < liveSettingItemAdapter!!.data.size
            ) {
                liveSettingItemAdapter!!.selectItem(currentLiveChannelItem!!.getSourceIndex(), true, false)
            }
            1 -> liveSettingItemAdapter!!.selectItem(livePlayerManager.getLivePlayerScale(), true, true)
            2 -> liveSettingItemAdapter!!.selectItem(livePlayerManager.getLivePlayerType(), true, true)
            6 -> liveSettingItemAdapter!!.selectItem(getCurrentLiveApiHistoryIndex(), true, true)
        }
        var scrollToPosition = liveSettingItemAdapter!!.getSelectedItemIndex()
        if (scrollToPosition < 0) scrollToPosition = 0
        mSettingItemView!!.scrollToPosition(scrollToPosition)
        mHandler.removeCallbacks(mHideSettingLayoutRun)
        mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
    }

    private fun initSettingItemView() {
        mSettingItemView!!.setHasFixedSize(true)
        mSettingItemView!!.layoutManager = V7LinearLayoutManager(this.mContext, 1, false)

        liveSettingItemAdapter = LiveSettingItemAdapter()
        mSettingItemView!!.setAdapter(liveSettingItemAdapter)
        mSettingItemView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mHandler.removeCallbacks(mHideSettingLayoutRun)
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
            }
        })

        //电视
        mSettingItemView!!.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position < 0) return
                liveSettingGroupAdapter!!.setFocusedGroupIndex(-1)
                liveSettingItemAdapter!!.setFocusedItemIndex(position)
                mHandler.removeCallbacks(mHideSettingLayoutRun)
                mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                clickSettingItem(position)
            }
        })

        //手机/模拟器
        liveSettingItemAdapter!!.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            clickSettingItem(position)
        }
    }

    private fun clickSettingItem(position: Int) {
        val settingGroupIndex = liveSettingGroupAdapter!!.selectedGroupIndex
        if (settingGroupIndex >= 0 && settingGroupIndex < 3 && !isCurrentLiveChannelValid()) {
            return
        }
        if (settingGroupIndex < 4) {
            if (position == liveSettingItemAdapter!!.getSelectedItemIndex()) return
            liveSettingItemAdapter!!.selectItem(position, true, true)
        }
        when (settingGroupIndex) {
            0 //线路切换
            -> {
                if (position >= 0 && position < currentLiveChannelItem!!.getSourceNum()) {
                    currentLiveChannelItem!!.setSourceIndex(position)
                    playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
                }
            }
            1 //画面比例
            -> livePlayerManager.changeLivePlayerScale(mVideoView!!, position)
            2 //播放解码
            -> {
                mVideoView!!.release()
                livePlayerManager.changeLivePlayerType(mVideoView!!, position)
                mVideoView!!.setUrl(currentLiveChannelItem!!.getUrl(), liveChannelHeader())
                mVideoView!!.start()
            }
            3 //超时换源
            -> Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position)
            4 //偏好设置
            -> {
                var select = false
                when (position) {
                    0 -> {
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)
                        Hawk.put(HawkConfig.LIVE_SHOW_TIME, select)
                        showTime()
                    }
                    1 -> {
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
                        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, select)
                        showNetSpeed()
                    }
                    2 -> {
                        select = !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)
                        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, select)
                    }
                    3 -> {
                        select = !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)
                        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, select)
                    }
                }
                liveSettingItemAdapter!!.selectItem(position, select, false)
            }
            5 //多源切换
            -> {
                //TODO
                if (position != ApiConfig.get().getLiveGroupIndex()) {
                    val currentChannelName = getPreferredLiveRefreshChannelName()
                    val currentSourceIndex = getPreferredLiveRefreshSourceIndex()
                    val liveGroups: JsonArray? = Hawk.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
                    if (liveGroups != null && position < liveGroups.size()) {
                        liveConfigRequestId++
                        val livesOBJ = liveGroups[position].asJsonObject
                        liveSettingItemAdapter!!.selectItem(position, true, true)
                        ApiConfig.get().setLiveGroupIndex(position)
                        ApiConfig.get().loadLiveApi(livesOBJ)
                        if (ApiConfig.get().getChannelGroupList().isEmpty()) {
                            if (mVideoView != null) mVideoView!!.release()
                            setEmptyLiveChannelList(false)
                        } else {
                            refreshLiveChannelListAndPlay(currentChannelName, currentSourceIndex)
                        }
                    }
                }
            }
            6 -> { //配置切换
                val history: ArrayList<String> = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList())
                if (!history.isEmpty() && position >= 0 && position < history.size) {
                    val value = history[position]
                    val oldLiveApi = Hawk.get(HawkConfig.LIVE_API_URL, "")
                    val configChannelName = getPreferredLiveRefreshChannelName()
                    val configSourceIndex = getPreferredLiveRefreshSourceIndex()
                    liveSettingItemAdapter!!.selectItem(position, true, true)
                    if (value != oldLiveApi) {
                        liveConfigRequestId++
                        val requestId = liveConfigRequestId
                        Hawk.put(HawkConfig.LIVE_API_URL, value)
                        HistoryHelper.setLiveApiHistory(value)
                        ApiConfig.get().refreshLiveApiHistoryItems()
                        ApiConfig.get().loadLiveConfig(false, object : ApiConfig.LoadConfigCallback {
                            override fun success() {
                                mHandler.post {
                                    if (requestId != liveConfigRequestId || isFinishing) return@post
                                    refreshLiveChannelListAndPlay(configChannelName, configSourceIndex)
                                }
                            }

                            override fun error(msg: String) {
                                mHandler.post {
                                    if (requestId != liveConfigRequestId || isFinishing) return@post
                                    if (mVideoView != null) mVideoView!!.release()
                                    ApiConfig.get().refreshLiveApiHistoryItems()
                                    setEmptyLiveChannelList(false)
                                    Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            }

                            override fun notice(msg: String) {
                                mHandler.post {
                                    if (requestId != liveConfigRequestId || isFinishing) return@post
                                    Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                    }
                }
            }
        }
        mHandler.removeCallbacks(mHideSettingLayoutRun)
        mHandler.postDelayed(mHideSettingLayoutRun, postTimeout)
    }

    private fun getPreferredLiveRefreshChannelName(): String {
        return if (currentLiveChannelItem != null) currentLiveChannelItem!!.getChannelName() else Hawk.get(HawkConfig.LIVE_CHANNEL, "")
    }

    private fun getPreferredLiveRefreshSourceIndex(): Int {
        return if (currentLiveChannelItem != null) currentLiveChannelItem!!.getSourceIndex() else -1
    }

    private fun refreshLiveChannelListAndPlay(channelName: String?, sourceIndex: Int) {
        refreshingLiveChannelList = true
        pendingLiveRefreshChannelName = channelName
        pendingLiveRefreshSourceIndex = sourceIndex
        currentLiveLookBackIndex = -1
        currentLiveChangeSourceTimes = 0
        allowLiveSwitchPlayer = true
        channelGroupPasswordConfirmed.clear()
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        mHandler.removeCallbacks(mLoadEpgRun)
        hideSwitchChannelSnapshot()
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout!!.visibility = View.INVISIBLE
        if (tvRightSettingLayout != null) tvRightSettingLayout!!.visibility = View.INVISIBLE
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter!!.clearGroupState()
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter!!.setFocusedChannelIndex(-1)
            liveChannelItemAdapter!!.setSelectedChannelIndex(-1)
            liveChannelItemAdapter!!.setNewData(ArrayList<LiveChannelItem>())
        }
        initLiveChannelList()
        initLiveSettingGroupList()
    }

    private fun getCurrentLiveApiHistoryIndex(): Int {
        val history: ArrayList<String> = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList())
        if (history.isEmpty()) return -1
        val current = Hawk.get(HawkConfig.LIVE_API_URL, "")
        val idx = history.indexOf(current)
        return if (idx >= 0) idx else -1
    }

    private fun initLiveChannelList() {
        if (ApiConfig.get().shouldReloadLiveConfig()) {
            loadLiveConfigOnEnter()
            return
        }
        val list = ApiConfig.get().getChannelGroupList()
        if (list.isEmpty()) {
            loadLiveConfigOnEnter()
            return
        }
        initLiveObj()
        if (list.size == 1 && list[0].groupName!!.startsWith("http://127.0.0.1")) {
            loadProxyLives(list[0].groupName!!)
        } else {
            applyLiveChannelGroups(list)
        }
    }

    private fun loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return
        loadingLiveConfigOnEnter = true
        showLoading()
        ApiConfig.get().loadLiveConfig(true, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                mHandler.post {
                    loadingLiveConfigOnEnter = false
                    initLiveChannelList()
                    initLiveSettingGroupList()
                }
            }

            override fun error(msg: String) {
                mHandler.post {
                    loadingLiveConfigOnEnter = false
                    setEmptyLiveChannelList()
                }
            }

            override fun notice(msg: String) {
                mHandler.post {
                    Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun loadProxyLives(urlIn: String) {
        var url = urlIn
        try {
            val parsedUrl = Uri.parse(url)
            url = String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (th: Throwable) {
            if (!url.startsWith("http://127.0.0.1")) {
                setEmptyLiveChannelList()
                return
            }
        }
        if (!isValidLiveProxyUrl(url)) {
            setEmptyLiveChannelList()
            return
        }
        if (!refreshingLiveChannelList) {
            showLoading()
        }

        LOG.i("echo-live-url:" + url)

        if (url.contains(".py") || url.contains(".js")) {
            val finalUrl = url
            val waitResponse = Runnable {
                val executor = Executors.newSingleThreadExecutor()
                // 无论成功、失败还是提前返回，都必须关闭线程池，避免线程泄漏
                try {
                    val future = executor.submit(Callable<String> {
                        val sp = ApiConfig.get().getLiveCSP(finalUrl)
                        val json = sp.liveContent(finalUrl)
                        //                            LOG.i("echo--loadProxyLives-json--"+json);
                        json
                    })
                    var sortJson: String? = null
                    try {
                        sortJson = future.get(ApiConfig.get().getLiveConnectTimeoutSeconds().toLong(), TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        e.printStackTrace()
                        future.cancel(true)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    } catch (e: ExecutionException) {
                        e.printStackTrace()
                    }
                    if (sortJson == null || sortJson.isEmpty()) {
                        // 频道列表为空时，使用默认播放列表
                        mHandler.post {
                            setEmptyLiveChannelList()
                        }
                        return@Runnable
                    }
                    val livesArray = TxtSubscribe.parseToJsonArray(sortJson)

                    ApiConfig.get().loadLives(livesArray)
                    val list = ApiConfig.get().getChannelGroupList()
                    if (list.isEmpty()) {
                        mHandler.post {
                            setEmptyLiveChannelList()
                        }
                        return@Runnable
                    }
                    val loadedGroups = ArrayList(list)

                    mHandler.post {
                        applyLiveChannelGroups(loadedGroups)
                    }
                } catch (th: Throwable) {
                    th.printStackTrace()
                } finally {
                    executor.shutdownNow()
                }
            }
            // 外层改为单次守护线程执行，任务结束线程随之释放，不再泄漏线程池
            val waitThread = Thread(waitResponse)
            waitThread.isDaemon = true
            waitThread.start()
        } else {
            OkGo.get<String>(url).execute(object : AbsCallback<String>() {
                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    assert(response.body() != null)
                    return response.body()!!.string()
                }

                override fun onSuccess(response: Response<String>) {
                    val livesArray = TxtSubscribe.parseToJsonArray(response.body())

                    ApiConfig.get().loadLives(livesArray)
                    val list = ApiConfig.get().getChannelGroupList()
                    if (list.isEmpty()) {
                        mHandler.post {
                            setEmptyLiveChannelList()
                        }
                        return
                    }
                    val loadedGroups = ArrayList(list)

                    mHandler.post {
                        applyLiveChannelGroups(loadedGroups)
                    }
                }

                override fun onError(response: Response<String>) {
                    mHandler.post {
                        setEmptyLiveChannelList()
                    }
                }
            })
        }
    }

    private fun isValidLiveProxyUrl(url: String?): Boolean {
        if (TextUtils.isEmpty(url)) return false
        val lowerUrl = url!!.trim().toLowerCase(Locale.US)
        return lowerUrl.startsWith("http://")
            || lowerUrl.startsWith("https://")
            || lowerUrl.startsWith("rtsp://")
            || lowerUrl.startsWith("rtmp://")
            || lowerUrl.startsWith("rtp://")
    }

    private fun applyLiveChannelGroups(groups: List<LiveChannelGroup>) {
        liveChannelGroupList.clear()
        liveChannelGroupList.addAll(groups)
        showSuccess()
        initLiveState()
    }

    private fun initLiveState() {
        refreshingLiveChannelList = false
        val lastChannelName = pendingLiveRefreshChannelName ?: Hawk.get(HawkConfig.LIVE_CHANNEL, "")
        val sourceIndex = pendingLiveRefreshSourceIndex
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1

        var lastChannelGroupIndex = -1
        var lastLiveChannelIndex = -1
        var lastLiveChannelItem: LiveChannelItem? = null
        for (liveChannelGroup in liveChannelGroupList) {
            val groupChannels = liveChannelGroup.getLiveChannels()
            if (groupChannels == null || groupChannels.isEmpty()) {
                continue
            }
            for (liveChannelItem in groupChannels) {
                if (liveChannelItem.getChannelName() == lastChannelName) {
                    lastChannelGroupIndex = liveChannelGroup.groupIndex
                    lastLiveChannelIndex = liveChannelItem.channelIndex
                    lastLiveChannelItem = liveChannelItem
                    break
                }
            }
            if (lastChannelGroupIndex != -1) break
        }
        if (lastChannelGroupIndex == -1) {
            val cctv1Channel = getFirstChannelByName("CCTV1")
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0]
                lastLiveChannelIndex = cctv1Channel[1]
            } else {
                lastChannelGroupIndex = getFirstNoPasswordChannelGroup()
                if (lastChannelGroupIndex == -1) lastChannelGroupIndex = 0
                lastLiveChannelIndex = 0
            }
        }
        if (lastLiveChannelItem != null && sourceIndex >= 0 && lastLiveChannelItem!!.getSourceNum() > 0) {
            lastLiveChannelItem!!.setSourceIndex(Math.min(sourceIndex, lastLiveChannelItem.getSourceNum() - 1))
        }

        livePlayerManager.init(mVideoView!!)
        showTime()
        showNetSpeed()
        tvLeftChannelListLayout!!.visibility = View.INVISIBLE
        tvRightSettingLayout!!.visibility = View.INVISIBLE
        updateTopStatusVisibility()

        liveChannelGroupAdapter!!.clearGroupState()
        liveChannelGroupAdapter!!.setNewData(ArrayList(liveChannelGroupList))
        currentLiveChannelIndex = -1
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex)
    }

    private fun isListOrSettingLayoutVisible(): Boolean {
        return tvLeftChannelListLayout!!.visibility == View.VISIBLE || tvRightSettingLayout!!.visibility == View.VISIBLE
    }

    private fun hasCurrentLiveChannelSource(): Boolean {
        return currentLiveChannelItem != null
            && currentLiveChannelItem!!.getChannelUrls() != null
            && currentLiveChannelItem!!.getSourceNum() > 0
            && currentLiveChannelItem!!.getSourceIndex() >= 0
            && currentLiveChannelItem!!.getSourceIndex() < currentLiveChannelItem!!.getChannelUrls()!!.size
    }

    private fun getDefaultSettingGroupIndex(): Int {
        return if (hasCurrentLiveChannelSource()) 0 else if (liveSettingGroupList.size > 6) 6 else 0
    }

    private fun getVisibleLiveSettingGroupList(): ArrayList<LiveSettingGroup> {
        val visibleGroups = ArrayList<LiveSettingGroup>()
        val showChannelOptions = hasCurrentLiveChannelSource()
        for (group in liveSettingGroupList) {
            val groupIndex = group.groupIndex
            if (!showChannelOptions && groupIndex >= 0 && groupIndex <= 2) continue
            visibleGroups.add(group)
        }
        return visibleGroups
    }

    private fun initLiveSettingGroupList() {
        liveSettingGroupList = ApiConfig.get().getLiveSettingGroupList()
        if (liveSettingGroupList.size < 7) return
        liveSettingGroupList[3].liveSettingItems!![Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)].setItemSelected(true)
        liveSettingGroupList[4].liveSettingItems!![0].setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_TIME, false))
        liveSettingGroupList[4].liveSettingItems!![1].setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false))
        liveSettingGroupList[4].liveSettingItems!![2].setItemSelected(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
        liveSettingGroupList[4].liveSettingItems!![3].setItemSelected(Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false))
        val liveGroupIndex = ApiConfig.get().getLiveGroupIndex()
        if (liveGroupIndex >= 0 && liveGroupIndex < liveSettingGroupList[5].liveSettingItems!!.size) {
            liveSettingGroupList[5].liveSettingItems!![liveGroupIndex].setItemSelected(true)
        }
    }

    private fun loadCurrentSourceList() {
        val liveSettingItemList = ArrayList<LiveSettingItem>()
        if (currentLiveChannelItem != null && currentLiveChannelItem!!.getChannelSourceNames() != null) {
            val currentSourceNames = currentLiveChannelItem!!.getChannelSourceNames()
            for (j in currentSourceNames!!.indices) {
                val liveSettingItem = LiveSettingItem()
                liveSettingItem.itemIndex = j
                liveSettingItem.itemName = currentSourceNames[j]
                liveSettingItemList.add(liveSettingItem)
            }
        }
        liveSettingGroupList[0].liveSettingItems = liveSettingItemList
    }

    private fun showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true
        resolutionInfoRetryCount = 0
        if (tvResolution != null) {
            tvResolution!!.text = ""
            tvResolution!!.visibility = View.GONE
        }
        mHandler.removeCallbacks(mHideResolutionInfoRun)
        mHandler.removeCallbacks(mUpdateResolutionInfoRun)
        mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
    }

    private fun showResolutionSetting() {
        mHandler.removeCallbacks(mHideResolutionInfoRun)
        mHandler.removeCallbacks(mUpdateResolutionInfoRun)
        if (Hawk.get(HawkConfig.LIVE_SHOW_RESOLUTION, false)) {
            resolutionInfoPending = true
            resolutionInfoRetryCount = 0
            if (tvResolution != null) {
                tvResolution!!.visibility = View.GONE
                mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
            }
        } else {
            showResolutionAfterChannelSwitch()
        }
    }

    private fun updateResolutionText(width: Int, height: Int) {
        resolutionInfoPending = false
        tvResolution!!.text = "$width x $height"
        tvResolution!!.visibility = View.VISIBLE
        mHandler.removeCallbacks(mHideResolutionInfoRun)
    }

    private fun retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
        } else {
            tvResolution!!.visibility = View.GONE
        }
    }

    fun showTime() {
        if (Hawk.get(HawkConfig.LIVE_SHOW_TIME, false) && !isTopStatusObstructed()) {
            mHandler.post(mUpdateTimeRun)
            tvTime!!.visibility = View.VISIBLE
        } else {
            mHandler.removeCallbacks(mUpdateTimeRun)
            tvTime!!.visibility = View.GONE
        }
    }

    private fun showNetSpeed() {
        //        tv_right_top_tipnetspeed.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mUpdateNetSpeedRun)
        if (Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false) && !isTopStatusObstructed()) {
            mHandler.post(mUpdateNetSpeedRun)
            tvNetSpeed!!.visibility = View.VISIBLE
        } else {
            tvNetSpeed!!.visibility = View.GONE
        }
    }

    private fun isTopStatusObstructed(): Boolean {
        return (tvRightSettingLayout != null && tvRightSettingLayout!!.visibility == View.VISIBLE)
            || (ll_right_top_loading != null && ll_right_top_loading!!.visibility == View.VISIBLE)
            || (ll_right_top_huikan != null && ll_right_top_huikan!!.visibility == View.VISIBLE)
    }

    private fun updateTopStatusVisibility() {
        if (isTopStatusObstructed()) {
            tvTopStatus!!.visibility = View.GONE
        } else {
            showTime()
            showNetSpeed()
            tvTopStatus!!.visibility = if (tvTime!!.visibility == View.VISIBLE
                || tvNetSpeed!!.visibility == View.VISIBLE
            ) View.VISIBLE else View.GONE
        }
    }

    private fun showPasswordDialog(groupIndex: Int, liveChannelIndex: Int) {
        if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) mHandler.removeCallbacks(mHideChannelListRun)

        val dialog = LivePasswordDialog(this)
        dialog.setOnListener(object : LivePasswordDialog.OnListener {
            override fun onChange(password: String) {
                if (password == liveChannelGroupList[groupIndex].groupPassword) {
                    channelGroupPasswordConfirmed.add(groupIndex)
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
                } else {
                    Toast.makeText(KtApp.instance(), "密码错误", Toast.LENGTH_SHORT).show()
                }

                if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) mHandler.postDelayed(mHideChannelListRun, postTimeout)
            }

            override fun onCancel() {
                if (tvLeftChannelListLayout!!.visibility == View.VISIBLE) {
                    val groupIndex = liveChannelGroupAdapter!!.getSelectedGroupIndex()
                    liveChannelItemAdapter!!.setNewData(getLiveChannels(groupIndex))
                }
            }
        })
        dialog.show()
    }

    private fun loadChannelGroupDataAndPlay(groupIndex: Int, liveChannelIndex: Int) {
        liveChannelGroupAdapter!!.setSelectedGroupIndex(groupIndex)
        loadChannelGroupData(groupIndex)

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex)
            mChannelGroupView!!.scrollToPosition(groupIndex)
            mLiveChannelView!!.scrollToPosition(liveChannelIndex)
        }
    }

    private fun loadChannelGroupData(groupIndex: Int) {
        liveChannelItemAdapter!!.setNewData(getLiveChannels(groupIndex))
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1) mLiveChannelView!!.scrollToPosition(currentLiveChannelIndex)
            liveChannelItemAdapter!!.setSelectedChannelIndex(currentLiveChannelIndex)
        } else {
            mLiveChannelView!!.scrollToPosition(0)
            liveChannelItemAdapter!!.setSelectedChannelIndex(-1)
        }
    }

    private fun isNeedInputPassword(groupIndex: Int): Boolean {
        return !liveChannelGroupList[groupIndex].groupPassword.isNullOrEmpty()
            && !isPasswordConfirmed(groupIndex)
    }

    private fun isPasswordConfirmed(groupIndex: Int): Boolean {
        for (confirmedNum in channelGroupPasswordConfirmed) {
            if (confirmedNum == groupIndex) return true
        }
        return false
    }

    private fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem> {
        return if (!isNeedInputPassword(groupIndex)) {
            liveChannelGroupList[groupIndex].getLiveChannels()!!
        } else {
            ArrayList()
        }
    }

    private fun getFirstChannelByName(keyword: String?): Array<Int>? {
        if (TextUtils.isEmpty(keyword)) return null
        val upperKeyword = keyword!!.toUpperCase(Locale.US)
        for (liveChannelGroup in liveChannelGroupList) {
            if (isNeedInputPassword(liveChannelGroup.groupIndex)) continue
            val groupChannels = liveChannelGroup.getLiveChannels()
            if (groupChannels == null || groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                if (TextUtils.isEmpty(item.getChannelName())) continue
                if (item.getChannelName().toUpperCase(Locale.US).contains(upperKeyword)) {
                    return arrayOf(liveChannelGroup.groupIndex, item.channelIndex)
                }
            }
        }
        return null
    }

    private fun getNextChannel(direction: Int): Array<Int> {
        var channelGroupIndex = currentChannelGroupIndex
        var liveChannelIndex = currentLiveChannelIndex

        //跨选分组模式下跳过加密频道分组（遥控器上下键换台/超时换源）
        if (direction > 0) {
            liveChannelIndex++
            if (liveChannelIndex >= getLiveChannels(channelGroupIndex).size) {
                liveChannelIndex = 0
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    // 安全上限：最多遍历全部分组一轮，避免所有分组都带密码时 do-while 永不退出（主线程 ANR）
                    var loopCount = 0
                    do {
                        channelGroupIndex++
                        if (channelGroupIndex >= liveChannelGroupList.size) channelGroupIndex = 0
                        if (++loopCount >= liveChannelGroupList.size) {
                            // 已遍历全部分组仍未找到无密码分组，回退到不跨组行为
                            channelGroupIndex = currentChannelGroupIndex
                            break
                        }
                    } while (!liveChannelGroupList[channelGroupIndex].groupPassword.isNullOrEmpty() || channelGroupIndex == currentChannelGroupIndex)
                }
            }
        } else {
            liveChannelIndex--
            if (liveChannelIndex < 0) {
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    // 安全上限：最多遍历全部分组一轮，避免所有分组都带密码时 do-while 永不退出（主线程 ANR）
                    var loopCount = 0
                    do {
                        channelGroupIndex--
                        if (channelGroupIndex < 0) channelGroupIndex = liveChannelGroupList.size - 1
                        if (++loopCount >= liveChannelGroupList.size) {
                            // 已遍历全部分组仍未找到无密码分组，回退到不跨组行为
                            channelGroupIndex = currentChannelGroupIndex
                            break
                        }
                    } while (!liveChannelGroupList[channelGroupIndex].groupPassword.isNullOrEmpty() || channelGroupIndex == currentChannelGroupIndex)
                }
                liveChannelIndex = getLiveChannels(channelGroupIndex).size - 1
            }
        }

        return arrayOf(channelGroupIndex, liveChannelIndex)
    }

    private fun getFirstNoPasswordChannelGroup(): Int {
        for (liveChannelGroup in liveChannelGroupList) {
            if (liveChannelGroup.groupPassword.isNullOrEmpty()) return liveChannelGroup.groupIndex
        }
        return -1
    }

    private fun isCurrentLiveChannelValid(): Boolean {
        if (currentLiveChannelItem == null) {
            Toast.makeText(KtApp.instance(), "请先选择频道", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    //计算两个时间相差的秒数
    private fun durationToString(duration0: Int): String {
        var duration = duration0
        if (duration < 0) {
            duration = 0
        }
        var result = ""
        val dur = duration / 1000
        val hour = dur / 3600
        val min = (dur / 60) % 60
        val sec = dur % 60
        result = if (hour > 0) {
            if (min > 9) {
                if (sec > 9) {
                    "$hour:$min:$sec"
                } else {
                    "$hour:$min:0$sec"
                }
            } else {
                if (sec > 9) {
                    "$hour:0$min:$sec"
                } else {
                    "$hour:0$min:0$sec"
                }
            }
        } else {
            if (min > 9) {
                if (sec > 9) {
                    "$min:$sec"
                } else {
                    "$min:0$sec"
                }
            } else {
                if (sec > 9) {
                    "0$min:$sec"
                } else {
                    "0$min:0$sec"
                }
            }
        }
        return result
    }

    fun showProgressBars(show: Boolean) {
        sBar!!.requestFocus()
        if (show) {
            ll_right_top_huikan!!.visibility = View.VISIBLE
            updateTopStatusVisibility()
            backcontroller!!.visibility = View.VISIBLE
            ll_epg!!.visibility = View.GONE
        } else {
            backcontroller!!.visibility = View.GONE
            ll_right_top_huikan!!.visibility = View.GONE
            updateTopStatusVisibility()
            if (tip_epg1!!.text != "暂无信息") {
                ll_epg!!.visibility = View.VISIBLE
            }
        }

        iv_play!!.setOnClickListener {
            mVideoView!!.start()
            iv_play!!.visibility = View.INVISIBLE
            countDownTimer!!.start()
            iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
        }

        iv_playpause!!.setOnClickListener {
            if (mVideoView!!.isPlaying) {
                mVideoView!!.pause()
                countDownTimer!!.cancel()
                iv_play!!.visibility = View.VISIBLE
                iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.icon_play) }
            } else {
                mVideoView!!.start()
                iv_play!!.visibility = View.INVISIBLE
                countDownTimer!!.start()
                iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
            }
        }
        sBar!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(arg0: SeekBar?) {}

            override fun onStartTrackingTouch(arg0: SeekBar?) {}

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromuser: Boolean) {
                if (fromuser) {
                    if (countDownTimer != null) {
                        mVideoView!!.seekTo(progress.toLong())
                        countDownTimer!!.cancel()
                        countDownTimer!!.start()
                    }
                }
            }
        })
        sBar!!.setOnKeyListener { _, keycode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keycode == KeyEvent.KEYCODE_DPAD_CENTER || keycode == KeyEvent.KEYCODE_ENTER) {
                    if (mVideoView!!.isPlaying) {
                        mVideoView!!.pause()
                        countDownTimer!!.cancel()
                        iv_play!!.visibility = View.VISIBLE
                        iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.icon_play) }
                    } else {
                        mVideoView!!.start()
                        iv_play!!.visibility = View.INVISIBLE
                        countDownTimer!!.start()
                        iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
                    }
                }
            }
            false
        }
        if (mVideoView!!.isPlaying) {
            iv_play!!.visibility = View.INVISIBLE
            iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.vod_pause) }
        } else {
            iv_play!!.visibility = View.VISIBLE
            iv_playpause!!.background = LivePlayActivity.context?.let { ContextCompat.getDrawable(it, R.drawable.icon_play) }
        }
        if (countDownTimer3 == null) {
            countDownTimer3 = object : CountDownTimer(postTimeout, 1000) {
                override fun onTick(arg0: Long) {
                    if (mVideoView != null) {
                        sBar!!.progress = safeTimeMs(mVideoView!!.currentPosition.toLong())
                        tv_currentpos!!.text = durationToString(safeTimeMs(mVideoView!!.currentPosition.toLong()))
                    }
                }

                override fun onFinish() {
                    if (backcontroller!!.visibility == View.VISIBLE) {
                        backcontroller!!.visibility = View.GONE
                    }
                }
            }
        } else {
            countDownTimer3!!.cancel()
        }
        countDownTimer3!!.start()
    }

    /**
     * 当播放列表为空或加载失败时，设置一个默认的播放列表，保证播放界面不会崩溃
     */
    private fun clearLiveChannelList() {
        clearLiveChannelList(true)
    }

    private fun clearLiveChannelList(releasePlayer: Boolean) {
        refreshingLiveChannelList = false
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1
        currentLiveChannelItem = null
        currentLiveChannelIndex = -1
        currentLiveLookBackIndex = -1
        currentLiveChangeSourceTimes = 0
        liveChannelGroupList.clear()
        (ApiConfig.get().getChannelGroupList() as ArrayList<LiveChannelGroup>).clear()
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        mHandler.removeCallbacks(mLoadEpgRun)
        hideSwitchChannelSnapshot()
        if (releasePlayer && mVideoView != null) mVideoView!!.release()
        showSuccess()
        if (liveChannelGroupAdapter != null) {
            liveChannelGroupAdapter!!.clearGroupState()
            liveChannelGroupAdapter!!.setNewData(ArrayList<LiveChannelGroup>())
        }
        if (liveChannelItemAdapter != null) {
            liveChannelItemAdapter!!.setFocusedChannelIndex(-1)
            liveChannelItemAdapter!!.setSelectedChannelIndex(-1)
            liveChannelItemAdapter!!.setNewData(ArrayList<LiveChannelItem>())
        }
        if (tvLeftChannelListLayout != null) tvLeftChannelListLayout!!.visibility = View.INVISIBLE
        if (tvRightSettingLayout != null) tvRightSettingLayout!!.visibility = View.INVISIBLE
    }

    private fun setEmptyLiveChannelList(releasePlayer: Boolean = true) {
        clearLiveChannelList(releasePlayer)
        // P3-6:恢复被注释的失败提示(原版此处 Toast 处于注释状态),mHandler.post 保证主线程弹出
        mHandler.post {
            Toast.makeText(KtApp.instance(), "源异常,请切换到其他源", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        var context: Context? = null // P2-1:改为可空,onDestroy 置空,防止静态引用钉死 Activity(仅 init 赋值,与旧版 public static Context context 一致)

        @JvmField
        var currentChannelGroupIndex = 0

        private const val EPG_LOAD_DELAY = 1200L
        private const val RESOLUTION_INFO_MAX_RETRY = 10
        private const val RESOLUTION_INFO_RETRY_DELAY = 300L
        private const val RESOLUTION_INFO_HIDE_DELAY = 3000L
        private const val DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}"
        private val CATCHUP_TOKEN_PATTERN = Pattern.compile("(\\$?\\{[^}]*\\})")
        private val CATCHUP_TAG_PATTERN = Pattern.compile("\\{([^}]*)\\}")
        private const val LONG_PRESS_DELAY = 800L

        //EPG   by 龍
        private var channel_Name: LiveChannelItem? = null

        // P2-2:EPG 缓存改用 LruCache(64 条),避免全天节目单按"频道_日期"无限累积(原为 Hashtable 无淘汰)
        private val hsEpg = LruCache<String, ArrayList<Epginfo>>(64)

        //laodao 7day replay
        @JvmField
        var formatDate = SimpleDateFormat("yyyy-MM-dd")

        @JvmField
        var formatDate1 = SimpleDateFormat("MM-dd")

        @JvmField
        var day = formatDate.format(Date())

        @JvmField
        var nowday = Date()

        private var shiyi_time_c = 0 //时移时间差值

        @JvmField
        var playUrl: String? = null

        private const val postTimeout = 6000L

        //计算两个时间相差的秒数
        @JvmStatic
        fun getTime(startTime: String?, endTime: String?): Long {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            var eTime: Long = 0
            try {
                eTime = df.parse(endTime).time
            } catch (e: ParseException) {
                e.printStackTrace()
            }
            var sTime: Long = 0
            try {
                sTime = df.parse(startTime).time
            } catch (e: ParseException) {
                e.printStackTrace()
            }
            val diff = (eTime - sTime) / 1000
            return diff
        }
    }
}
