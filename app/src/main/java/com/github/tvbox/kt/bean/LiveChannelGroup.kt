package com.github.tvbox.kt.bean

/** 直播频道分组,Kotlin 移植(与旧版 LiveChannelGroup.java 一致)。 */
class LiveChannelGroup {
    /** 分组索引号 */
    var groupIndex: Int = 0

    /** 分组名称 */
    var groupName: String? = null

    /** 分组密码 */
    var groupPassword: String? = null

    var liveChannelItems: ArrayList<LiveChannelItem>? = null

    fun getLiveChannels(): ArrayList<LiveChannelItem>? = liveChannelItems
}
