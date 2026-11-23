package com.github.tvbox.kt.picasso

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.squareup.picasso.Transformation

/**
 * 图片圆角/裁剪变换,Kotlin 移植(与旧版 RoundTransformation 一致)。
 */
class RoundTransformation(private var key: String = "") : Transformation {

    private var viewWidth = 0
    private var viewHeight = 0
    private var bottomShapeHeight = 0

    @RoundType
    private var mRoundType: Int = RoundType.NONE
    private var diameter = 0
    private var radius = 0
    private var isCenterCorp = true // 垂直方向不是中间裁剪,就是顶部

    fun override(width: Int, height: Int): RoundTransformation {
        viewWidth = width
        viewHeight = height
        return this
    }

    fun centerCorp(centerCorp: Boolean): RoundTransformation {
        isCenterCorp = centerCorp
        return this
    }

    fun bottomShapeHeight(shapeHeight: Int): RoundTransformation {
        bottomShapeHeight = shapeHeight
        return this
    }

    fun roundRadius(radius: Int, @RoundType mRoundType: Int): RoundTransformation {
        this.radius = radius
        diameter = radius * 2
        this.mRoundType = mRoundType
        return this
    }

    override fun transform(source: Bitmap): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (viewWidth == 0 || viewHeight == 0) {
            viewWidth = sourceWidth
            viewHeight = sourceHeight
        }
        val scale: Float
        val targetWidth: Int
        val targetHeight: Int
        if (sourceWidth != viewWidth || sourceHeight != viewHeight) {
            if (sourceWidth * 1f / viewWidth > sourceHeight * 1f / viewHeight) {
                scale = viewHeight.toFloat() / sourceHeight
                targetWidth = (sourceWidth * scale).toInt()
                targetHeight = viewHeight
            } else {
                scale = viewWidth.toFloat() / sourceWidth
                targetWidth = viewWidth
                targetHeight = (sourceHeight * scale).toInt()
            }
        } else {
            scale = 1f
            targetWidth = sourceWidth
            targetHeight = sourceHeight
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (scale != 1f) {
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            shader.setLocalMatrix(matrix)
        }
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = shader
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paint)
        source.recycle()
        return bitmap
    }

    override fun key(): String = key

    @Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.SOURCE)
    annotation class RoundType {
        companion object {
            const val ALL = 0
            const val TOP = 1
            const val RIGHT = 2
            const val BOTTOM = 3
            const val LEFT = 4
            const val LEFT_TOP = 5
            const val LEFT_BOTTOM = 6
            const val RIGHT_TOP = 7
            const val RIGHT_BOTTOM = 8
            const val NONE = 9
        }
    }

    private fun drawBottomLabel(mCanvas: Canvas, mPaint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        if (bottomShapeHeight <= 0) return
        mPaint.shader = null
        mPaint.color = 0x99000000.toInt()
        mCanvas.drawPath(
            RoundedRect(
                left, bottom - bottomShapeHeight * 2, right, bottom,
                radius.toFloat(), radius.toFloat(), false, false, true, true
            ),
            mPaint
        )
    }

    companion object {
        fun RoundedRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            rx: Float,
            ry: Float,
            tl: Boolean,
            tr: Boolean,
            br: Boolean,
            bl: Boolean
        ): android.graphics.Path {
            var rx = rx
            var ry = ry
            val path = android.graphics.Path()
            if (rx < 0) rx = 0f
            if (ry < 0) ry = 0f
            val width = right - left
            val height = bottom - top
            if (rx > width / 2) rx = width / 2
            if (ry > height / 2) ry = height / 2
            val widthMinusCorners = width - (2 * rx)
            val heightMinusCorners = height - (2 * ry)

            path.moveTo(right, top + ry)
            if (tr) path.rQuadTo(0f, -ry, -rx, -ry)
            else {
                path.rLineTo(0f, -ry)
                path.rLineTo(-rx, 0f)
            }
            path.rLineTo(-widthMinusCorners, 0f)
            if (tl) path.rQuadTo(-rx, 0f, -rx, ry)
            else {
                path.rLineTo(-rx, 0f)
                path.rLineTo(0f, ry)
            }
            path.rLineTo(0f, heightMinusCorners)
            if (bl) path.rQuadTo(0f, ry, rx, ry)
            else {
                path.rLineTo(0f, ry)
                path.rLineTo(rx, 0f)
            }
            path.rLineTo(widthMinusCorners, 0f)
            if (br) path.rQuadTo(rx, 0f, rx, -ry)
            else {
                path.rLineTo(rx, 0f)
                path.rLineTo(0f, -ry)
            }
            path.rLineTo(0f, -heightMinusCorners)
            path.close()
            return path
        }
    }
}
