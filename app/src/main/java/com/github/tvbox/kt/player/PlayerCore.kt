package com.github.tvbox.kt.player

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.github.tvbox.kt.util.ImgUtil
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.ui.widget.DanmakuView
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.player.PlayerFactory
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.RenderViewFactory

/** Exo 工厂,Kotlin 移植(与旧版 ExoMediaPlayerFactory 一致)。 */
class ExoMediaPlayerFactory : PlayerFactory<ExoPlayer>() {
    override fun createPlayer(context: Context): ExoPlayer {
        return ExoPlayer(context)
    }

    companion object {
        fun create(): ExoMediaPlayerFactory {
            return ExoMediaPlayerFactory()
        }
    }
}

/** 轨道信息,Kotlin 移植(与旧版 TrackInfoBean/TrackInfo 一致)。 */
class TrackInfoBean {
    var trackId = 0
    var renderId = 0
    var trackGroupId = 0
    var name: String? = null
    var language: String? = null
    var groupIndex = 0
    var index = 0
    var selected = false
    var bitmapSubtitle = false
}

class TrackInfo {
    private val audio: MutableList<TrackInfoBean> = ArrayList()
    private val video: MutableList<TrackInfoBean> = ArrayList()
    private val subtitle: MutableList<TrackInfoBean> = ArrayList()

    fun getAudio(): List<TrackInfoBean> = audio

    fun getAudioSelected(track: Boolean): Int {
        return getSelected(audio, track)
    }

    fun getSubtitleSelected(track: Boolean): Int {
        return getSelected(subtitle, track)
    }

    fun getSelected(list: List<TrackInfoBean>, track: Boolean): Int {
        var i = 0
        for (trackInfoBean in list) {
            if (trackInfoBean.selected) return if (track) trackInfoBean.trackId else i
            i++
        }
        return 99999
    }

    fun addAudio(audio: TrackInfoBean) {
        this.audio.add(audio)
    }

    fun getVideo(): List<TrackInfoBean> = video

    fun getVideoSelected(track: Boolean): Int {
        return getSelected(video, track)
    }

    fun addVideo(video: TrackInfoBean) {
        this.video.add(video)
    }

    fun getSubtitle(): List<TrackInfoBean> = subtitle

    fun addSubtitle(subtitle: TrackInfoBean) {
        this.subtitle.add(subtitle)
    }
}

/**
 * VideoView 扩展(封面/弹幕联动),Kotlin 移植(与旧版 MyVideoView 一致)。
 */
class MyVideoView : VideoView<AbstractPlayer>, DrawHandler.Callback {

    interface DanmuSeekListener {
        fun onDanmuSeek(position: Long)
    }

    private var danmuView: DanmakuView? = null
    private var artworkView: ImageView? = null
    private var danmuSeekListener: DanmuSeekListener? = null

    constructor(context: Context) : super(context, null)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun getMediaPlayer(): AbstractPlayer? = mMediaPlayer

    fun setArtwork(url: String?) {
        if (TextUtils.isEmpty(url)) {
            clearArtwork()
            return
        }
        if (artworkView == null) {
            artworkView = ImageView(context)
            artworkView!!.setBackgroundColor(Color.BLACK)
            artworkView!!.scaleType = ImageView.ScaleType.FIT_CENTER
            artworkView!!.isClickable = false
            artworkView!!.isFocusable = false
            val index = if (mRenderView == null) 0 else 1.coerceAtMost(mPlayerContainer.childCount)
            mPlayerContainer.addView(
                artworkView, index,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
            )
        }
        artworkView!!.visibility = VISIBLE
        ImgUtil.load(url, artworkView!!, 0, 0, 0, "", ImageView.ScaleType.FIT_CENTER)
    }

    fun clearArtwork() {
        artworkView?.let {
            it.visibility = GONE
            it.setImageDrawable(null)
        }
    }

    override fun getVideoSize(): IntArray? = mVideoSize

    fun getPlaybackPosition(): Long = mCurrentPosition

    override fun seekTo(pos: Long) {
        super.seekTo(pos)
        if (danmuSeekListener != null) {
            danmuSeekListener!!.onDanmuSeek(pos)
        } else if (haveDanmu()) {
            danmuView!!.seekTo(pos)
        }
    }

    override fun resume() {
        super.resume()
        if (haveDanmu()) danmuView!!.resume()
    }

    override fun start() {
        super.start()
        if (haveDanmu()) danmuView!!.resume()
    }

    override fun pause() {
        super.pause()
        if (haveDanmu()) danmuView!!.pause()
    }

    override fun release() {
        super.release()
        if (haveDanmu()) danmuView!!.release()
    }

    private fun haveDanmu(): Boolean {
        return danmuView != null && danmuView!!.isPrepared
    }

    fun setDanmuView(view: DanmakuView?) {
        danmuView = view
        if (danmuView != null) danmuView!!.setCallback(this)
    }

    fun getDanmuView(): DanmakuView? = danmuView

    fun setDanmuSeekListener(listener: DanmuSeekListener?) {
        danmuSeekListener = listener
    }

    override fun prepared() {
        post {
            if (danmuView == null) return@post
            if (isPlaying && danmuView!!.isPrepared) {
                danmuView!!.start(currentPosition)
            }
        }
    }

    override fun updateTimer(timer: DanmakuTimer) {}

    override fun danmakuShown(danmaku: BaseDanmaku) {}

    override fun drawingFinished() {}
}

/** Surface 渲染工厂,Kotlin 移植(与旧版 SurfaceRenderViewFactory 一致)。 */
class SurfaceRenderViewFactory : RenderViewFactory() {
    override fun createRenderView(context: Context): IRenderView {
        return SurfaceRenderView(context)
    }

    companion object {
        fun create(): SurfaceRenderViewFactory {
            return SurfaceRenderViewFactory()
        }
    }
}
