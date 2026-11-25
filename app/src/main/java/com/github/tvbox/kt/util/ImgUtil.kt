package com.github.tvbox.kt.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import me.jessyan.autosize.utils.AutoSizeUtils
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Random

/** 图片加载工具,Kotlin 移植(与旧版 ImgUtil 一致)。 */
object ImgUtil {
    // LRU 上限缓存（最多 64 张）：fallback 占位图按影片名逐张累积曾导致无界增长（千部影片可达数百 MB，OOM 风险）
    private val drawableCache = object : LinkedHashMap<String, Drawable>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>): Boolean {
            return size > 64
        }
    }
    const val defaultWidth = 244
    const val defaultHeight = 320

    class Style(var ratio: Float, var type: String?)

    fun isBase64Image(picUrl: String?): Boolean {
        return picUrl != null && picUrl.startsWith("data:image")
    }

    fun initStyle(): Style? {
        val bStyle = ApiConfig.get().homeSourceBean.style
        if (!bStyle.isEmpty()) {
            try {
                val jsonObject = JSONObject(bStyle)
                return Style(jsonObject.getDouble("ratio").toFloat(), jsonObject.getString("type"))
            } catch (ignored: JSONException) {
            }
        }
        return null
    }

    fun spanCountByStyle(style: Style, defaultCount: Int): Int {
        var spanCount = defaultCount
        if ("rect" == style.type) {
            if (style.ratio >= 1.7) {
                spanCount = 3
            } else if (style.ratio >= 1.3) {
                spanCount = 4
            }
        } else if ("list" == style.type) {
            spanCount = 1
        }
        return spanCount
    }

    fun getStyleDefaultWidth(style: Style): Int {
        var styleDefaultWidth = 280
        if (style.ratio < 1) styleDefaultWidth = 214
        if (style.ratio > 1.7) styleDefaultWidth = 380
        return styleDefaultWidth
    }

    fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        val base64Data = base64Str.substring(base64Str.indexOf(",") + 1)
        val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }

    @JvmOverloads
    fun load(url: String?, view: ImageView, roundingRadius: Int, newWidth: Int = 0, newHeight: Int = 0, label: String? = null, scaleType: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP) {
        view.scaleType = scaleType
        var radius = roundingRadius
        if (radius <= 0) radius = 1
        val fallbackLabel = if (TextUtils.isEmpty(label)) "TVBox" else label!!
        val fallbackRadius = radius.toFloat()
        val placeholder = createImagePlaceholderDrawable(newWidth, newHeight, fallbackRadius)
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(createTextDrawable(fallbackLabel, newWidth, newHeight, fallbackRadius))
            return
        }
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .transform(FitCenter(), RoundedCorners(radius))
        val finalOptions = if (newWidth > 0 && newHeight > 0) options.override(newWidth, newHeight) else options
        Glide.with(KtApp.instance())
            .asBitmap()
            .load(getUrl(url))
            .placeholder(placeholder)
            .listener(getListener(view, scaleType, fallbackLabel, newWidth, newHeight, fallbackRadius))
            .apply(finalOptions)
            .into(view)
    }

    fun loadCenterCrop(url: String?, view: ImageView, roundingRadius: Int, newWidth: Int, newHeight: Int, label: String?) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        var radius = roundingRadius
        if (radius <= 0) radius = 1
        val fallbackLabel = if (TextUtils.isEmpty(label)) "TVBox" else label!!
        val fallbackRadius = radius.toFloat()
        val placeholder = createImagePlaceholderDrawable(newWidth, newHeight, fallbackRadius)
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(createTextDrawable(fallbackLabel, newWidth, newHeight, fallbackRadius))
            return
        }
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .transform(CenterCrop(), RoundedCorners(radius))
        val finalOptions = if (newWidth > 0 && newHeight > 0) options.override(newWidth, newHeight) else options
        Glide.with(KtApp.instance())
            .asBitmap()
            .load(getUrl(url))
            .placeholder(placeholder)
            .listener(getListener(view, ImageView.ScaleType.CENTER_CROP, fallbackLabel, newWidth, newHeight, fallbackRadius))
            .apply(finalOptions)
            .into(view)
    }

    fun getRandomColor(): Int {
        val random = Random()
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256))
    }

    fun createTextDrawable(text: String): Drawable {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(KtApp.instance(), 10f).toFloat())
    }

    private fun createTextDrawable(text0: String?, width0: Int, height0: Int, cornerRadius0: Float): Drawable {
        var text = text0
        var width = width0
        var height = height0
        var cornerRadius = cornerRadius0
        if (TextUtils.isEmpty(text)) text = "TVBox"
        if (width <= 0) width = 180
        if (height <= 0) height = 240
        if (cornerRadius <= 0) cornerRadius = 1f
        val key = text + "_" + width + "x" + height + "_" + cornerRadius.toInt()
        text = text!!.substring(0, 1)
        if (drawableCache.containsKey(key)) return drawableCache[key]!!
        val randomColor = getRandomColor()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = randomColor
        paint.style = Paint.Style.FILL
        val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        paint.color = Color.WHITE
        paint.textSize = 60f
        paint.textAlign = Paint.Align.CENTER
        val fontMetrics = paint.fontMetrics
        val x = width / 2f
        val y = (height - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(text, x, y, paint)
        val drawable = BitmapDrawable(KtApp.instance().resources, bitmap)
        drawableCache[key] = drawable
        return drawable
    }

    private fun createImagePlaceholderDrawable(width0: Int, height0: Int, cornerRadius0: Float): Drawable {
        var width = width0
        var height = height0
        var cornerRadius = cornerRadius0
        if (width <= 0) width = 180
        if (height <= 0) height = 240
        if (cornerRadius <= 0) cornerRadius = 1f
        val key = "placeholder_" + width + "x" + height + "_" + cornerRadius.toInt()
        if (drawableCache.containsKey(key)) return drawableCache[key]!!

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val icon = BitmapFactory.decodeResource(KtApp.instance().resources, R.drawable.icon_img_placeholder)
        if (icon != null) {
            val left = (width - icon.width) / 2f
            val top = (height - icon.height) / 2f
            canvas.drawBitmap(icon, left, top, null)
        }
        val drawable = BitmapDrawable(KtApp.instance().resources, bitmap)
        drawableCache[key] = drawable
        return drawable
    }

    fun clearCache() {
        drawableCache.clear()
    }

    fun clearMemoryCache() {
        clearCache()
        try {
            Glide.get(KtApp.instance()).clearMemory()
            LOG.i("echo-img-clear-memory-cache")
        } catch (th: Throwable) {
            LOG.i("echo-img-clear-memory-cache-error:" + th.message)
        }
    }

    private fun getUrl(url0: String?): Any? {
        var url = url0 ?: return null
        if (url.startsWith("data:")) return url
        // 绝大多数海报 URL 不带 @Headers/@Cookie 等附加参数。走这里可省掉
        // 4 次正则 split、HashMap 与 LazyHeaders 构建,直接交给 Glide 当 String model。
        if (url.indexOf('@') < 0) return url
        var header: String? = null
        var referer: String? = null
        var ua: String? = null
        var cookie: String? = null
        if (url.contains("@Headers=")) {
            header = url.split("@Headers=".toRegex())[1].split("@".toRegex())[0]
            try {
                header = URLDecoder.decode(header, "UTF-8")
            } catch (ignored: Exception) {
            }
        }
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=".toRegex())[1].split("@".toRegex())[0]
        if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=".toRegex())[1].split("@".toRegex())[0]
        if (url.contains("@Referer=")) referer = url.split("@Referer=".toRegex())[1].split("@".toRegex())[0]
        url = url.split("@".toRegex())[0]
        if (TextUtils.isEmpty(url)) return null

        val builder = LazyHeaders.Builder()
        val headers = HashMap<String, String>()
        if (!TextUtils.isEmpty(header)) {
            try {
                val jsonInfo = Gson().fromJson(header, JsonObject::class.java)
                for (key in jsonInfo.keySet()) {
                    putHeader(headers, key, jsonInfo.get(key).asString)
                }
            } catch (ignored: Throwable) {
            }
        }
        putHeader(headers, "Cookie", cookie)
        if (!TextUtils.isEmpty(ua)) putHeader(headers, "User-Agent", ua)
        if (!TextUtils.isEmpty(referer)) putHeader(headers, "Referer", referer)
        for (entry in headers.entries) builder.setHeader(entry.key, entry.value)
        return GlideUrl(url, builder.build())
    }

    fun getImageModel(url: String?): Any? {
        return getUrl(url)
    }

    private fun isInvalidImageUrl(url0: String?): Boolean {
        var url = url0
        if (TextUtils.isEmpty(url)) return true
        url = url!!.trim()
        if (TextUtils.isEmpty(url)) return true
        return hasEmptyProxyParam(url, "img")
    }

    private fun hasEmptyProxyParam(url: String, key: String): Boolean {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false
        val queryIndex = url.indexOf('?')
        val query = if (queryIndex >= 0) url.substring(queryIndex + 1) else url.substring("proxy://".length)
        val pairs = query.split("&".toRegex()).toTypedArray()
        for (pair in pairs) {
            val eqIndex = pair.indexOf('=')
            if (eqIndex < 0) continue
            if (key == pair.substring(0, eqIndex) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true
            }
        }
        return false
    }

    private fun putHeader(headers: MutableMap<String, String>, key: String?, value: String?) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) return
        headers[key!!] = value!!.trim()
    }

    private fun getListener(
        view: ImageView,
        scaleType: ImageView.ScaleType,
        fallbackLabel: String,
        width: Int,
        height: Int,
        radius: Float
    ): RequestListener<Bitmap> {
        return object : RequestListener<Bitmap> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Bitmap>,
                isFirstResource: Boolean
            ): Boolean {
                view.scaleType = scaleType
                // 只在真正失败时才生成占位图,成功加载的 item 不再白白分配一张 Bitmap。
                view.setImageDrawable(createTextDrawable(fallbackLabel, width, height, radius))
                return true
            }

            override fun onResourceReady(
                resource: Bitmap,
                model: Any,
                target: Target<Bitmap>,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                view.scaleType = scaleType
                return false
            }
        }
    }
}
