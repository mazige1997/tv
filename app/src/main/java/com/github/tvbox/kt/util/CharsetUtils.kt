package com.github.tvbox.kt.util

import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * 字符集检测工具,Kotlin 移植(与旧版 CharsetUtils 一致)。
 */
object CharsetUtils {
    /** 中文常用字符集 */
    val AVAILABLE_CHINESE_CHARSET_NAMES = arrayOf("GBK", "gb2312", "GB18030", "UTF-8", "Big5")

    /** 中文常用字 */
    private val CHINESE_COMMON_CHARACTER_PATTERN: Pattern = Pattern.compile(
        "的|一|是|了|我|不|人|在|他|有|这|个|上|们|来|到|时|大|地|为|子|中|你|说|生|国|年|着|就|那|和|要"
    )

    fun detect(content: ByteArray): Charset {
        var charset = universalDetect(content)
        if (charset != null && charset.isNotEmpty()) {
            return Charset.forName(charset)
        }
        var longestMatch = 0
        for (cs in AVAILABLE_CHINESE_CHARSET_NAMES) {
            val temp = String(content, Charset.forName(cs))
            val matcher = CHINESE_COMMON_CHARACTER_PATTERN.matcher(temp)
            var count = 0
            while (matcher.find()) {
                count += 1
            }
            if (count > longestMatch) {
                longestMatch = count
                charset = cs
            }
        }
        return if (charset == null) Charset.forName("GB18030") else Charset.forName(charset)
    }

    /** 使用 mozilla universalchardet 检测 */
    fun universalDetect(content: ByteArray): String? {
        val detector = UniversalDetector(null)
        detector.handleData(content, 0, content.size)
        detector.dataEnd()
        return detector.detectedCharset
    }
}

/**
 * BOM 感知 Reader,Kotlin 移植(与旧版 UnicodeReader 一致)。
 */
class UnicodeReader : Reader {
    private var internalIn: InputStreamReader? = null
    var encoding: String? = null
        private set

    @get:Throws(IOException::class)
    private val in2: InputStreamReader
        get() = internalIn!!

    constructor(file: String?) : this(FileInputStream(file))

    constructor(file: File?) : this(FileInputStream(file))

    constructor(file: File?, defaultEncoding: String?) : this(FileInputStream(file), defaultEncoding)

    constructor(`in`: InputStream?) : this(`in`, null)

    constructor(`in`: InputStream?, defaultEncoding: String?) {
        init(`in`, defaultEncoding)
    }

    @Throws(IOException::class)
    override fun close() {
        internalIn!!.close()
    }

    @Throws(IOException::class)
    protected fun init(`in`: InputStream?, defaultEncoding: String?) {
        val tempIn = PushbackInputStream(`in`, 4)
        val bom = ByteArray(4)
        val n = tempIn.read(bom, 0, bom.size)
        var unread: Int
        if ((bom[0].toInt() == 0) && (bom[1].toInt() == 0) && (bom[2].toInt() == -2) && (bom[3].toInt() == -1)) {
            encoding = "UTF-32BE"
            unread = n - 4
        } else {
            unread = if (n == 4) {
                if ((bom[0].toInt() == -1) && (bom[1].toInt() == -2) && (bom[2].toInt() == 0) && (bom[3].toInt() == 0)) {
                    encoding = "UTF-32LE"
                    n - 4
                } else {
                    n
                }
            } else {
                n
            }
            if ((bom[0].toInt() == -17) && (bom[1].toInt() == -69) && (bom[2].toInt() == -65)) {
                encoding = "UTF-8"
                unread = n - 3
            } else if ((bom[0].toInt() == -2) && (bom[1].toInt() == -1)) {
                encoding = "UTF-16BE"
                unread = n - 2
            } else if ((bom[0].toInt() == -1) && (bom[1].toInt() == -2)) {
                encoding = "UTF-16LE"
                unread = n - 2
            } else {
                encoding = defaultEncoding
                unread = n
            }
        }
        if (unread > 0) tempIn.unread(bom, n - unread, unread)
        else if (unread < -1) tempIn.unread(bom, 0, 0)

        if (encoding == null) {
            internalIn = InputStreamReader(tempIn)
            encoding = internalIn!!.encoding
        } else {
            internalIn = InputStreamReader(tempIn, encoding)
        }
    }

    @Throws(IOException::class)
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        return internalIn!!.read(cbuf, off, len)
    }
}
