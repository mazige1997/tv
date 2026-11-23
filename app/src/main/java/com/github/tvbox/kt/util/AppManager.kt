package com.github.tvbox.kt.util

import android.app.Activity
import java.util.Stack

/**
 * Activity 堆栈管理,Kotlin 移植(与旧版 AppManager.java 一致)。
 */
class AppManager private constructor() {

    private val activityStack = Stack<Activity>()

    companion object {
        val instance: AppManager by lazy { AppManager() }
    }

    /** 添加 Activity 到堆栈 */
    fun addActivity(activity: Activity) {
        activityStack.add(activity)
    }

    /** 是否有 activity */
    fun isActivity(): Boolean = activityStack.isNotEmpty()

    /** 获取当前 Activity(堆栈中最后一个压入的) */
    fun currentActivity(): Activity = activityStack.lastElement()

    /** 结束当前 Activity */
    fun finishActivity() {
        val activity = activityStack.lastElement()
        if (!activity.isFinishing) activity.finish()
    }

    fun finishActivity(activity: Activity) {
        activityStack.remove(activity)
    }

    /** 结束指定类名的 Activity */
    fun finishActivity(cls: Class<*>) {
        for (activity in activityStack) {
            if (activity.javaClass == cls) {
                if (!activity.isFinishing) activity.finish()
                break
            }
        }
    }

    fun backActivity(cls: Class<*>) {
        while (activityStack.isNotEmpty()) {
            val activity = activityStack.pop()
            if (activity.javaClass == cls) {
                activityStack.push(activity)
                break
            } else {
                activity.finish()
            }
        }
    }

    /** 结束所有 Activity */
    fun finishAllActivity() {
        if (activityStack.isNotEmpty()) {
            for (activity in activityStack) {
                if (!activity.isFinishing) activity.finish()
            }
            activityStack.clear()
        }
    }

    /** 获取指定的 Activity */
    fun getActivity(cls: Class<*>): Activity? =
        activityStack.firstOrNull { it.javaClass == cls }

    fun appExit(code: Int) {
        try {
            finishAllActivity()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(code)
        } catch (e: Exception) {
            activityStack.clear()
            e.printStackTrace()
        }
    }
}
