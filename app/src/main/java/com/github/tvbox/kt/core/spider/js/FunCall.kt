package com.github.tvbox.kt.core.spider.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import java.util.concurrent.Callable

/**
 * JS 函数同步调用(含 Promise then 展开),Kotlin 移植(与旧版 FunCall 一致)。
 */
class FunCall private constructor(
    private val jsObject: JSObject,
    private val name: String,
    private val args: Array<out Any?>
) : Callable<Any?> {

    private var result: Any? = null

    override fun call(): Any? {
        // Java 变参需要展开数组,否则 Object[] 会被当成单个 JS 参数
        result = jsObject.getJSFunction(name).call(*args)
        if (result !is JSObject) return result
        val promise = result as JSObject
        val then: JSFunction? = promise.getJSFunction("then")
        if (then != null) then.call(jsCallFunction)
        return result
    }

    private val jsCallFunction: JSCallFunction = object : JSCallFunction {
        override fun call(vararg args: Any?): Any? {
            result = args[0]
            return result
        }
    }

    companion object {
        fun call(jsObject: JSObject, name: String, vararg args: Any?): FunCall {
            return FunCall(jsObject, name, args)
        }
    }
}
