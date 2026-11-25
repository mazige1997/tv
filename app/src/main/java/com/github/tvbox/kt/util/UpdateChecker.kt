package com.github.tvbox.kt.util

import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.StringCallback
import com.lzy.okgo.model.Response
import org.json.JSONArray

/**
 * OTA 检查更新:只拉取 GitHub 最新 Release 的元信息,不下载、不安装。
 *
 * 之所以不做自动版本比较:CI 的 Release tag 是 v{构建号},与本地 versionCode
 * 不是同一套编号,强行比较会误报。这里把版本信息如实展示,由用户决定。
 */
object UpdateChecker {
    // main 分支构建产出的 Release 是 prerelease,latest 接口会 404,这里取列表第一条。
    private const val LATEST_RELEASE = "https://api.github.com/repos/mazige1997/tv/releases?per_page=1"
    private const val RELEASES_PAGE = "https://github.com/mazige1997/tv/releases"

    data class Release(
        val tag: String,
        val name: String,
        val publishedAt: String,
        val notes: String,
        val pageUrl: String
    )

    /** 回调在主线程;任何失败都回 null,由调用方决定提示方式。 */
    fun check(onResult: (Release?) -> Unit) {
        try {
            OkGo.get<String>(LATEST_RELEASE)
                .execute(object : StringCallback() {
                    override fun onSuccess(response: Response<String>) {
                        try {
                            val releases = JSONArray(response.body())
                            if (releases.length() == 0) {
                                onResult(null)
                                return
                            }
                            val json = releases.getJSONObject(0)
                            val page = json.optString("html_url", "")
                            onResult(
                                Release(
                                    tag = json.optString("tag_name", ""),
                                    name = json.optString("name", ""),
                                    publishedAt = json.optString("published_at", ""),
                                    notes = json.optString("body", ""),
                                    pageUrl = if (page.isEmpty()) RELEASES_PAGE else page
                                )
                            )
                        } catch (t: Throwable) {
                            LOG.i("ota parse failed: ${t.message}")
                            onResult(null)
                        }
                    }

                    override fun onError(response: Response<String>) {
                        LOG.i("ota request failed")
                        onResult(null)
                    }
                })
        } catch (t: Throwable) {
            LOG.i("ota request error: ${t.message}")
            onResult(null)
        }
    }
}
