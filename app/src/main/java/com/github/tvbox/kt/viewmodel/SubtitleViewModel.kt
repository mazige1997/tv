package com.github.tvbox.kt.viewmodel

import android.text.TextUtils
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.tvbox.kt.bean.Subtitle
import com.github.tvbox.kt.bean.SubtitleData
import com.github.tvbox.kt.ui.dialog.SearchSubtitleDialog
import com.github.tvbox.kt.util.OkGoHelper
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLDecoder
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 字幕搜索 ViewModel(assrt.net),Kotlin 移植(与旧版 SubtitleViewModel 一致)。
 */
class SubtitleViewModel : ViewModel() {

    var searchResultData: MutableLiveData<SubtitleData> = MutableLiveData()

    private var pagesTotal = -1

    private val regexShooterFileOnclick: Pattern =
        Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)")

    fun searchResult(title: String, page: Int) {
        searchResultFromAssrt(title, page)
    }

    fun getSearchResultSubtitleUrls(subtitle: Subtitle) {
        getSearchResultSubtitleUrlsFromAssrt(subtitle)
    }

    fun getSubtitleUrl(subtitle: Subtitle, subtitleLoader: SearchSubtitleDialog.SubtitleLoader) {
        getSubtitleUrlFromAssrt(subtitle, subtitleLoader)
    }

    private fun setSearchListData(data: List<Subtitle>?, isNew: Boolean, isZip: Boolean) {
        try {
            val subtitleData = SubtitleData()
            subtitleData.setSubtitleList(data)
            subtitleData.setIsNew(isNew)
            subtitleData.setIsZip(isZip)
            searchResultData.postValue(subtitleData)
        } catch (e: Throwable) {
            e.printStackTrace()
            searchResultData.postValue(null)
        }
    }

    private fun searchResultFromAssrt(title: String, page: Int) {
        try {
            if (pagesTotal > 0 && page > pagesTotal) {
                setSearchListData(ArrayList(), page <= 1, true)
                return
            }
            if (page == 1) pagesTotal = -1 // 第一页时重置页大小
            val searchApiUrl = "https://secure.assrt.net/sub/"
            OkGo.get<String>(searchApiUrl)
                .params("searchword", title)
                .params("sort", "rank")
                .params("page", page)
                .params("no_redir", "1")
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        try {
                            val content = response.body()
                            val doc = Jsoup.parse(content)
                            val items = doc.select(".resultcard .sublist_box_title a.introtitle")
                            val data = ArrayList<Subtitle>()
                            for (item in items) {
                                val subtitleTitle = item.attr("title")
                                val href = item.attr("href")
                                if (TextUtils.isEmpty(href) || !containsSearchWord(subtitleTitle, title)) continue
                                val one = Subtitle()
                                one.name = subtitleTitle
                                one.url = "https://assrt.net$href"
                                one.setIsZip(true)
                                data.add(one)
                            }
                            setSearchListData(data, page <= 1, true)
                            val pages = doc.select(".pagelinkcard a")
                            if (pages.size > 0) {
                                val ps = pages.last()!!.text().split("/".toRegex(), 2).toTypedArray()
                                if (ps.size == 2 && !TextUtils.isEmpty(ps[1])) {
                                    pagesTotal = ps[1].trim().toInt()
                                }
                            }
                        } catch (th: Throwable) {
                            th.printStackTrace()
                        }
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        return response.body()!!.string()
                    }

                    override fun onError(response: com.lzy.okgo.model.Response<String>) {
                        super.onError(response)
                        setSearchListData(null, page <= 1, true)
                    }
                })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSearchResultSubtitleUrlsFromAssrt(subtitle: Subtitle) {
        try {
            val url = subtitle.url
            OkGo.get<String>(url).execute(object : AbsCallback<String>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                    try {
                        val content = response.body()
                        val data = ArrayList<Subtitle>()
                        val doc = Jsoup.parse(content)
                        val items = doc.select("#detail-filelist .waves-effect")
                        if (items.size > 0) { // 压缩包里面的字幕
                            for (item in items) {
                                val onclick = item.attr("onclick")
                                if (TextUtils.isEmpty(onclick)) continue
                                val matcher = regexShooterFileOnclick.matcher(onclick)
                                if (matcher.find()) {
                                    val fileName = matcher.group(3)
                                    if (!isSupportedSubtitleFile(fileName)) continue
                                    val fileUrl = String.format(
                                        "https://secure.assrt.net/download/%s/-/%s/%s",
                                        matcher.group(1), matcher.group(2), matcher.group(3)
                                    )
                                    val one = Subtitle()
                                    val name = item.selectFirst("#filelist-name")
                                    one.name = if (name == null) fileName else name.text()
                                    one.url = fileUrl
                                    one.setIsZip(false)
                                    data.add(one)
                                }
                            }
                            setSearchListData(data, true, false)
                        } else { // 有的字幕不一定是压缩包
                            val item = doc.selectFirst(".download a#btn_download")
                            if (item == null) {
                                setSearchListData(null, true, false)
                                return
                            }
                            val href = item.attr("href")
                            if (TextUtils.isEmpty(href)) {
                                setSearchListData(null, true, false)
                                return
                            }
                            if (isSupportedSubtitleFile(href)) {
                                val fileUrl = "https://assrt.net$href"
                                val one = Subtitle()
                                val title = href.substring(href.lastIndexOf("/") + 1)
                                one.name = URLDecoder.decode(title)
                                one.url = fileUrl
                                one.setIsZip(false)
                                data.add(one)
                                setSearchListData(data, true, false)
                            } else {
                                setSearchListData(null, true, false)
                            }
                        }
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }

                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    return response.body()!!.string()
                }

                override fun onError(response: com.lzy.okgo.model.Response<String>) {
                    super.onError(response)
                    setSearchListData(null, true, true)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun containsSearchWord(subtitleTitle: String?, searchWord: String): Boolean {
        if (TextUtils.isEmpty(subtitleTitle) || TextUtils.isEmpty(searchWord)) return false
        return subtitleTitle!!.lowercase(Locale.ROOT).contains(searchWord.lowercase(Locale.ROOT))
    }

    private fun isSupportedSubtitleFile(fileName: String?): Boolean {
        if (TextUtils.isEmpty(fileName)) return false
        val lower = fileName!!.lowercase(Locale.ROOT)
        return lower.endsWith(".srt") ||
            lower.endsWith(".ass") ||
            lower.endsWith(".stl") ||
            lower.endsWith(".ttml")
    }

    private fun getSubtitleUrlFromAssrt(subtitle: Subtitle, subtitleLoader: SearchSubtitleDialog.SubtitleLoader) {
        val ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
        val request = Request.Builder()
            .url(subtitle.url)
            .get()
            .addHeader("Referer", "https://secure.assrt.net")
            .addHeader("User-Agent", ua)
            .build()
        val base = OkGoHelper.getDefaultClient()
        val builder = base?.newBuilder()
            ?: OkHttpClient.Builder()
                .proxySelector(OkGoHelper.proxySelector())
                .proxyAuthenticator(OkGoHelper.proxyAuthenticator())
        builder.readTimeout(15, TimeUnit.SECONDS)
        builder.writeTimeout(15, TimeUnit.SECONDS)
        builder.connectTimeout(15, TimeUnit.SECONDS)
        builder.followRedirects(false)
        builder.followSslRedirects(false)
        builder.retryOnConnectionFailure(true)
        val client = builder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                subtitle.url = response.header("location")
                subtitleLoader.loadSubtitle(subtitle)
            }
        })
    }
}
