package com.github.tvbox.kt.ui.tv.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Message
import android.util.AttributeSet
import android.view.View
import android.widget.ListView
import com.github.tvbox.kt.ui.activity.LivePlayActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.HashMap
import java.util.Random

/** 音量波形动画,Kotlin 移植(与旧版 AudioWaveView 一致)。 */
class AudioWaveView : View {
    private var paint: Paint? = null
    private var rectF1: RectF? = null
    private var rectF2: RectF? = null
    private var rectF3: RectF? = null
    private var rectF4: RectF? = null
    private var rectF5: RectF? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var rectWidth = 0
    private var columnCount = 7
    private val space = 8
    private var randomHeight = 0
    private var random: Random? = null
    private var handler: Handler? = null

    constructor(context: Context) : super(context) {
        initHandler()
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initHandler()
        init()
    }

    private fun initHandler() {
        handler = object : Handler() {
            override fun handleMessage(msg: Message) {
                invalidate()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        viewHeight = MeasureSpec.getSize(heightMeasureSpec)
        rectWidth = (viewWidth - space * (columnCount - 1)) / columnCount
    }

    private fun init() {
        paint = Paint()
        paint!!.color = Color.RED // 字节跳动颜色
        paint!!.style = Paint.Style.FILL
        random = Random()
        initRect()
    }

    private fun initRect() {
        rectF1 = RectF()
        rectF2 = RectF()
        rectF3 = RectF()
        rectF4 = RectF()
        rectF5 = RectF()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = rectWidth + space

        // 画每个条之前高度都重新随机生成
        randomHeight = random!!.nextInt(viewHeight)
        rectF1!![left * 0.toFloat(), randomHeight.toFloat(), left * 0 + rectWidth.toFloat()] = viewHeight.toFloat()
        randomHeight = random!!.nextInt(viewHeight)
        rectF2!![left * 1.toFloat(), randomHeight.toFloat(), left * 1 + rectWidth.toFloat()] = viewHeight.toFloat()
        randomHeight = random!!.nextInt(viewHeight)
        rectF3!![left * 2.toFloat(), randomHeight.toFloat(), left * 2 + rectWidth.toFloat()] = viewHeight.toFloat()
        randomHeight = random!!.nextInt(viewHeight)
        rectF4!![left * 3.toFloat(), randomHeight.toFloat(), left * 3 + rectWidth.toFloat()] = viewHeight.toFloat()
        randomHeight = random!!.nextInt(viewHeight)
        rectF5!![left * 4.toFloat(), randomHeight.toFloat(), left * 4 + rectWidth.toFloat()] = viewHeight.toFloat()

        canvas.drawRect(rectF1!!, paint!!)
        canvas.drawRect(rectF2!!, paint!!)
        canvas.drawRect(rectF3!!, paint!!)
        canvas.drawRect(rectF4!!, paint!!)
        canvas.drawRect(rectF5!!, paint!!)

        // 仅在已挂载到窗口且可见时才自续 200ms 刷新循环（波形动画保持不变）；
        // detach 或不可见时不再调度，避免 EPG 列表项无限 invalidate 导致泄漏与常驻耗电
        if (isAttachedToWindow && visibility == View.VISIBLE && windowVisibility == View.VISIBLE) {
            handler!!.sendEmptyMessageDelayed(0, 200) // 每间隔 200 毫秒发送消息刷新
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 从窗口移除时清掉挂起的刷新消息，终止自续循环；重新挂载后由框架重绘自然恢复动画
        handler!!.removeCallbacksAndMessages(null)
    }
}

/** 频道列表 ListView(记忆焦点位置),Kotlin 移植(与旧版 ChannelListView 一致)。 */
class ChannelListView : ListView {
    @JvmField
    var dataChangedListener: DataChangedListener? = null
    var pos = LivePlayActivity.currentChannelGroupIndex
    private var y = 0

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setSelect(position: Int, y: Int) {
        super.setSelection(position)
        pos = position
        this.y = y
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            setSelectionFromTop(pos, y)
        }
    }

    override fun handleDataChanged() {
        super.handleDataChanged()
        dataChangedListener?.onSuccess()
    }

    fun setDataChangedListener(dataChangedListener: DataChangedListener?) {
        this.dataChangedListener = dataChangedListener
    }

    interface DataChangedListener {
        fun onSuccess()
    }
}

/** 二维码生成,Kotlin 移植(与旧版 QRCodeGen 一致)。 */
object QRCodeGen {
    @JvmStatic
    fun generateBitmap(content: String, width: Int, height: Int, padding: Int): Bitmap? {
        val qrCodeWriter = QRCodeWriter()
        val hints = HashMap<EncodeHintType, String>()
        hints[EncodeHintType.CHARACTER_SET] = "utf-8"
        hints[EncodeHintType.MARGIN] = padding.toString() + ""
        return try {
            val encode: BitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints)
            val pixels = IntArray(width * height)
            for (i in 0 until height) {
                for (j in 0 until width) {
                    pixels[i * width + j] = if (encode.get(j, i)) {
                        0x00000000
                    } else {
                        0xffffffff.toInt()
                    }
                }
            }
            Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @JvmStatic
    fun generateBitmap(content: String, width: Int, height: Int): Bitmap? {
        return generateBitmap(content, width, height, 0)
    }
}
