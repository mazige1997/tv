package com.github.tvbox.kt.util

import com.github.tvbox.kt.core.NativeCore
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES 工具,Kotlin 移植(行为与旧版 AES.java 一致)。
 * 配置解码优先走 Rust(NativeCore.aesDecryptHex),失败回退 javax.crypto。
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
            if (NativeCore.available) {
                NativeCore.aesDecryptHex(data, padded, null, false)?.let { return it }
            }
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
            if (NativeCore.available) {
                NativeCore.aesDecryptHex(data, key, iv, true)?.let { return it }
            }
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
