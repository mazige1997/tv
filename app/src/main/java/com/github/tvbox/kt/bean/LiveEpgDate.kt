package com.github.tvbox.kt.bean

import java.util.Date

/** 直播 EPG 日期条目,Kotlin 移植(与旧版 LiveEpgDate.java 一致)。 */
class LiveEpgDate {
    var index = 0
    var datePresented: String? = null
    var dateParamVal: Date? = null
}
