package com.github.tvbox.kt.player.danmu

import android.text.TextUtils
import android.view.View
import com.github.tvbox.kt.api.DanmakuApi
import com.github.tvbox.kt.player.MyVideoView
import com.github.tvbox.kt.player.controller.VodController
import com.github.tvbox.kt.util.DanmuHelper
import com.github.tvbox.kt.util.LOG
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.ui.widget.DanmakuView
import xyz.doikki.videoplayer.player.VideoView
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 弹幕加载生命周期管理,Kotlin 移植(与旧版 DanmuLoadController 一致)。
 */
class DanmuLoadController(
    private val videoView: MyVideoView?,
    private val controller: VodController?,
    private val danmuView: DanmakuView?
) {
    interface LoadCallback {
        fun onFailed()
    }

    private val danmakuContext: DanmakuContext = DanmakuContext.create()
    private val loadSeq = AtomicInteger()
    private var executor: java.util.concurrent.ExecutorService? = null
    private var danmuText = ""
    private var danmuTitle = ""
    private var danmuEpisode = ""
    private var startedSeq = -1
    private var pendingPrepare = false
    private var temporarilyClosed = false
    private var loadCallback: LoadCallback? = null

    @Volatile
    private var seekGeneration = 0

    @Volatile
    private var pendingSeekPosition: Long = -1

    init {
        if (videoView != null) {
            videoView.setDanmuView(danmuView)
            videoView.setDanmuSeekListener(object : MyVideoView.DanmuSeekListener {
                override fun onDanmuSeek(position: Long) {
                    this@DanmuLoadController.onDanmuSeek(position)
                }
            })
        }
        applySettings(false)
    }

    fun applySettings(reload: Boolean) {
        if (danmuView == null || danmakuContext == null) return
        if (!DanmuHelper.isOpen()) {
            releaseView()
            controller?.setHasDanmu(!TextUtils.isEmpty(danmuText))
            return
        }
        val maxLines = HashMap<Int, Int>()
        val maxLine = DanmuHelper.getMaxLine()
        maxLines[BaseDanmaku.TYPE_FIX_TOP] = maxLine
        maxLines[BaseDanmaku.TYPE_SCROLL_RL] = maxLine
        maxLines[BaseDanmaku.TYPE_SCROLL_LR] = maxLine
        maxLines[BaseDanmaku.TYPE_FIX_BOTTOM] = maxLine
        danmakuContext.setMaximumLines(maxLines)
            .setScrollSpeedFactor(DanmuHelper.getSpeed())
            .setDanmakuTransparency(DanmuHelper.getAlpha())
            .setScaleTextSize(DanmuHelper.getSizeScale())
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f)
            .setDanmakuMargin(8)
        if (reload && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()) {
            prepare(danmuText)
        }
    }

    fun check(danmu: String) {
        check(danmu, "", "")
    }

    fun check(danmu: String, title: String, episode: String) {
        check(danmu, title, episode, null)
    }

    fun check(danmu: String, title: String, episode: String, callback: LoadCallback?) {
        DanmakuApi.cancel()
        loadCallback = callback
        seekGeneration++
        pendingSeekPosition = -1
        temporarilyClosed = false
        danmuText = if (TextUtils.isEmpty(danmu)) "" else danmu.trim()
        danmuTitle = if (TextUtils.isEmpty(title)) "" else title
        danmuEpisode = if (TextUtils.isEmpty(episode)) "" else episode
        releaseView()
        val hasDanmu = !TextUtils.isEmpty(danmuText)
        controller?.setHasDanmu(hasDanmu)
        if (!hasDanmu || !DanmuHelper.isOpen()) {
            danmuView?.visibility = View.GONE
            return
        }
        danmuView?.visibility = View.VISIBLE
        if (!isVideoReady()) {
            pendingPrepare = true
            return
        }
        prepare(danmuText)
    }

    fun startIfReady() {
        if (pendingPrepare && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen() &&
            videoView != null && videoView.isPlaying
        ) {
            pendingPrepare = false
            prepare(danmuText)
            return
        }
        startIfReady(loadSeq.get())
    }

    fun reset() {
        DanmakuApi.cancel()
        seekGeneration++
        pendingSeekPosition = -1
        temporarilyClosed = false
        danmuText = ""
        danmuTitle = ""
        danmuEpisode = ""
        pendingPrepare = false
        loadCallback = null
        loadSeq.incrementAndGet()
        startedSeq = -1
        controller?.setHasDanmu(false)
        releaseView()
    }

    fun close() {
        DanmakuApi.cancel()
        loadSeq.incrementAndGet()
        seekGeneration++
        pendingSeekPosition = -1
        startedSeq = -1
        pendingPrepare = false
        releaseView()
    }

    fun toggle(): Boolean {
        return if (temporarilyClosed) {
            temporarilyClosed = false
            reloadForPlayback()
            startIfReady()
            true
        } else {
            temporarilyClosed = true
            close()
            false
        }
    }

    fun reloadForPlayback() {
        temporarilyClosed = false
        loadSeq.incrementAndGet()
        seekGeneration++
        pendingSeekPosition = -1
        startedSeq = -1
        releaseView()
        pendingPrepare = !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()
    }

    fun destroy() {
        reset()
        videoView?.setDanmuSeekListener(null)
        executor?.let {
            it.shutdownNow()
            executor = null
        }
    }

    private fun prepare(danmu: String) {
        if (TextUtils.isEmpty(danmu)) return
        pendingPrepare = false
        val seq = loadSeq.incrementAndGet()
        startedSeq = -1
        val currentSeekGeneration = seekGeneration
        LOG.i("echo-danmu load title: " + safeLog(danmuTitle) + ", episode: " + safeLog(danmuEpisode) + ", source: " + getSourceSummary(danmu))
        if (executor == null || executor!!.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        val initialPosition: Long = if (pendingSeekPosition >= 0) {
            pendingSeekPosition
        } else if (videoView == null) {
            0
        } else {
            val currentPosition = videoView.currentPosition
            if (currentPosition > 0) currentPosition else videoView.getPlaybackPosition()
        }
        LOG.i("echo-danmu parse start at: $initialPosition")
        executor!!.execute {
            val currentParser = Parser(danmu, { seq != loadSeq.get() }, initialPosition)
            if (seq != loadSeq.get()) return@execute
            val danmuCount = currentParser.getDanmuCount()
            LOG.i("echo-danmu parsed count: $danmuCount")
            if (danmuView == null) return@execute
            val preparedParser = currentParser
            danmuView.post {
                if (seq != loadSeq.get() || danmakuContext == null) return@post
                try {
                    danmuView.release()
                    if (videoView != null) videoView.setDanmuView(danmuView)
                    if (danmuCount <= 0) {
                        LOG.e("echo-danmu empty after parse")
                        danmuView.visibility = View.GONE
                        notifyLoadFailed(seq)
                        return@post
                    }
                    danmuView.prepare(preparedParser, danmakuContext)
                    clearLoadCallback(seq)
                    danmuView.visibility = if (DanmuHelper.isOpen()) View.VISIBLE else View.GONE
                    startIfReady(seq)
                    danmuView.postDelayed({ startIfReady(seq) }, 300)
                    danmuView.postDelayed({ startIfReady(seq) }, 1000)
                    appendRemaining(preparedParser, seq, currentSeekGeneration)
                } catch (th: Throwable) {
                    LOG.e("echo-danmu prepare error: " + th.message)
                    danmuView.visibility = View.GONE
                    notifyLoadFailed(seq)
                }
            }
        }
    }

    private fun appendRemaining(parser: Parser, seq: Int, generation: Int) {
        appendRemaining(parser, seq, generation, false)
    }

    private fun appendRemaining(parser: Parser, seq: Int, generation: Int, seekFirstBatch: Boolean) {
        if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || !parser.hasMoreDanmaku()) return
        if (danmuView == null) return
        if (!danmuView.isPrepared) {
            danmuView.postDelayed({ appendRemaining(parser, seq, generation, seekFirstBatch) }, 100)
            return
        }
        if (executor == null || executor!!.isShutdown) return
        executor!!.execute {
            if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || danmuView == null) return@execute
            val batch = parser.parseNextBatch()
            if (batch.isEmpty()) return@execute
            for (item in batch) {
                if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || danmuView == null) return@execute
                danmuView.addDanmaku(item)
            }
            LOG.i("echo-danmu appended count: " + batch.size)
            if (seq == loadSeq.get() && generation == seekGeneration && DanmuHelper.isOpen() && parser.hasMoreDanmaku()) {
                val delay = if (seekFirstBatch) 1000L else 50L
                danmuView.postDelayed({ appendRemaining(parser, seq, generation) }, delay)
            }
        }
    }

    private fun onDanmuSeek(position0: Long) {
        val targetPosition = maxOf(0, position0)
        pendingSeekPosition = targetPosition
        seekGeneration++
        loadSeq.incrementAndGet()
        startedSeq = -1
        if (DanmuHelper.isOpen() && !TextUtils.isEmpty(danmuText)) {
            pendingPrepare = true
            danmuView?.visibility = View.GONE
        }
    }

    private fun clearLoadCallback(seq: Int) {
        if (seq == loadSeq.get()) loadCallback = null
    }

    private fun notifyLoadFailed(seq: Int) {
        if (seq != loadSeq.get() || loadCallback == null) return
        val callback = loadCallback
        loadCallback = null
        callback!!.onFailed()
    }

    private fun startIfReady(seq: Int) {
        if (seq != loadSeq.get() ||
            seq == startedSeq ||
            videoView == null ||
            !videoView.isPlaying ||
            danmuView == null ||
            !danmuView.isPrepared ||
            !DanmuHelper.isOpen()
        ) {
            return
        }
        val position = videoView.currentPosition
        danmuView.visibility = View.VISIBLE
        danmuView.seekTo(position)
        danmuView.start(position)
        startedSeq = seq
        LOG.i("echo-danmu start at: $position")
    }

    private fun isVideoReady(): Boolean {
        if (videoView == null) return false
        val state = videoView.currentPlayState
        return state == VideoView.STATE_PREPARED ||
            state == VideoView.STATE_BUFFERED ||
            state == VideoView.STATE_PLAYING
    }

    private fun releaseView() {
        if (danmuView == null) return
        try {
            danmuView.release()
        } catch (ignored: Throwable) {
        }
        danmuView.visibility = View.GONE
    }

    private fun getSourceSummary(danmu: String): String {
        if (TextUtils.isEmpty(danmu)) return ""
        if (danmu.startsWith("http") || danmu.startsWith("file")) return danmu
        return "inline xml length=" + danmu.length
    }

    private fun safeLog(text: String?): String {
        return if (TextUtils.isEmpty(text)) "" else text!!
    }
}
