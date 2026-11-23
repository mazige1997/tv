package com.github.tvbox.kt.core.spider.js

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * RSA 分段加解密工具,Kotlin 移植(与旧版 crawler.js.rsa.RSAEncrypt + DataUtils 一致)。
 */
object RSAEncrypt {

    private const val TAG = "RSAEncrypt --> "

    /** 标准 jdk 加密填充方式 */
    const val ECB_PKCS1_PADDING = "RSA/ECB/PKCS1Padding"

    private const val KEY_ALGORITHM = "RSA"

    /** RSA 最大加密明文大小 */
    private const val MAX_ENCRYPT_BLOCK = 117

    /** RSA 最大解密密文大小 */
    private const val MAX_DECRYPT_BLOCK = 128

    fun getPublicKey(pubKey: String): PublicKey? {
        return try {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(base64Decode(pubKey)))
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    fun getPrivateKey(prvKey: String): PrivateKey? {
        return try {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(base64Decode(prvKey)))
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    // --------------------- 1 公钥加密,私钥解密 ---------------------

    @JvmStatic
    fun encryptByPublicKey(data: String?, pubKey: String?, mlong: Int, block: Boolean): String? {
        return encryptByPublicKey(data, pubKey, ECB_PKCS1_PADDING, mlong, block)
    }

    fun encryptByPublicKey(data: String?, pubKey: String?, config: String?, mlong: Int, block: Boolean): String? {
        return try {
            val bytes = data?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val cipher = Cipher.getInstance(config)
            val rSAPublicKey = getPublicKey(pubKey!!) as java.security.interfaces.RSAPublicKey
            cipher.init(Cipher.ENCRYPT_MODE, rSAPublicKey)
            if (mlong == 1) {
                return base64Encode(cipher.doFinal(bytes))
            }
            var bitLength = MAX_ENCRYPT_BLOCK
            if (block) {
                bitLength = rSAPublicKey.modulus.bitLength() / 8 - 11
            }
            val inputLen = bytes.size
            val out = ByteArrayOutputStream()
            var offSet = 0
            var i = 0
            while (inputLen - offSet > 0) {
                val cache = if (inputLen - offSet > bitLength) {
                    cipher.doFinal(bytes, offSet, bitLength)
                } else {
                    cipher.doFinal(bytes, offSet, inputLen - offSet)
                }
                out.write(cache, 0, cache.size)
                i++
                offSet = i * bitLength
            }
            out.close()
            base64Encode(out.toByteArray())
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    @JvmStatic
    fun decryptByPrivateKey(encryptBase64Data: String?, prvKey: String?, mlong: Int, block: Boolean): String? {
        return decryptByPrivateKey(encryptBase64Data, prvKey, ECB_PKCS1_PADDING, mlong, block)
    }

    fun decryptByPrivateKey(encryptBase64Data: String?, prvKey: String?, config: String?, mlong: Int, block: Boolean): String? {
        return try {
            val encryptedData = base64Decode(encryptBase64Data!!)
            val cipher = Cipher.getInstance(config)
            val rSAPrivateKey = getPrivateKey(prvKey!!) as java.security.interfaces.RSAPrivateKey
            cipher.init(Cipher.DECRYPT_MODE, rSAPrivateKey)
            if (mlong == 1) {
                return String(cipher.doFinal(encryptedData))
            }
            var bitLength = MAX_DECRYPT_BLOCK
            if (block) {
                bitLength = rSAPrivateKey.modulus.bitLength() / 8
            }
            val inputLen = encryptedData.size
            val out = ByteArrayOutputStream()
            var offSet = 0
            var i = 0
            while (inputLen - offSet > 0) {
                val cache = if (inputLen - offSet > bitLength) {
                    cipher.doFinal(encryptedData, offSet, bitLength)
                } else {
                    cipher.doFinal(encryptedData, offSet, inputLen - offSet)
                }
                out.write(cache, 0, cache.size)
                i++
                offSet = i * bitLength
            }
            out.close()
            out.toString("UTF-8")
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    // --------------------- 2 私钥加密,公钥解密 ---------------------

    @JvmStatic
    fun encryptByPrivateKey(data: String?, prvKey: String?, mlong: Int, block: Boolean): String? {
        return encryptByPrivateKey(data, prvKey, ECB_PKCS1_PADDING, mlong, block)
    }

    fun encryptByPrivateKey(data: String?, prvKey: String?, config: String?, mlong: Int, block: Boolean): String? {
        return try {
            val bytes = data?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val cipher = Cipher.getInstance(config)
            val rSAPrivateKey = getPrivateKey(prvKey!!) as java.security.interfaces.RSAPrivateKey
            cipher.init(Cipher.ENCRYPT_MODE, rSAPrivateKey)
            if (mlong == 1) {
                return base64Encode(cipher.doFinal(bytes))
            }
            var bitLength = MAX_ENCRYPT_BLOCK
            if (block) {
                bitLength = rSAPrivateKey.modulus.bitLength() / 8 - 11
            }
            val inputLen = bytes.size
            val out = ByteArrayOutputStream()
            var offSet = 0
            var i = 0
            while (inputLen - offSet > 0) {
                val cache = if (inputLen - offSet > bitLength) {
                    cipher.doFinal(bytes, offSet, bitLength)
                } else {
                    cipher.doFinal(bytes, offSet, inputLen - offSet)
                }
                out.write(cache, 0, cache.size)
                i++
                offSet = i * bitLength
            }
            out.close()
            base64Encode(out.toByteArray())
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    @JvmStatic
    fun decryptByPublicKey(encryptBase64Data: String?, pubKey: String?, mlong: Int, block: Boolean): String? {
        return decryptByPublicKey(encryptBase64Data, pubKey, ECB_PKCS1_PADDING, mlong, block)
    }

    fun decryptByPublicKey(encryptBase64Data: String?, pubKey: String?, config: String?, mlong: Int, block: Boolean): String? {
        return try {
            val encryptedData = base64Decode(encryptBase64Data!!)
            val cipher = Cipher.getInstance(config)
            val rSAPublicKey = getPublicKey(pubKey!!) as java.security.interfaces.RSAPublicKey
            cipher.init(Cipher.DECRYPT_MODE, rSAPublicKey)
            if (mlong == 1) {
                return String(cipher.doFinal(encryptedData))
            }
            var bitLength = MAX_DECRYPT_BLOCK
            if (block) {
                bitLength = rSAPublicKey.modulus.bitLength() / 8
            }
            val inputLen = encryptedData.size
            val out = ByteArrayOutputStream()
            var offSet = 0
            var i = 0
            while (inputLen - offSet > 0) {
                val cache = if (inputLen - offSet > bitLength) {
                    cipher.doFinal(encryptedData, offSet, bitLength)
                } else {
                    cipher.doFinal(encryptedData, offSet, inputLen - offSet)
                }
                out.write(cache, 0, cache.size)
                i++
                offSet = i * bitLength
            }
            out.close()
            out.toString("UTF-8")
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    private fun base64Decode(data: String): ByteArray {
        return Base64.decode(data.toByteArray(), Base64.NO_WRAP)
    }

    private fun base64Encode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun handleException(e: Exception) {
        e.printStackTrace()
        Log.e(TAG, TAG + e)
    }
}
