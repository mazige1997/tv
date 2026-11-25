package com.github.tvbox.kt.core

import android.util.Log
import com.github.tvbox.kt.util.LOG

/**
 * Rust 原生核心桥(libtvbox_core.so)。
 *
 * 设计原则:
 * - 每个方法都是原子单元,无状态,失败返回 null,由调用方决定回退;
 * - [available] 为 false 时(JNI 未链接/加载失败),调用方走纯 Kotlin 回退实现,
 *   保证无 Rust 环境的构建依旧功能完整。
 */
object NativeCore {

    /**
     * 原生核心总开关:false = 启用 Rust 快路径(正常形态)。
     *
     * 诊断期曾临时置 true,让所有调用方经 `if (NativeCore.available)` 保护回退到
     * 与原版逐行一致的 Kotlin 实现,以隔离原生路径排查问题。功能已确认稳定,现恢复启用。
     * 若日后需再次隔离原生路径,把此值改回 true 即可,无需改动其它文件。
     */
    private const val FORCE_DISABLE_NATIVE = false

    // 注意:不可加 @JvmField —— 带 private set 的属性有自定义访问器,@JvmField 非法;
    // Kotlin 调用方照常属性访问,Java 调用方(如有)使用 getAvailable()
    var available = false
        private set

    fun load() {
        if (FORCE_DISABLE_NATIVE) {
            available = false
            Log.i("NativeCore", "DIAG: 原生核心已整体禁用,数据链路全部走 Kotlin 回退实现")
            LOG.i("DIAG native=DISABLED (diagnostic isolation)")
            return
        }
        if (available) return
        try {
            System.loadLibrary("tvbox_core")
            available = true
        } catch (th: Throwable) {
            Log.w("NativeCore", "libtvbox_core 加载失败,使用 Kotlin 回退实现: ${th.message}")
            available = false
        }
    }

    // ---------------- 哈希 ----------------
    external fun md5Hex(input: String): String

    // ---------------- 配置解码 ----------------
    /** 等价 ApiConfig.FindResult;失败返回 null。 */
    external fun decodeConfig(body: String, configKey: String?): String?

    /** 从图片包装中提取 jar 字节;无标记时返回空数组。 */
    external fun extractJar(body: String): ByteArray

    // ---------------- 直播解析 ----------------
    /** 完整数组形态 [{group, channels:[{name, urls, logo, epg, ...}]}]。 */
    external fun parseLive(text: String): String

    /** 折叠形态 {group:{channel:[urls]}}。 */
    external fun parseLiveSimple(text: String): String

    // ---------------- URL ----------------
    external fun joinUrl(parent: String, child: String): String

    // ---------------- M3U8 去广告 ----------------
    /**
     * 等价 M3u8.purify(tsUrlPre, content) 的原生实现(纯函数,无全局状态)。
     *
     * @param adsJson 广告正则 JSON 数组 `["regex", ...]`(取自 VideoParseRuler.getHostsRegex
     * 按 tsUrlPre 包含匹配后的列表;空串表示无规则)
     * @return `{"content":"...","removedAds":N}` JSON 字符串;不该代理时返回 null,
     * 调用方(Rust 不可用/解析失败/返回 null)一律回退纯 Kotlin 实现
     */
    external fun purifyM3u8(tsUrlPre: String, content: String, adsJson: String): String?

    /**
     * 等价 ProxyUtil.processM3u8Content 的原生实现(纯函数,无全局状态)。
     *
     * @param proxyPrefix Kotlin 侧拼好的 `"<address>proxy?go=live&type="`
     * @param headerQuery Kotlin 侧拼好的 `"&ua=...&referer=..."`,可为空串
     * @return 重写后的完整 m3u8 文本;native 不可用/失败时返回 null,调用方回退 Kotlin 实现
     */
    external fun rewriteM3u8Proxy(
        content: String,
        m3u8Url: String,
        proxyPrefix: String,
        headerQuery: String
    ): String?

    // ---------------- 弹幕 ----------------
    /** 解析弹幕 XML;startPosition>0 时按毫秒过滤。
     *  @return `[{"p":"...","t":"..."}, ...]`;失败返回 null(调用方回退纯 Kotlin)。 */
    external fun parseDanmuXml(xml: String, startPosition: Long): String?

    /** 弹幕评论 JSON(comments/data 数组)→ `<i><d p="...">text</d>...</i>`;失败返回 null。 */
    external fun commentJsonToXml(body: String): String?

    /** 弹幕搜索结果解析 → JSON 数组 `[{"name","url","builtIn"}, ...]`;失败返回 null。 */
    external fun parseDanmuSearchList(body: String): String?

    /** 内置弹幕搜索结果解析 → 同上 JSON 数组;失败返回 null。 */
    external fun parseBuiltinDanmuSearchList(body: String, baseUrl: String): String?

    /** 从搜索响应中提取资源 url;失败返回 null。 */
    external fun parseDanmuUrl(body: String): String?
}
