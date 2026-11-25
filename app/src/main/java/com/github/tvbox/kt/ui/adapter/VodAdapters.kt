package com.github.tvbox.kt.ui.adapter

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.VodCollect
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.ImgUtil
import com.orhanobut.hawk.Hawk
import me.jessyan.autosize.utils.AutoSizeUtils
import java.io.File
import java.text.DecimalFormat
import java.util.ArrayList

/** 首页网格/列表适配器,Kotlin 移植(与旧版 GridAdapter 一致)。 */
class GridAdapter(showList: Boolean, val style: ImgUtil.Style?) :
    BaseQuickAdapter<Movie.Video, BaseViewHolder>(if (showList) R.layout.item_list else R.layout.item_grid, ArrayList()) {

    private var mShowList = showList
    private var defaultWidth = 0
    // 样式尺寸换算一次即可;每次 bind 重新赋值 layoutParams 会触发 requestLayout,
    // 首页/分类每屏数十个 item 反复重排是滚动掉帧的主要来源。
    private var styledWidthPx = 0
    private var styledHeightPx = 0

    init {
        if (style != null) {
            if (style.type == "list") mShowList = true
            defaultWidth = ImgUtil.getStyleDefaultWidth(style)
        }
    }

    override fun convert(helper: BaseViewHolder, item: Movie.Video) {
        if (mShowList) {
            helper.setText(R.id.tvNote, item.note)
            helper.setText(R.id.tvName, item.name)
            val ivThumb = helper.getView<ImageView>(R.id.ivThumb)
            val pic = item.pic?.trim() ?: ""
            if (!TextUtils.isEmpty(pic)) {
                if (ImgUtil.isBase64Image(pic)) {
                    ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic))
                } else {
                    val roundingRadius = AutoSizeUtils.mm2px(mContext, 10f)
                    val imageWidth = AutoSizeUtils.mm2px(mContext, 240f)
                    val imageHeight = AutoSizeUtils.mm2px(mContext, 336f)
                    if (style != null) {
                        ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name, ImageView.ScaleType.FIT_XY)
                    } else {
                        ImgUtil.loadCenterCrop(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name)
                    }
                }
            } else {
                ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
            }
            return
        }

        val tvYear = helper.getView<TextView>(R.id.tvYear)
        if (item.year <= 0) {
            tvYear.visibility = View.GONE
        } else {
            tvYear.text = item.year.toString()
            tvYear.visibility = View.VISIBLE
        }
        helper.getView<TextView>(R.id.tvLang).visibility = View.GONE
        helper.getView<TextView>(R.id.tvArea).visibility = View.GONE
        if (TextUtils.isEmpty(item.note)) {
            helper.setVisible(R.id.tvNote, false)
        } else {
            helper.setVisible(R.id.tvNote, true)
            helper.setText(R.id.tvNote, item.note)
        }
        helper.setText(R.id.tvName, item.name)
        helper.setText(R.id.tvActor, item.actor)
        val ivThumb = helper.getView<ImageView>(R.id.ivThumb)
        var newWidth = ImgUtil.defaultWidth
        var newHeight = ImgUtil.defaultHeight
        if (style != null) {
            newWidth = defaultWidth
            newHeight = (newWidth / style.ratio).toInt()
        }
        val pic = item.pic?.trim() ?: ""
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic))
            } else {
                val roundingRadius = AutoSizeUtils.mm2px(mContext, 10f)
                val imageWidth = AutoSizeUtils.mm2px(mContext, newWidth.toFloat())
                val imageHeight = AutoSizeUtils.mm2px(mContext, newHeight.toFloat())
                if (style != null) {
                    ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name, ImageView.ScaleType.FIT_XY)
                } else {
                    ImgUtil.loadCenterCrop(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name)
                }
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
        }
        applyStyleToImage(ivThumb)
    }

    private fun applyStyleToImage(ivThumb: ImageView) {
        val s = style ?: return
        val container = ivThumb.parent as? ViewGroup ?: return
        val containerParams = container.layoutParams ?: return
        if (styledWidthPx == 0) {
            val height = (defaultWidth / s.ratio).toInt()
            styledWidthPx = AutoSizeUtils.mm2px(mContext, defaultWidth.toFloat())
            styledHeightPx = AutoSizeUtils.mm2px(mContext, height.toFloat())
        }
        // 尺寸没变就不要回写,否则每个 item 每次复用都会触发一次 measure/layout。
        if (containerParams.width == styledWidthPx && containerParams.height == styledHeightPx) return
        containerParams.width = styledWidthPx
        containerParams.height = styledHeightPx
        container.layoutParams = containerParams
    }
}

/** 历史记录适配器,Kotlin 移植(与旧版 HistoryAdapter 一致)。 */
class HistoryAdapter : BaseQuickAdapter<VodInfo, BaseViewHolder>(R.layout.item_grid, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: VodInfo) {
        val tvDel = helper.getView<FrameLayout>(R.id.delFrameLayout)
        tvDel.visibility = if (HawkConfig.hotVodDelete) View.VISIBLE else View.GONE

        val tvYear = helper.getView<TextView>(R.id.tvYear)
        val bean = ApiConfig.get().getSource(item.sourceKey)
        tvYear.text = bean?.name ?: "搜"
        helper.setVisible(R.id.tvLang, false)
        helper.setVisible(R.id.tvArea, false)
        if (item.note == null || item.note!!.isEmpty()) {
            helper.setVisible(R.id.tvNote, false)
        } else {
            helper.setText(R.id.tvNote, item.note)
        }
        helper.setText(R.id.tvName, item.name)
        val ivThumb = helper.getView<ImageView>(R.id.ivThumb)
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.loadCenterCrop(
                item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10f),
                AutoSizeUtils.mm2px(mContext, ImgUtil.defaultWidth.toFloat()),
                AutoSizeUtils.mm2px(mContext, ImgUtil.defaultHeight.toFloat()), item.name
            )
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
        }
    }
}

/** 收藏适配器,Kotlin 移植(与旧版 CollectAdapter 一致)。 */
class CollectAdapter : BaseQuickAdapter<VodCollect, BaseViewHolder>(R.layout.item_grid, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: VodCollect) {
        val tvDel = helper.getView<FrameLayout>(R.id.delFrameLayout)
        tvDel.visibility = if (HawkConfig.hotVodDelete) View.VISIBLE else View.GONE

        helper.setVisible(R.id.tvLang, false)
        helper.setVisible(R.id.tvArea, false)
        helper.setVisible(R.id.tvNote, false)
        helper.setText(R.id.tvName, item.name)
        val tvYear = helper.getView<TextView>(R.id.tvYear)
        val source = ApiConfig.get().getSource(item.sourceKey)
        tvYear.text = source?.name ?: ""

        val ivThumb = helper.getView<ImageView>(R.id.ivThumb)
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.loadCenterCrop(
                item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10f),
                AutoSizeUtils.mm2px(mContext, ImgUtil.defaultWidth.toFloat()),
                AutoSizeUtils.mm2px(mContext, ImgUtil.defaultHeight.toFloat()), item.name
            )
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
        }
    }
}

/** 首页热门推荐适配器,Kotlin 移植(与旧版 HomeHotVodAdapter 一致)。 */
class HomeHotVodAdapter(style: ImgUtil.Style?, tvRate0: String) :
    BaseQuickAdapter<Movie.Video, BaseViewHolder>(R.layout.item_user_hot_vod, ArrayList()) {

    private var defaultWidth = 0
    private val style: ImgUtil.Style?
    private var tvRateValue: String? = tvRate0
    private var styledWidthPx = 0
    private var styledHeightPx = 0

    init {
        if (style != null) {
            this.defaultWidth = ImgUtil.getStyleDefaultWidth(style)
        }
        this.style = style
    }

    override fun convert(helper: BaseViewHolder, item: Movie.Video) {
        val tvDel = helper.getView<FrameLayout>(R.id.delFrameLayout)
        tvDel.visibility = if (HawkConfig.hotVodDelete) View.VISIBLE else View.GONE

        val tvRate = helper.getView<TextView>(R.id.tvRate)
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            val bean = ApiConfig.get().getSource(item.sourceKey)
            tvRateValue = bean?.name ?: ""
        }
        tvRate.text = tvRateValue

        val tvNote = helper.getView<TextView>(R.id.tvNote)
        if (item.note == null || item.note!!.isEmpty()) {
            tvNote.visibility = View.GONE
        } else {
            tvNote.text = item.note
            tvNote.visibility = View.VISIBLE
        }
        helper.setText(R.id.tvName, item.name)
        val ivThumb = helper.getView<ImageView>(R.id.ivThumb)

        var newWidth = ImgUtil.defaultWidth
        var newHeight = ImgUtil.defaultHeight
        if (style != null) {
            newWidth = defaultWidth
            newHeight = (newWidth / style.ratio).toInt()
        }

        val pic = item.pic?.trim() ?: ""
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic))
            } else {
                ImgUtil.loadCenterCrop(
                    pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10f),
                    AutoSizeUtils.mm2px(mContext, newWidth.toFloat()),
                    AutoSizeUtils.mm2px(mContext, newHeight.toFloat()), item.name
                )
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
        }
        applyStyleToImage(ivThumb)
    }

    private fun applyStyleToImage(ivThumb: ImageView) {
        val s = style ?: return
        val container = ivThumb.parent as? ViewGroup ?: return
        val containerParams = container.layoutParams ?: return
        if (styledWidthPx == 0) {
            val height = (defaultWidth / s.ratio).toInt()
            styledWidthPx = AutoSizeUtils.mm2px(mContext, defaultWidth.toFloat())
            styledHeightPx = AutoSizeUtils.mm2px(mContext, height.toFloat())
        }
        if (containerParams.width == styledWidthPx && containerParams.height == styledHeightPx) return
        containerParams.width = styledWidthPx
        containerParams.height = styledHeightPx
        container.layoutParams = containerParams
    }
}

/** 快搜适配器,Kotlin 移植(与旧版 FastSearchAdapter 一致)。 */
class FastSearchAdapter : BaseQuickAdapter<Movie.Video, BaseViewHolder>(R.layout.item_search, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: Movie.Video) {
        helper.setText(R.id.tvName, item.name)
        helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey)?.name)
        helper.setVisible(R.id.tvNote, item.note != null && !item.note!!.isEmpty())
        if (item.note != null && !item.note!!.isEmpty()) helper.setText(R.id.tvNote, item.note)
        val ivThumb = helper.getView<ImageView>(R.id.ivThumb)
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.loadCenterCrop(
                item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10f),
                AutoSizeUtils.mm2px(mContext, 214f),
                AutoSizeUtils.mm2px(mContext, 280f), item.name
            )
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
        }
    }
}

/** 搜索适配器,Kotlin 移植(与旧版 SearchAdapter 一致)。 */
class SearchAdapter : BaseQuickAdapter<Movie.Video, BaseViewHolder>(
    if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0) R.layout.item_search_lite else R.layout.item_search_normal,
    ArrayList()
) {

    override fun convert(helper: BaseViewHolder, item: Movie.Video) {
        if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0) {
            helper.setText(
                R.id.tvName,
                String.format(
                    "%s  %s %s %s",
                    ApiConfig.get().getSource(item.sourceKey)?.name,
                    item.name,
                    item.type ?: "",
                    item.note ?: ""
                )
            )
        } else {
            helper.setText(R.id.tvName, item.name)
            helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey)?.name)
            helper.setVisible(R.id.tvNote, item.note != null && !item.note!!.isEmpty())
            if (item.note != null && !item.note!!.isEmpty()) helper.setText(R.id.tvNote, item.note)
            val ivThumb = helper.getView<ImageView>(R.id.ivThumb)
            if (!TextUtils.isEmpty(item.pic)) {
                ImgUtil.loadCenterCrop(
                    item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10f),
                    AutoSizeUtils.mm2px(mContext, SEARCH_POSTER_WIDTH.toFloat()),
                    AutoSizeUtils.mm2px(mContext, SEARCH_POSTER_HEIGHT.toFloat()), item.name
                )
            } else {
                ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name ?: ""))
            }
        }
    }

    companion object {
        private const val SEARCH_POSTER_WIDTH = 180
        private const val SEARCH_POSTER_HEIGHT = 240
    }
}

/** 快速搜索适配器,Kotlin 移植(与旧版 QuickSearchAdapter 一致)。 */
class QuickSearchAdapter : BaseQuickAdapter<Movie.Video, BaseViewHolder>(R.layout.item_quick_search_lite, ArrayList()) {

    override fun convert(helper: BaseViewHolder, item: Movie.Video) {
        // lite
        helper.setText(
            R.id.tvName,
            String.format(
                "%s  %s %s %s",
                ApiConfig.get().getSource(item.sourceKey)?.name,
                item.name,
                item.type ?: "",
                item.note ?: ""
            )
        )
    }
}

/** 本地文件适配器,Kotlin 移植(与旧版 LocalFileAdapter 一致)。 */
class LocalFileAdapter : BaseQuickAdapter<File, BaseViewHolder>(R.layout.item_local_file, ArrayList()) {
    private var parentDir: File? = null

    override fun convert(helper: BaseViewHolder, item: File) {
        val isParent = parentDir != null && parentDir == item
        helper.setText(R.id.tvType, if (item.isDirectory) "目录" else "文件")
        helper.setText(R.id.tvName, if (isParent) ".." else item.name)
        helper.setText(R.id.tvInfo, if (item.isDirectory) "进入" else formatSize(item.length()))
    }

    fun setParentDir(parentDir: File?) {
        this.parentDir = parentDir
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return SIZE_FORMAT.format(kb) + " KB"
        val mb = kb / 1024.0
        if (mb < 1024) return SIZE_FORMAT.format(mb) + " MB"
        return SIZE_FORMAT.format(mb / 1024.0) + " GB"
    }

    companion object {
        private val SIZE_FORMAT = DecimalFormat("0.#")
    }
}
