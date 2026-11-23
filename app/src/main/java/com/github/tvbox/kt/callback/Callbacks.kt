package com.github.tvbox.kt.callback

import com.github.tvbox.kt.R
import com.kingja.loadsir.callback.Callback

/** LoadSir 空态页,Kotlin 移植(与旧版 EmptyCallback 一致)。 */
class EmptyCallback : Callback() {
    override fun onCreateView(): Int {
        return R.layout.loadsir_empty_layout
    }
}

/** LoadSir 加载页,Kotlin 移植(与旧版 LoadingCallback 一致)。 */
class LoadingCallback : Callback() {
    override fun onCreateView(): Int {
        return R.layout.loadsir_loading_layout
    }
}
