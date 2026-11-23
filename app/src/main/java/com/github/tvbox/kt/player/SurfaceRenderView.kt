package com.github.tvbox.kt.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.MeasureHelper

/** Surface 渲染视图,Kotlin 移植(与旧版 SurfaceRenderView 一致)。 */
class SurfaceRenderView : SurfaceView, IRenderView, SurfaceHolder.Callback {
    private lateinit var mMeasureHelper: MeasureHelper

    private var mMediaPlayer: AbstractPlayer? = null

    constructor(context: Context) : super(context) {
        initSelf()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initSelf()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initSelf()
    }

    private fun initSelf() {
        mMeasureHelper = MeasureHelper()
        val surfaceHolder = holder
        surfaceHolder.addCallback(this)
        surfaceHolder.setFormat(PixelFormat.RGBA_8888)
    }

    override fun attachToPlayer(player: AbstractPlayer) {
        this.mMediaPlayer = player
        val holder = holder
        if (holder.surface != null && holder.surface.isValid) {
            player.setDisplay(holder)
        }
    }

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight)
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        mMeasureHelper.setVideoRotation(degree)
        rotation = degree.toFloat()
    }

    override fun setScaleType(scaleType: Int) {
        mMeasureHelper.setScreenScale(scaleType)
        requestLayout()
    }

    override fun getView(): View {
        return this
    }

    override fun doScreenShot(): Bitmap? {
        return null
    }

    override fun release() {
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredSize[0], measuredSize[1])
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mMediaPlayer?.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mMediaPlayer?.setDisplay(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mMediaPlayer?.setDisplay(null)
    }
}
