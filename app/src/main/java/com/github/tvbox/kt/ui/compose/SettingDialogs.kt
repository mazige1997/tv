package com.github.tvbox.kt.ui.compose

import android.content.Context
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.kt.bean.IJKCode
import com.github.tvbox.kt.bean.SourceBean
import com.github.tvbox.kt.ui.dialog.SelectDialog
import com.github.tvbox.kt.ui.adapter.SelectDialogAdapter

/** 设置页原生 Dialog 复用层:Compose 只负责触发,弹出/交互仍走项目既有的原生 Dialog。 */

object SettingDiff {
    val string = object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(a: String, b: String) = a == b
        override fun areContentsTheSame(a: String, b: String) = a == b
    }
    val int = object : DiffUtil.ItemCallback<Int>() {
        override fun areItemsTheSame(a: Int, b: Int) = a == b
        override fun areContentsTheSame(a: Int, b: Int) = a == b
    }
    val source = object : DiffUtil.ItemCallback<SourceBean>() {
        override fun areItemsTheSame(a: SourceBean, b: SourceBean) = a === b
        override fun areContentsTheSame(a: SourceBean, b: SourceBean) = a.key == b.key
    }
    val ijk = object : DiffUtil.ItemCallback<IJKCode>() {
        override fun areItemsTheSame(a: IJKCode, b: IJKCode) = a === b
        override fun areContentsTheSame(a: IJKCode, b: IJKCode) = a.name == b.name
    }
}

/** 通用单选弹窗,onPicked 返回「选中值」和「位置」。 */
fun <T> showSelect(
    context: Context,
    tip: String,
    data: List<T>,
    select: Int,
    diff: DiffUtil.ItemCallback<T>,
    display: (T) -> String,
    onPicked: (value: T, pos: Int) -> Unit
) {
    val dialog = SelectDialog<T>(context)
    dialog.setTip(tip)
    dialog.setAdapter(
        object : SelectDialogAdapter.SelectDialogInterface<T> {
            override fun click(value: T, pos: Int) {
                dialog.dismiss()
                onPicked(value, pos)
            }
            override fun getDisplay(value: T): String = display(value)
        },
        diff, data, select
    )
    dialog.show()
}
