package com.github.tvbox.kt.ui.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.kt.R
import com.github.tvbox.kt.api.ApiConfig
import com.github.tvbox.kt.base.BaseLazyFragment
import com.github.tvbox.kt.bean.Movie
import com.github.tvbox.kt.bean.VodInfo
import com.github.tvbox.kt.cache.RoomDataManger
import com.github.tvbox.kt.event.ServerEvent
import com.github.tvbox.kt.ui.activity.CollectActivity
import com.github.tvbox.kt.ui.activity.DetailActivity
import com.github.tvbox.kt.ui.activity.FastSearchActivity
import com.github.tvbox.kt.ui.activity.HistoryActivity
import com.github.tvbox.kt.ui.activity.LivePlayActivity
import com.github.tvbox.kt.ui.activity.PushActivity
import com.github.tvbox.kt.ui.activity.SearchActivity
import com.github.tvbox.kt.ui.activity.SettingActivity
import com.github.tvbox.kt.ui.activity.TransferActivity
import com.github.tvbox.kt.ui.adapter.HomeHotVodAdapter
import com.github.tvbox.kt.util.FastClickCheckUtil
import com.github.tvbox.kt.util.HawkConfig
import com.github.tvbox.kt.util.ImgUtil
import com.github.tvbox.kt.util.ScreenUtils
import com.github.tvbox.kt.util.UA
import com.github.tvbox.kt.viewmodel.SourceViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 首页"我的"Fragment(直播/搜索/设置入口 + 推荐/历史列表),Kotlin 移植(与旧版 UserFragment 一致)。
 */
class UserFragment : BaseLazyFragment(), View.OnClickListener {
    private lateinit var tvLive: LinearLayout
    private lateinit var tvSearch: LinearLayout
    private lateinit var tvSetting: LinearLayout
    private lateinit var tvTransfer: LinearLayout
    private lateinit var tvHistory: LinearLayout
    private lateinit var tvCollect: LinearLayout
    private lateinit var tvPush: LinearLayout
    private var homeSourceRec: List<Movie.Video>? = null
    lateinit var tvHotList: TvRecyclerView
        private set
    private var sourceViewModel: SourceViewModel? = null
    private var style: ImgUtil.Style? = null

    fun setArguments(recVod: List<Movie.Video>?): UserFragment {
        homeSourceRec = recVod
        return this
    }

    override fun onFragmentResume() {
        super.onFragmentResume()
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvHotList.visibility = View.VISIBLE
            tvHotList.setHasFixedSize(true)
            var spanCount = 5
            if (style != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                spanCount = ImgUtil.spanCountByStyle(style!!, spanCount)
            }
            tvHotList.layoutManager = V7GridLayoutManager(mContext, spanCount)
            val paddingLeft = -tvHotList.mHorizontalSpacingWithMargins / 2 + resources.getDimensionPixelSize(R.dimen.vs_6)
            val paddingTop = resources.getDimensionPixelSize(R.dimen.vs_20)
            val paddingRight = -tvHotList.mHorizontalSpacingWithMargins / 2 + resources.getDimensionPixelSize(R.dimen.vs_6)
            val paddingBottom = resources.getDimensionPixelSize(R.dimen.vs_20)
            tvHotList.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        } else {
            tvHotList.visibility = View.VISIBLE
            tvHotList.layoutManager = V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false)
            val paddingLeft = -tvHotList.mHorizontalSpacingWithMargins / 2 + resources.getDimensionPixelSize(R.dimen.vs_6)
            val paddingTop = resources.getDimensionPixelSize(R.dimen.vs_20)
            val paddingRight = -tvHotList.mHorizontalSpacingWithMargins / 2 + resources.getDimensionPixelSize(R.dimen.vs_6)
            val paddingBottom = resources.getDimensionPixelSize(R.dimen.vs_20)
            tvHotList.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            val allVodRecord = RoomDataManger.getAllVodRecord(20)
            val vodList = ArrayList<Movie.Video>()
            for (vodInfo in allVodRecord) {
                val vod = Movie.Video()
                vod.id = vodInfo.id
                vod.sourceKey = vodInfo.sourceKey
                vod.name = vodInfo.name
                vod.pic = vodInfo.pic
                if (vodInfo.playNote != null && !vodInfo.playNote!!.isEmpty()) {
                    vod.note = "上次看到" + vodInfo.playNote
                }
                vodList.add(vod)
            }
            Companion.homeHotVodAdapter?.setNewData(vodList)
        }
    }

    override val layoutResID: Int
        get() = R.layout.fragment_user

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hotList = view.findViewById<TvRecyclerView>(R.id.tvHotList)
        if (hotList != null && hotList.layoutManager == null) {
            hotList.layoutManager = V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false)
        }
    }

    private fun jumpSearch(vod: Movie.Video) {
        val newIntent: Intent = if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, true)) {
            Intent(mContext, FastSearchActivity::class.java)
        } else {
            Intent(mContext, SearchActivity::class.java)
        }
        newIntent.putExtra("title", vod.name)
        newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        mActivity.startActivity(newIntent)
    }

    override fun init() {
        EventBus.getDefault().register(this)
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        tvLive = findViewById(R.id.tvLive)!!
        tvSearch = findViewById(R.id.tvSearch)!!
        tvSetting = findViewById(R.id.tvSetting)!!
        tvTransfer = findViewById(R.id.tvTransfer)!!
        tvTransfer.visibility = if (ScreenUtils.isTv(mContext)) View.VISIBLE else View.GONE
        tvCollect = findViewById(R.id.tvFavorite)!!
        tvHistory = findViewById(R.id.tvHistory)!!
        tvPush = findViewById(R.id.tvPush)!!
        // 传输入口在非 TV 设备隐藏时,tvHistory/tvSetting 原本指向它的左右焦点会断链,改指彼此形成闭环
        if (tvTransfer.visibility == View.GONE) {
            tvHistory.nextFocusLeft = R.id.tvSetting
            tvSetting.nextFocusRight = R.id.tvHistory
        }
        tvLive.setOnClickListener(this)
        tvSearch.setOnClickListener(this)
        tvSetting.setOnClickListener(this)
        tvTransfer.setOnClickListener(this)
        tvHistory.setOnClickListener(this)
        tvPush.setOnClickListener(this)
        tvCollect.setOnClickListener(this)
        tvLive.onFocusChangeListener = focusChangeListener
        tvSearch.onFocusChangeListener = focusChangeListener
        tvSetting.onFocusChangeListener = focusChangeListener
        tvTransfer.onFocusChangeListener = focusChangeListener
        tvHistory.onFocusChangeListener = focusChangeListener
        tvPush.onFocusChangeListener = focusChangeListener
        tvCollect.onFocusChangeListener = focusChangeListener
        tvHotList = findViewById(R.id.tvHotList)!!
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && homeSourceRec != null) {
            style = ImgUtil.initStyle()
        }
        var tvRate = ""
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 0) {
            tvRate = "豆瓣热播"
        } else if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            tvRate = if (homeSourceRec != null) "站点推荐" else "豆瓣热播"
        }
        val homeHotVodAdapter = HomeHotVodAdapter(style, tvRate)
        Companion.homeHotVodAdapter = homeHotVodAdapter
        homeHotVodAdapter.setOnItemClickListener { adapter, _, position ->
            if (ApiConfig.get().getSourceBeanList().isEmpty()) return@setOnItemClickListener
            val vod = adapter.getItem(position) as? Movie.Video ?: return@setOnItemClickListener

            if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 &&
                homeSourceRec != null && vod.action != null
            ) {
                sourceViewModel!!.action(vod.sourceKey ?: "", vod.action)
                return@setOnItemClickListener
            }

            if ((vod.id != null && vod.id!!.isNotEmpty()) &&
                Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2 && HawkConfig.hotVodDelete
            ) {
                homeHotVodAdapter.remove(position)
                val vodInfo = RoomDataManger.getVodInfo(vod.sourceKey ?: "", vod.id ?: "")
                RoomDataManger.deleteVodRecord(vod.sourceKey ?: "", vodInfo!!)
                Toast.makeText(mContext, "已删除当前记录", Toast.LENGTH_SHORT).show()
            } else if (vod.id != null && vod.id!!.startsWith("msearch:")) {
                jumpSearch(vod)
            } else {
                val bundle = Bundle()
                bundle.putString("id", vod.id)
                bundle.putString("sourceKey", vod.sourceKey)
                bundle.putString("title", vod.name)
                bundle.putString("picture", vod.pic)
                jumpActivity(DetailActivity::class.java, bundle)
            }
        }

        homeHotVodAdapter.setOnItemLongClickListener { _, _, position ->
            if (ApiConfig.get().getSourceBeanList().isEmpty()) return@setOnItemLongClickListener false
            val vod = homeHotVodAdapter.getItem(position)
            if (vod == null) return@setOnItemLongClickListener false
            // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
            if (vod.id != null && vod.id!!.startsWith("msearch:")) {
                val bundle = Bundle()
                bundle.putString("id", vod.id)
                bundle.putString("sourceKey", vod.sourceKey)
                bundle.putString("title", vod.name)
                bundle.putString("picture", vod.pic)
                jumpActivity(DetailActivity::class.java, bundle)
            } else if ((vod.id != null && vod.id!!.isNotEmpty()) &&
                Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2
            ) {
                HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete
                homeHotVodAdapter.notifyDataSetChanged()
            } else {
                val bundle = Bundle()
                bundle.putString("title", vod.name)
                jumpActivity(FastSearchActivity::class.java, bundle)
            }
            true
        }

        tvHotList.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                itemView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.setInterpolator(DecelerateInterpolator())?.start()
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                itemView?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(150)?.setInterpolator(DecelerateInterpolator())?.start()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
            }
        })
        tvHotList.setAdapter(homeHotVodAdapter)

        initHomeHotVod(homeHotVodAdapter)
        sourceViewModel!!.actionResult.observe(this) { jsonObject ->
            if (jsonObject == null) return@observe
            val msg = jsonObject.optString("msg")
            if (msg.isNotEmpty()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun initHomeHotVod(adapter: HomeHotVodAdapter) {
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec)
                return
            }
        } else if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            return
        }
        setDouBanData(adapter)
    }

    private fun setDouBanData(adapter: HomeHotVodAdapter) {
        try {
            val cal = java.util.Calendar.getInstance()
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DATE)
            val today = String.format("%d%d%d", year, month, day)
            val requestDay = Hawk.get("home_hot_day", "")
            if (requestDay == today) {
                val json = Hawk.get("home_hot", "")
                if (json.isNotEmpty()) {
                    val hotMovies = loadHots(json)
                    if (hotMovies != null && hotMovies.isNotEmpty()) {
                        adapter.setNewData(hotMovies)
                        return
                    }
                }
            }
            val doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=$year,$year"
            OkGo.get<String>(doubanUrl)
                .headers("User-Agent", UA.randomOne())
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        val netJson = response.body()
                        Hawk.put("home_hot_day", today)
                        Hawk.put("home_hot", netJson)
                        mActivity.runOnUiThread {
                            adapter.setNewData(loadHots(netJson))
                        }
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        return response.body()!!.string()
                    }
                })
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    private fun loadHots(json: String?): ArrayList<Movie.Video> {
        val result = ArrayList<Movie.Video>()
        try {
            val infoJson = Gson().fromJson(json, JsonObject::class.java)
            val array = infoJson.getAsJsonArray("data")
            val limit = minOf(array.size(), 25)
            for (i in 0 until limit) { // 改用索引循环
                val ele = array[i]
                val obj = ele.asJsonObject
                val vod = Movie.Video()
                vod.name = obj.get("title").asString
                vod.note = obj.get("rate").asString
                if (!vod.note!!.isEmpty()) vod.note += " 分"
                vod.pic = obj.get("cover").asString +
                    "@User-Agent=" + UA.randomOne() +
                    "@Referer=https://www.douban.com/"
                result.add(vod)
            }
        } catch (ignored: Throwable) {
        }
        return result
    }

    private val focusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
        if (hasFocus) {
            v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
        } else {
            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    override fun onClick(v: View) {
        // takagen99: Remove Delete Mode
        HawkConfig.hotVodDelete = false

        FastClickCheckUtil.check(v)
        when (v.id) {
            R.id.tvLive -> jumpActivity(LivePlayActivity::class.java)
            R.id.tvSearch -> jumpActivity(SearchActivity::class.java)
            R.id.tvSetting -> jumpActivity(SettingActivity::class.java)
            R.id.tvTransfer -> jumpActivity(TransferActivity::class.java)
            R.id.tvHistory -> jumpActivity(HistoryActivity::class.java)
            R.id.tvPush -> jumpActivity(PushActivity::class.java)
            R.id.tvFavorite -> jumpActivity(CollectActivity::class.java)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun server(event: ServerEvent) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // P2-1:解除静态 adapter 对本 Fragment 的持有(点击 lambda 捕获了本 Fragment);
        // 仅当静态引用仍指向本视图绑定的 adapter 时置空,避免误杀新实例(外部使用点 HomeActivity 均已判空)
        if (Companion.homeHotVodAdapter != null && Companion.homeHotVodAdapter === tvHotList.adapter) {
            Companion.homeHotVodAdapter = null
        }
    }

    companion object {
        @JvmStatic
        var homeHotVodAdapter: HomeHotVodAdapter? = null
            private set

        @JvmStatic
        fun newInstance(): UserFragment {
            return UserFragment()
        }

        @JvmStatic
        fun newInstance(recVod: List<Movie.Video>): UserFragment {
            return UserFragment().setArguments(recVod)
        }
    }
}
