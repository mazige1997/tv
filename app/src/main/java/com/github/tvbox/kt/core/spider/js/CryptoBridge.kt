package com.github.tvbox.kt.core.spider.js

import android.util.Base64
import java.security.Key
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JS 爬虫加密桥(aesX/rsaX),Kotlin 移植(与旧版 crawler.js.Crypto 一致)。
 */
object Crypto {

    fun aes(
        mode: String,
        encrypt: Boolean,
        input: String,
        inBase64: Boolean,
        key: String,
        iv: String?,
        outBase64: Boolean
    ): String {
        return try {
            var keyBuf = key.toByteArray()
            if (keyBuf.size < 16) keyBuf = keyBuf.copyOf(16)
            var ivBuf = if (iv == null) ByteArray(0) else iv.toByteArray()
            if (ivBuf.size < 16) ivBuf = ivBuf.copyOf(16)
            val cipher = Cipher.getInstance(mode + "Padding")
            val keySpec = SecretKeySpec(keyBuf, "AES")
            if (iv == null) cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
            else cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                keySpec,
                IvParameterSpec(ivBuf)
            )
            val inBuf = if (inBase64) Base64.decode(input.replace("_", "/").replace("-", "+"), Base64.DEFAULT)
            else input.toByteArray(Charsets.UTF_8)
            if (outBase64) Base64.encodeToString(cipher.doFinal(inBuf), Base64.NO_WRAP)
            else String(cipher.doFinal(inBuf), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun rsa(pub: Boolean, encrypt: Boolean, input: String, inBase64: Boolean, key: String, outBase64: Boolean): String {
        return try {
            val rsaKey = generateKey(pub, key)
            val len = getModulusLength(rsaKey)
            var outBytes = ByteArray(0)
            val inBytes = if (inBase64) Base64.decode(input.replace("_", "/").replace("-", "+"), Base64.DEFAULT)
            else input.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, rsaKey)
            val blockLen = if (encrypt) len / 8 - 11 else len / 8
            var bufIdx = 0
            while (bufIdx < inBytes.size) {
                val bufEndIdx = minOf(bufIdx + blockLen, inBytes.size)
                val tmpInBytes = ByteArray(bufEndIdx - bufIdx)
                System.arraycopy(inBytes, bufIdx, tmpInBytes, 0, tmpInBytes.size)
                val tmpBytes = cipher.doFinal(tmpInBytes)
                bufIdx = bufEndIdx
                outBytes = concatArrays(outBytes, tmpBytes)
            }
            if (outBase64) Base64.encodeToString(outBytes, Base64.NO_WRAP)
            else String(outBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @Throws(Exception::class)
    private fun generateKey(pub: Boolean, key0: String): Key {
        val key = if (pub) key0
            .replace("\r\n", "")
            .replace("\n", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
        else key0
            .replace("\r\n", "")
            .replace("\n", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
        return if (pub) {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
        } else {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
        }
    }

    private fun getModulusLength(key: Key): Int {
        return if (key is PublicKey) (key as RSAPublicKey).modulus.bitLength()
        else (key as RSAPrivateKey).modulus.bitLength()
    }

    private fun concatArrays(a: ByteArray, b: ByteArray): ByteArray {
        val aLen = a.size
        val bLen = b.size
        val result = ByteArray(aLen + bLen)
        System.arraycopy(a, 0, result, 0, aLen)
        System.arraycopy(b, 0, result, aLen, bLen)
        return result
    }
}
