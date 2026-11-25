package com.github.tvbox.kt.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Environment
import android.os.Handler
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.R
import com.github.tvbox.kt.cache.CacheManager
import com.github.tvbox.kt.data.AppDataManager
import com.github.tvbox.kt.ui.adapter.BackupAdapter
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.FileUtils
import com.github.tvbox.kt.util.XWalkUtils
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.FileCallback
import com.lzy.okgo.model.Response
import com.owen.tvrecyclerview.widget.TvRecyclerView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Date

/** XWalk 内核下载对话框,Kotlin 移植(与旧版 XWalkInitDialog 一致)。 */
class XWalkInitDialog(context: Context) : BaseDialog(context) {
    private var listener: OnListener? = null

    interface OnListener {
        fun onchange()
    }

    init {
        setCanceledOnTouchOutside(false)
        setCancelable(true)
        setContentView(R.layout.dialog_xwalk)
        setOnDismissListener { OkGo.getInstance().cancelTag("down_xwalk") }
        val downText = findViewById<TextView>(R.id.downXWalk)
        val downTip = findViewById<TextView>(R.id.downXWalkArch)

        downTip.text = "下载XWalkView运行组件\nArch:" + XWalkUtils.getRuntimeAbi()

        if (XWalkUtils.xWalkLibExist(context)) {
            downText.text = "重新下载"
        }

        downText.setOnClickListener(object : View.OnClickListener {
            private fun setTextEnable(enable: Boolean) {
                downText.isEnabled = enable
                downText.setTextColor(
                    ContextCompat.getColor(context, if (enable) R.color.dialog_text_primary else R.color.dialog_text_hint)
                )
            }

            override fun onClick(v: View) {
                FastClickCheckUtil.check(v)
                setTextEnable(false)
                OkGo.get<File>(XWalkUtils.downUrl())
                    .tag("down_xwalk")
                    .execute(object : FileCallback(context.cacheDir.absolutePath, XWalkUtils.saveZipFile()) {
                        override fun onSuccess(response: Response<File>) {
                            try {
                                XWalkUtils.unzipXWalkZip(context, response.body().absolutePath)
                                XWalkUtils.extractXWalkLib(context)
                                downText.text = "重新下载"
                                listener?.onchange()
                                dismiss()
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                setTextEnable(true)
                            }
                        }

                        override fun onError(response: Response<File>) {
                            super.onError(response)
                            Toast.makeText(context, response.exception.message, Toast.LENGTH_LONG).show()
                            setTextEnable(true)
                        }

                        override fun downloadProgress(progress: com.lzy.okgo.model.Progress) {
                            super.downloadProgress(progress)
                            downText.text = String.format("%.2f%%", progress.fraction * 100)
                        }
                    })
            }
        })
    }

    fun setOnListener(listener: OnListener?): XWalkInitDialog {
        this.listener = listener
        return this
    }
}

/** 备份/恢复对话框,Kotlin 移植(与旧版 BackupDialog 一致)。 */
class BackupDialog(context: Context) : BaseDialog(context) {

    init {
        setContentView(R.layout.dialog_backup)
        val tvRecyclerView = findViewById<TvRecyclerView>(R.id.list)
        val adapter = BackupAdapter()
        tvRecyclerView.setAdapter(adapter)
        adapter.setNewData(allBackup())
        adapter.setOnItemChildClickListener { adapter0, view, position ->
            if (view.id == R.id.tvName) {
                restore(adapter0.getItem(position) as String)
            } else if (view.id == R.id.tvDel) {
                delete(adapter0.getItem(position) as String)
                adapter0.setNewData(allBackup())
            }
        }
        findViewById<View>(R.id.backupNow).setOnClickListener {
            backup()
            adapter.setNewData(allBackup())
        }
        findViewById<View>(R.id.storagePermission).setOnClickListener { v ->
            if (XXPermissions.isGranted(context, Permission.Group.STORAGE)) {
                Toast.makeText(context, "已获得存储权限", Toast.LENGTH_SHORT).show()
            } else {
                XXPermissions.with(context)
                    .permission(Permission.Group.STORAGE)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                            if (all) {
                                adapter.setNewData(allBackup())
                                Toast.makeText(context, "已获得存储权限", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                            if (never) {
                                Toast.makeText(context, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show()
                                XXPermissions.startPermissionActivity(context as Activity, permissions)
                            } else {
                                Toast.makeText(context, "获取存储权限失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
            }
        }
    }

    fun allBackup(): List<String> {
        val result = ArrayList<String>()
        try {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val file = File("$root/tvbox_backup/")
            val list = file.listFiles()
            Arrays.sort(list) { o1: File, o2: File ->
                if (o1.isDirectory && o2.isFile) return@sort -1
                if (o1.isFile && o2.isDirectory) 1 else o2.name.compareTo(o1.name)
            }
            if (file.exists()) {
                for (f in list) {
                    if (result.size > 10) {
                        FileUtils.recursiveDelete(f)
                        continue
                    }
                    if (f.isDirectory) {
                        result.add(f.name)
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return result
    }

    fun restore(dir: String) {
        try {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val backup = File("$root/tvbox_backup/$dir")
            if (backup.exists()) {
                val db = File(backup, "sqlite")
                if (AppDataManager.restore(db)) {
                    val data = FileUtils.readSimple(File(backup, "hawk"))
                    if (data != null) {
                        val hawkJson = String(data, Charsets.UTF_8)
                        val jsonObject = JSONObject(hawkJson)
                        val it = jsonObject.keys()
                        // 原来每个 key 都 edit().commit() 一次,逐条同步落盘会卡住界面;
                        // 改为两份 SP 各收集完再提交一次,仍然保证重启前已写盘。
                        val mainEditor = KtApp.instance()
                            .getSharedPreferences("Hawk2", Context.MODE_PRIVATE).edit()
                        val cryptoEditor = KtApp.instance()
                            .getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE).edit()
                        while (it.hasNext()) {
                            val key = it.next()
                            val value = jsonObject.getString(key)
                            if (key == "cipher_key") {
                                cryptoEditor.putString(key, value)
                            } else {
                                mainEditor.putString(key, value)
                            }
                        }
                        mainEditor.commit()
                        cryptoEditor.commit()
                        Toast.makeText(context, "恢复成功,即将自动重启应用!", Toast.LENGTH_SHORT).show()
                        Handler().postDelayed({ restartApp() }, 3000)
                    } else {
                        Toast.makeText(context, "Hawk恢复失败!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "DB文件恢复失败!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun restartApp() {
        val context: Context? = context
        if (context != null) {
            val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(i)
                System.exit(0)
            }
        }
    }

    fun backup() {
        try {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val file = File("$root/tvbox_backup/")
            if (!file.exists()) file.mkdirs()
            val now = Date()
            val f = SimpleDateFormat("yyyy-MM-dd-HHmmss")
            val backup = File(file, f.format(now))
            backup.mkdirs()
            val db = File(backup, "sqlite")
            if (AppDataManager.backup(db)) {
                var sharedPreferences: SharedPreferences =
                    KtApp.instance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE)
                val jsonObject = JSONObject()
                for (key in sharedPreferences.all.keys) {
                    jsonObject.put(key, sharedPreferences.getString(key, ""))
                }
                sharedPreferences = KtApp.instance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE)
                for (key in sharedPreferences.all.keys) {
                    jsonObject.put(key, sharedPreferences.getString(key, ""))
                }
                if (!FileUtils.writeSimple(jsonObject.toString().toByteArray(Charsets.UTF_8), File(backup, "hawk"))) {
                    backup.delete()
                    Toast.makeText(context, "备份Hawk失败!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "备份成功!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "DB文件不存在!", Toast.LENGTH_SHORT).show()
                backup.delete()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            Toast.makeText(context, "备份失败!", Toast.LENGTH_SHORT).show()
        }
    }

    fun delete(dir: String) {
        try {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val backup = File("$root/tvbox_backup/$dir")
            FileUtils.recursiveDelete(backup)
            Toast.makeText(context, "删除成功!", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
