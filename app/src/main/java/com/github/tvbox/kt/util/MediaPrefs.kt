package com.github.tvbox.kt.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.Pair
import com.orhanobut.hawk.Hawk

/** 弹幕配置,Kotlin 移植(与旧版 DanmuHelper 一致)。 */
object DanmuHelper {
    private val PALETTE = arrayOf(
        "#ffffff", "#70f3ff", "#44cef6", "#3eede7", "#00e079", "#2edfa3",
        "#bce672", "#fff143", "#ffa631", "#ff7500", "#ff4e20", "#ff2d51",
        "#ef7a82", "#ff0097", "#b0a4e3", "#4b5cc4"
    )

    @JvmStatic
    fun isOpen(): Boolean = Hawk.get(HawkConfig.DANMU_OPEN, true)

    @JvmStatic
    fun setOpen(open: Boolean) {
        Hawk.put(HawkConfig.DANMU_OPEN, open)
    }

    @JvmStatic
    fun getMaxLine(): Int = Hawk.get(HawkConfig.DANMU_MAX_LINE, 3)

    @JvmStatic
    fun setMaxLine(line: Int) {
        Hawk.put(HawkConfig.DANMU_MAX_LINE, clamp(line, 1, 15))
    }

    @JvmStatic
    fun getSpeed(): Float = Hawk.get(HawkConfig.DANMU_SPEED, 1.5f)

    @JvmStatic
    fun setSpeed(speed: Float) {
        Hawk.put(HawkConfig.DANMU_SPEED, speed)
    }

    @JvmStatic
    fun getAlpha(): Float = Hawk.get(HawkConfig.DANMU_ALPHA, 0.9f)

    @JvmStatic
    fun setAlpha(alpha: Float) {
        Hawk.put(HawkConfig.DANMU_ALPHA, 0.1f.coerceAtLeast(alpha.coerceAtMost(1.0f)))
    }

    @JvmStatic
    fun getSizeScale(): Float = Hawk.get(HawkConfig.DANMU_SIZE_SCALE, 0.8f)

    @JvmStatic
    fun setSizeScale(scale: Float) {
        Hawk.put(HawkConfig.DANMU_SIZE_SCALE, 0.6f.coerceAtLeast(scale.coerceAtMost(2.0f)))
    }

    @JvmStatic
    fun useRandomColor(): Boolean = Hawk.get(HawkConfig.DANMU_RANDOM_COLOR, false)

    @JvmStatic
    fun setRandomColor(randomColor: Boolean) {
        Hawk.put(HawkConfig.DANMU_RANDOM_COLOR, randomColor)
    }

    @JvmStatic
    fun randomColor(): Int {
        val index = (Math.random() * PALETTE.size).toInt()
        return Color.parseColor(PALETTE[index])
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return maxOf(min, value.coerceAtMost(max))
    }
}

/** 字幕配置,Kotlin 移植(与旧版 SubtitleHelper 一致)。 */
object SubtitleHelper {

    @JvmStatic
    fun getSubtitleTextAutoSize(activity: Activity): Int {
        val screenSqrt = ScreenUtils.getSqrt(activity)
        val subtitleTextSize: Int
        subtitleTextSize = when {
            screenSqrt > 7.0 && screenSqrt <= 13.0 -> 24
            screenSqrt > 13.0 && screenSqrt <= 50.0 -> 36
            screenSqrt > 50.0 -> 46
            else -> 16
        }
        return subtitleTextSize
    }

    @JvmStatic
    fun getTextSize(activity: Activity): Int {
        val autoSize = getSubtitleTextAutoSize(activity)
        return Hawk.get(HawkConfig.SUBTITLE_TEXT_SIZE, autoSize)
    }

    @JvmStatic
    fun setTextSize(size: Int) {
        Hawk.put(HawkConfig.SUBTITLE_TEXT_SIZE, size)
    }

    @JvmStatic
    fun getTimeDelay(): Int {
        return Hawk.get(HawkConfig.SUBTITLE_TIME_DELAY, 0)
    }

    @JvmStatic
    fun setTimeDelay(delay: Int) {
        Hawk.put(HawkConfig.SUBTITLE_TIME_DELAY, delay)
    }

    @JvmStatic
    fun getExoSubtitleScale(): Int {
        return Hawk.get(HawkConfig.SUBTITLE_EXO_SCALE, 100)
    }

    @JvmStatic
    fun setExoSubtitleScale(scale: Int) {
        Hawk.put(HawkConfig.SUBTITLE_EXO_SCALE, scale)
    }

    @JvmStatic
    fun getExoSubtitlePosition(): Float {
        return Hawk.get(HawkConfig.SUBTITLE_EXO_POSITION, 0.0f)
    }

    @JvmStatic
    fun setExoSubtitlePosition(position: Float) {
        Hawk.put(HawkConfig.SUBTITLE_EXO_POSITION, position)
    }
}

/** 音轨记忆,Kotlin 移植(与旧版 AudioTrackMemory 一致)。 */
class AudioTrackMemory private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "audio_track_prefs"
        private const val KEY_GROUP_SUFFIX = "_group"
        private const val KEY_TRACK_SUFFIX = "_track"
        private var instance: AudioTrackMemory? = null

        @Synchronized
        @JvmStatic
        fun getInstance(context: Context): AudioTrackMemory {
            if (instance == null) {
                instance = AudioTrackMemory(context)
            }
            return instance!!
        }
    }

    fun save(playKey0: String, groupIndex: Int, trackIndex: Int) {
        val playKey = playKey0 + "_exo"
        LOG.i("echo-AudioTrackMemory save playKey:$playKey")
        prefs.edit()
            .putInt(playKey + KEY_GROUP_SUFFIX, groupIndex)
            .putInt(playKey + KEY_TRACK_SUFFIX, trackIndex)
            .apply()
    }

    fun save(playKey0: String, trackIndex: Int) {
        val playKey = playKey0 + "_ijk"
        LOG.i("echo-AudioTrackMemory save playKey:$playKey")
        prefs.edit().putInt(playKey + KEY_TRACK_SUFFIX, trackIndex).apply()
    }

    fun exoLoad(playKey0: String): Pair<Int, Int>? {
        val playKey = playKey0 + "_exo"
        val group = prefs.getInt(playKey + KEY_GROUP_SUFFIX, -1)
        val track = prefs.getInt(playKey + KEY_TRACK_SUFFIX, -1)
        if (group >= 0 && track >= 0) {
            return Pair.create(group, track)
        }
        return null
    }

    fun ijkLoad(playKey0: String): Int {
        val playKey = playKey0 + "_ijk"
        return prefs.getInt(playKey + KEY_TRACK_SUFFIX, -1)
    }
}
