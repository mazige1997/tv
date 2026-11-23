package com.github.tvbox.kt.util

import android.text.TextUtils
import android.util.Log
import com.github.tvbox.kt.core.NativeCore
import java.io.File
import java.security.MessageDigest

/**
 * MD5 工具,Kotlin 移植(行为与旧版 MD5.java 一致)。
 * NativeCore 可用时优先走 Rust 实现,保持结果一致且更快。
 */
object MD5 {
    private const val TAG = "MD5"

    private fun newDigest(): MessageDigest? = try {
        MessageDigest.getInstance("MD5")
    } catch (e: Exception) {
        Log.e(TAG, "echo-获取MD5信息摘要失败", e)
        null
    }

    /** 等价 MD5.encode。 */
    fun encode(res: String): String {
        if (NativeCore.available) {
            try {
                return NativeCore.md5Hex(res)
            } catch (_: Throwable) {
            }
        }
        return encode(res!!.toByteArray()) ?: ""
    }

    private fun encode(bytes: ByteArray): String? {
        val digest = newDigest() ?: return null
        val md = digest.digest(bytes)
        return md.joinToString("") { "%02x".format(it) }
    }

    fun getFileMd5(f: File): String {
        val sb = StringBuilder("")
        try {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { fis ->
                val buffer = ByteArray(4096)
                while (true) {
                    val len = fis.read(buffer)
                    if (len == -1) break
                    md.update(buffer, 0, len)
                }
            }
            val b = md.digest()
            for (i in b.indices) {
                var d = b[i].toInt()
                if (d < 0) d = b[i].toInt() and 0xff
                if (d < 16) sb.append("0")
                sb.append(Integer.toHexString(d))
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
        return sb.toString()
    }

    /** 等价 MD5.string2MD5。 */
    @JvmStatic
    fun string2MD5(inStr: String): String? {
        val digest = newDigest() ?: return null
        if (TextUtils.isEmpty(inStr)) {
            Log.e(TAG, "echo-参数strSource不能为空")
            return null
        }
        val charArray = inStr.toCharArray()
        val byteArray = ByteArray(charArray.size)
        for (i in charArray.indices) byteArray[i] = charArray[i].code.toByte()
        val md5Bytes = digest.digest(byteArray)
        val hexValue = StringBuilder()
        for (md5Byte in md5Bytes) {
            val val0 = md5Byte.toInt() and 0xff
            if (val0 < 16) hexValue.append("0")
            hexValue.append(Integer.toHexString(val0))
        }
        return hexValue.toString()
    }

    /** 先 MD5 再 Base64(截断换行),等价 MD5.encrypt。 */
    fun encrypt(strSource: String): String? {
        val digest = newDigest() ?: return null
        if (TextUtils.isEmpty(strSource)) {
            Log.e(TAG, "echo-参数strSource不能为空")
            return null
        }
        return try {
            val md5Bytes = digest.digest(strSource.toByteArray(Charsets.UTF_8))
            val encryptBytes = android.util.Base64.encode(md5Bytes, android.util.Base64.DEFAULT)
            val strEncrypt = String(encryptBytes, Charsets.UTF_8)
            strEncrypt.substring(0, strEncrypt.length - 1)
        } catch (e: Exception) {
            Log.e(TAG, "echo-加密模块暂不支持此字符集合$e")
            null
        }
    }

    fun encrypt4login(strSource: String, appSecert: String): String? {
        val str = (encrypt(strSource) ?: "") + appSecert
        return string2MD5(str)
    }
}
