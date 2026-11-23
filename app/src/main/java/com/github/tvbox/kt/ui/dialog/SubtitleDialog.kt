package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.github.tvbox.kt.R
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.SubtitleHelper

/** 字幕设置对话框,Kotlin 移植(与旧版 SubtitleDialog 一致)。 */
class SubtitleDialog(context: Context) : BaseDialog(context) {

    lateinit var selectInternal: TextView
    private lateinit var selectLocal: TextView
    private lateinit var selectRemote: TextView
    private lateinit var subtitleSizeMinus: TextView
    private lateinit var subtitleSizeText: TextView
    private lateinit var subtitleSizePlus: TextView
    private lateinit var subtitleTimeMinus: TextView
    private lateinit var subtitleTimeText: TextView
    private lateinit var subtitleTimePlus: TextView
    private lateinit var subtitleStyleOne: TextView
    private lateinit var subtitleStyleTwo: TextView
    private lateinit var subtitlePositionText: TextView
    private lateinit var subtitleTimeHint: TextView
    private var exoInternalSubtitle = false

    private var mSearchSubtitleListener: SearchSubtitleListener? = null
    private var mLocalFileChooserListener: LocalFileChooserListener? = null
    private var mSubtitleViewListener: SubtitleViewListener? = null

    init {
        if (context is Activity) {
            setOwnerActivity(context)
        }
        setContentView(R.layout.dialog_subtitle)
        initView(context)
    }

    private fun initView(context: Context) {
        selectInternal = findViewById(R.id.selectInternal)
        selectLocal = findViewById(R.id.selectLocal)
        selectRemote = findViewById(R.id.selectRemote)
        subtitleSizeMinus = findViewById(R.id.subtitleSizeMinus)
        subtitleSizeText = findViewById(R.id.subtitleSizeText)
        subtitleSizePlus = findViewById(R.id.subtitleSizePlus)
        subtitleTimeMinus = findViewById(R.id.subtitleTimeMinus)
        subtitleTimeText = findViewById(R.id.subtitleTimeText)
        subtitleTimePlus = findViewById(R.id.subtitleTimePlus)
        subtitleStyleOne = findViewById(R.id.subtitleStyleOne)
        subtitleStyleTwo = findViewById(R.id.subtitleStyleTwo)
        subtitlePositionText = findViewById(R.id.subtitlePositionText)
        subtitleTimeHint = findViewById(R.id.subtitleTimeHint)

        selectLocal.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            dismiss()
            mLocalFileChooserListener?.openLocalFileChooserDialog()
        }

        selectRemote.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            dismiss()
            mSearchSubtitleListener?.openSearchSubtitleDialog()
        }

        val size = SubtitleHelper.getTextSize(ownerActivity!!)
        subtitleSizeText.text = size.toString()

        subtitleSizeMinus.setOnClickListener {
            if (exoInternalSubtitle) {
                val scale = maxOf(50, SubtitleHelper.getExoSubtitleScale() - 5)
                subtitleSizeText.text = "$scale%"
                SubtitleHelper.setExoSubtitleScale(scale)
                mSubtitleViewListener?.setSubtitleScale(scale)
                return@setOnClickListener
            }
            val sizeStr = subtitleSizeText.text.toString()
            var curSize = sizeStr.toInt()
            curSize -= 2
            if (curSize <= 12) {
                curSize = 12
            }
            subtitleSizeText.text = curSize.toString()
            SubtitleHelper.setTextSize(curSize)
            mSubtitleViewListener?.setTextSize(curSize)
        }
        subtitleSizePlus.setOnClickListener {
            if (exoInternalSubtitle) {
                val scale = minOf(200, SubtitleHelper.getExoSubtitleScale() + 5)
                subtitleSizeText.text = "$scale%"
                SubtitleHelper.setExoSubtitleScale(scale)
                mSubtitleViewListener?.setSubtitleScale(scale)
                return@setOnClickListener
            }
            val sizeStr = subtitleSizeText.text.toString()
            var curSize = sizeStr.toInt()
            curSize += 2
            if (curSize >= 60) {
                curSize = 60
            }
            subtitleSizeText.text = curSize.toString()
            SubtitleHelper.setTextSize(curSize)
            mSubtitleViewListener?.setTextSize(curSize)
        }

        val timeDelay = SubtitleHelper.getTimeDelay()
        var timeStr = "0"
        if (timeDelay != 0) {
            val dbTimeDelay = timeDelay / 1000.0
            timeStr = dbTimeDelay.toString()
        }
        subtitleTimeText.text = timeStr

        subtitleTimeMinus.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            var timeStr = subtitleTimeText.text.toString()
            var time = timeStr.toFloat().toDouble()
            val oneceDelay = -0.5
            time += oneceDelay
            timeStr = if (time == 0.0) "0" else time.toString()
            subtitleTimeText.text = timeStr
            val mseconds = (oneceDelay * 1000).toInt()
            SubtitleHelper.setTimeDelay((time * 1000).toInt())
            mSubtitleViewListener?.setSubtitleDelay(mseconds)
        }
        subtitleTimePlus.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            var timeStr = subtitleTimeText.text.toString()
            var time = timeStr.toFloat().toDouble()
            val oneceDelay = 0.5
            time += oneceDelay
            timeStr = if (time == 0.0) "0" else time.toString()
            subtitleTimeText.text = timeStr
            val mseconds = (oneceDelay * 1000).toInt()
            SubtitleHelper.setTimeDelay((time * 1000).toInt())
            mSubtitleViewListener?.setSubtitleDelay(mseconds)
        }
        selectInternal.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            dismiss()
            mSubtitleViewListener?.selectInternalSubtitle()
        }

        subtitleStyleOne.setOnClickListener {
            if (exoInternalSubtitle) {
                val position = minOf(80.0f, SubtitleHelper.getExoSubtitlePosition() + 0.5f)
                SubtitleHelper.setExoSubtitlePosition(position)
                subtitlePositionText.text = if (position == 0.0f) "0" else "$position%"
                mSubtitleViewListener?.moveSubtitle(position)
                return@setOnClickListener
            }
            val style = 0
            dismiss()
            mSubtitleViewListener?.setTextStyle(style)
            Toast.makeText(context, "设置样式成功", Toast.LENGTH_SHORT).show()
        }

        subtitleStyleTwo.setOnClickListener {
            if (exoInternalSubtitle) {
                val position = maxOf(-80.0f, SubtitleHelper.getExoSubtitlePosition() - 0.5f)
                SubtitleHelper.setExoSubtitlePosition(position)
                subtitlePositionText.text = if (position == 0.0f) "0" else "$position%"
                mSubtitleViewListener?.moveSubtitle(position)
                return@setOnClickListener
            }
            val style = 1
            dismiss()
            mSubtitleViewListener?.setTextStyle(style)
            Toast.makeText(context, "设置样式成功", Toast.LENGTH_SHORT).show()
        }
    }

    fun setExoInternalSubtitle(exoInternalSubtitle: Boolean) {
        this.exoInternalSubtitle = exoInternalSubtitle
        if (exoInternalSubtitle) {
            subtitleSizeText.text = SubtitleHelper.getExoSubtitleScale().toString() + "%"
            subtitleStyleOne.text = "字幕上移"
            subtitleStyleTwo.text = "字幕下移"
            subtitleStyleTwo.setTextColor(context.resources.getColor(R.color.dialog_text_primary))
            val position = SubtitleHelper.getExoSubtitlePosition()
            subtitlePositionText.text = if (position == 0.0f) "0" else "$position%"
            subtitleTimeHint.text = "字幕延时对内置字幕有效"
        } else {
            subtitleSizeText.text = SubtitleHelper.getTextSize(ownerActivity!!).toString()
            subtitleStyleOne.text = "字幕样式一"
            subtitleStyleTwo.text = "字幕样式二"
            subtitleStyleTwo.setTextColor(context.resources.getColor(R.color.color_FFB6C1))
            subtitlePositionText.text = ""
            subtitleTimeHint.text = "字幕延时仅对外挂字幕有效"
        }
    }

    fun setLocalFileChooserListener(localFileChooserListener: LocalFileChooserListener?) {
        mLocalFileChooserListener = localFileChooserListener
    }

    interface LocalFileChooserListener {
        fun openLocalFileChooserDialog()
    }

    fun setSearchSubtitleListener(searchSubtitleListener: SearchSubtitleListener?) {
        mSearchSubtitleListener = searchSubtitleListener
    }

    interface SearchSubtitleListener {
        fun openSearchSubtitleDialog()
    }

    fun setSubtitleViewListener(subtitleViewListener: SubtitleViewListener?) {
        mSubtitleViewListener = subtitleViewListener
    }

    interface SubtitleViewListener {
        fun setTextSize(size: Int)

        fun setSubtitleDelay(milliseconds: Int)

        fun selectInternalSubtitle()

        fun setTextStyle(style: Int)

        fun setSubtitleScale(scale: Int)

        fun moveSubtitle(offset: Float)
    }
}
