package com.github.tvbox.kt.util

import com.github.tvbox.kt.core.NativeCore
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * 直播源解析入口:Rust 主路径 + Kotlin 回退。
 * - 完整数组形态:NativeCore.parseLive(text) / TxtSubscribe.parseToJsonArray(text)
 * - 折叠形态:NativeCore.parseLiveSimple(text) / TxtSubscribe.parse(map, text)
 */
object LiveParseHelper {

    /** 完整数组形态(Rust 优先,失败回退 Kotlin)。 */
    fun parseToJsonArray(text: String?): JsonArray {
        return try {
            if (NativeCore.available) {
                val json = NativeCore.parseLive(text ?: "")
                if (!json.isNullOrEmpty()) {
                    return JsonParser.parseString(json).asJsonArray
                }
            }
            TxtSubscribe.parseToJsonArray(text)
        } catch (th: Throwable) {
            LOG.i("echo-parseLive rust-fallback: " + th.message)
            TxtSubscribe.parseToJsonArray(text)
        }
    }

    /** 折叠形态 {group:{channel:[urls]}}(Rust 优先,失败回退 Kotlin)。 */
    fun parseSimple(text: String?): LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>>()
        return try {
            if (NativeCore.available) {
                val json = NativeCore.parseLiveSimple(text ?: "")
                if (!json.isNullOrEmpty()) {
                    val groups = JsonParser.parseString(json).asJsonObject
                    for ((groupName, channels) in groups.entrySet()) {
                        val channelMap = LinkedHashMap<String, ArrayList<String>>()
                        if (channels.isJsonObject) {
                            for ((channelName, urls) in channels.asJsonObject.entrySet()) {
                                val urlList = ArrayList<String>()
                                if (urls.isJsonArray) {
                                    for (u in urls.asJsonArray) {
                                        urlList.add(u.asString)
                                    }
                                }
                                channelMap[channelName] = urlList
                            }
                        }
                        out[groupName] = channelMap
                    }
                    return out
                }
            }
            TxtSubscribe.parse(out, text ?: "")
            out
        } catch (th: Throwable) {
            LOG.i("echo-parseLiveSimple rust-fallback: " + th.message)
            TxtSubscribe.parse(out, text ?: "")
            out
        }
    }
}
