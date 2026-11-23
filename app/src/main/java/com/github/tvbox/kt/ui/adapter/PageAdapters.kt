package com.github.tvbox.kt.ui.adapter

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.github.tvbox.kt.base.BaseLazyFragment
import java.util.ArrayList

/** 首页 ViewPager 适配器(hide/show 管理),Kotlin 移植(与旧版 HomePageAdapter 一致)。 */
class HomePageAdapter : FragmentPagerAdapter {

    var fragmentManager: FragmentManager
    var list: List<BaseLazyFragment>
    private val adapterId: Long = System.nanoTime()

    constructor(fm: FragmentManager) : super(fm) {
        this.fragmentManager = fm
        this.list = ArrayList<BaseLazyFragment>()
    }

    constructor(fm: FragmentManager, list: List<BaseLazyFragment>) : super(fm) {
        this.fragmentManager = fm
        this.list = list
    }

    fun clear() {
        (list as ArrayList<BaseLazyFragment>).clear()
        notifyDataSetChanged()
    }

    fun removeAll() {
        val transaction = fragmentManager.beginTransaction()
        for (fragment in list) {
            if (fragment.isAdded) {
                transaction.remove(fragment)
            }
        }
        transaction.commitAllowingStateLoss()
        fragmentManager.executePendingTransactions()
        (list as ArrayList<BaseLazyFragment>).clear()
        notifyDataSetChanged()
    }

    override fun getItem(position: Int): Fragment {
        return list[position]
    }

    override fun getItemId(position: Int): Long {
        return adapterId + position
    }

    override fun getCount(): Int {
        return list?.size ?: 0
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Fragment {
        val fragment = super.instantiateItem(container, position) as Fragment
        fragmentManager.beginTransaction().show(fragment).commitAllowingStateLoss()
        return fragment
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        val fragment = `object` as Fragment
        fragmentManager.beginTransaction().hide(fragment).commitAllowingStateLoss()
    }
}

/** 设置页 ViewPager 适配器,Kotlin 移植(与旧版 SettingPageAdapter 一致)。 */
class SettingPageAdapter : FragmentPagerAdapter {

    var fragmentManager: FragmentManager
    var list: List<BaseLazyFragment>

    constructor(fm: FragmentManager) : super(fm) {
        this.fragmentManager = fm
        this.list = ArrayList<BaseLazyFragment>()
    }

    constructor(fm: FragmentManager, list: List<BaseLazyFragment>) : super(fm) {
        this.fragmentManager = fm
        this.list = list
    }

    override fun getItem(position: Int): Fragment {
        return list[position]
    }

    override fun getCount(): Int {
        return list?.size ?: 0
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Fragment {
        val fragment = super.instantiateItem(container, position) as Fragment
        fragmentManager.beginTransaction().show(fragment).commitAllowingStateLoss()
        return fragment
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        val fragment = list[position]
        fragmentManager.beginTransaction().hide(fragment).commitAllowingStateLoss()
    }
}
