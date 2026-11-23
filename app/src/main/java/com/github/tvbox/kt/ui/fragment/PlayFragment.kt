package com.github.tvbox.kt.ui.fragment

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import com.google.android.exoplayer2.text.Cue
import com.google.gson.JsonObject
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.base.BaseLazyFragment
import com.github.tvbox.kt.bean.ParseBean
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.bean.Subtitle
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.CacheManager
import com.github.tvbox.kt.dlna.CastVideo
import com.github.tvbox.kt.event.RefreshEvent
import com.github.tvbox.kt.player.ExoPlayer
import com.github.tvbox.kt.player.IjkMediaPlayer
import com.github.tvbox.kt.player.MusicPlaybackService
import com.github.tvbox.kt.player.MyVideoView
import com.github.tvbox.kt.player.TrackInfo
import com.github.tvbox.kt.player.TrackInfoBean
import com.github.tvbox.kt.player.controller.VodController
import com.github.tvbox.kt.player.danmu.DanmuLoadController
import com.github.tvbox.kt.server.ControlManager
import com.github.tvbox.kt.ui.activity.DetailActivity
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter
import com.github.tvbox.kt.ui.dialog.CastDeviceDialog
import com.github.tvbox.kt.ui.dialog.DanmuSettingDialog
import com.github.tvbox.kt.ui.dialog.EpisodeDialog
import com.github.tvbox.kt.ui.dialog.SearchDanmuDialog
import com.github.tvbox.kt.ui.dialog.SearchSubtitleDialog
import com.github.tvbox.kt.ui.dialog.SelectDialog
import com.github.tvbox.kt.ui.dialog.SubtitleDialog
import com.github.tvbox.kt.util.AdBlocker
import com.github.tvbox.kt.util.DefaultConfig
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.ImgUtil
import com.github.tvbox.kt.util.LOG
import com.github.tvbox.kt.util.MD5
import com.github.tvbox.kt.util.PlayerHelper
import com.github.tvbox.kt.util.SubtitleHelper
import com.github.tvbox.kt.util.VideoParseRuler
import com.github.tvbox.kt.util.XWalkUtils
import com.github.tvbox.kt.util.parser.SuperParse
import com.github.tvbox.kt.util.thunder.Jianpian
import com.github.tvbox.kt.util.thunder.Thunder
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.HttpHeaders
import com.lzy.okgo.model.Response
import com.obsez.android.lib.filechooser.ChooserDialog
import com.orhanobut.hawk.Hawk
import me.jessyan.autosize.AutoSize
import master.flame.danmaku.ui.widget.DanmakuView
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xwalk.core.XWalkJavascriptResult
import org.xwalk.core.XWalkResourceClient
import org.xwalk.core.XWalkSettings
import org.xwalk.core.XWalkUIClient
import org.xwalk.core.XWalkView
import org.xwalk.core.XWalkWebResourceRequest
import org.xwalk.core.XWalkWebResourceResponse
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkTimedText
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.player.ProgressManager
import xyz.doikki.videoplayer.player.VideoView
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import java.util.LinkedHashMap
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 播放 Fragment,Kotlin 移植(与旧版 PlayFragment 一致)。
 * TODO: com.github.tvbox.kt.ui.activity.DetailActivity 由并行任务移植中,引用其 startDetailFallbackAfterLinesExhausted()。
 */
class PlayFragment : BaseLazyFragment() {

    companion object {
        private const val MSG_PARSE_TIMEOUT = 100
        private const val MSG_RESOLVE_PLAY_URL_TIMEOUT = 101
        private const val MSG_SWITCH_LINE_PLAY_TIMEOUT = 102
        private const val RESOLVE_PLAY_URL_TIMEOUT_MS = 15 * 1000L
        private const val SWITCH_LINE_PLAY_TIMEOUT_MS = 20 * 1000L
    }

    private var mVideoView: MyVideoView? = null
    private var mPlayLoadTip: TextView? = null
    private var mPlayLoadErr: ImageView? = null
    private var mPlayLoading: ProgressBar? = null
    private var mController: VodController? = null
    private var sourceViewModel: SourceViewModel? = null
    private var playResultObserver: Observer<JSONObject>? = null
    private var qualityResult: JSONObject? = null
    private var mHandler: Handler? = null
    private var exitingPreview = false
    private var audioPlayback = false
    private var switchingPlayback = false
    private var reusePlayerOnSwitch = false
    private var releasePlayerOnSwitch = false
    private var previewMode = false
    private var playLyric: String? = null
    private var lyricCacheKey: String? = null
    private var playArtwork: String? = null
    private var mDanmuView: DanmakuView? = null
    private var danmuLoadController: DanmuLoadController? = null
    private val exoCues: MutableList<Cue> = ArrayList()
    private var exoInternalSubtitle = false

    private val videoDuration: Long = -1

    override val layoutResID: Int
        get() = R.layout.activity_play

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            mController!!.mSubtitleView.setTextSize((event.obj as Int).toFloat())
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            setDanmuViewSettings(event.obj is Boolean && event.obj as Boolean)
        } else if (event.type == RefreshEvent.TYPE_DANMU_REFRESH) {
            checkDanmu(if (event.obj is String) event.obj as String else "")
        }
    }

    override fun init() {
        initView()
        initDanmuView()
        initViewModel()
        initData()
    }

    private fun initDanmuView() {
        mDanmuView = findViewById(R.id.danmaku)
        danmuLoadController = DanmuLoadController(mVideoView, mController, mDanmuView)
    }

    private fun setDanmuViewSettings(reload: Boolean) {
        if (danmuLoadController != null) danmuLoadController!!.applySettings(reload)
    }

    private fun checkDanmu(danmu: String) {
        checkDanmu(danmu, null)
    }

    private fun checkDanmu(danmu: String, callback: DanmuLoadController.LoadCallback?) {
        if (danmuLoadController != null) {
            val series = if (mVodInfo == null) null else getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
            danmuLoadController!!.check(
                danmu,
                if (mVodInfo == null) "" else mVodInfo!!.name ?: "",
                series?.name ?: "",
                callback
            )
        }
    }

    private fun startDanmuIfReady() {
        if (danmuLoadController != null) danmuLoadController!!.startIfReady()
    }

    private fun resetDanmuState() {
        if (danmuLoadController != null) danmuLoadController!!.reset()
    }

    private fun reloadDanmuForPlayback() {
        if (danmuLoadController != null) danmuLoadController!!.reloadForPlayback()
    }

    fun getSavedProgress(url: String): Long {
        var st = 0
        try {
            st = mVodPlayerCfg!!.getInt("st")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val skip = st * 1000L
        val theCache = CacheManager.getCache(MD5.string2MD5(url) ?: "")
        if (theCache == null) {
            return skip
        }
        var rec: Long = 0
        if (theCache is Long) {
            rec = theCache
        } else if (theCache is String) {
            try {
                rec = theCache.toLong()
            } catch (e: NumberFormatException) {
                LOG.i("echo-String value is not a valid long.")
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.")
        }
        return Math.max(rec, skip)
    }

    private fun initView() {
        EventBus.getDefault().register(this)
        mHandler = Handler(object : Handler.Callback {
            override fun handleMessage(msg: Message): Boolean {
                when (msg.what) {
                    MSG_PARSE_TIMEOUT -> {
                        stopParse()
                        errorWithRetry("嗅探错误", false)
                    }
                    MSG_RESOLVE_PLAY_URL_TIMEOUT -> handleResolvePlayUrlTimeout()
                    MSG_SWITCH_LINE_PLAY_TIMEOUT -> handleSwitchLinePlayTimeout()
                }
                return false
            }
        })
        mVideoView = findViewById(R.id.mVideoView)
        mPlayLoadTip = findViewById(R.id.play_load_tip)
        mPlayLoading = findViewById(R.id.play_loading)
        mPlayLoadErr = findViewById(R.id.play_load_error)
        mController = VodController(requireContext())
        val passThroughTouch = View.OnTouchListener { v, event ->
            mController != null && mController!!.onTouchEvent(event)
        }
        mPlayLoadTip!!.setOnTouchListener(passThroughTouch)
        mPlayLoadErr!!.setOnTouchListener(passThroughTouch)
        mController!!.mLyricView.setTextSize(if (previewMode) 16f else 24f)
        mController!!.setCanChangePosition(true)
        mController!!.setEnableInNormal(true)
        mController!!.setGestureEnabled(true)
        val progressManager: ProgressManager = object : ProgressManager() {
            override fun saveProgress(url: String?, progress: Long) {
                CacheManager.save(MD5.string2MD5(url ?: "") ?: "", progress)
                if (webPlayUrl != null && progress > 0) {
                    markPlaybackStarted()
                    hideTipOnUiThread()
                }
            }

            override fun getSavedProgress(url: String?): Long {
                return this@PlayFragment.getSavedProgress(url ?: "")
            }
        }
        mVideoView!!.setProgressManager(progressManager)
        mVideoView!!.addOnStateChangeListener(object : VideoView.SimpleOnStateChangeListener() {
            override fun onPlayStateChanged(playState: Int) {
                if (webPlayUrl != null && isStartedPlayState(playState)) {
                    markPlaybackStarted()
                    hideTipOnUiThread()
                }
                if (switchingPlayback) {
                    if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                        LOG.i("echo-music keep session while resolving next episode")
                        return
                    } else if (playState == VideoView.STATE_ERROR) {
                        switchingPlayback = false
                        audioPlayback = false
                    } else if (isStartedPlayState(playState)) {
                        val audioOnly = getAudioOnlyPlayback()
                        if (audioOnly != null) {
                            switchingPlayback = false
                            audioPlayback = audioOnly
                        }
                    }
                }
                if (!switchingPlayback) updateMusicSession()
                startDanmuIfReady()
            }
        })
        mController!!.setListener(object : VodController.VodControlListener {
            override fun showDanmuSetting() {
                val dialog = DanmuSettingDialog(requireContext())
                dialog.setDanmuSearchListener(object : DanmuSettingDialog.DanmuSearchListener {
                    override fun openSearchDanmuDialog() {
                        val searchDanmuDialog = SearchDanmuDialog(requireContext())
                        searchDanmuDialog.setDanmuLoader(object : SearchDanmuDialog.DanmuLoader {
                            override fun loadDanmu(danmu: String) {
                                if (!isAdded) return
                                checkDanmu(danmu)
                            }
                        })
                        val series = if (mVodInfo == null) null else getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
                        searchDanmuDialog.setEpisode(if (series == null) "" else series.name)
                        searchDanmuDialog.setSearchWord(if (mVodInfo == null) "" else mVodInfo!!.name)
                        searchDanmuDialog.show()
                    }
                })
                dialog.show()
            }

            override fun toggleDanmu(): Boolean {
                return danmuLoadController != null && danmuLoadController!!.toggle()
            }

            override fun searchDanmuUi(longClick: Boolean) {
                val series = if (mVodInfo == null) null else getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
                ApiConfig.get().searchDanmuUi(
                    if (mVodInfo == null) "" else mVodInfo!!.name,
                    if (series == null) "" else series.name,
                    longClick
                )
            }

            override fun playNext(rmProgress: Boolean) {
                val preProgressKey = progressKey
                this@PlayFragment.playNext(rmProgress)
                if (rmProgress && preProgressKey != null)
                    CacheManager.delete(MD5.string2MD5(preProgressKey) ?: "", 0)
            }

            override fun playPre() {
                playPrevious()
            }

            override fun showEpisodeDialog() {
                this@PlayFragment.showEpisodeDialog()
            }

            override fun changeParse(pb: ParseBean?) {
                autoRetryCount = 0
                hasAutoSwitchedPlayer = false
                triedLineFlags.clear()
                doParse(pb)
            }

            override fun updatePlayerCfg() {
                mVodInfo!!.playerCfg = mVodPlayerCfg!!.toString()
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg))
            }

            override fun replay(replay: Boolean) {
                autoRetryCount = 0
                hasAutoSwitchedPlayer = false
                triedLineFlags.clear()
                if (replay) {
                    play(true)
                } else {
                    reloadDanmuForPlayback()
                    if (webPlayUrl != null && webPlayUrl!!.isNotEmpty()) {
                        stopParse()
                        initParseLoadFound()
                        if (mVideoView != null) mVideoView!!.release()
                        goPlayUrl(webPlayUrl, webHeaderMap)
                    } else {
                        play(false)
                    }
                }
            }

            override fun errReplay() {
                errorWithRetry("视频播放出错", false)
            }

            override fun selectSubtitle() {
                try {
                    selectMySubtitle()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun selectAudioTrack() {
                selectMyAudioTrack()
            }

            override fun selectVideoTrack() {
                selectMyVideoTrack()
            }

            override fun prepared() {
                initSubtitleView()
                if (mVideoView != null) mVideoView!!.prepared()
                startDanmuIfReady()
            }

            override fun startPlayUrl(url: String?, headers: HashMap<String, String>?) {
                if (!TextUtils.isEmpty(m3u8SourceUrl) && !isM3u8ProxyUrl(url)) clearM3u8ProxyUrl()
                goPlayUrl(url, headers)
            }

            override fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?) {
                m3u8ProxyUrl = proxyUrl
                m3u8SourceUrl = sourceUrl
            }

            override fun clickCast() {
                showCastDialog()
            }

            override fun setAllowSwitchPlayer(isAllow: Boolean) {
                allowSwitchPlayer = isAllow
            }
        })
        mVideoView!!.setVideoController(mController)
    }

    private fun showCastDialog() {
        if (TextUtils.isEmpty(webPlayUrl)) {
            Toast.makeText(mContext, "暂无可投屏播放地址", Toast.LENGTH_SHORT).show()
            return
        }
        val headers: HashMap<String, String>? = if (webHeaderMap == null) null else HashMap(webHeaderMap)
        val video = CastVideo(getCastUrl(webPlayUrl), getCastTitle(), headers, getCastPosition())
        val dialog = CastDeviceDialog(requireActivity(), video)
        dialog.setOnCastListener(object : CastDeviceDialog.OnCastListener {
            override fun onCastSuccess() {
                if (mVideoView != null) mVideoView!!.pause()
            }

            override fun onCastFailed() {
            }
        })
        dialog.show()
    }

    private fun getCastTitle(): String {
        if (mVodInfo == null) return "TVBox"
        return try {
            val series = mVodInfo!!.seriesMap!![mVodInfo!!.playFlag]!![mVodInfo!!.playIndex]
            "${mVodInfo!!.name} ${series.name}"
        } catch (e: Exception) {
            if (TextUtils.isEmpty(mVodInfo!!.name)) "TVBox" else mVodInfo!!.name!!
        }
    }

    private fun getCastPosition(): Long {
        return try {
            if (mVideoView == null) 0 else mVideoView!!.currentPosition
        } catch (e: Exception) {
            0
        }
    }

    private fun getCastUrl(url: String?): String? {
        if (TextUtils.isEmpty(url)) return url
        if (isM3u8ProxyUrl(url) && !TextUtils.isEmpty(m3u8SourceUrl)) return m3u8SourceUrl
        val local = ControlManager.get().getAddress(true)
        val server = ControlManager.get().getAddress(false)
        if (!TextUtils.isEmpty(local) && !TextUtils.isEmpty(server) && url!!.startsWith(local)) {
            return server + url.substring(local.length)
        }
        return url
    }

    private fun isM3u8ProxyUrl(url: String?): Boolean {
        return !TextUtils.isEmpty(m3u8ProxyUrl) && url == m3u8ProxyUrl
    }

    private fun clearM3u8ProxyUrl() {
        m3u8ProxyUrl = null
        m3u8SourceUrl = null
    }

    //设置字幕
    fun setSubtitle(path: String?) {
        if (path != null && path.length > 0) {
            hideExoInternalSubtitle()
            // 设置字幕
            mController!!.mSubtitleView.visibility = View.GONE
            mController!!.mSubtitleView.setSubtitlePath(path)
            mController!!.mSubtitleView.visibility = View.VISIBLE
        }
    }

    @Throws(Exception::class)
    fun selectMySubtitle() {
        val subtitleDialog = SubtitleDialog(requireActivity())
        val mediaPlayer = mVideoView!!.getMediaPlayer()
        val hasInternal = mController!!.mSubtitleView.hasInternal || hasExoInternalSubtitle(mediaPlayer)
        if (hasInternal) {
            subtitleDialog.selectInternal.visibility = View.VISIBLE
        } else {
            subtitleDialog.selectInternal.visibility = View.GONE
        }
        subtitleDialog.setExoInternalSubtitle(mediaPlayer is ExoPlayer && exoInternalSubtitle)
        subtitleDialog.setSubtitleViewListener(object : SubtitleDialog.SubtitleViewListener {
            override fun setTextSize(size: Int) {
                mController!!.mSubtitleView.setTextSize(size.toFloat())
            }

            override fun setSubtitleDelay(milliseconds: Int) {
                if (mediaPlayer is ExoPlayer && exoInternalSubtitle) {
                    mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                } else {
                    mController!!.mSubtitleView.setSubtitleDelay(milliseconds)
                }
            }

            override fun selectInternalSubtitle() {
                selectMyInternalSubtitle()
            }

            override fun setTextStyle(style: Int) {
                setSubtitleViewTextStyle(style)
            }

            override fun setSubtitleScale(scale: Int) {
                if (mediaPlayer is ExoPlayer && exoInternalSubtitle) {
                    applyExoSubtitleSettings()
                }
            }

            override fun moveSubtitle(offset: Float) {
                if (mediaPlayer is ExoPlayer && exoInternalSubtitle) {
                    applyExoSubtitleSettings()
                }
            }
        })
        subtitleDialog.setSearchSubtitleListener(object : SubtitleDialog.SearchSubtitleListener {
            override fun openSearchSubtitleDialog() {
                val searchSubtitleDialog = SearchSubtitleDialog(requireActivity())
                searchSubtitleDialog.setSubtitleLoader(object : SearchSubtitleDialog.SubtitleLoader {
                    override fun loadSubtitle(subtitle: Subtitle?) {
                        if (!isAdded) return
                        requireActivity().runOnUiThread {
                            val zimuUrl = subtitle!!.url
                            LOG.i("echo-Remote Subtitle Url: $zimuUrl")
                            setSubtitle(zimuUrl) //设置字幕
                            searchSubtitleDialog.dismiss()
                        }
                    }
                })
                if (mVodInfo!!.playFlag!!.contains("Ali") || mVodInfo!!.playFlag!!.contains("parse")) {
                    searchSubtitleDialog.setSearchWord(mVodInfo!!.playNote)
                } else {
                    searchSubtitleDialog.setSearchWord(mVodInfo!!.name ?: "")
                }
                searchSubtitleDialog.show()
            }
        })
        subtitleDialog.setLocalFileChooserListener(object : SubtitleDialog.LocalFileChooserListener {
            override fun openLocalFileChooserDialog() {
                ChooserDialog(requireActivity())
                    .withFilter(false, false, "srt", "ass", "scc", "stl", "ttml")
                    .withStartFile("/storage/emulated/0/Download")
                    .withChosenListener { path, pathFile ->
                        LOG.i("echo-Local Subtitle Path: $path")
                        setSubtitle(path) //设置字幕
                    }
                    .build()
                    .show()
            }
        })
        subtitleDialog.show()
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    fun setSubtitleViewTextStyle(style: Int) {
        if (style == 0) {
            mController!!.mSubtitleView.setTextColor(context!!.resources.getColorStateList(R.color.color_FFFFFF))
        } else if (style == 1) {
            mController!!.mSubtitleView.setTextColor(context!!.resources.getColorStateList(R.color.color_FFB6C1))
        }
    }

    private fun isSameTrack(left: TrackInfoBean, right: TrackInfoBean): Boolean {
        return left.renderId == right.renderId &&
                left.trackGroupId == right.trackGroupId &&
                left.trackId == right.trackId
    }

    fun selectMyAudioTrack() {
        val mediaPlayer = mVideoView!!.getMediaPlayer()
        var trackInfo: TrackInfo? = null
        if (mediaPlayer is IjkMediaPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (mediaPlayer is ExoPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.getAudio()
        if (bean.size < 1) return
        val dialog = SelectDialog<TrackInfoBean>(requireActivity())
        dialog.setTip("切换音轨")
        dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<TrackInfoBean> {
            override fun click(value: TrackInfoBean, pos: Int) {
                try {
                    for (audio in bean) {
                        audio.selected = isSameTrack(audio, value)
                    }
                    mediaPlayer!!.pause()
                    val progress = mediaPlayer!!.currentPosition //保存当前进度，ijk 切换轨道 会有快进几秒
                    if (mediaPlayer is IjkMediaPlayer) mediaPlayer.setTrack(value.trackId, progressKey!!)
                    if (mediaPlayer is ExoPlayer) mediaPlayer.setTrack(value, progressKey!!)
                    Handler().postDelayed({
                        if (mediaPlayer is IjkMediaPlayer) mediaPlayer.seekTo(progress)
                        mediaPlayer!!.start()
                    }, 200L)
                    dialog.dismiss()
                } catch (e: Exception) {
                    LOG.e("切换音轨出错")
                }
            }

            override fun getDisplay(value: TrackInfoBean): String {
                return value.name ?: ""
            }
        }, object : DiffUtil.ItemCallback<TrackInfoBean>() {
            override fun areItemsTheSame(oldItem: TrackInfoBean, newItem: TrackInfoBean): Boolean {
                return isSameTrack(oldItem, newItem)
            }

            override fun areContentsTheSame(oldItem: TrackInfoBean, newItem: TrackInfoBean): Boolean {
                return isSameTrack(oldItem, newItem)
            }
        }, bean, trackInfo.getAudioSelected(false))
        dialog.show()
    }

    fun selectMyVideoTrack() {
        val mediaPlayer = mVideoView!!.getMediaPlayer()
        var trackInfo: TrackInfo? = null
        if (mediaPlayer is IjkMediaPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        } else if (mediaPlayer is ExoPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (trackInfo == null || trackInfo.getVideo().isEmpty()) {
            Toast.makeText(mContext, "没有视轨", Toast.LENGTH_SHORT).show()
            return
        }
        val tracks = trackInfo.getVideo()
        val dialog = SelectDialog<TrackInfoBean>(requireActivity())
        dialog.setTip("切换视轨")
        dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<TrackInfoBean> {
            override fun click(value: TrackInfoBean, pos: Int) {
                try {
                    for (track in tracks) {
                        track.selected = isSameTrack(track, value)
                    }
                    mediaPlayer!!.pause()
                    val progress = mediaPlayer!!.currentPosition
                    if (mediaPlayer is IjkMediaPlayer) {
                        mediaPlayer.setTrack(value.trackId)
                    } else if (mediaPlayer is ExoPlayer) {
                        mediaPlayer.setTrack(value, "")
                    }
                    Handler().postDelayed({
                        mediaPlayer!!.seekTo(progress)
                        mediaPlayer!!.start()
                    }, 200L)
                    dialog.dismiss()
                } catch (e: Exception) {
                    LOG.e("echo-switch-video-track-error:" + e.message)
                }
            }

            override fun getDisplay(value: TrackInfoBean): String {
                return value.name ?: ""
            }
        }, object : DiffUtil.ItemCallback<TrackInfoBean>() {
            override fun areItemsTheSame(oldItem: TrackInfoBean, newItem: TrackInfoBean): Boolean {
                return isSameTrack(oldItem, newItem)
            }

            override fun areContentsTheSame(oldItem: TrackInfoBean, newItem: TrackInfoBean): Boolean {
                return isSameTrack(oldItem, newItem)
            }
        }, tracks, trackInfo.getVideoSelected(false))
        dialog.show()
    }

    fun selectMyInternalSubtitle() {
        val mediaPlayer = mVideoView!!.getMediaPlayer()
        var trackInfo: TrackInfo? = null
        if (mediaPlayer is IjkMediaPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        } else if (mediaPlayer is ExoPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, "没有内置字幕", Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.getSubtitle()
        if (bean.size < 1) return
        val dialog = SelectDialog<TrackInfoBean>(requireActivity())
        dialog.setTip("切换内置字幕")
        dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<TrackInfoBean> {
            override fun click(value: TrackInfoBean, pos: Int) {
                try {
                    for (subtitle in bean) {
                        subtitle.selected = isSameTrack(subtitle, value)
                    }
                    if (mediaPlayer is IjkMediaPlayer) {
                        mediaPlayer.pause()
                        val progress = mediaPlayer.currentPosition
                        mController!!.mSubtitleView.destroy()
                        mController!!.mSubtitleView.clearSubtitleCache()
                        mController!!.mSubtitleView.isInternal = true
                        mediaPlayer.setTrack(value.trackId)
                        Handler().postDelayed({
                            mediaPlayer.seekTo(progress)
                            mediaPlayer.start()
                        }, 800L)
                    } else if (mediaPlayer is ExoPlayer) {
                        mController!!.mSubtitleView.visibility = View.GONE
                        mController!!.mSubtitleView.destroy()
                        mController!!.mSubtitleView.clearSubtitleCache()
                        mController!!.mSubtitleView.isInternal = false
                        exoInternalSubtitle = true
                        mediaPlayer.setTrack(value, "")
                        mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                        mController!!.mExoSubtitleView.visibility = View.VISIBLE
                        applyExoSubtitleSettings()
                    }
                    dialog.dismiss()
                } catch (e: Exception) {
                    LOG.e("echo-switch-internal-subtitle-error:" + e.message)
                }
            }

            override fun getDisplay(value: TrackInfoBean): String {
                return value.name ?: ""
            }
        }, object : DiffUtil.ItemCallback<TrackInfoBean>() {
            override fun areItemsTheSame(oldItem: TrackInfoBean, newItem: TrackInfoBean): Boolean {
                return isSameTrack(oldItem, newItem)
            }

            override fun areContentsTheSame(oldItem: TrackInfoBean, newItem: TrackInfoBean): Boolean {
                return isSameTrack(oldItem, newItem)
            }
        }, bean, trackInfo.getSubtitleSelected(false))
        dialog.show()
    }

    private fun hasExoInternalSubtitle(mediaPlayer: AbstractPlayer?): Boolean {
        if (mediaPlayer !is ExoPlayer) return false
        val trackInfo = mediaPlayer.getTrackInfo()
        return trackInfo.getSubtitle().isNotEmpty()
    }

    private fun hideExoInternalSubtitle() {
        exoInternalSubtitle = false
        exoCues.clear()
        if (mController != null && mController!!.mExoSubtitleView != null) {
            mController!!.mExoSubtitleView.setCues(exoCues)
            mController!!.mExoSubtitleView.visibility = View.GONE
        }
    }

    private fun onExoCues(cues: List<Cue>?) {
        if (!isAdded || !exoInternalSubtitle) return
        exoCues.clear()
        if (cues != null) exoCues.addAll(cues)
        requireActivity().runOnUiThread {
            applyExoSubtitleSettings()
        }
    }

    private fun applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || mController == null || mController!!.mExoSubtitleView == null) return
        val scale = SubtitleHelper.getExoSubtitleScale() / 100f
        val position = SubtitleHelper.getExoSubtitlePosition()
        mController!!.mExoSubtitleView.setFractionalTextSize(0.0533f * scale)
        mController!!.mExoSubtitleView.setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f))

        val displayCues = ArrayList<Cue>()
        for (cue in exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue)
                continue
            }
            val builder = cue.buildUpon()
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f))
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f))
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType)
            }
            displayCues.add(builder.build())
        }
        mController!!.mExoSubtitleView.setCues(displayCues)
    }

    private fun limit(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, value))
    }

    fun setTip(msg: String?, loading: Boolean, err: Boolean) {
        if (!isAdded) return
        requireActivity().runOnUiThread { //影魔
            mPlayLoadTip!!.text = msg
            mPlayLoadTip!!.visibility = View.VISIBLE
            mPlayLoading!!.visibility = if (loading) View.VISIBLE else View.GONE
            mPlayLoadErr!!.visibility = if (err) View.VISIBLE else View.GONE
        }
    }

    fun hideTip() {
        mPlayLoadTip!!.visibility = View.GONE
        mPlayLoading!!.visibility = View.GONE
        mPlayLoadErr!!.visibility = View.GONE
    }

    fun hideTipOnUiThread() {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            hideTip()
        }
    }

    fun errorWithRetry(err: String, finish: Boolean) {
        if (isPlaybackStarted()) {
            cancelPlayTimeout()
            hideTipOnUiThread()
            return
        }
        if (!autoRetry()) {
            stopMusicSessionForFailedPlayback()
            if (!isAdded) return
            requireActivity().runOnUiThread {
                if (finish) {
                    setTip(err, false, true)
                    Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show()
                } else {
                    setTip(err, false, true)
                }
            }
        }
    }

    private fun stopMusicSessionForFailedPlayback() {
        switchingPlayback = false
        audioPlayback = false
        MusicPlaybackService.stop(context, this)
    }

    fun playUrl(url0: String, headers: HashMap<String, String>?) {
        startSwitchLinePlayTimeout()
        val url = attachProxySiteKey(url0)
        if (!url.startsWith("data:application")) EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, url)) //更新播放地址
        if (!Hawk.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(url, headers)
            return
        }
        if (url.startsWith("http://127.0.0.1") || !url.contains(".m3u8")) {
            goPlayUrl(url, headers)
            return
        }
        if (DefaultConfig.noAd(mVodInfo!!.playFlag)) {
            goPlayUrl(url, headers)
            return
        }
        LOG.i("echo-playM3u8:" + url)
        mController!!.playM3u8(url, headers)
    }

    fun goPlayUrl(url: String?, headers: HashMap<String, String>?) {
        LOG.i("echo-goPlayUrl:" + url)
        if (TextUtils.isEmpty(url)) {
            handleResolvePlayUrlFailed("获取播放地址为空")
            return
        }
        if (autoRetryCount == 0) webPlayUrl = url
        if (mActivity == null) return
        if (!isAdded) return
        val finalUrl = url
        requireActivity().runOnUiThread {
            stopParse()
            if (mVideoView != null) {
                if (finalUrl != null) {
                    var url = finalUrl
                    try {
                        val playerType = mVodPlayerCfg!!.getInt("pl")
                        if (playerType >= 10) {
                            mVideoView!!.release()
                            val vs = mVodInfo!!.seriesMap!![mVodInfo!!.playFlag]!![mVodInfo!!.playIndex]
                            val playTitle = "${mVodInfo!!.name} ${vs.name}"
                            setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + "进行播放", true, false)
                            val progress = getSavedProgress(progressKey!!)
                            val callResult = PlayerHelper.runExternalPlayer(
                                playerType, requireActivity(), url, playTitle, playSubtitle, headers, progress
                            )
                            setTip(
                                "调用外部播放器" + PlayerHelper.getPlayerName(playerType) + (if (callResult) "成功" else "失败"),
                                callResult, !callResult
                            )
                            return@runOnUiThread
                        }
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                    playTimeoutBasePosition = getSavedProgress(progressKey!!)
                    val forceExoPlayer = url.startsWith("data:application/dash+xml;base64,")
                            || url.contains(".mpd") || url.contains("type=mpd")
                    if (url.startsWith("data:application/dash+xml;base64,")) {
                        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg!!, 2)
                        KtApp.instance().setDashData(url.split("base64,")[1])
                        url = ControlManager.get().getAddress(true) + "dash/proxy.mpd"
                    } else if (url.contains(".mpd") || url.contains("type=mpd")) {
                        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg!!, 2)
                    } else {
                        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg!!)
                    }
                    mController!!.hidePauseRoot()
                    val reusePlayer = !forceExoPlayer && mVideoView!!.getMediaPlayer() != null
                    if (!reusePlayer) hideTip()
                    if (!reusePlayer && mVideoView!!.getMediaPlayer() != null) {
                        mVideoView!!.release()
                    }
                    mVideoView!!.setProgressKey(progressKey)
                    if (headers != null) {
                        mVideoView!!.setUrl(url, headers)
                    } else {
                        mVideoView!!.setUrl(url)
                    }
                    startSwitchLinePlayTimeout()
                    if (reusePlayer) {
                        mVideoView!!.skipPositionWhenPlay(playTimeoutBasePosition.toInt())
                        mVideoView!!.replay(false)
                    } else {
                        mVideoView!!.start()
                    }
                    mController!!.resetSpeed()
                }
            }
        }
    }

    private fun attachProxySiteKey(url: String): String {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sourceKey)) return url
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url
        if (url.contains("siteKey=")) return url
        return try {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + URLEncoder.encode(sourceKey, "UTF-8")
        } catch (th: Throwable) {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + sourceKey
        }
    }

    private fun initSubtitleView() {
        var trackInfo: TrackInfo? = null
        val mediaPlayer = mVideoView!!.getMediaPlayer()
        mController!!.mLyricView.setTextSize(if (previewMode) 16f else 24f)
        mController!!.mLyricView.visibility = View.GONE
        mController!!.mLyricView.reset()
        mController!!.mLyricView.bindToMediaPlayer(mediaPlayer)
        mController!!.mLyricView.setMergeSameTime(true)
        mController!!.mLyricView.setLyricMode(true)
        mController!!.mLyricView.setPlaySubtitleCacheKey(lyricCacheKey)
        mController!!.mSubtitleView.hasInternal = false
        mController!!.mSubtitleView.isInternal = false
        hideExoInternalSubtitle()
        if (mediaPlayer is IjkMediaPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
            if (trackInfo != null && trackInfo.getSubtitle().size > 0) {
                mController!!.mSubtitleView.hasInternal = true
            }
            //默认选中第一个音轨 一般第一个音轨是国语 && 加载上一次选中的
            mediaPlayer.loadDefaultTrack(trackInfo, progressKey!!)
            mediaPlayer.setOnTimedTextListener { mp, text ->
                if (text == null) return@setOnTimedTextListener
                if (mController!!.mSubtitleView.isInternal) {
                    val subtitle = com.github.tvbox.kt.subtitle.model.Subtitle()
                    subtitle.content = text.text
                    mController!!.mSubtitleView.onSubtitleChanged(subtitle)
                }
            }
        }
        if (mediaPlayer is ExoPlayer) {
            val exoPlayer: ExoPlayer = mediaPlayer
            trackInfo = exoPlayer.getTrackInfo()
            if (trackInfo != null && trackInfo.getSubtitle().isNotEmpty()) {
                mController!!.mSubtitleView.hasInternal = true
                exoInternalSubtitle = true
                mController!!.mExoSubtitleView.visibility = View.VISIBLE
                exoPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                exoPlayer.setOnCuesListener(object : ExoPlayer.OnCuesListener {
                    override fun onCues(cues: List<Cue>) {
                        onExoCues(cues)
                    }
                })
                applyExoSubtitleSettings()
            }
            exoPlayer.loadDefaultTrack(progressKey!!)
        }
        if (!TextUtils.isEmpty(playLyric)) {
            mController!!.mLyricView.setSubtitlePath(playLyric)
            mController!!.mLyricView.visibility = View.VISIBLE
        }
        mController!!.mSubtitleView.bindToMediaPlayer(mVideoView!!.getMediaPlayer())
        mController!!.mSubtitleView.setPlaySubtitleCacheKey(subtitleCacheKey)
        val subtitlePathCache = CacheManager.getCache(MD5.string2MD5(subtitleCacheKey!!) ?: "") as String?
        if (subtitlePathCache != null && subtitlePathCache.isNotEmpty()) {
            hideExoInternalSubtitle()
            mController!!.mSubtitleView.setSubtitlePath(subtitlePathCache)
        } else {
            if (playSubtitle != null && playSubtitle!!.length > 0) {
                hideExoInternalSubtitle()
                mController!!.mSubtitleView.setSubtitlePath(playSubtitle)
            } else {
                if (mController!!.mSubtitleView.hasInternal) {
                    if (mediaPlayer is ExoPlayer) {
                        mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                        exoInternalSubtitle = true
                        mController!!.mExoSubtitleView.visibility = View.VISIBLE
                        applyExoSubtitleSettings()
                    } else if (mediaPlayer is IjkMediaPlayer && trackInfo != null && trackInfo.getSubtitle().size > 0) {
                        mController!!.mSubtitleView.isInternal = true
                        val subtitleTrackList = trackInfo.getSubtitle()
                        val selectedIndex = trackInfo.getSubtitleSelected(true)
                        var hasMandarin = false
                        for (subtitleTrackInfoBean in subtitleTrackList) {
                            if ("国语" == subtitleTrackInfoBean.language) {
                                hasMandarin = true
                                if (selectedIndex != subtitleTrackInfoBean.trackId) {
                                    mediaPlayer.setTrack(subtitleTrackInfoBean.trackId)
                                    break
                                }
                            }
                        }
                        if (!hasMandarin) {
                            mediaPlayer.setTrack(subtitleTrackList[0].trackId)
                        }
                    }
                }
            }
        }
    }

    private fun getSubtitleUrl(obj: JSONObject?): String {
        if (obj == null) return ""
        var url = obj.optString("url", "")
        if (!TextUtils.isEmpty(url) && !FileUtils.hasExtension(url)) {
            val format = obj.optString("format", "")
            val name = obj.optString("name", "字幕")
            var ext = ".srt"
            if (format == "text/x-ssa") {
                ext = ".ass"
            } else if (format == "text/vtt") {
                ext = ".vtt"
            } else if (format == "text/lrc") {
                ext = ".lrc"
            }
            val filename = name + if (name.lowercase(Locale.ROOT).endsWith(ext)) "" else ext
            url += "#" + mController!!.encodeUrl(filename)
        }
        return url
    }

    private fun isLyricSubtitle(name: String?): Boolean {
        if (TextUtils.isEmpty(name)) return false
        val value = name!!.lowercase(Locale.ROOT)
        return value.contains("lyric") || value.contains("lrc") || name!!.contains("歌词")
    }

    private fun clearLyricView() {
        if (mController == null || mController!!.mLyricView == null) return
        mController!!.mLyricView.visibility = View.GONE
        mController!!.mLyricView.destroy()
        mController!!.mLyricView.text = ""
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        playResultObserver = Observer { info: JSONObject? ->
            if (info == null) publishQuality(null)
            if (info != null) {
                try {
                    if (isStalePlayResult(info)) {
                        LOG.i("echo-ignore stale play result")
                        return@Observer
                    }
                    mHandler!!.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT)
                    publishQuality(info)
                    webPlayUrl = null
                    progressKey = info.optString("proKey", null)
                    val parse = info.optString("parse", "1") == "1"
                    val jx = info.optString("jx", "0") == "1"
                    playSubtitle = info.optString("subt", "")
                    playLyric = info.optString("lyric", "")
                    lyricCacheKey = info.optString("lyricKey", null)
                    if (TextUtils.isEmpty(lyricCacheKey) && !TextUtils.isEmpty(progressKey)) {
                        lyricCacheKey = "$progressKey-lyric"
                    }
                    val lyrics = info.optJSONArray("lyrics")
                    if (lyrics != null && lyrics.length() > 0) {
                        playLyric = getSubtitleUrl(lyrics.optJSONObject(0))
                    }
                    val subtitles = info.optJSONArray("subs")
                    if (subtitles != null) {
                        for (i in 0 until subtitles.length()) {
                            val obj = subtitles.optJSONObject(i) ?: continue
                            val url = getSubtitleUrl(obj)
                            val name = obj.optString("name", "")
                            if (isLyricSubtitle(name)) {
                                if (TextUtils.isEmpty(playLyric)) playLyric = url
                            } else if (TextUtils.isEmpty(playSubtitle)) {
                                playSubtitle = url
                            }
                        }
                    }
                    subtitleCacheKey = info.optString("subtKey", null)
                    val playUrlStr = info.optString("playUrl", "")
                    val flag = info.optString("flag")
                    val rawUrl = info.opt("url")
                    var url = if (rawUrl is JSONArray) rawUrl.toString() else rawUrl.toString()
                    if (url.startsWith("[")) {
                        url = mController!!.firstUrlByArray(url)
                    }
                    var artwork: String? = info.optString("artwork", "")
                    if (TextUtils.isEmpty(artwork) && !TextUtils.isEmpty(playLyric) && mVodInfo != null) {
                        artwork = mVodInfo!!.pic
                    }
                    playArtwork = artwork
                    mVideoView!!.setArtwork(playArtwork)
                    val msg = info.optString("msg", "")
                    if (!TextUtils.isEmpty(msg)) {
                        handleResolvePlayUrlFailed(msg)
                        return@Observer
                    }
                    val danmaku = info.optString("danmaku", "").trim()
                    val danmuProgressKey = progressKey
                    var headers: HashMap<String, String>? = null
                    webUserAgent = null
                    webHeaderMap = null
                    headers = getHeaders(info)
                    if (headers != null) {
                        webHeaderMap = headers
                        webUserAgent = getHeaderValue(headers, "user-agent")
                        if (webUserAgent != null) webUserAgent = webUserAgent!!.trim()
                    }
                    if (parse || jx) {
                        val userJxList = (playUrlStr.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx
                        initParse(flag, userJxList, playUrlStr, url)
                    } else {
                        mController!!.showParse(false)
                        playUrl(playUrlStr + url, headers)
                    }
                    if (TextUtils.isEmpty(danmaku)) {
                        checkDanmu("")
                        searchDanmu("")
                    } else {
                        checkDanmu(danmaku, object : DanmuLoadController.LoadCallback {
                            override fun onFailed() {
                                if (TextUtils.equals(danmuProgressKey, progressKey)) {
                                    searchDanmu("")
                                }
                            }
                        })
                    }
                } catch (th: Throwable) {
                    handleResolvePlayUrlFailed("获取播放信息错误")
                }
            } else {
//                    获取播放信息错误后只需再重试一次
                handleResolvePlayUrlFailed("获取播放信息错误")
            }
        }
        sourceViewModel!!.playResult.observeForever(playResultObserver!!)
    }

    fun selectQuality(position: Int): Boolean {
        if (qualityResult == null) return false
        try {
            val urls = JSONArray(qualityResult!!.optString("url"))
            val url = urls.optString(position * 2 + 1)
            if (TextUtils.isEmpty(url)) return false
            val playUrlStr = qualityResult!!.optString("playUrl", "")
            val flag = qualityResult!!.optString("flag")
            val parse = qualityResult!!.optString("parse", "1") == "1"
            val jx = qualityResult!!.optString("jx", "0") == "1"
            val headers = getHeaders(qualityResult)
            if (parse || jx) {
                val userJxList = (playUrlStr.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx
                initParse(flag, userJxList, playUrlStr, url)
            } else {
                mController!!.showParse(false)
                playUrl(playUrlStr + url, headers)
            }
            return true
        } catch (th: Throwable) {
            return false
        }
    }

    private fun publishQuality(info: JSONObject?) {
        try {
            val urls = JSONArray(if (info == null) "" else info.optString("url"))
            if (urls.length() < 4 || urls.length() % 2 != 0) throw JSONException("invalid quality urls")
            qualityResult = JSONObject(info.toString())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, qualityResult))
        } catch (th: Throwable) {
            qualityResult = null
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, null))
        }
    }

    private fun searchDanmu(danmaku: String) {
        if (!TextUtils.isEmpty(danmaku) || !DanmakuApi.canSearch() || mVodInfo == null) return
        val series = getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
        val key = progressKey
        DanmakuApi.search(mVodInfo!!.name ?: "", series?.name ?: "", object : DanmakuApi.SearchCallback {
            override fun onFound(url: String) {
                if (!TextUtils.equals(key, progressKey)) return
                checkDanmu(url)
            }

            override fun onNotFound() {
                if (!TextUtils.equals(key, progressKey)) return
                checkDanmu("")
            }
        })
    }

    fun isStalePlayResult(info: JSONObject): Boolean {
        if (mVodInfo == null || mVodInfo!!.seriesMap == null || TextUtils.isEmpty(progressKey)) return false
        val resultKey = info.optString("proKey", "")
        if (!TextUtils.isEmpty(resultKey) && progressKey != resultKey) return true
        val resultFlag = info.optString("flag", "")
        if (!TextUtils.isEmpty(resultFlag) && resultFlag != mVodInfo!!.playFlag) return true
        val sourceUrl = info.optString("key", "")
        if (!TextUtils.isEmpty(sourceUrl)) {
            val vs = getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
            return vs != null && sourceUrl != vs.url
        }
        return false
    }

    fun setData(bundle: Bundle) {
//        mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = KtApp.instance().getVodInfo()
        sourceKey = bundle.getString("sourceKey")
        sourceBean = ApiConfig.get().getSource(sourceKey)
        ApiConfig.get().setCurrentPlaySourceKey(sourceKey)
        initPlayerCfg()
        triedLineFlags.clear()
        play(false)
    }

    private fun initData() {
        /*Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {

        }*/
    }

    fun initPlayerCfg() {
        try {
            mVodPlayerCfg = JSONObject(mVodInfo!!.playerCfg)
        } catch (th: Throwable) {
            mVodPlayerCfg = JSONObject()
        }
        try {
            if (!mVodPlayerCfg!!.has("pl")) {
                mVodPlayerCfg!!.put(
                    "pl",
                    if (sourceBean!!.playerType == -1) (Hawk.get(HawkConfig.PLAY_TYPE, 2) as Int) else sourceBean!!.playerType
                )
            }
            if (mVodPlayerCfg!!.optInt("pl", 2) == 0) {
                mVodPlayerCfg!!.put("pl", 2)
            }
            mVodPlayerCfg!!.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0))
            if (!mVodPlayerCfg!!.has("ijk")) {
                mVodPlayerCfg!!.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, "硬解码"))
            }
            if (!mVodPlayerCfg!!.has("sc")) {
                mVodPlayerCfg!!.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0))
            }
            if (!mVodPlayerCfg!!.has("sp")) {
                mVodPlayerCfg!!.put("sp", 1.0f)
            }
            if (!mVodPlayerCfg!!.has("st")) {
                mVodPlayerCfg!!.put("st", 0)
            }
            if (!mVodPlayerCfg!!.has("et")) {
                mVodPlayerCfg!!.put("et", 0)
            }
        } catch (th: Throwable) {

        }
        mController!!.setPlayerConfig(mVodPlayerCfg)
    }

    fun onBackPressed(): Boolean {
        val requestedOrientation = requireActivity().requestedOrientation
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            mController!!.mLandscapePortraitBtn.text = "竖屏"
        }
        if (mController!!.onBackPressed()) {
            return true
        }
        return false
    }

    fun setExitingPreview(exitingPreview: Boolean) {
        this.exitingPreview = exitingPreview
    }

    private fun hasAudioOnlyPlayback(): Boolean {
        return java.lang.Boolean.TRUE == getAudioOnlyPlayback()
    }

    private fun getAudioOnlyPlayback(): Boolean? {
        if (mVideoView == null) return null
        return try {
            val mediaPlayer = mVideoView!!.getMediaPlayer()
            var trackInfo: TrackInfo? = null
            if (mediaPlayer is IjkMediaPlayer) {
                trackInfo = mediaPlayer.getTrackInfo()
            } else if (mediaPlayer is ExoPlayer) {
                trackInfo = mediaPlayer.getTrackInfo()
            }
            if (trackInfo == null) null
            else !trackInfo.getAudio().isEmpty() && trackInfo.getVideo().isEmpty()
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun updateMusicSession() {
        val context = context ?: return
        if (!MusicPlaybackService.isSupported(context)) return
        if (switchingPlayback) return
        val audioOnly = getAudioOnlyPlayback()
        if (audioOnly != null) audioPlayback = audioOnly
        if (audioPlayback && TextUtils.isEmpty(playArtwork) && mVodInfo != null && !TextUtils.isEmpty(mVodInfo!!.pic)) {
            playArtwork = mVodInfo!!.pic
            mVideoView!!.setArtwork(playArtwork)
        }
        if (mVodInfo == null || mVideoView == null || !audioPlayback
            || mVideoView!!.currentPlayState == VideoView.STATE_ERROR
            || mVideoView!!.currentPlayState == VideoView.STATE_PLAYBACK_COMPLETED
        ) {
            MusicPlaybackService.stop(context, this)
            audioPlayback = false
            return
        }
        val currentSeries = getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
        val episode = if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) "" else currentSeries.name!!
        MusicPlaybackService.update(
            context, this,
            if (TextUtils.isEmpty(mVodInfo!!.name)) "TVBox" else mVodInfo!!.name,
            episode, mVodInfo!!.pic, mVideoView!!.currentPosition,
            mVideoView!!.duration, mVideoView!!.isPlaying
        )
    }

    fun resumeFromMediaSession() {
        if (mVideoView != null) {
            mVideoView!!.start()
            updateMusicSession()
        }
    }

    fun pauseFromMediaSession() {
        if (mVideoView != null) {
            mVideoView!!.pause()
            updateMusicSession()
        }
    }

    fun stopFromMediaSession() {
        if (mVideoView != null) mVideoView!!.pause()
        MusicPlaybackService.stop(context, this)
    }

    fun seekFromMediaSession(position: Long) {
        if (mVideoView != null) {
            mVideoView!!.seekTo(position)
            updateMusicSession()
        }
    }

    fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            if (mController!!.onKeyEvent(event)) {
                return true
            }
        }
        return false
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            if (mController!!.onKeyDown(keyCode, event)) {
                return true
            }
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            if (mController!!.onKeyUp(keyCode, event)) {
                return true
            }
        }
        return false
    }

    override fun onPause() {
        super.onPause()
        if (mVideoView != null && !exitingPreview && !hasAudioOnlyPlayback()) {
            mVideoView!!.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        exitingPreview = false
        if (mVideoView != null) {
            mVideoView!!.resume()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) {
            if (mVideoView != null) {
                mVideoView!!.pause()
            }
        } else {
            if (mVideoView != null) {
                mVideoView!!.resume()
            }
        }
        super.onHiddenChanged(hidden)
    }

    override fun onDestroyView() {
        audioPlayback = false
        switchingPlayback = false
        MusicPlaybackService.stop(context, this)
        if (sourceViewModel != null && playResultObserver != null) {
            sourceViewModel!!.playResult.removeObserver(playResultObserver!!)
            playResultObserver = null
        }
        super.onDestroyView()
        ApiConfig.get().setCurrentPlaySourceKey("")
        cancelPlayTimeout()
        EventBus.getDefault().unregister(this)
        if (danmuLoadController != null) {
            danmuLoadController!!.destroy()
            danmuLoadController = null
        }
        if (mVideoView != null) {
            mVideoView!!.release()
            mVideoView = null
        }
        stopLoadWebView(true)
        stopParse()
        mController!!.stopOther()
    }

    private var mVodInfo: VodInfo? = null
    private var mVodPlayerCfg: JSONObject? = null
    private var sourceKey: String? = null
    private var sourceBean: SourceBean? = null

    fun playNext(isProgress: Boolean) {
        triedLineFlags.clear()
        var hasNext: Boolean
        if (mVodInfo == null || mVodInfo!!.seriesMap!![mVodInfo!!.playFlag] == null) {
            hasNext = false
        } else {
            hasNext = mVodInfo!!.playIndex + 1 < mVodInfo!!.seriesMap!![mVodInfo!!.playFlag]!!.size
        }
        if (!hasNext) {
            Toast.makeText(requireContext(), "已经是最后一集了!", Toast.LENGTH_SHORT).show()
            return
        } else {
            mVodInfo!!.playIndex++
        }
        reusePlayerOnSwitch = true
        play(false)
    }

    fun playPrevious() {
        triedLineFlags.clear()
        var hasPre: Boolean
        if (mVodInfo == null || mVodInfo!!.seriesMap!![mVodInfo!!.playFlag] == null) {
            hasPre = false
        } else {
            hasPre = mVodInfo!!.playIndex - 1 >= 0
        }
        if (!hasPre) {
            Toast.makeText(requireContext(), "已经是第一集了!", Toast.LENGTH_SHORT).show()
            return
        }
        mVodInfo!!.playIndex--
        reusePlayerOnSwitch = true
        play(false)
    }

    private fun showEpisodeDialog() {
        if (!isAdded || mVodInfo == null || mVodInfo!!.seriesMap == null || TextUtils.isEmpty(mVodInfo!!.playFlag)) return
        val episodes = mVodInfo!!.seriesMap!![mVodInfo!!.playFlag] ?: return
        if (episodes.isEmpty()) return
        val episodeCount = episodes.size
        val currentPosition = Math.max(0, Math.min(mVodInfo!!.playIndex, episodeCount - 1))
        // 当前集及前后各最多 60 集，不受集数分组限制。
        val startPosition = Math.max(0, currentPosition - 60)
        val endPosition = Math.min(episodeCount, currentPosition + 61)
        val dialogEpisodes = ArrayList(episodes.subList(startPosition, endPosition))
        val selectedPosition = currentPosition - startPosition
        val title = if (TextUtils.isEmpty(mVodInfo!!.name)) "选集" else "${mVodInfo!!.name} 选集"
        val dialog = EpisodeDialog(requireContext(), title, dialogEpisodes, selectedPosition, object : EpisodeDialog.EpisodeSelectListener {
            override fun selectEpisode(position: Int) {
                val actualPosition = startPosition + position
                if (position < 0 || position >= dialogEpisodes.size
                    || actualPosition >= episodes.size || actualPosition == mVodInfo!!.playIndex
                ) return
                triedLineFlags.clear()
                mVodInfo!!.playIndex = actualPosition
                reusePlayerOnSwitch = true
                play(false)
            }
        })
        dialog.show()
    }

    private var autoRetryCount = 0
    private var lastRetryTime: Long = 0  // 记录上次调用时间（毫秒）

    private var allowSwitchPlayer = true
    private var hasAutoSwitchedPlayer = false
    private var autoSwitchedPlayerType = -1
    private var allowAutoSwitchLine = true
    private var playbackStarted = false
    private var playTimeoutBasePosition: Long = 0
    private val triedLineFlags: MutableSet<String> = HashSet()  // 记录已尝试过的线路

    private fun restoreAutoSwitchedPlayer() {
        if (autoSwitchedPlayerType < 0) return
        releasePlayerOnSwitch = true
        try {
            LOG.i("echo-autoRetry restore player: " + mVodPlayerCfg!!.optInt("pl", -1) + " -> " + autoSwitchedPlayerType)
            mVodPlayerCfg!!.put("pl", autoSwitchedPlayerType)
            mVodInfo!!.playerCfg = mVodPlayerCfg!!.toString()
            mController!!.setPlayerConfig(mVodPlayerCfg)
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg))
        } catch (th: Throwable) {
            th.printStackTrace()
        } finally {
            autoSwitchedPlayerType = -1
        }
    }

    fun autoRetry(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRetryTime > 60_000) {
            LOG.i("echo-reset-autoRetryCount")
            autoRetryCount = 0
            allowSwitchPlayer = true
            hasAutoSwitchedPlayer = false
            triedLineFlags.clear()
        }

        lastRetryTime = currentTime  // 更新上次调用时间
        if (loadFoundVideoUrls != null && loadFoundVideoUrls.size > 0) {
            autoRetryFromLoadFoundVideoUrls()
            return true
        }
        if (webPlayUrl != null) {
            if (allowSwitchPlayer && !hasAutoSwitchedPlayer) {
                LOG.i("echo-autoRetry switch player and replay current url")
                val playerType = mVodPlayerCfg!!.optInt("pl", -1)
                val switchSkipped = mController!!.switchPlayer()
                hasAutoSwitchedPlayer = true
                allowSwitchPlayer = false
                if (!switchSkipped) {
                    autoSwitchedPlayerType = playerType
                    stopParse()
                    initParseLoadFound()
                    if (mVideoView != null) mVideoView!!.release()
                    playUrl(webPlayUrl!!, webHeaderMap)
                    return true
                }
            }
            LOG.i("echo-autoRetry current url failed after player switch, try next line")
            return tryNextLineIfEnabled()
        }
        return tryNextLineIfEnabled()
    }

    fun tryNextLineIfEnabled(): Boolean {
        restoreAutoSwitchedPlayer()
        if (allowAutoSwitchLine && Hawk.get(HawkConfig.AUTO_SWITCH_LINE, true)) return tryNextLine()
        LOG.i("echo-autoRetry line switching disabled")
        autoRetryCount = 0
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        triedLineFlags.clear()
        return false
    }

    fun tryNextLine(): Boolean {
        if (mVodInfo == null || mVodInfo!!.seriesMap == null || mVodInfo!!.seriesMap!!.isEmpty()) {
            autoRetryCount = 0
            triedLineFlags.clear()
            return false
        }
        // 将当前线路标记为已尝试
        val currentFlag = mVodInfo!!.playFlag
        val currentIndex = Math.max(mVodInfo!!.playIndex, 0)
        val currentSeries = getCurrentSeries(currentFlag, currentIndex)
        if (!TextUtils.isEmpty(currentFlag)) {
            triedLineFlags.add(currentFlag!!)
        }
        val lineFlags = getLineFlagsInDisplayOrder()
        val currentLineIndex = findLineFlagIndex(lineFlags, currentFlag)
        val startLineIndex = if (currentLineIndex >= 0) currentLineIndex + 1 else 0
        // 查找下一条未尝试过的线路
        var nextFlag: String? = null
        var nextIndex = 0
        for (i in startLineIndex until lineFlags.size) {
            val flag = lineFlags[i]
            val seriesList = mVodInfo!!.seriesMap!![flag]
            if (!triedLineFlags.contains(flag) && seriesList != null && seriesList.isNotEmpty()) {
                nextFlag = flag
                nextIndex = findSameEpisodeIndex(currentSeries, seriesList, currentIndex)
                break
            }
        }
        if (nextFlag == null) {
            // 所有线路都已尝试过
            LOG.i("echo-autoRetry all lines exhausted")
            triedLineFlags.clear()
            autoRetryCount = 0
            return requestDetailFallbackAfterLinesExhausted()
        }
        val flagToSwitch = nextFlag
        val preProgressKey = progressKey
        val savedProgress = if (TextUtils.isEmpty(preProgressKey)) 0 else getSavedProgress(preProgressKey!!)
        val preProgress = Math.max(savedProgress, if (mVideoView == null) 0 else mVideoView!!.currentPosition)
        LOG.i("echo-autoRetry switch line: " + mVodInfo!!.playFlag + " -> " + flagToSwitch)
        // 显示切换线路提示
        if (isAdded) {
            requireActivity().runOnUiThread {
                Toast.makeText(mContext, "线路切换至" + flagToSwitch, Toast.LENGTH_SHORT).show()
            }
        }
        // 切换到新线路
        mVodInfo!!.playFlag = flagToSwitch
        mVodInfo!!.playIndex = nextIndex
        autoRetryCount = 0
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        inheritProgressKey = preProgressKey
        inheritProgress = preProgress
        reusePlayerOnSwitch = true
        play(false)
        return true
    }

    private fun requestDetailFallbackAfterLinesExhausted(): Boolean {
        val activity: Activity? = activity
        if (activity !is DetailActivity) {
            return false
        }
        return (activity as DetailActivity).startDetailFallbackAfterLinesExhausted()
    }

    private fun getLineFlagsInDisplayOrder(): List<String> {
        val lineFlags = ArrayList<String>()
        if (mVodInfo == null || mVodInfo!!.seriesMap == null) {
            return lineFlags
        }
        if (mVodInfo!!.seriesFlags != null) {
            for (flag in mVodInfo!!.seriesFlags!!) {
                if (flag != null && !TextUtils.isEmpty(flag.name) && mVodInfo!!.seriesMap!!.containsKey(flag.name) && !lineFlags.contains(flag.name)) {
                    lineFlags.add(flag.name!!)
                }
            }
        }
        for (flag in mVodInfo!!.seriesMap!!.keys) {
            if (!TextUtils.isEmpty(flag) && !lineFlags.contains(flag)) {
                lineFlags.add(flag)
            }
        }
        return lineFlags
    }

    private fun findLineFlagIndex(lineFlags: List<String>?, currentFlag: String?): Int {
        if (lineFlags == null || TextUtils.isEmpty(currentFlag)) {
            return -1
        }
        for (i in lineFlags.indices) {
            if (currentFlag == lineFlags[i]) {
                return i
            }
        }
        return -1
    }

    private fun getCurrentSeries(flag: String?, index: Int): VodInfo.VodSeries? {
        if (flag == null || mVodInfo == null || mVodInfo!!.seriesMap == null) {
            return null
        }
        val currentList = mVodInfo!!.seriesMap!![flag] ?: return null
        if (currentList.isEmpty()) {
            return null
        }
        val safeIndex = Math.max(0, Math.min(index, currentList.size - 1))
        return currentList[safeIndex]
    }

    private fun findSameEpisodeIndex(
        currentSeries: VodInfo.VodSeries?,
        targetList: List<VodInfo.VodSeries>?,
        fallbackIndex: Int
    ): Int {
        if (targetList == null || targetList.isEmpty()) {
            return 0
        }
        if (targetList.size == 1) {
            return 0
        }
        if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) {
            return Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
        }
        val currentEpisode = extractEpisodeNumber(currentSeries.name)
        var matchedIndex = -1
        var bestScore = 0
        for (i in targetList.indices) {
            val targetSeries = targetList[i]
            val score = getEpisodeMatchScore(currentSeries.name, currentEpisode, targetSeries?.name)
            if (score > bestScore) {
                bestScore = score
                matchedIndex = i
            }
        }
        if (matchedIndex >= 0) {
            return matchedIndex
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
    }

    private fun getEpisodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (TextUtils.isEmpty(currentName) || TextUtils.isEmpty(targetName)) {
            return 0
        }
        if (targetName.equals(currentName, ignoreCase = true)) {
            return 100
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80
        }
        val currentLower = currentName!!.lowercase(Locale.ROOT)
        val targetLower = targetName!!.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName!!.length >= 2 && targetLower.contains(currentLower)) {
            return 70
        }
        if (currentEpisode < 0 && targetName!!.length >= 2 && currentLower.contains(targetLower)) {
            return 60
        }
        return 0
    }

    private fun extractEpisodeNumber(name: String?): Int {
        if (TextUtils.isEmpty(name)) {
            return -1
        }
        try {
            var text = name!!.replace("\\[.*?\\]|\\(.*?\\)".toRegex(), "")
            text = text.replace("\\b(19|20)\\d{2}\\b".toRegex(), "")
            text = text.lowercase(Locale.ROOT).replace("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4".toRegex(), "")
            val matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text)
            if (matcher.find()) {
                return matcher.group(1).toInt()
            }
            val number = text.replace("\\D+".toRegex(), "")
            if (!TextUtils.isEmpty(number)) {
                return number.toInt()
            }
        } catch (ignored: Exception) {
        }
        return -1
    }

    fun autoRetryFromLoadFoundVideoUrls() {
        val videoUrl = loadFoundVideoUrls.poll()
        val header = loadFoundVideoUrlsHeader[videoUrl]
        playUrl(videoUrl, header)
    }

    fun initParseLoadFound() {
        loadFoundCount.set(0)
        loadFoundVideoUrls = LinkedList()
        loadFoundVideoUrlsHeader = HashMap()
    }

    fun setPlayTitle(show: Boolean) {
        if (show) {
            var playTitleInfo = ""
            if (mVodInfo != null) {
                playTitleInfo = "${mVodInfo!!.name} ${mVodInfo!!.seriesMap!![mVodInfo!!.playFlag]!![mVodInfo!!.playIndex]!!.name}"
            }
            mController!!.setTitle(playTitleInfo)
        } else {
            mController!!.setTitle("")
        }
    }

    fun play(reset: Boolean) {
        if (mVodInfo == null) return
        val reusePlayer = reusePlayerOnSwitch && !releasePlayerOnSwitch
        reusePlayerOnSwitch = false
        releasePlayerOnSwitch = false
        switchingPlayback = true
        audioPlayback = false
        playArtwork = ""
        exitingPreview = false
        val vs = mVodInfo!!.seriesMap!![mVodInfo!!.playFlag]!![mVodInfo!!.playIndex]
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodInfo))
        if (reusePlayer) {
            mPlayLoadTip!!.visibility = View.GONE
            mPlayLoadErr!!.visibility = View.GONE
            mPlayLoading!!.visibility = View.VISIBLE
        } else {
            setTip("正在获取播放信息", true, false)
        }
        val playTitleInfo = "${mVodInfo!!.name} ${vs.name}"
        mController!!.setTitle(playTitleInfo)

        stopParse()
        playbackStarted = false
        playTimeoutBasePosition = 0
        webPlayUrl = null
        webHeaderMap = null
        initParseLoadFound()
        allowSwitchPlayer = true
        hasAutoSwitchedPlayer = false
        mController!!.stopOther()
        resetDanmuState()
        clearLyricView()
        mVideoView!!.clearArtwork()
        if (mVideoView != null) {
            if (reusePlayer) {
                val previousPosition = mVideoView!!.currentPosition
                if (previousPosition > 0 && !TextUtils.isEmpty(progressKey)) {
                    CacheManager.save(MD5.string2MD5(progressKey!!) ?: "", previousPosition)
                }
                val mediaPlayer = mVideoView!!.getMediaPlayer()
                if (mediaPlayer != null) {
                    mediaPlayer.stop()
                }
            } else {
                mVideoView!!.release()
            }
        }
        ImgUtil.clearMemoryCache()
        subtitleCacheKey = "${mVodInfo!!.sourceKey}-${mVodInfo!!.id}-${mVodInfo!!.playFlag}-${mVodInfo!!.playIndex}-${vs.name}-subt"
        progressKey = "${mVodInfo!!.sourceKey}${mVodInfo!!.id}${mVodInfo!!.playFlag}${mVodInfo!!.playIndex}${vs.name}"
        startResolvePlayUrlTimeout()
        //重新播放清除现有进度
        if (reset) {
            CacheManager.delete(MD5.string2MD5(progressKey!!) ?: "", 0)
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey!!) ?: "", 0)
        } else {
            inheritProgressIfNeeded()
            try {
                val playerType = mVodPlayerCfg!!.getInt("pl")
                if (playerType == 1) {
                    mController!!.mSubtitleView.visibility = View.VISIBLE
                } else {
                    mController!!.mSubtitleView.visibility = View.GONE
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        if (Jianpian.isJpUrl(vs.url!!)) { //荐片地址特殊判断
            val jp_url = vs.url
            mController!!.showParse(false)
            if (vs.url!!.startsWith("tvbox-xg:")) {
                playUrl(Jianpian.JPUrlDec(jp_url!!.substring(9)), null)
            } else {
                playUrl(Jianpian.JPUrlDec(jp_url!!), null)
            }
            return
        }
        if (Thunder.play(vs.url!!, object : Thunder.ThunderCallback {
                override fun status(code: Int, info: String?) {
                    if (code < 0) {
                        setTip(info, false, true)
                    } else {
                        setTip(info, true, false)
                    }
                }

                override fun list(urlMap: Map<Int, String>) {
                }

                override fun play(url: String?) {
                    playUrl(url!!, null)
                }
            })
        ) {
            mController!!.showParse(false)
            return
        }
        sourceViewModel!!.getPlay(sourceKey!!, mVodInfo!!.playFlag!!, progressKey!!, vs.url!!, subtitleCacheKey!!)
    }

    private fun inheritProgressIfNeeded() {
        try {
            if (TextUtils.isEmpty(inheritProgressKey) || TextUtils.isEmpty(progressKey)) return
            if (inheritProgressKey == progressKey) return
            if (inheritProgress <= 0) return
            val targetCache = CacheManager.getCache(MD5.string2MD5(progressKey!!) ?: "")
            if (targetCache == null) {
                CacheManager.save(MD5.string2MD5(progressKey!!) ?: "", inheritProgress)
            }
        } finally {
            inheritProgressKey = null
            inheritProgress = 0
        }
    }

    private var playSubtitle: String? = null
    private var subtitleCacheKey: String? = null
    private var progressKey: String? = null
    private var inheritProgressKey: String? = null
    private var inheritProgress: Long = 0
    private var parseFlag: String? = null
    private var webUrl: String? = null
    private var webUserAgent: String? = null
    private var webHeaderMap: HashMap<String, String>? = null
    private var webPlayUrl: String? = null
    private var m3u8ProxyUrl: String? = null
    private var m3u8SourceUrl: String? = null

    private fun initParse(flag: String, useParse: Boolean, playUrl: String, url: String) {
        parseFlag = flag
        webUrl = url
        var parseBean: ParseBean? = null
        mController!!.showParse(useParse)
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse()
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = ParseBean()
                parseBean.type = 1
                parseBean.url = playUrl.substring(5)
            } else if (playUrl.startsWith("parse:")) {
                val parseRedirect = playUrl.substring(6)
                for (pb in ApiConfig.get().getParseBeanList()) {
                    if (pb.name == parseRedirect) {
                        parseBean = pb
                        break
                    }
                }
            }
            if (parseBean == null) {
                parseBean = ParseBean()
                parseBean.type = 0
                parseBean.url = playUrl
            }
        }
        doParse(parseBean)
    }

    @Throws(JSONException::class)
    fun jsonParse(input: String, json: String): JSONObject? {
        val jsonPlayData = JSONObject(json)
        var playData = jsonPlayData.optJSONObject("data")
        if (playData == null) {
            playData = jsonPlayData
        }
        var url = playData.optString("url", jsonPlayData.optString("url", ""))
        if (url.startsWith("//")) {
            url = "http:$url"
        }
        var parse = false
        if (url.startsWith("video://")) {
            url = url.substring(8)
            parse = true
        }
        url = DefaultConfig.checkReplaceProxy(url)
        if (!url.startsWith("http") && !url.startsWith("data:application")) {
            return null
        }
        parse = parse || playData.optInt("parse", jsonPlayData.optInt("parse", 0)) == 1
        val headers = JSONObject()
        val headerMap = getHeaders(jsonPlayData)
        val dataHeaderMap = getHeaders(playData)
        if (headerMap != null) putHeaders(headers, headerMap)
        if (dataHeaderMap != null) putHeaders(headers, dataHeaderMap)
        val ua = playData.optString("user-agent", jsonPlayData.optString("user-agent", ""))
        if (ua.trim().length > 0) {
            headers.put("User-Agent", " $ua")
        }
        val referer = playData.optString("referer", jsonPlayData.optString("referer", ""))
        if (referer.trim().length > 0) {
            headers.put("Referer", " $referer")
        }
        val taskResult = JSONObject()
        taskResult.put("header", headers)
        taskResult.put("url", url)
        taskResult.put("parse", if (parse) 1 else 0)
        return taskResult
    }

    private fun getHeaders(obj: JSONObject?): HashMap<String, String>? {
        if (obj == null) return null
        val headers = HashMap<String, String>()
        appendHeaders(headers, obj.opt("header"))
        appendHeaders(headers, obj.opt("headers"))
        return if (headers.isEmpty()) null else headers
    }

    private fun appendHeaders(headers: HashMap<String, String>, rawHeaders: Any?) {
        if (rawHeaders == null || rawHeaders === JSONObject.NULL) return
        try {
            var json: JSONObject? = null
            if (rawHeaders is JSONObject) {
                json = rawHeaders
            } else if (rawHeaders is String) {
                val text = (rawHeaders as String).trim()
                if (!TextUtils.isEmpty(text)) {
                    json = JSONObject(text)
                }
            }
            if (json == null) return
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!TextUtils.isEmpty(key)) {
                    headers[key] = json.optString(key, "")
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun putHeaders(target: JSONObject?, headers: HashMap<String, String>?) {
        if (target == null || headers == null) return
        for (key in headers.keys) {
            target.put(key, headers[key])
        }
    }

    private fun getHeaderValue(headers: HashMap<String, String>?, name: String?): String? {
        if (headers == null || name == null) return null
        for (key in headers.keys) {
            if (name.equals(key, ignoreCase = true)) {
                return headers[key]
            }
        }
        return null
    }

    fun startResolvePlayUrlTimeout() {
        cancelPlayTimeout()
        mHandler!!.sendEmptyMessageDelayed(MSG_RESOLVE_PLAY_URL_TIMEOUT, getResolvePlayUrlTimeoutMs())
    }

    private fun getResolvePlayUrlTimeoutMs(): Long {
        if (sourceBean == null) return RESOLVE_PLAY_URL_TIMEOUT_MS
        return Math.max(RESOLVE_PLAY_URL_TIMEOUT_MS, (sourceBean!!.playTimeoutSeconds + 1L) * 1000L)
    }

    fun startSwitchLinePlayTimeout() {
        if (!allowAutoSwitchLine) {
            cancelPlayTimeout()
            return
        }
        cancelPlayTimeout()
        LOG.i("echo-switchLinePlay start timeout")
        mHandler!!.sendEmptyMessageDelayed(MSG_SWITCH_LINE_PLAY_TIMEOUT, SWITCH_LINE_PLAY_TIMEOUT_MS)
    }

    fun cancelSwitchLinePlayTimeout() {
        cancelPlayTimeout()
    }

    fun cancelPlayTimeout() {
        mHandler!!.removeMessages(MSG_RESOLVE_PLAY_URL_TIMEOUT)
        mHandler!!.removeMessages(MSG_SWITCH_LINE_PLAY_TIMEOUT)
    }

    fun setAutoSwitchLineEnabled(enabled: Boolean) {
        allowAutoSwitchLine = enabled
        if (!enabled) {
            cancelPlayTimeout()
            triedLineFlags.clear()
        }
    }

    fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        if (mController != null) {
            mController!!.setPreviewMode(previewMode)
            mController!!.mLyricView.setTextSize(if (previewMode) 16f else 24f)
        }
    }

    fun pauseForHidden() {
        cancelPlayTimeout()
        stopParse()
        playbackStarted = false
        if (mVideoView != null) {
            mVideoView!!.pause()
            mVideoView!!.release()
        }
        mController!!.stopOther()
        resetDanmuState()
        webPlayUrl = null
        webHeaderMap = null
        initParseLoadFound()
    }

    fun markPlaybackStarted() {
        playbackStarted = true
        cancelPlayTimeout()
    }

    fun isPlaybackStarted(): Boolean {
        if (playbackStarted) return true
        if (mVideoView == null) return false
        val state = mVideoView!!.currentPlayState
        return isStartedPlayState(state) || hasPlaybackProgress(mVideoView!!.currentPosition) || mVideoView!!.isPlaying
    }

    fun isStartedPlayState(state: Int): Boolean {
        return state == VideoView.STATE_PREPARED || state == VideoView.STATE_BUFFERED || state == VideoView.STATE_PLAYING
    }

    fun hasPlaybackProgress(progress: Long): Boolean {
        return progress > Math.max(playTimeoutBasePosition, 0) + 1000
    }

    fun handleResolvePlayUrlTimeout() {
        LOG.i("echo-resolvePlayUrl timeout, try next line")
        if (sourceViewModel != null) sourceViewModel!!.cancelPlayRequest()
        stopParse()
        if (!tryNextLineIfEnabled()) {
            stopMusicSessionForFailedPlayback()
            setTip("获取播放地址超时", false, true)
        }
    }

    fun handleResolvePlayUrlFailed(err: String) {
        LOG.i("echo-resolvePlayUrl failed, try next line: $err")
        if (sourceViewModel != null) sourceViewModel!!.cancelPlayRequest()
        stopParse()
        if (tryNextLineIfEnabled()) return
        cancelPlayTimeout()
        stopMusicSessionForFailedPlayback()
        setTip(err, false, true)
    }

    fun handleSwitchLinePlayTimeout() {
        val state = if (mVideoView == null) -1 else mVideoView!!.currentPlayState
        LOG.i("echo-switchLinePlay timeout state: $state, started: $playbackStarted")
        if (isPlaybackStarted()) {
            cancelPlayTimeout()
            hideTipOnUiThread()
            return
        }
        LOG.i("echo-switchLinePlay timeout, try next line")
        stopParse()
        if (hasAutoSwitchedPlayer) {
            if (!tryNextLineIfEnabled()) {
                stopMusicSessionForFailedPlayback()
                setTip("播放超时", false, true)
            }
            return
        }
        if (!autoRetry()) {
            stopMusicSessionForFailedPlayback()
            setTip("播放超时", false, true)
        }
    }

    fun stopParse() {
        mHandler!!.removeMessages(MSG_PARSE_TIMEOUT)
        stopLoadWebView(false)
        OkGo.getInstance().cancelTag("play")
        OkGo.getInstance().cancelTag("json_jx")
        if (parseThreadPool != null) {
            try {
                parseThreadPool!!.shutdown()
                parseThreadPool = null
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }
    }

    var parseThreadPool: ExecutorService? = null

    private fun doParse(pb: ParseBean?) {
        stopParse()
        initParseLoadFound()
        if (pb!!.type == 4) {
            parseMix(pb, true)
        } else if (pb.type == 0) {
            setTip("正在嗅探播放地址", true, false)
            mHandler!!.removeMessages(MSG_PARSE_TIMEOUT)
            mHandler!!.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, 20 * 1000)
            if (pb.ext != null) {
                // 解析ext
                try {
                    val reqHeaders = HashMap<String, String>()
                    val jsonObject = JSONObject(pb.ext)
                    val headerMap = getHeaders(jsonObject)
                    if (headerMap != null) {
                        for (key in headerMap.keys) {
                            if (key.equals("user-agent", ignoreCase = true)) {
                                webUserAgent = headerMap[key]!!.trim()
                            } else {
                                reqHeaders[key] = headerMap[key]!!
                            }
                        }
                        if (reqHeaders.size > 0) webHeaderMap = reqHeaders
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            loadWebView(pb.url + webUrl!!)
        } else if (pb.type == 1) { // json 解析
            setTip("正在解析播放地址", true, false)
            // 解析ext
            val reqHeaders = HttpHeaders()
            try {
                val jsonObject = JSONObject(pb.ext)
                val headerMap = getHeaders(jsonObject)
                if (headerMap != null) {
                    for (key in headerMap.keys) {
                        reqHeaders.put(key, headerMap[key])
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            OkGo.get<String>(pb.url + mController!!.encodeUrl(webUrl!!))
                .tag("json_jx")
                .headers(reqHeaders)
                .execute(object : AbsCallback<String>() {
                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        val body = response.body() ?: throw IllegalStateException("网络请求错误")
                        return body.string()
                    }

                    override fun onSuccess(response: Response<String>) {
                        val json = response.body()
                        try {
                            val rs = jsonParse(webUrl!!, json)
                            val headers = getHeaders(rs)
                            if (rs!!.optInt("parse", 0) == 1) {
                                webHeaderMap = headers
                                if (headers != null) {
                                    webUserAgent = getHeaderValue(headers, "user-agent")
                                    if (webUserAgent != null) webUserAgent = webUserAgent!!.trim()
                                }
                                loadWebView(DefaultConfig.checkReplaceProxy(rs.getString("url")))
                            } else {
                                playUrl(rs.getString("url"), headers)
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                            errorWithRetry("解析错误", false)
//                                setTip("解析错误", false, true);
                        }
                    }

                    override fun onError(response: Response<String>) {
                        super.onError(response)
                        errorWithRetry("解析错误", false)
//                            setTip("解析错误", false, true);
                    }
                })
        } else if (pb.type == 2) { // json 扩展
            setTip("正在解析播放地址", true, false)
            parseThreadPool = Executors.newSingleThreadExecutor()
            val jxs = LinkedHashMap<String, String>()
            for (p in ApiConfig.get().getParseBeanList()) {
                if (p.type == 1) {
                    jxs[p.name] = p.mixUrl()
                }
            }
            parseThreadPool!!.execute {
                val rs = ApiConfig.get().jsonExt(pb.url, jxs, webUrl)
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误", false);
                    setTip("解析错误", false, true)
                } else {
                    val headers = getHeaders(rs)
                    if (rs.has("jxFrom")) {
                        if (!isAdded) return@execute
                        requireActivity().runOnUiThread {
                            Toast.makeText(mContext, "解析来自:" + rs.optString("jxFrom"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    val parseWV = rs.optInt("parse", 0) == 1
                    if (parseWV) {
                        val wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                        loadUrl(wvUrl)
                    } else {
                        playUrl(rs.optString("url", ""), headers)
                    }
                }
            }
        } else if (pb.type == 3) { // json 聚合
            parseMix(pb, false)
        }
    }

    private fun parseMix(pb: ParseBean?, isSuper: Boolean) {
        setTip("正在解析播放地址", true, false)
        parseThreadPool = Executors.newSingleThreadExecutor()
        val jxs = LinkedHashMap<String, HashMap<String, String>>()
        val json_jxs = LinkedHashMap<String, String>()
        var extendName = ""
        for (p in ApiConfig.get().getParseBeanList()) {
            val data = HashMap<String, String>()
            data["url"] = p.url
            if (p.url == pb!!.url) {
                extendName = p.name
            }
            data["type"] = "${p.type}"
            data["ext"] = p.ext
            jxs[p.name] = data

            if (p.type == 1) {
                json_jxs[p.name] = p.mixUrl()
            }
        }
        val finalExtendName = extendName
        parseThreadPool!!.execute {
            if (isSuper) {
                //并发执行 嗅探和json
                val rs = SuperParse.parse(jxs, "${parseFlag}123", webUrl!!)
                if (!rs.has("url") || rs.optString("url").isEmpty()) {
                    setTip("解析错误", false, true)
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            webUserAgent = rs.optString("ua").trim()
                        }
                        setTip("超级解析中", true, false)

                        if (!isAdded) return@execute
                        requireActivity().runOnUiThread {
                            val mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                            stopParse()
                            mHandler!!.removeMessages(MSG_PARSE_TIMEOUT)
                            mHandler!!.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, 20 * 1000)
                            loadWebView(mixParseUrl)
                        }
                        parseThreadPool!!.execute {
                            val res = SuperParse.doJsonJx(webUrl!!)
                            rsJsonJX(res, true)
                        }
                    } else {
                        rsJsonJX(rs, false)
                    }
                }
            } else {
                val rs = ApiConfig.get().jsonExtMix("${parseFlag}111", pb!!.url, finalExtendName, jxs, webUrl)
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误", false);
                    setTip("解析错误", false, true)
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            webUserAgent = rs.optString("ua").trim()
                        }
                        if (!isAdded) return@execute
                        requireActivity().runOnUiThread {
                            val mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""))
                            stopParse()
                            setTip("正在嗅探播放地址", true, false)
                            mHandler!!.removeMessages(MSG_PARSE_TIMEOUT)
                            mHandler!!.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, 20 * 1000)
                            loadWebView(mixParseUrl)
                        }
                    } else {
                        rsJsonJX(rs, false)
                    }
                }
            }
        }
    }

    private fun rsJsonJX(rs: JSONObject?, isSuper: Boolean) {
        if (isSuper) {
            if (rs == null || !rs.has("url")) return
            stopLoadWebView(false)
        }
        val headers = getHeaders(rs)
        if (rs!!.has("jxFrom")) {
            if (!isAdded) return
            requireActivity().runOnUiThread {
                Toast.makeText(mContext, "解析来自:" + rs.optString("jxFrom"), Toast.LENGTH_SHORT).show()
            }
        }
        playUrl(rs.optString("url", ""), headers)
    }

    fun getPlayer(): MyVideoView? {
        return mVideoView
    }

    // RemoteServer 以属性方式访问(playFragment.mediaInfo);JVM 侧仍生成 getMediaInfo()
    val mediaInfo: JsonObject
        get() {
            val info = JsonObject()
            if (mVideoView == null) return info
            try {
                val playState = mVideoView!!.currentPlayState
                val state: Int
                if (mVideoView!!.isPlaying) state = 3
                else if (playState == VideoView.STATE_BUFFERING) state = 1
                else if (playState == VideoView.STATE_PAUSED || playState == VideoView.STATE_PREPARED
                    || playState == VideoView.STATE_BUFFERED
                ) state = 2
                else state = -1
                info.addProperty("state", state)
                info.addProperty("speed", mVideoView!!.speed)
                info.addProperty("duration", mVideoView!!.duration)
                info.addProperty("position", mVideoView!!.currentPosition)
                info.addProperty("url", if (webPlayUrl == null) "" else webPlayUrl)
                var title = ""
                var artist = ""
                var artwork = ""
                if (mVodInfo != null) {
                    title = if (TextUtils.isEmpty(mVodInfo!!.name)) "" else mVodInfo!!.name!!
                    val series = getCurrentSeries(mVodInfo!!.playFlag, mVodInfo!!.playIndex)
                    if (series != null && !TextUtils.isEmpty(series.name)) artist = series.name!!
                    artwork = if (TextUtils.isEmpty(mVodInfo!!.pic)) "" else mVodInfo!!.pic!!
                }
                if (!TextUtils.isEmpty(playArtwork)) artwork = playArtwork!!
                info.addProperty("title", title)
                info.addProperty("artist", artist)
                info.addProperty("artwork", artwork)
            } catch (th: Throwable) {
                LOG.e("echo-media getMediaInfo error: " + th.message)
            }
            return info
        }

    // webview
    private var mXwalkWebView: XWalkView? = null
    private var mSysWebView: WebView? = null
    private val loadedUrls = HashMap<String, Boolean>()
    private var loadFoundVideoUrls = LinkedList<String>()
    private var loadFoundVideoUrlsHeader = HashMap<String, HashMap<String, String>>()
    private val loadFoundCount = AtomicInteger(0)

    fun loadWebView(url: String) {
        if (mSysWebView == null && mXwalkWebView == null) {
            val useSystemWebView = Hawk.get(HawkConfig.PARSE_WEBVIEW, true)
            if (!useSystemWebView) {
                XWalkUtils.tryUseXWalk(mContext, object : XWalkUtils.XWalkState {
                    override fun success() {
                        initWebView(false)
                        loadUrl(url)
                    }

                    override fun fail() {
                        Toast.makeText(mContext, "XWalkView不兼容，已替换为系统自带WebView", Toast.LENGTH_SHORT).show()
                        initWebView(true)
                        loadUrl(url)
                    }

                    override fun ignore() {
                        Toast.makeText(mContext, "XWalkView运行组件未下载，已替换为系统自带WebView", Toast.LENGTH_SHORT).show()
                        initWebView(true)
                        loadUrl(url)
                    }
                })
            } else {
                initWebView(true)
                loadUrl(url)
            }
        } else {
            loadUrl(url)
        }
    }

    fun initWebView(useSystemWebView: Boolean) {
        if (useSystemWebView) {
            mSysWebView = MyWebView(mContext!!)
            configWebViewSys(mSysWebView)
        } else {
            mXwalkWebView = MyXWalkView(mContext!!)
            configWebViewX5(mXwalkWebView)
        }
    }

    fun loadUrl(url: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            if (mXwalkWebView != null) {
                mXwalkWebView!!.stopLoading()
                if (webUserAgent != null) {
                    mXwalkWebView!!.settings.userAgentString = webUserAgent
                }
                //mXwalkWebView.clearCache(true);
                if (webHeaderMap != null) {
                    mXwalkWebView!!.loadUrl(url, webHeaderMap)
                } else {
                    mXwalkWebView!!.loadUrl(url)
                }
            }
            if (mSysWebView != null) {
                mSysWebView!!.stopLoading()
                if (webUserAgent != null) {
                    mSysWebView!!.settings.userAgentString = webUserAgent
                }
                //mSysWebView.clearCache(true);
                val headerMap = webHeaderMap
                if (headerMap != null) {
                    mSysWebView!!.loadUrl(url, headerMap)
                } else {
                    mSysWebView!!.loadUrl(url)
                }
            }
        }
    }

    fun stopLoadWebView(destroy: Boolean) {
        if (mActivity == null) return
        if (!isAdded) return
        requireActivity().runOnUiThread {

            if (mXwalkWebView != null) {
                mXwalkWebView!!.stopLoading()
                mXwalkWebView!!.loadUrl("about:blank")
                if (destroy) {
                    mXwalkWebView!!.clearCache(true)
                    mXwalkWebView!!.removeAllViews()
                    mXwalkWebView!!.onDestroy()
                    mXwalkWebView = null
                }
            }
            if (mSysWebView != null) {
                mSysWebView!!.stopLoading()
                mSysWebView!!.loadUrl("about:blank")
                if (destroy) {
                    mSysWebView!!.clearCache(true)
                    mSysWebView!!.removeAllViews()
                    mSysWebView!!.destroy()
                    mSysWebView = null
                }
            }
        }
    }

    fun checkVideoFormat(url: String): Boolean {
        return try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false
            }
            if (sourceBean!!.type == 3) {
                val sp = ApiConfig.get().getCSP(sourceBean!!)
                if (sp.manualVideoCheck()) {
                    return sp.isVideoFormat(url)
                }
            }
            VideoParseRuler.checkIsVideoForParse(webUrl, url)
        } catch (e: Exception) {
            false
        }
    }

    inner class MyWebView(context: Context) : WebView(context) {
        override fun setOverScrollMode(mode: Int) {
            super.setOverScrollMode(mode)
            if (mContext is Activity)
                AutoSize.autoConvertDensityOfCustomAdapt(mContext as Activity, this@PlayFragment)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return false
        }
    }

    inner class MyXWalkView(context: Context) : XWalkView(context) {
        override fun setOverScrollMode(mode: Int) {
            super.setOverScrollMode(mode)
            if (mContext is Activity)
                AutoSize.autoConvertDensityOfCustomAdapt(mContext as Activity, this@PlayFragment)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configWebViewSys(webView: WebView?) {
        if (webView == null) {
            return
        }
        val layoutParams = if (Hawk.get(HawkConfig.DEBUG_OPEN, false))
            ViewGroup.LayoutParams(800, 400) else
            ViewGroup.LayoutParams(1, 1)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.clearFocus()
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS
        if (!isAdded) return
        requireActivity().addContentView(webView, layoutParams)
        /* 添加webView配置 */
        val settings = webView.settings
        settings.setNeedInitialFocus(false)
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.databaseEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptEnabled = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.mediaPlaybackRequiresUserGesture = false
        }
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            settings.blockNetworkImage = false
        } else {
            settings.blockNetworkImage = true
        }
        settings.useWideViewPort = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.setSupportZoom(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        /* 添加webView配置 */
        //设置编码
        settings.defaultTextEncodingName = "utf-8"
        settings.userAgentString = webView.settings.userAgentString
//         settings.setUserAgentString(ANDROID_UA);

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                return false
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                return true
            }
        }
        val mSysWebClient = SysWebClient()
        webView.webViewClient = mSysWebClient
        webView.setBackgroundColor(Color.BLACK)
    }

    private inner class SysWebClient : WebViewClient() {

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(webView: WebView, sslErrorHandler: SslErrorHandler, sslError: SslError) {
            sslErrorHandler.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return false
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            LOG.i("echo-onPageFinished url:$url")
            if (url != "about:blank") {
                mController!!.evaluateScript(sourceBean!!, url, view, null)
            }
        }

        fun checkIsVideo(url0: String, headers: HashMap<String, String>): WebResourceResponse? {
            var url = url0
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return WebResourceResponse("image/x-icon", "UTF-8", null as InputStream?)
                }
                return null
            }

            val isFilter = VideoParseRuler.isFilter(webUrl, url)
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:$url")
                return null
            }

            val ad: Boolean
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url)
                loadedUrls[url] = ad
            } else {
                ad = loadedUrls[url] == true
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url)
                    loadFoundVideoUrlsHeader[url] = headers
                    LOG.i("echo-loadFoundVideoUrl:$url")
                    if (loadFoundCount.incrementAndGet() == 1) {
                        stopLoadWebView(false)
                        SuperParse.stopJsonJx()
                        url = loadFoundVideoUrls.poll()
                        mHandler!!.removeMessages(MSG_PARSE_TIMEOUT)
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (!TextUtils.isEmpty(cookie)) headers["Cookie"] = " $cookie" //携带cookie
                        playUrl(url, headers)
                    }
                }
            }

            return if (ad || loadFoundCount.get() > 0)
                AdBlocker.createEmptyResource()
            else
                null
        }

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            LOG.i("echo-shouldInterceptRequest url:$url")
            val webHeaders = HashMap<String, String>()
            val hds = request.requestHeaders
            if (hds != null && hds.keys.size > 0) {
                for (k in hds.keys) {
                    if (k.equals("user-agent", ignoreCase = true)
                        || k.equals("referer", ignoreCase = true)
                        || k.equals("origin", ignoreCase = true)
                    ) {
                        webHeaders[k] = " " + hds[k]
                    }
                }
            }
            return checkIsVideo(url, webHeaders)
        }

        override fun onLoadResource(webView: WebView, url: String) {
            super.onLoadResource(webView, url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configWebViewX5(webView: XWalkView?) {
        if (webView == null) {
            return
        }
        val layoutParams = if (Hawk.get(HawkConfig.DEBUG_OPEN, false))
            ViewGroup.LayoutParams(800, 400) else
            ViewGroup.LayoutParams(1, 1)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.clearFocus()
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS
        if (!isAdded) return
        requireActivity().addContentView(webView, layoutParams)
        /* 添加webView配置 */
        val settings: XWalkSettings = webView.settings
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.databaseEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptEnabled = true

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            settings.blockNetworkImage = false
        } else {
            settings.blockNetworkImage = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.mediaPlaybackRequiresUserGesture = false
        }
        settings.useWideViewPort = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.setSupportZoom(false)
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // settings.setUserAgentString(ANDROID_UA);

        webView.setBackgroundColor(Color.BLACK)
        webView.setUIClient(object : XWalkUIClient(webView) {
            override fun onConsoleMessage(view: XWalkView, message: String, lineNumber: Int, sourceId: String, messageType: XWalkUIClient.ConsoleMessageType): Boolean {
                return false
            }

            override fun onJsAlert(view: XWalkView, url: String, message: String, result: XWalkJavascriptResult): Boolean {
                return true
            }

            override fun onJsConfirm(view: XWalkView, url: String, message: String, result: XWalkJavascriptResult): Boolean {
                return true
            }

            override fun onJsPrompt(view: XWalkView, url: String, message: String, defaultValue: String, result: XWalkJavascriptResult): Boolean {
                return true
            }
        })
        val mX5WebClient = XWalkWebClient(webView)
        webView.setResourceClient(mX5WebClient)
    }

    private inner class XWalkWebClient(view: XWalkView) : XWalkResourceClient(view) {

        override fun onDocumentLoadedInFrame(view: XWalkView, frameId: Long) {
            super.onDocumentLoadedInFrame(view, frameId)
        }

        override fun onLoadStarted(view: XWalkView, url: String) {
            super.onLoadStarted(view, url)
        }

        override fun onLoadFinished(view: XWalkView, url: String) {
            super.onLoadFinished(view, url)
            LOG.i("echo-onLoadFinished url:$url")
            if (url != "about:blank") {
                mController!!.evaluateScript(sourceBean!!, url, null, view)
            }
        }

        override fun onProgressChanged(view: XWalkView, progressInPercent: Int) {
            super.onProgressChanged(view, progressInPercent)
        }

        override fun shouldInterceptLoadRequest(view: XWalkView, request: XWalkWebResourceRequest): XWalkWebResourceResponse? {
            var url = request.url.toString()
            LOG.i("echo-shouldInterceptLoadRequest url:$url")
            // suppress favicon requests as we don't display them anywhere
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return createXWalkWebResourceResponse("image/x-icon", "UTF-8", null)
                }
                return null
            }

            val isFilter = VideoParseRuler.isFilter(webUrl, url)
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:$url")
                return null
            }

            val ad: Boolean
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url)
                loadedUrls[url] = ad
            } else {
                ad = loadedUrls[url] == true
            }
            if (!ad) {
                if (checkVideoFormat(url)) {
                    val webHeaders = HashMap<String, String>()
                    val hds = request.requestHeaders
                    if (hds != null && hds.keys.size > 0) {
                        for (k in hds.keys) {
                            if (k.equals("user-agent", ignoreCase = true)
                                || k.equals("referer", ignoreCase = true)
                                || k.equals("origin", ignoreCase = true)
                            ) {
                                webHeaders[k] = " " + hds[k]
                            }
                        }
                    }
                    loadFoundVideoUrls.add(url)
                    loadFoundVideoUrlsHeader[url] = webHeaders
                    LOG.i("echo-loadFoundVideoUrl:$url")
                    if (loadFoundCount.incrementAndGet() == 1) {
                        stopLoadWebView(false)
                        SuperParse.stopJsonJx()
                        mHandler!!.removeMessages(MSG_PARSE_TIMEOUT)
                        url = loadFoundVideoUrls.poll()
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (!TextUtils.isEmpty(cookie)) webHeaders["Cookie"] = " $cookie" //携带cookie
                        playUrl(url, webHeaders)
                    }
                }
            }
            return if (ad || loadFoundCount.get() > 0)
                createXWalkWebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
            else
                null
        }

        override fun shouldOverrideUrlLoading(view: XWalkView, s: String): Boolean {
            return false
        }

        override fun onReceivedSslError(view: XWalkView, callback: ValueCallback<Boolean>, error: SslError) {
            callback.onReceiveValue(true)
        }
    }
}
