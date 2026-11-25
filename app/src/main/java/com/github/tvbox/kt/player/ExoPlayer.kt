package com.github.tvbox.kt.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.tvbox.kt.util.AudioTrackMemory
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.LOG
import com.orhanobut.hawk.Hawk
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.decoder.DecoderCounters
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.text.Cue
import com.google.android.exoplayer2.text.CueGroup
import com.google.android.exoplayer2.text.TextOutput
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.MappingTrackSelector
import com.google.android.exoplayer2.util.MimeTypes
import xyz.doikki.videoplayer.exo.ExoMediaPlayer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap

/**
 * Exo 播放器封装(轨道选择/字幕延迟/卡死自恢复),Kotlin 移植(与旧版 ExoPlayer 一致)。
 */
class ExoPlayer(context: Context) : ExoMediaPlayer(context) {

    private var onCuesListener: OnCuesListener? = null
    private var defaultSubtitleTrackSelected = false
    private var defaultSubtitleTrackSelectionClosed = false
    private var bufferingHandler: Handler? = null
    private var vodPlaybackReady = false
    private var bufferingRecoveryAttempted = false
    private var bufferingRecoveryReadyPosition = C.TIME_UNSET
    private var bufferingPosition: Long = 0
    private var bufferingBufferedPosition: Long = 0
    @Volatile
    private var internalSubtitleDelayUs: Long = 0

    private val bufferingTimeout: Runnable = Runnable {
        if (!shouldCheckBuffering()) return@Runnable
        val position = mInternalPlayer.currentPosition
        val bufferedPosition = mInternalPlayer.bufferedPosition
        if (position != bufferingPosition || bufferedPosition > bufferingBufferedPosition) {
            scheduleBufferingCheck()
            return@Runnable
        }
        LOG.i(
            "echo-exo-buffering-stalled position=$position"
                + ", buffered=$bufferedPosition, loading=" + mInternalPlayer.isLoading
                + ", speed=" + mInternalPlayer.playbackParameters.speed
                + ", totalBuffered=" + mInternalPlayer.totalBufferedDuration
                + ", recoveryAttempted=$bufferingRecoveryAttempted"
                + ", recoveryReadyPosition=$bufferingRecoveryReadyPosition"
        )
        logBufferingDecoders("stalled")
        if (bufferingRecoveryAttempted) {
            mInternalPlayer.stop()
            onPlayerError(PlaybackException("echo-exo-buffering-stalled-after-recovery", null, PlaybackException.ERROR_CODE_TIMEOUT))
            return@Runnable
        }
        bufferingRecoveryAttempted = true
        // 同一次持续卡死只重载一次;确认恢复播放后,后续独立卡顿可以再次恢复
        mInternalPlayer.setMediaSource(mMediaSource, position)
        mInternalPlayer.prepare()
        mInternalPlayer.playWhenReady = true
    }

    init {
        setRenderersFactory(buildRenderersFactory(context))
        memory = AudioTrackMemory.getInstance(context)
    }

    override fun initPlayer() {
        // 缓冲调优:直播维持默认 max 15s(避免增大直播延迟),点播把 max 15s→30s,
        // 让播放器提前多缓冲后续分片,提升弱网下的播放连续性。
        // min/起播/重缓冲阈值一律不动,保证首帧与重缓冲速度不变。
        // 必须在 super.initPlayer() 之前设置:DKVideo 在 super 里用 mLoadControl 构建内部
        // player(ExoMediaPlayer.java 的 initPlayer);而 PLAYER_IS_LIVE 在
        // LivePlayActivity.initView() 末尾才置 true,构造期读取过早,这里才是可靠时机。
        val isLive = try {
            Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)
        } catch (t: Throwable) {
            false
        }
        val maxBufferMs = if (isLive) DefaultLoadControl.DEFAULT_MAX_BUFFER_MS else 30_000
        setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    maxBufferMs,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()
        )
        LOG.i("echo-exo-load-control live=$isLive maxBufferMs=$maxBufferMs")
        super.initPlayer()
        bufferingHandler = Handler(mInternalPlayer.applicationLooper)
        mInternalPlayer.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                loadDefaultSubtitleTrackBeforeReady()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    defaultSubtitleTrackSelectionClosed = true
                    vodPlaybackReady = !mInternalPlayer.isCurrentMediaItemLive &&
                        mInternalPlayer.isCurrentMediaItemSeekable && mInternalPlayer.duration > 0
                    if (bufferingRecoveryAttempted && bufferingRecoveryReadyPosition == C.TIME_UNSET) {
                        bufferingRecoveryReadyPosition = mInternalPlayer.currentPosition
                    }
                } else if (playbackState == Player.STATE_BUFFERING && shouldCheckBuffering()) {
                    logBufferingDecoders("start")
                }
                scheduleBufferingCheck()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                scheduleBufferingCheck()
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                scheduleBufferingCheck()
            }

            override fun onCues(cueGroup: CueGroup) {
                onCuesListener?.onCues(cueGroup.cues)
            }
        })
        LOG.i("echo-exo-cues-listener-ready")
    }

    // headers 必须为可空类型:父类 VideoView.setUrl(url) 会传 null(无自定义请求头)。
    // Kotlin 对非空参数会自动插入 Intrinsics.checkNotNullParameter,若这里声明为非空,
    // 每次播放普通 m3u8 都会在 setDataSource 入口抛 NPE,被 PlayFragment 的 observer
    // 误判成「获取播放信息错误」并触发自动换线路。与 IjkMediaPlayer 的覆写保持一致。
    override fun setDataSource(path: String, headers: MutableMap<String, String>?) {
        // 边下边播:开关开启且为非直播的 http(s) 地址时,媒体数据经本地 CacheDataSource
        // 边播边缓存(512M LRU);直播流地址持续变化,缓存无意义且会挤占空间,故不启用。
        val cacheOn = Hawk.get(HawkConfig.PLAY_CACHE, false)
            && !Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)
            && (path.startsWith("http://") || path.startsWith("https://"))
        try {
            mMediaSourceHelper.setCacheEnabled(cacheOn)
        } catch (th: Throwable) {
            LOG.i("DIAG play_cache_set_error=${th.javaClass.simpleName}")
        }
        LOG.i("DIAG setDataSource headers=${headers?.size ?: -1} path_len=${path.length} cache=${if (cacheOn) "on" else "off"}")
        resetBufferingRecovery()
        defaultSubtitleTrackSelected = false
        defaultSubtitleTrackSelectionClosed = false
        super.setDataSource(path, headers)
    }

    private fun shouldCheckBuffering(): Boolean {
        return mInternalPlayer != null && mMediaSource != null && vodPlaybackReady &&
            mInternalPlayer.playbackState == Player.STATE_BUFFERING &&
            mInternalPlayer.playWhenReady &&
            mInternalPlayer.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !mInternalPlayer.isCurrentMediaItemLive
    }

    private fun scheduleBufferingCheck() {
        if (bufferingHandler == null) return
        bufferingHandler!!.removeCallbacks(bufferingTimeout)
        if (!shouldCheckBuffering()) return
        bufferingPosition = mInternalPlayer.currentPosition
        // READY 可能只短暂出现,必须在 READY 之后实际推进进度,才重置恢复次数
        if (bufferingRecoveryAttempted && bufferingRecoveryReadyPosition != C.TIME_UNSET &&
            bufferingPosition - bufferingRecoveryReadyPosition >= RECOVERY_SUCCESS_PROGRESS_MS
        ) {
            LOG.i("echo-exo-buffering-recovery-succeeded readyPosition=$bufferingRecoveryReadyPosition, position=$bufferingPosition")
            bufferingRecoveryAttempted = false
            bufferingRecoveryReadyPosition = C.TIME_UNSET
        }
        bufferingBufferedPosition = mInternalPlayer.bufferedPosition
        bufferingHandler!!.postDelayed(bufferingTimeout, BUFFERING_TIMEOUT_MS)
    }

    private fun resetBufferingRecovery() {
        bufferingHandler?.removeCallbacks(bufferingTimeout)
        vodPlaybackReady = false
        bufferingRecoveryAttempted = false
        bufferingRecoveryReadyPosition = C.TIME_UNSET
    }

    private fun logBufferingDecoders(stage: String) {
        logBufferingDecoder(stage, "audio", mInternalPlayer.audioFormat, mInternalPlayer.audioDecoderCounters)
        logBufferingDecoder(stage, "video", mInternalPlayer.videoFormat, mInternalPlayer.videoDecoderCounters)
    }

    private fun logBufferingDecoder(stage: String, type: String, format: Format?, counters: DecoderCounters?) {
        var counts = "none"
        if (counters != null) {
            counters.ensureUpdated()
            counts = "queued=" + counters.queuedInputBufferCount +
                ", rendered=" + counters.renderedOutputBufferCount +
                ", skipped=" + counters.skippedOutputBufferCount +
                ", dropped=" + counters.droppedBufferCount
        }
        LOG.i("echo-exo-buffering-decoder stage=$stage, type=$type, format=$format, counters=$counts")
    }

    override fun seekTo(time: Long) {
        bufferingRecoveryAttempted = false
        bufferingRecoveryReadyPosition = C.TIME_UNSET
        super.seekTo(time)
        scheduleBufferingCheck()
    }

    override fun reset() {
        resetBufferingRecovery()
        super.reset()
    }

    override fun release() {
        resetBufferingRecovery()
        super.release()
        bufferingHandler = null
    }

    private fun buildRenderersFactory(context: Context): RenderersFactory {
        val factory = SubtitleOffsetRenderersFactory(context) { internalSubtitleDelayUs }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        try {
            val method = DefaultRenderersFactory::class.java.getMethod("forceDisableMediaCodecAsynchronousQueueing")
            method.invoke(factory)
            LOG.i("echo-exo-disable-async-codec-queue")
        } catch (th: Throwable) {
            LOG.i("echo-exo-disable-async-codec-queue-skip:" + th.javaClass.simpleName)
        }
        return factory
    }

    fun getTrackInfo(): TrackInfo {
        val data = TrackInfo()
        val mappedInfo = trackSelector.currentMappedTrackInfo ?: return data

        for (rendererIndex in 0 until mappedInfo.rendererCount) {
            val type = mappedInfo.getRendererType(rendererIndex)
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue

            val groups = mappedInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val group: TrackGroup = groups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val fmt = group.getFormat(trackIndex)
                    if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue
                    val language = getLanguage(fmt)
                    val detail = if (type == C.TRACK_TYPE_VIDEO) getVideoName(fmt) else getName(fmt)
                    val bean = TrackInfoBean()
                    bean.language = language
                    bean.name = buildDisplayName(
                        when (type) {
                            C.TRACK_TYPE_AUDIO -> "音轨"
                            C.TRACK_TYPE_VIDEO -> "视轨"
                            else -> "字幕"
                        },
                        when (type) {
                            C.TRACK_TYPE_AUDIO -> data.getAudio().size + 1
                            C.TRACK_TYPE_VIDEO -> data.getVideo().size + 1
                            else -> data.getSubtitle().size + 1
                        },
                        language, detail
                    )
                    bean.renderId = rendererIndex
                    bean.trackGroupId = groupIndex
                    bean.trackId = trackIndex
                    bean.groupIndex = groupIndex
                    bean.index = trackIndex
                    bean.selected = isCurrentTrackSelected(fmt, type)
                    bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt)

                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean)
                    } else if (type == C.TRACK_TYPE_VIDEO) {
                        data.addVideo(bean)
                    } else {
                        data.addSubtitle(bean)
                    }
                }
            }
        }
        return data
    }

    fun setTrack(track: TrackInfoBean?, playKey: String) {
        if (track == null) return
        setTrack(track.renderId, track.trackGroupId, track.trackId, playKey)
    }

    private fun setTrack(rendererIndex: Int, groupIndex: Int, trackIndex: Int, playKey: String) {
        try {
            val mappedInfo = trackSelector.currentMappedTrackInfo
            if (mappedInfo == null) {
                LOG.i("echo-setTrack: MappedTrackInfo is null")
                return
            }
            if (rendererIndex == C.INDEX_UNSET || rendererIndex < 0 || rendererIndex >= mappedInfo.rendererCount) {
                LOG.i("echo-setTrack: No renderer found")
                return
            }
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            if (!isTrackIndexValid(groups, groupIndex, trackIndex)) {
                LOG.i("echo-setTrack: Invalid track index - group:$groupIndex, track:$trackIndex")
                return
            }
            val override = DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
            val builder = trackSelector.buildUponParameters()
            builder.setRendererDisabled(rendererIndex, false)
            builder.clearSelectionOverrides(rendererIndex)
            builder.setSelectionOverride(rendererIndex, groups, override)
            trackSelector.setParameters(builder.build())

            if (mappedInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_AUDIO && playKey.isNotEmpty()) {
                memory!!.save(playKey, groupIndex, trackIndex)
            }
        } catch (e: Exception) {
            LOG.i("echo-setTrack error: " + e.message)
        }
    }

    fun loadDefaultTrack(playKey: String) {
        val pair = memory!!.exoLoad(playKey) ?: return
        val mappedInfo = trackSelector.currentMappedTrackInfo ?: return
        val audioRendererIndex = findAudioRendererIndex(mappedInfo)
        if (audioRendererIndex == C.INDEX_UNSET) return
        setTrack(audioRendererIndex, pair.first, pair.second, "")
    }

    fun loadDefaultSubtitleTrack() {
        if (defaultSubtitleTrackSelected) return
        val trackInfo = getTrackInfo()
        val subtitles = trackInfo.getSubtitle()
        if (subtitles.isEmpty()) return

        defaultSubtitleTrackSelected = true
        var target = subtitles[0]
        for (subtitle in subtitles) {
            if ("国语" == subtitle.language) {
                target = subtitle
                break
            }
        }
        setTrack(target, "")
    }

    private fun loadDefaultSubtitleTrackBeforeReady() {
        if (defaultSubtitleTrackSelectionClosed) return
        loadDefaultSubtitleTrack()
    }

    fun setOnCuesListener(listener: OnCuesListener?) {
        onCuesListener = listener
    }

    fun setInternalSubtitleDelay(milliseconds: Int) {
        internalSubtitleDelayUs = milliseconds * 1000L
    }

    private fun findAudioRendererIndex(mappedInfo: MappingTrackSelector.MappedTrackInfo?): Int {
        if (mappedInfo == null) return C.INDEX_UNSET
        for (i in 0 until mappedInfo.rendererCount) {
            if (mappedInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                return i
            }
        }
        return C.INDEX_UNSET
    }

    private fun isTrackIndexValid(groups: TrackGroupArray, groupIndex: Int, trackIndex: Int): Boolean {
        if (groupIndex < 0 || groupIndex >= groups.length) return false
        val group = groups[groupIndex]
        return trackIndex >= 0 && trackIndex < group.length
    }

    private fun isBitmapSubtitle(format: Format?): Boolean {
        if (format == null || format.sampleMimeType == null) return false
        val mimeType = format.sampleMimeType!!.lowercase()
        return mimeType.contains("pgs") || mimeType.contains("dvb") || mimeType.contains("vobsub")
    }

    private fun isUndeclaredClosedCaptionTrack(format: Format?): Boolean {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false
        return MimeTypes.APPLICATION_CEA608 == format.sampleMimeType || MimeTypes.APPLICATION_CEA708 == format.sampleMimeType
    }

    private fun isCurrentTrackSelected(format: Format, trackType: Int): Boolean {
        val tracks: Tracks = mInternalPlayer.currentTracks
        for (group in tracks.groups) {
            if (group.type != trackType || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i) && isSameFormat(format, group.getTrackFormat(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun isSameFormat(a: Format?, b: Format?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.id != null && b.id != null && a.id == b.id) return true
        return a == b
    }

    private fun getLanguage(fmt: Format): String {
        val language = matchLanguage(fmt.language)
        if (language.isNotEmpty()) {
            return language
        }
        return matchLanguage(
            (fmt.label ?: "") + " " + (fmt.id ?: "") + " " + (fmt.codecs ?: "")
        )
    }

    private fun matchLanguage(text0: String?): String {
        if (text0 == null) return ""
        val value = text0.lowercase()
        val mapped = LANG_MAP[value]
        if (mapped != null) return mapped
        if (value.contains("yue") || value.contains("cantonese") || value.contains("粤") || value.contains("广东")) {
            return "粤语"
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs") ||
            value.contains("cht") || value.contains("cmn") || value.contains("中") ||
            value.contains("国语") || value.contains("普通话")
        ) {
            return "国语"
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("英")) {
            return "英语"
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("日")) {
            return "日语"
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("韩")) {
            return "韩语"
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "泰语"
        }
        return ""
    }

    private fun getName(fmt: Format): String {
        val channelLabel = when {
            fmt.channelCount <= 0 -> ""
            fmt.channelCount == 1 -> "单声道"
            fmt.channelCount == 2 -> "立体声"
            else -> fmt.channelCount.toString() + " 声道"
        }
        var codec = ""
        val codecs = fmt.codecs
        if (codecs != null && !codecs.isEmpty()) {
            codec = codecs.uppercase()
        }
        val sampleMimeType = fmt.sampleMimeType
        if (sampleMimeType != null && sampleMimeType.contains("/")) {
            val mime = sampleMimeType.substring(sampleMimeType.indexOf('/') + 1)
            if (codec.isEmpty()) {
                codec = mime.uppercase()
            }
        }
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        appendPart(builder, codec)
        appendPart(builder, channelLabel)
        return builder.toString()
    }

    private fun getVideoName(fmt: Format): String {
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, fmt.width.toString() + "x" + fmt.height)
        }
        val codecs = fmt.codecs
        if (codecs != null && !codecs.isEmpty()) {
            appendPart(builder, codecs.uppercase())
        } else {
            val sampleMimeType = fmt.sampleMimeType
            if (sampleMimeType != null && sampleMimeType.contains("/")) {
                appendPart(builder, sampleMimeType.substring(sampleMimeType.indexOf('/') + 1).uppercase())
            }
        }
        return builder.toString()
    }

    private fun buildDisplayName(prefix: String, number: Int, language: String?, detail: String?): String {
        val builder = StringBuilder(prefix).append(number)
        if (language != null && language.isNotEmpty()) {
            builder.append(" - ").append(language)
        }
        if (detail != null && detail.isNotEmpty()) {
            builder.append(" ").append(detail)
        }
        return builder.toString()
    }

    private fun appendPart(builder: StringBuilder, value: String?) {
        if (value == null) return
        val part = value.trim()
        if (part.isEmpty() || "und".equals(part, ignoreCase = true) || "未知" == part) return
        if (builder.isNotEmpty()) {
            builder.append(" / ")
        }
        builder.append(part)
    }

    interface OnCuesListener {
        fun onCues(cues: List<Cue>)
    }

    private fun interface SubtitleDelayProvider {
        fun getDelayUs(): Long
    }

    private class SubtitleOffsetRenderersFactory(
        context: Context,
        private val subtitleDelayProvider: SubtitleDelayProvider
    ) : DefaultRenderersFactory(context) {

        override fun buildTextRenderers(
            context: Context,
            output: TextOutput,
            outputLooper: Looper,
            extensionRendererMode: Int,
            out: ArrayList<Renderer>
        ) {
            val firstRendererIndex = out.size
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
            for (i in firstRendererIndex until out.size) {
                val renderer: Renderer = out[i]
                out[i] = Proxy.newProxyInstance(
                    Renderer::class.java.classLoader,
                    arrayOf(Renderer::class.java),
                    SubtitleOffsetRendererHandler(renderer, subtitleDelayProvider)
                ) as Renderer
            }
        }
    }

    private class SubtitleOffsetRendererHandler(
        private val renderer: Renderer,
        private val subtitleDelayProvider: SubtitleDelayProvider
    ) : InvocationHandler {

        @Throws(Throwable::class)
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>?): Any? {
            var invokeArgs = args
            if ("render" == method.name && args != null && args.isNotEmpty() && args[0] is Long) {
                invokeArgs = args.clone()
                val positionUs = invokeArgs!![0] as Long
                invokeArgs[0] = maxOf(0L, positionUs - subtitleDelayProvider.getDelayUs())
            }
            try {
                return method.invoke(renderer, *invokeArgs ?: arrayOfNulls<Any>(0))
            } catch (e: InvocationTargetException) {
                throw e.cause!!
            }
        }
    }

    companion object {
        private const val BUFFERING_TIMEOUT_MS = 15_000L
        private const val RECOVERY_SUCCESS_PROGRESS_MS = 5_000L
        private var memory: AudioTrackMemory? = null

        private val LANG_MAP = HashMap<String, String>()

        init {
            LANG_MAP["zh"] = "国语"
            LANG_MAP["zh-cn"] = "国语"
            LANG_MAP["cmn"] = "国语"
            LANG_MAP["chi"] = "国语"
            LANG_MAP["zho"] = "国语"
            LANG_MAP["chs"] = "国语"
            LANG_MAP["yue"] = "粤语"
            LANG_MAP["zh-hk"] = "粤语"
            LANG_MAP["zh-yue"] = "粤语"
            LANG_MAP["en"] = "英语"
            LANG_MAP["en-us"] = "英语"
            LANG_MAP["eng"] = "英语"
            LANG_MAP["ja"] = "日语"
            LANG_MAP["jpn"] = "日语"
            LANG_MAP["ko"] = "韩语"
            LANG_MAP["kor"] = "韩语"
            LANG_MAP["th"] = "泰语"
            LANG_MAP["tha"] = "泰语"
        }
    }
}
