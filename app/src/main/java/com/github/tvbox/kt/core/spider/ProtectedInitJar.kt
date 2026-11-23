package com.github.tvbox.kt.core.spider

import android.content.Context
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.github.tvbox.kt.KtApp
import dalvik.system.DexClassLoader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加固 jar 检测与初始化,Kotlin 移植(与旧版 ProtectedInitJar 一致:
 * 扫描 DEX 结构判断 Init 类是否包含 killProcess 与白名单逻辑)。
 */
class ProtectedInitJar {

    private val jars = ConcurrentHashMap<String, Boolean>()

    fun clear() {
        jars.clear()
    }

    fun check(jar: String): Boolean {
        val cached = jars[jar]
        if (cached != null) return cached
        val result = scan(jar, KtApp.instance().packageName)
        jars[jar] = result
        return result
    }

    fun init(clz: Class<*>): Boolean {
        var init: Any? = null
        try {
            val get = clz.getMethod("get")
            init = get.invoke(null)
        } catch (ignored: Throwable) {
        }
        bindContext(clz, init)
        if (!bindDexLoader(clz, init)) return false
        // 部分 JAR 的首页依赖 saveConfig() 初始化的本地配置
        invokeNoArg(clz, "saveConfig")
        invokeNoArg(clz, "replaceCloudDiskNames")
        invokeStartGoProxy(clz)
        return true
    }

    private fun bindContext(clz: Class<*>, init: Any?) {
        if (init == null) return
        try {
            val context = clz.getDeclaredField("c")
            context.isAccessible = true
            context.set(init, KtApp.instance())
            return
        } catch (ignored: Throwable) {
        }
        for (field in clz.declaredFields) {
            try {
                if (Modifier.isStatic(field.modifiers) || !Context::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                field.set(init, KtApp.instance())
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun bindDexLoader(clz: Class<*>, init: Any?): Boolean {
        if (init == null) return false
        return try {
            val nativeClass = clz.classLoader.loadClass("com.github.catvod.spider.DexNative")
            val getLoader = nativeClass.getMethod("getLoader", Any::class.java)
            val loader = getLoader.invoke(null, KtApp.instance())
            if (loader !is DexClassLoader) return false
            var bound = false
            var type: Class<*>? = clz
            while (type != null) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || !DexClassLoader::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    field.set(init, loader)
                    bound = true
                }
                type = type.superclass
            }
            bound
        } catch (ignored: ClassNotFoundException) {
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun invokeNoArg(clz: Class<*>, methodName: String) {
        try {
            val method = clz.getDeclaredMethod(methodName)
            method.isAccessible = true
            method.invoke(null)
        } catch (ignored: NoSuchMethodException) {
        } catch (th: Throwable) {
            Log.e("JarLoader", "echo-init $methodName failed", th)
        }
    }

    private fun invokeStartGoProxy(clz: Class<*>) {
        try {
            val method = clz.getMethod("startGoProxy", Context::class.java)
            method.invoke(null, KtApp.instance())
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte())
        private const val BUFFER_SIZE = 8192

        private fun scan(jar: String, packageName: String): Boolean {
            try {
                val file = File(jar)
                if (!file.exists()) return false
                if (isDexFile(file)) {
                    var is0: InputStream? = null
                    try {
                        is0 = FileInputStream(file)
                        return checkDex(readBytes(is0), packageName)
                    } finally {
                        close(is0)
                    }
                }
                var zip: ZipFile? = null
                try {
                    zip = ZipFile(file)
                    val entries: java.util.Enumeration<out ZipEntry> = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.name.endsWith(".dex")) continue
                        var is0: InputStream? = null
                        try {
                            is0 = zip.getInputStream(entry)
                            if (checkDex(readBytes(is0), packageName)) return true
                        } finally {
                            close(is0)
                        }
                    }
                } finally {
                    close(zip)
                }
            } catch (ignored: Throwable) {
            }
            return false
        }

        private fun checkDex(data: ByteArray, packageName: String): Boolean {
            if (data.size < 112 || !startsWith(data, DEX_MAGIC)) return false
            return try {
                Dex(data, packageName).isProtected()
            } catch (ignored: Throwable) {
                false
            }
        }

        private fun isDexFile(file: File): Boolean {
            var is0: InputStream? = null
            try {
                is0 = FileInputStream(file)
                val magic = ByteArray(DEX_MAGIC.size)
                val len = is0.read(magic)
                return len == DEX_MAGIC.size && startsWith(magic, DEX_MAGIC)
            } catch (ignored: Throwable) {
                return false
            } finally {
                close(is0)
            }
        }

        @Throws(Exception::class)
        private fun readBytes(is0: InputStream?): ByteArray {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val len = is0!!.read(buffer)
                if (len == -1) break
                baos.write(buffer, 0, len)
            }
            close(is0)
            return baos.toByteArray()
        }

        private fun startsWith(data: ByteArray, pattern: ByteArray): Boolean {
            if (data.size < pattern.size) return false
            for (i in pattern.indices) {
                if (data[i] != pattern[i]) return false
            }
            return true
        }

        private fun close(closeable: Closeable?) {
            try {
                closeable?.close()
            } catch (ignored: Throwable) {
            }
        }

        /** DEX 结构扫描器 */
        private class Dex(data: ByteArray, packageName: String) {
            private val data = data
            private val stringIdsSize = uint(0x38)
            private val stringIdsOff = uint(0x3c)
            private val typeIdsSize = uint(0x40)
            private val typeIdsOff = uint(0x44)
            private val methodIdsSize = uint(0x58)
            private val methodIdsOff = uint(0x5c)
            private val classDefsSize = uint(0x60)
            private val classDefsOff = uint(0x64)
            private val whitelist = Whitelist(packageName)

            fun isProtected(): Boolean {
                var hasInit = false
                var hasKillProcess = false
                var checkedWhitelist = false
                for (i in 0 until classDefsSize) {
                    val off = classDefsOff + i * 32
                    val desc = typeDesc(uint(off))
                    if (!desc.startsWith("Lcom/github/catvod/spider/Init")) continue
                    hasInit = hasInit || "Lcom/github/catvod/spider/Init;" == desc
                    val classDataOff = uint(off + 24)
                    if (classDataOff <= 0) continue
                    if ("Lcom/github/catvod/spider/Init;" == desc) {
                        checkedWhitelist = true
                        if (scanInitWhitelist(classDataOff)) return false
                    }
                    hasKillProcess = hasKillProcess || scanKillProcess(classDataOff)
                    if (hasInit && hasKillProcess && checkedWhitelist) return true
                }
                return hasInit && hasKillProcess
            }

            private fun scanInitWhitelist(off: Int): Boolean {
                val cursor = intArrayOf(off)
                val staticFields = uleb(cursor)
                val instanceFields = uleb(cursor)
                val directMethods = uleb(cursor)
                val virtualMethods = uleb(cursor)
                skipFields(cursor, staticFields + instanceFields)
                return scanInitMethods(cursor, directMethods) || scanInitMethods(cursor, virtualMethods)
            }

            private fun scanInitMethods(cursor: IntArray, count: Int): Boolean {
                var methodIdx = 0
                for (i in 0 until count) {
                    methodIdx += uleb(cursor)
                    uleb(cursor)
                    val codeOff = uleb(cursor)
                    if (codeOff <= 0 || methodIdx >= methodIdsSize) continue
                    if ("init" == methodName(methodIdx) && scanWhitelist(codeOff)) return true
                }
                return false
            }

            private fun scanKillProcess(off: Int): Boolean {
                val cursor = intArrayOf(off)
                val staticFields = uleb(cursor)
                val instanceFields = uleb(cursor)
                val directMethods = uleb(cursor)
                val virtualMethods = uleb(cursor)
                skipFields(cursor, staticFields + instanceFields)
                return scanKillMethods(cursor, directMethods) || scanKillMethods(cursor, virtualMethods)
            }

            private fun scanKillMethods(cursor: IntArray, count: Int): Boolean {
                var methodIdx = 0
                for (i in 0 until count) {
                    methodIdx += uleb(cursor)
                    uleb(cursor)
                    val codeOff = uleb(cursor)
                    if (codeOff > 0 && scanKillProcessCode(codeOff)) return true
                }
                return false
            }

            private fun scanWhitelist(codeOff: Int): Boolean {
                val insnsSize = uint(codeOff + 12)
                val insnsOff = codeOff + 16
                val end = insnsOff + insnsSize * 2
                var off = insnsOff
                while (off + 1 < end && off + 1 < data.size) {
                    val op = data[off].toInt() and 0xff
                    if (op == 0x1a && off + 3 < end) {
                        if (whitelist.contains(string(ushort(off + 2)))) return true
                    } else if (op == 0x1b && off + 5 < end) {
                        if (whitelist.contains(string(uint(off + 2)))) return true
                    }
                    off += 2
                }
                return false
            }

            private fun scanKillProcessCode(codeOff: Int): Boolean {
                val insnsSize = uint(codeOff + 12)
                val insnsOff = codeOff + 16
                val end = insnsOff + insnsSize * 2
                var off = insnsOff
                while (off + 3 < end && off + 3 < data.size) {
                    val op = data[off].toInt() and 0xff
                    if (op >= 0x6e && op <= 0x72 && isKillProcess(ushort(off + 2))) return true
                    off += 2
                }
                return false
            }

            private fun skipFields(cursor: IntArray, count: Int) {
                for (i in 0 until count) {
                    uleb(cursor)
                    uleb(cursor)
                }
            }

            private fun isKillProcess(methodIdx: Int): Boolean {
                if (methodIdx < 0 || methodIdx >= methodIdsSize) return false
                return "Landroid/os/Process;" == methodClass(methodIdx) && "killProcess" == methodName(methodIdx)
            }

            private fun methodClass(methodIdx: Int): String {
                return typeDesc(ushort(methodIdsOff + methodIdx * 8))
            }

            private fun methodName(methodIdx: Int): String {
                return string(uint(methodIdsOff + methodIdx * 8 + 4))
            }

            private fun typeDesc(typeIdx: Int): String {
                if (typeIdx < 0 || typeIdx >= typeIdsSize) return ""
                return string(uint(typeIdsOff + typeIdx * 4))
            }

            private fun string(stringIdx: Int): String {
                if (stringIdx < 0 || stringIdx >= stringIdsSize) return ""
                val cursor = intArrayOf(uint(stringIdsOff + stringIdx * 4))
                uleb(cursor)
                val start = cursor[0]
                var end = start
                while (end < data.size && data[end].toInt() != 0) end++
                return try {
                    String(data, start, end - start, Charsets.UTF_8)
                } catch (ignored: Throwable) {
                    ""
                }
            }

            private fun uleb(cursor: IntArray): Int {
                var result = 0
                var shift = 0
                while (cursor[0] < data.size) {
                    val b = data[cursor[0]++].toInt() and 0xff
                    result = result or ((b and 0x7f) shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                return result
            }

            private fun ushort(off: Int): Int {
                if (off < 0 || off + 1 >= data.size) return 0
                return (data[off].toInt() and 0xff) or ((data[off + 1].toInt() and 0xff) shl 8)
            }

            private fun uint(off: Int): Int {
                if (off < 0 || off + 3 >= data.size) return 0
                return (data[off].toInt() and 0xff) or
                    ((data[off + 1].toInt() and 0xff) shl 8) or
                    ((data[off + 2].toInt() and 0xff) shl 16) or
                    ((data[off + 3].toInt() and 0xff) shl 24)
            }
        }

        /** 白名单(支持 AES 加密包名) */
        private class Whitelist(private val packageName: String) {
            fun contains(value: String?): Boolean {
                if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(value)) return false
                if (packageName == value || containsItem(value)) return true
                return containsItem(decrypt(value))
            }

            private fun containsItem(text: String?): Boolean {
                if (TextUtils.isEmpty(text)) return false
                var start = 0
                while (start <= text!!.length) {
                    var end = text.indexOf(',', start)
                    if (end < 0) end = text.length
                    if (packageName == text.substring(start, end).trim()) return true
                    start = end + 1
                }
                return false
            }

            private fun decrypt(value: String?): String {
                if (!isCipherText(value)) return ""
                return try {
                    val key = AES_KEY.toByteArray(Charsets.UTF_8)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
                    String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), Charsets.UTF_8)
                } catch (ignored: Throwable) {
                    ""
                }
            }

            private fun isCipherText(value: String?): Boolean {
                val len = value?.length ?: return false
                if (len < 24 || len % 4 != 0) return false
                var padding = 0
                for (i in 0 until len) {
                    val c = value[i]
                    if (c == '=') {
                        padding++
                        if (i < len - 2) return false
                    } else if (!isBase64(c)) {
                        return false
                    }
                }
                val rawLen = len * 3 / 4 - padding
                return rawLen > 0 && rawLen % 16 == 0
            }

            private fun isBase64(c: Char): Boolean {
                return (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/'
            }

            companion object {
                private const val AES_KEY = "1234123412341234"
            }
        }
    }
}
