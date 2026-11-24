package com.github.tvbox.kt.core.spider.js

import com.github.tvbox.kt.util.LOG
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import java.util.concurrent.CountDownLatch

/**
 * JS 异步调用转同步等待(Promise then/catch),Kotlin 移植(与旧版 Async 一致)。
 */
class Async private constructor() {

    private val future = Result()

    private val success: JSCallFunction = object : JSCallFunction {
        override fun call(vararg args: Any?): Any? {
            future.complete(if (args != null && args.isNotEmpty()) args[0] else null)
            return null
        }
    }

    private val error: JSCallFunction = object : JSCallFunction {
        override fun call(vararg args: Any?): Any? {
            val msg = if (args != null && args.isNotEmpty() && args[0] != null) args[0].toString() else ""
            future.completeExceptionally(Exception(msg))
            return null
        }
    }

    private fun call(`object`: JSObject, name: String, args: Array<out Any?>): Result {
        val function: JSFunction? = `object`.getJSFunction(name)
        if (function == null) return empty()
        try {
            // JSFunction.call(Object...) 是 Java 变参;必须用 * 展开,否则整个 Object[]
            // 会被当作单个参数交给 JNI,触发 "Unsupported Java type [Ljava.lang.Object;"。
            val result = function.call(*args)
            if (result is JSObject) then(result)
            else future.complete(result)
        } catch (t: Throwable) {
            LOG.e("DIAG js_call_error name=$name args=${args.size} err=$t")
            future.completeExceptionally(t)
        } finally {
            function.release()
        }
        return future
    }

    private fun empty(): Result {
        future.complete(null)
        return future
    }

    private fun then(promise: JSObject) {
        val then: JSFunction? = promise.getJSFunction("then")
        if (then == null) {
            future.complete(promise)
        } else {
            consume(then, success)
            consume(promise.getJSFunction("catch"), error)
        }
    }

    private fun consume(function: JSFunction?, callback: JSCallFunction) {
        if (function == null) return
        try {
            function.call(callback)
        } finally {
            function.release()
        }
    }

    class Result {
        private val latch = CountDownLatch(1)
        private var value: Any? = null
        private var error: Throwable? = null

        fun complete(value: Any?) {
            this.value = value
            latch.countDown()
        }

        fun completeExceptionally(error: Throwable) {
            this.error = error
            latch.countDown()
        }

        @Throws(Exception::class)
        fun get(): Any? {
            latch.await()
            if (error != null) {
                if (error is Exception) throw error as Exception
                throw Exception(error)
            }
            return value
        }
    }

    companion object {
        fun run(`object`: JSObject, name: String, args: Array<out Any?>): Result {
            return Async().call(`object`, name, args)
        }
    }
}
