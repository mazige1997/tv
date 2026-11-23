package com.github.tvbox.kt.data

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.tvbox.kt.KtApp
import com.github.tvbox.kt.util.FileUtils
import java.io.File
import java.io.IOException

/**
 * Room 数据库管理,Kotlin 移植(与旧版 AppDataManager 一致;DB 文件名一致保证数据沿用)。
 */
object AppDataManager {
    private const val DB_FILE_VERSION = 3
    private const val DB_NAME = "tvbox"

    // DCL 惰性初始化必须 volatile，避免读到半初始化的数据库实例
    @Volatile
    private var dbInstance: AppDataBase? = null

    fun init() {
        // 单例由 object 保证
    }

    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            try {
                database.execSQL("ALTER TABLE sourceState ADD COLUMN tidSort TEXT")
            } catch (e: SQLiteException) {
                e.printStackTrace()
            }
        }
    }

    fun dbPath(): String {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db"
    }

    fun get(): AppDataBase {
        if (dbInstance == null) {
            synchronized(this) {
                if (dbInstance == null) {
                    dbInstance = Room.databaseBuilder(KtApp.instance(), AppDataBase::class.java, dbPath())
                        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                        .allowMainThreadQueries() // 可以在主线程操作
                        .build()
                }
            }
        }
        return dbInstance!!
    }

    @Throws(IOException::class)
    fun backup(path: File): Boolean {
        if (dbInstance != null && dbInstance!!.isOpen) {
            dbInstance!!.close()
            dbInstance = null
        }
        val db = KtApp.instance().getDatabasePath(dbPath())
        return if (db.exists()) {
            FileUtils.copyFile(db, path)
            true
        } else {
            false
        }
    }

    @Throws(IOException::class)
    fun restore(path: File): Boolean {
        if (dbInstance != null && dbInstance!!.isOpen) {
            dbInstance!!.close()
            dbInstance = null
        }
        val db = KtApp.instance().getDatabasePath(dbPath())
        if (db.exists()) {
            db.delete()
        }
        if (db.parentFile != null && !db.parentFile!!.exists()) db.parentFile!!.mkdirs()
        FileUtils.copyFile(path, db)
        return true
    }
}
