package com.github.tvbox.kt.bean

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 直播节目单条目,Kotlin 移植(与旧版 Epginfo.java 一致,时间统一 GMT+8 解析)。
 */
class Epginfo(epgDate: Date?, titleText: String?, date: Date?, startTime: String?, endTime: String?, pos: Int) {

    var startdateTime: Date? = null
    var enddateTime: Date? = null
    var datestart: Int = 0
    var dateend: Int = 0
    var title: String? = null
    var originStart: String? = null
    var originEnd: String? = null
    var start: String? = null
    var end: String? = null
    var index: Int = 0
    var epgDate: Date? = null
    var currentEpgDate: String? = null

    init {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd")
        this.epgDate = epgDate
        this.currentEpgDate = timeFormat.format(this.epgDate)
        this.title = titleText
        this.originStart = startTime
        this.originEnd = endTime
        this.index = pos
        try {
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd")
            simpleDateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
            val userSimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
            userSimpleDateFormat.timeZone = TimeZone.getDefault()
            if (date != null) {
                this.startdateTime = userSimpleDateFormat.parse(
                    simpleDateFormat.format(date) + " " + startTime + ":00 GMT+8:00"
                )
                this.enddateTime = userSimpleDateFormat.parse(
                    simpleDateFormat.format(date) + " " + endTime + ":00 GMT+8:00"
                )
            }
            if (this.startdateTime != null && this.enddateTime != null && !this.enddateTime!!.after(this.startdateTime)) {
                val calendar = Calendar.getInstance()
                calendar.time = this.enddateTime!!
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                this.enddateTime = calendar.time
            }
            val zoneFormat = SimpleDateFormat("HH:mm")
            if (this.startdateTime != null) this.start = zoneFormat.format(this.startdateTime)
            if (this.enddateTime != null) this.end = zoneFormat.format(this.enddateTime)
            this.datestart = Integer.parseInt(this.start!!.replace(":", ""))
            this.dateend = Integer.parseInt(this.end!!.replace(":", ""))
        } catch (th: Throwable) {
            this.start = "00:00"
            this.end = "00:00"
            this.datestart = 0
            this.dateend = 0
        }
    }
}
