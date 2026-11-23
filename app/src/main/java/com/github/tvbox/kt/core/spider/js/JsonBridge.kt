package com.github.tvbox.kt.core.spider.js

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONObject

/**
 * JS 爬虫 JSON 工具桥,Kotlin 移植(与旧版 crawler.js.Json 一致)。
 */
object Json {

    fun valid(text: String?): Boolean {
        return try {
            JSONObject(text ?: "null")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun invalid(text: String?): Boolean {
        return !valid(text)
    }

    fun safeString(obj: JsonObject, key: String): String {
        return try {
            obj.getAsJsonPrimitive(key).asString.trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun safeListString(obj: JsonObject, key: String): List<String> {
        val result = ArrayList<String>()
        if (!obj.has(key)) return result
        if (obj.get(key).isJsonObject) result.add(safeString(obj, key))
        else for (opt in obj.getAsJsonArray(key)) result.add(opt.asString)
        return result
    }

    fun safeListElement(obj: JsonObject, key: String): List<JsonElement> {
        val result = ArrayList<JsonElement>()
        if (!obj.has(key)) return result
        if (obj.get(key).isJsonObject) result.add(obj.get(key).asJsonObject)
        else for (opt in obj.getAsJsonArray(key)) result.add(opt.asJsonObject)
        return result
    }

    fun safeObject(element: JsonElement?): JsonObject {
        return try {
            var el = element
            if (el != null && el.isJsonPrimitive) el = JsonParser.parseString(el.asJsonPrimitive.asString)
            el!!.asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }
    }

    fun toMap(element: JsonElement?): Map<String, String> {
        val map = HashMap<String, String>()
        val `object` = safeObject(element)
        for (key in `object`.keySet()) map[key] = safeString(`object`, key)
        return map
    }
}
