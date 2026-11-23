package com.github.tvbox.kt.bean

/** 直播设置条目,Kotlin 移植(与旧版 LiveSettingItem.java 一致)。 */
class LiveSettingItem {
    var itemIndex = 0

    var itemName: String? = null

    private var itemSelected = false

    fun isItemSelected(): Boolean = itemSelected

    fun setItemSelected(itemSelected: Boolean) {
        this.itemSelected = itemSelected
    }
}
