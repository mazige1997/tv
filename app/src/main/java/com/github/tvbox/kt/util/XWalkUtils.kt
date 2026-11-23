package com.github.tvbox.kt.util

import android.app.Activity
import android.content.Context
import android.widget.Toast
import org.xwalk.core.XWalkInitializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** XWalk 内核管理,Kotlin 移植(与旧版 XWalkUtils 一致)。 */
object XWalkUtils {

    private var xWalkInitializer: XWalkInitializer? = null

    interface XWalkState {
        fun success()
        fun fail()
        fun ignore()
    }

    fun downUrl(): String {
        return String.format("http://home.jundie.top:81/xwalk/maven2/crosswalk-apks-23.53.589.4-%s.zip", getRuntimeAbi())
    }

    fun saveZipFile(): String {
        return String.format("crosswalk-apks-23.53.589.4-%s.zip", getRuntimeAbi())
    }

    fun xWalkLibExist(context: Context): Boolean {
        val libFiles = arrayOf(
            "classes.dex", "icudtl.dat", "libxwalkcore.so", "xwalk.pak", "xwalk_100_percent.pak"
        )
        val dir = libExtractPath(context)
        for (lib in libFiles) {
            if (!File("$dir/$lib").exists()) return false
        }
        return true
    }

    fun tryUseXWalk(context: Context, state: XWalkState?) {
        if (!xWalkLibExist(context)) {
            state?.ignore()
            return
        }
        if (xWalkInitializer == null) {
            xWalkInitializer = XWalkInitializer(object : XWalkInitializer.XWalkInitListener {
                override fun onXWalkInitStarted() {}

                override fun onXWalkInitCancelled() {}

                override fun onXWalkInitFailed() {
                    state?.fail()
                }

                override fun onXWalkInitCompleted() {
                    state?.success()
                }
            }, context)
        }
        if (xWalkInitializer!!.isXWalkReady) {
            state?.success()
        } else {
            xWalkInitializer!!.initAsync()
        }
    }

    fun apkHadDown(context: Context): Boolean {
        return File(apkPath(context)).exists()
    }

    @Throws(Throwable::class)
    fun unzipXWalkZip(context: Context, archive: String) {
        var bi: BufferedInputStream? = null
        val zf = ZipFile(archive)
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val ze2 = entries.nextElement() as java.util.zip.ZipEntry
            val entryName = ze2.name
            if (ze2.isDirectory) {
                continue
            } else {
                val fileName = entryName.substring(entryName.lastIndexOf("/") + 1)
                if (fileName == "XWalkRuntimeLib.apk") {
                    val tempFile = apkPath(context) + ".tmp"
                    val finalApk = apkPath(context)
                    var f = File(tempFile)
                    if (f.exists()) f.delete()
                    f = File(finalApk)
                    if (f.exists()) f.delete()
                    val bos = BufferedOutputStream(FileOutputStream(tempFile))
                    bi = BufferedInputStream(zf.getInputStream(ze2))
                    val readContent = ByteArray(1024)
                    var readCount = bi.read(readContent)
                    while (readCount != -1) {
                        bos.write(readContent, 0, readCount)
                        readCount = bi.read(readContent)
                    }
                    bos.flush()
                    bos.close()
                    if (File(tempFile).renameTo(File(finalApk))) {
                        LOG.i(finalApk)
                    }
                }
            }
        }
        zf.close()
        val zipFile = File(archive)
        if (zipFile.exists() && zipFile.name.endsWith(".zip")) {
            zipFile.delete()
        }
    }

    fun getRuntimeAbi(): String {
        var result = "arm"
        try {
            val cls = Class.forName("org.xwalk.core.XWalkEnvironment")
            val method = cls.getMethod("getRuntimeAbi")
            val obj = method.invoke(null) as String
            when (obj) {
                "arm64-v8a" -> result = "arm64"
                "x86" -> result = "x86"
                "x86_64" -> result = "x86_64"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    @Throws(Throwable::class)
    fun extractXWalkLib(context: Context): Boolean {
        val cls = Class.forName("org.xwalk.core.XWalkDecompressor")
        val method = cls.getMethod("extractResource", String::class.java, String::class.java)
        val obj = method.invoke(null, apkPath(context), libExtractPath(context)) as Boolean
        if (obj) {
            Toast.makeText(context, "解压XWalkView运行组件完成!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "解压XWalkView运行组件失败!", Toast.LENGTH_LONG).show()
        }
        return obj
    }

    fun apkPath(context: Context): String {
        return context.cacheDir.absolutePath + "/XWalkRuntimeLib.apk"
    }

    fun libExtractPath(context: Context): String {
        // XWalkEnvironment.getExtractedCoreDir
        return context.getDir("extracted_xwalkcore", Context.MODE_PRIVATE).absolutePath
    }
}
