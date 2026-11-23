package com.github.tvbox.kt.base

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.tvbox.kt.callback.EmptyCallback
import com.github.tvbox.kt.callback.LoadingCallback
import com.kingja.loadsir.callback.Callback
import com.kingja.loadsir.core.LoadService
import com.kingja.loadsir.core.LoadSir
import me.jessyan.autosize.AutoSize
import me.jessyan.autosize.internal.CustomAdapt

/**
 * Fragment 基类(懒加载 + 双层可见性分发),Kotlin 移植(与旧版 BaseLazyFragment 一致)。
 */
abstract class BaseLazyFragment : Fragment(), CustomAdapt {

    protected var rootView: View? = null

    /** 布局是否创建完成 */
    protected var isViewCreated = false

    /** 当前可见状态 */
    protected var currentVisibleState = false

    /** 是否第一次可见 */
    protected var mIsFirstVisible = true
    protected lateinit var mContext: Context
    protected lateinit var mActivity: Activity
    private var mLoadService: LoadService<*>? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
        mActivity = context as Activity
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        AutoSize.autoConvertDensity(activity, sizeInDp, isBaseOnWidth)
        if (null == rootView) {
            rootView = inflater.inflate(layoutResID, container, false)
        }
        isViewCreated = true
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!isHidden && userVisibleHint) {
            // 可见状态,进行事件分发
            dispatchUserVisibleHint(true)
        }
    }

    /**
     * 修改 Fragment 的可见性 setUserVisibleHint 被调用有两种情况:
     * 1) 切换 tab 时先于其他生命周期调用
     * 2) 对已调用过 setUserVisibleHint 的 fragment 进行可见状态变化
     */
    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (isViewCreated) {
            if (isVisibleToUser && !currentVisibleState) {
                // 从不可见 -> 可见
                dispatchUserVisibleHint(true)
            } else if (!isVisibleToUser && currentVisibleState) {
                dispatchUserVisibleHint(false)
            }
        }
    }

    /**
     * 用 FragmentTransaction 控制 hide/show 时自动调用
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dispatchUserVisibleHint(false)
        } else {
            dispatchUserVisibleHint(true)
        }
    }

    /** 统一处理用户可见事件分发 */
    private fun dispatchUserVisibleHint(isVisible: Boolean) {
        // fragment 嵌套 fragment(只考虑 2 层嵌套)
        if (isVisible && isParentInvisible()) {
            // 父 Fragment 此时尚不可见,直接 return
            return
        }
        // 当前状态与目标状态一致则不处理
        if (currentVisibleState == isVisible) {
            return
        }
        currentVisibleState = isVisible
        if (isVisible) {
            if (mIsFirstVisible) {
                mIsFirstVisible = false
                // 第一次可见,进行全局初始化
                init()
            }
            onFragmentResume()
            // 分发事件给内嵌的 Fragment
            dispatchChildVisibleState(true)
        } else {
            onFragmentPause()
            dispatchChildVisibleState(false)
        }
    }

    /**
     * 双重 ViewPager 嵌套时,分发可见事件给内嵌的 Fragment
     */
    private fun dispatchChildVisibleState(visible: Boolean) {
        val fragmentManager: FragmentManager = childFragmentManager
        val fragments = fragmentManager.fragments
        for (fragment in fragments) {
            if (fragment is BaseLazyFragment && !fragment.isHidden && fragment.userVisibleHint) {
                fragment.dispatchUserVisibleHint(visible)
            }
        }
    }

    /** Fragment 真正的 Pause,暂停一切网络耗时操作 */
    protected open fun onFragmentPause() {}

    /** Fragment 真正的 Resume,开始处理网络加载等耗时操作 */
    protected open fun onFragmentResume() {}

    private fun isParentInvisible(): Boolean {
        val parentFragment = parentFragment
        if (parentFragment is BaseLazyFragment) {
            return !parentFragment.isSupportVisible()
        }
        return false
    }

    private fun isSupportVisible(): Boolean {
        return currentVisibleState
    }

    /** 滑动或跳转过程中,第一次创建 fragment 时会调用 onResume */
    override fun onResume() {
        AutoSize.autoConvertDensity(activity, sizeInDp, isBaseOnWidth)
        super.onResume()
        if (!mIsFirstVisible) {
            // Activity 跳转会让缓存 fragment 重复 onResume,只对可见 fragment 加载
            if (!isHidden && !currentVisibleState && userVisibleHint) {
                dispatchUserVisibleHint(true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (currentVisibleState && userVisibleHint) {
            dispatchUserVisibleHint(false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isViewCreated = false
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : View> findViewById(@IdRes viewId: Int): T? {
        return rootView?.findViewById(viewId) as T?
    }

    protected abstract val layoutResID: Int

    /** 第一次可见,根据业务进行初始化操作 */
    protected abstract fun init()

    protected fun setLoadSir(view: View) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view) { }
        }
    }

    protected fun setLoadSir2(view: View) {
        mLoadService = LoadSir.getDefault().register(view) { }
    }

    open fun showLoading() {
        mLoadService?.showCallback(LoadingCallback::class.java)
    }

    open fun showEmpty() {
        mLoadService?.showCallback(EmptyCallback::class.java)
    }

    open fun showSuccess() {
        mLoadService?.showSuccess()
    }

    fun jumpActivity(clazz: Class<out BaseActivity>) {
        val intent = Intent(mContext, clazz)
        startActivity(intent)
    }

    fun jumpActivity(clazz: Class<out BaseActivity>, bundle: Bundle?) {
        val intent = Intent(mContext, clazz)
        intent.putExtras(bundle!!)
        startActivity(intent)
    }

    override fun getSizeInDp(): Float {
        val activity = activity
        return if (activity != null && activity is CustomAdapt) activity.sizeInDp else 0f
    }

    override fun isBaseOnWidth(): Boolean {
        val activity = activity
        return if (activity != null && activity is CustomAdapt) activity.isBaseOnWidth else true
    }
}
