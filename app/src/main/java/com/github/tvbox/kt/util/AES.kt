package com.github.tvbox.kt.util

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES 工具,Kotlin 移植(行为与旧版 AES.java 一致)。
 *
 * 注意:这里刻意不走 Rust 快路径。AES 解密结果没有可校验的结构(Rust 实现一旦
 * 与原版 PKCS7/IV 处理有偏差会静默产出乱码),而它又是 FindResult 纯 Kotlin
 * 回退分支的最后一环;若快路径返回乱码,即使上层校验了 decodeConfig 的结果,
 * 仍会被绕过并导致整份配置解析失败。该调用只在加载配置时发生几次,无性能需求。
 */
object AES {

    fun rightPadding(key: String, replace: String, length: Int): String {
        val trimmed = key.trim()
        val curLength = trimmed.length
        return when {
            curLength > length -> trimmed.substring(0, length)
            curLength == length -> trimmed
            else -> {
                val sb = StringBuilder(trimmed)
                repeat(length - curLength) { sb.append(replace) }
                sb.toString()
            }
        }
    }

    /** AES/ECB/PKCS7 解密,data 为 hex 串;失败返回 null。 */
    fun ECB(data: String, key: String): String? {
        return try {
            val padded = rightPadding(key, "0", 16)
            val data2 = toBytes(data)
            val keySpec = SecretKeySpec(padded.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            String(cipher.doFinal(data2))
        } catch (th: Throwable) {
            th.printStackTrace()
            null
        }
    }

    /** AES/CBC/PKCS7 解密,data 为 hex 串;失败返回 null。 */
    fun CBC(data: String, key: String, iv: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val keySpec = SecretKeySpec(key.toByteArray(), "AES")
            val paramSpec = IvParameterSpec(iv.toByteArray())
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
            String(cipher.doFinal(toBytes(data)))
        } catch (th: Throwable) {
            th.printStackTrace()
            null
        }
    }

    fun isJson(content: String): Boolean {
        return try {
            org.json.JSONObject(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun toBytes(src: String): ByteArray {
        val l = src.length / 2
        val ret = ByteArray(l)
        for (i in 0 until l) {
            ret[i] = Integer.valueOf(src.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return ret
    }
}
