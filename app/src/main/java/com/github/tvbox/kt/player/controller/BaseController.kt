package com.github.tvbox.kt.player.controller

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Message
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import xyz.doikki.videoplayer.controller.BaseVideoController
import xyz.doikki.videoplayer.controller.IControlComponent
import xyz.doikki.videoplayer.controller.IGestureComponent
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils

/**
 * 播放控制基类(手势/亮度/音量/进度),Kotlin 移植(与旧版 BaseController 一致)。
 */
abstract class BaseController : BaseVideoController,
    GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {

    private var mGestureDetector: GestureDetector? = null
    private var mAudioManager: AudioManager? = null
    private var mIsGestureEnabled = true
    private var mStreamVolume = 0
    private var mBrightness = 0f
    private var mSeekPosition = -1
    private var mFirstTouch = false
    private var mChangePosition = false
    private var mChangeBrightness = false
    private var mChangeVolume = false
    private var mCanChangePosition = true
    private var mEnableInNormal = false
    private var mCanSlide = false
    private var mCurPlayState = 0

    protected var mHandler: Handler

    protected var mHandlerCallback: HandlerCallback? = null

    protected interface HandlerCallback {
        fun callback(msg: Message)
    }

    private var mIsDoubleTapTogglePlayEnabled = true

    private var mSlideInfo: TextView? = null
    private var mLoading: ProgressBar? = null
    private var mPauseRoot: ViewGroup? = null
    private var mPauseTime: TextView? = null

    constructor(context: Context) : super(context) {
        mHandler = Handler { msg ->
            when (msg.what) {
                100 -> { // 亮度+音量调整
                    mSlideInfo?.visibility = VISIBLE
                    mSlideInfo?.text = msg.obj.toString()
                }
                101 -> { // 亮度+音量调整 关闭
                    mSlideInfo?.visibility = GONE
                }
                else -> {
                    mHandlerCallback?.callback(msg)
                }
            }
            false
        }
        initSelf()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        // 二参构造在 Java 中未初始化 mHandler(仅主路径使用),保持一致
        mHandler = Handler {
            false
        }
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        mHandler = Handler {
            false
        }
    }

    private fun initSelf() {
        mAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mGestureDetector = GestureDetector(context, this)
        setOnTouchListener(this)
    }

    override fun initView() {
        super.initView()
        mAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mGestureDetector = GestureDetector(context, this)
        setOnTouchListener(this)
        mSlideInfo = findViewWithTag("vod_control_slide_info") as TextView?
        mLoading = findViewWithTag("vod_control_loading") as ProgressBar?
        mPauseRoot = findViewWithTag("vod_control_pause") as ViewGroup?
        mPauseTime = findViewWithTag("vod_control_pause_t") as TextView?
    }

    override fun setProgress(duration: Int, position: Int) {
        super.setProgress(duration, position)
        mPauseTime?.text = PlayerUtils.stringForTime(position) + " / " + PlayerUtils.stringForTime(duration)
    }

    override fun onPlayStateChanged(playState: Int) {
        super.onPlayStateChanged(playState)
        when (playState) {
            VideoView.STATE_IDLE -> {
                mPauseRoot?.visibility = GONE
                mLoading?.visibility = GONE
            }
            VideoView.STATE_PLAYING -> {
                mPauseRoot?.visibility = GONE
                mLoading?.visibility = GONE
            }
            VideoView.STATE_PAUSED -> {
                mPauseRoot?.visibility = VISIBLE
                mLoading?.visibility = GONE
            }
            VideoView.STATE_PREPARED, VideoView.STATE_ERROR, VideoView.STATE_BUFFERED -> {
                mPauseRoot?.visibility = GONE
                mLoading?.visibility = GONE
            }
            VideoView.STATE_PREPARING, VideoView.STATE_BUFFERING -> {
                mPauseRoot?.visibility = GONE
                mLoading?.visibility = VISIBLE
            }
            VideoView.STATE_PLAYBACK_COMPLETED -> {
                mLoading?.visibility = GONE
                mPauseRoot?.visibility = GONE
            }
        }
    }

    /** 设置是否可以滑动调节进度,默认可以 */
    fun setCanChangePosition(canChangePosition: Boolean) {
        mCanChangePosition = canChangePosition
    }

    /** 是否在竖屏模式下开始手势控制,默认关闭 */
    fun setEnableInNormal(enableInNormal: Boolean) {
        mEnableInNormal = enableInNormal
    }

    /** 是否开启手势控制,默认开启 */
    fun setGestureEnabled(gestureEnabled: Boolean) {
        mIsGestureEnabled = gestureEnabled
    }

    /** 是否开启双击播放/暂停,默认开启 */
    fun setDoubleTapTogglePlayEnabled(enabled: Boolean) {
        mIsDoubleTapTogglePlayEnabled = enabled
    }

    fun hidePauseRoot() {
        mPauseRoot?.visibility = GONE
    }

    fun showPauseRoot() {
        mPauseRoot?.visibility = VISIBLE
    }

    override fun setPlayerState(playerState: Int) {
        super.setPlayerState(playerState)
        if (playerState == VideoView.PLAYER_NORMAL) {
            mCanSlide = mEnableInNormal
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            mCanSlide = true
        }
    }

    override fun setPlayState(playState: Int) {
        super.setPlayState(playState)
        mCurPlayState = playState
    }

    protected fun isInPlaybackState(): Boolean {
        return mControlWrapper != null &&
            mCurPlayState != VideoView.STATE_ERROR &&
            mCurPlayState != VideoView.STATE_IDLE &&
            mCurPlayState != VideoView.STATE_PREPARING &&
            mCurPlayState != VideoView.STATE_PREPARED &&
            mCurPlayState != VideoView.STATE_START_ABORT &&
            mCurPlayState != VideoView.STATE_PLAYBACK_COMPLETED
    }

    protected fun canHandleGesture(event: MotionEvent?): Boolean {
        return event != null &&
            isInPlaybackState() &&
            mIsGestureEnabled &&
            mCanSlide &&
            !isLocked() &&
            !PlayerUtils.isEdge(context, event)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return mGestureDetector!!.onTouchEvent(event)
    }

    /** 手指按下的瞬间 */
    override fun onDown(e: MotionEvent): Boolean {
        if (!isInPlaybackState() || !mIsGestureEnabled || PlayerUtils.isEdge(context, e)) return true
        mStreamVolume = mAudioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
        val activity = PlayerUtils.scanForActivity(context)
        if (activity == null) {
            mBrightness = 0f
        } else {
            mBrightness = activity.window.attributes.screenBrightness
        }
        mFirstTouch = true
        mChangePosition = false
        mChangeBrightness = false
        mChangeVolume = false
        return true
    }

    /** 单击 */
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (isInPlaybackState()) {
            mControlWrapper.toggleShowState()
        }
        return true
    }

    /** 双击 */
    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (mIsDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()) togglePlay()
        return true
    }

    /** 在屏幕上滑动 */
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        if (e1 == null || !canHandleGesture(e1)) return true
        val deltaX = e1.x - e2.x
        val deltaY = e1.y - e2.y
        if (mFirstTouch) {
            mChangePosition = Math.abs(distanceX) >= Math.abs(distanceY)
            if (!mChangePosition) {
                // 半屏宽度
                val halfScreen = PlayerUtils.getScreenWidth(context, true) / 2
                if (e2.x > halfScreen) {
                    mChangeVolume = true
                } else {
                    mChangeBrightness = true
                }
            }
            if (mChangePosition) {
                // 根据用户设置是否可以滑动调节进度来决定最终是否可以滑动调节进度
                mChangePosition = mCanChangePosition
            }
            if (mChangePosition || mChangeBrightness || mChangeVolume) {
                for (next in mControlComponents.entries) {
                    val component = next.key
                    if (component is IGestureComponent) {
                        component.onStartSlide()
                    }
                }
            }
            mFirstTouch = false
        }
        if (mChangePosition) {
            slideToChangePosition(deltaX)
        } else if (mChangeBrightness) {
            slideToChangeBrightness(deltaY)
        } else if (mChangeVolume) {
            slideToChangeVolume(deltaY)
        }
        return true
    }

    protected fun slideToChangePosition(deltaX0: Float) {
        val deltaX = -deltaX0
        val width = measuredWidth
        val duration = PlayerUtils.safeTimeMs(mControlWrapper.duration)
        val currentPosition = PlayerUtils.safeTimeMs(mControlWrapper.currentPosition)
        var position = (deltaX / width * 120000 + currentPosition).toInt()
        if (position > duration) position = duration
        if (position < 0) position = 0
        for (next in mControlComponents.entries) {
            val component = next.key
            if (component is IGestureComponent) {
                component.onPositionChange(position, currentPosition, duration)
            }
        }
        updateSeekUI(currentPosition, position, duration)
        mSeekPosition = position
    }

    protected open fun updateSeekUI(curr: Int, seekTo: Int, duration: Int) {
    }

    protected fun slideToChangeBrightness(deltaY: Float) {
        val activity = PlayerUtils.scanForActivity(context) ?: return
        val window: Window = activity.window
        val attributes = window.attributes
        val height = measuredHeight
        if (mBrightness == -1.0f) mBrightness = 0.5f
        var brightness = deltaY * 2 / height * 1.0f + mBrightness
        if (brightness < 0) {
            brightness = 0f
        }
        if (brightness > 1.0f) brightness = 1.0f
        val percent = (brightness * 100).toInt()
        attributes.screenBrightness = brightness
        window.attributes = attributes
        for (next in mControlComponents.entries) {
            val component = next.key
            if (component is IGestureComponent) {
                component.onBrightnessChange(percent)
            }
        }
        val msg = Message.obtain()
        msg.what = 100
        msg.obj = "亮度$percent%"
        mHandler.sendMessage(msg)
        mHandler.removeMessages(101)
        mHandler.sendEmptyMessageDelayed(101, 1000)
    }

    protected fun slideToChangeVolume(deltaY: Float) {
        val streamMaxVolume = mAudioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val height = measuredHeight
        val deltaV = deltaY * 2 / height * streamMaxVolume
        var index = mStreamVolume + deltaV
        if (index > streamMaxVolume) index = streamMaxVolume.toFloat()
        if (index < 0) index = 0f
        val percent = (index / streamMaxVolume * 100).toInt()
        mAudioManager!!.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        for (next in mControlComponents.entries) {
            val component = next.key
            if (component is IGestureComponent) {
                component.onVolumeChange(percent)
            }
        }
        val msg = Message.obtain()
        msg.what = 100
        msg.obj = "音量$percent%"
        mHandler.sendMessage(msg)
        mHandler.removeMessages(101)
        mHandler.sendEmptyMessageDelayed(101, 1000)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 滑动结束时事件处理
        if (!mGestureDetector!!.onTouchEvent(event)) {
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    stopSlide()
                    if (mSeekPosition >= 0) {
                        mControlWrapper.seekTo(mSeekPosition.toLong())
                        mSeekPosition = -1
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopSlide()
                    mSeekPosition = -1
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun stopSlide() {
        for (next in mControlComponents.entries) {
            val component = next.key
            if (component is IGestureComponent) {
                component.onStopSlide()
            }
        }
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        return false
    }

    override fun onLongPress(e: MotionEvent) {}

    override fun onShowPress(e: MotionEvent) {}

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        return false
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return false
    }

    open fun onKeyEvent(event: KeyEvent): Boolean {
        return false
    }
}
