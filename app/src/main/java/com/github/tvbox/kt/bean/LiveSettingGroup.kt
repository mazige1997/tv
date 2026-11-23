package com.github.tvbox.kt.bean

/** 直播设置分组,Kotlin 移植(与旧版 LiveSettingGroup.java 一致)。 */
class LiveSettingGroup {
    var groupIndex = 0
    var groupName: String? = null
    var liveSettingItems: ArrayList<LiveSettingItem>? = null
}
