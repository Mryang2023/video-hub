package com.videohub.tv

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.videohub.tv.databinding.ActivityMainBinding
import com.videohub.tv.ui.connect.ConnectFragment
import com.videohub.tv.ui.detail.DetailArgs
import com.videohub.tv.ui.detail.DetailFragment
import com.videohub.tv.ui.fav.FavoritesFragment
import com.videohub.tv.ui.hist.HistoryFragment
import com.videohub.tv.ui.home.HomeFragment
import com.videohub.tv.ui.search.SearchFragment
import com.videohub.tv.ui.settings.SettingsFragment
import com.videohub.tv.ui.settings.SourceManagerFragment
import com.videohub.tv.util.toast
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var lastBackAt = 0L
    private var currentTab = -1

    /**
     * 整套 UI 按 1080p 电视标准视口 960×540dp 设计。不少电视盒子把
     * 密度报成 1.0/1.5，导致视口变成 1280×720dp——所有控件和文字偏小
     * 三分之一，看着费劲还发虚。这里强制把视口拉回 ~960dp 宽。
     */
    override fun attachBaseContext(newBase: Context) {
        val dm = newBase.resources.displayMetrics
        if (dm.widthPixels > dm.heightPixels && dm.widthPixels >= 1100) {
            val desired = dm.widthPixels / 960f * 160f
            val std = intArrayOf(160, 213, 240, 280, 320, 400, 480)
            val dpi = std.minByOrNull { abs(it - desired) } ?: desired.toInt()
            if (abs(dpi - dm.densityDpi) > 20) {
                val cfg = Configuration(newBase.resources.configuration)
                cfg.densityDpi = dpi
                super.attachBaseContext(newBase.createConfigurationContext(cfg))
                return
            }
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        immersive()

        setupTabs()

        if ((application as App).prefs.serverUrl == null) showConnect()
        else switchTab(TAB_HOME)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                if (fm.backStackEntryCount > 0) {
                    fm.popBackStackImmediate()
                    // 覆盖层退掉后恢复标签栏聚焦并把焦点交回当前标签，
                    // 再按 ↓ 由系统焦点搜索进入页面内容
                    if (fm.backStackEntryCount == 0) {
                        setTabsFocusable(true)
                        tabAt(currentTab)?.requestFocus()
                    }
                    return
                }
                if (fm.findFragmentByTag(TAG_CONNECT) != null) {
                    finish()
                    return
                }
                // 电视惯例：双击返回退出
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2000) finish()
                else {
                    lastBackAt = now
                    Toast.makeText(this@MainActivity, R.string.exit_hint, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** 顶部标签栏（tvOS 风）：无底色纯文字。当前页/聚焦 = 白色加粗，其余灰色；
     *  用命令式设值而非选择器，确保任何机型上都有明确视觉反馈 */
    private fun setupTabs() {
        val tabs = listOf(b.tabHome, b.tabLive, b.tabSearch, b.tabFav, b.tabHist, b.tabSet)
        tabs.forEachIndexed { index, tab ->
            tab.setOnFocusChangeListener { v, hasFocus ->
                applyTabLook(v as TextView, focused = hasFocus, active = index == currentTab)
                val target = if (hasFocus) 1.15f else 1f
                v.animate().scaleX(target).scaleY(target).setDuration(180).start()
                // 焦点即切页：扫过标签时页面实时跟随，不再要求按 OK 确认
                if (hasFocus && index != currentTab) switchTab(index)
            }
            tab.setOnClickListener {
                // 到达时已切页；OK 负责把焦点送进当前页内容（等价于按 ↓）。
                // 找不到目标时留在标签上——绝不落 focusable 容器（方向搜索黑洞）
                it.focusSearch(View.FOCUS_DOWN)?.requestFocus()
            }
        }
    }

    private fun applyTabLook(t: TextView, focused: Boolean, active: Boolean) {
        t.setTextColor(
            when {
                focused || active -> ContextCompat.getColor(this, R.color.white)
                else -> ContextCompat.getColor(this, R.color.tab_inactive)
            }
        )
        t.typeface = if (active || focused) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** 覆盖层(详情/播放器/连接页)打开期间禁用标签栏聚焦，
     *  否则方向键会把焦点搜到被盖住、看不见的标签上 */
    private fun setTabsFocusable(focusable: Boolean) {
        listOf(b.tabHome, b.tabLive, b.tabSearch, b.tabFav, b.tabHist, b.tabSet)
            .forEach { it.isFocusable = focusable }
    }

    private fun styleTabs() {
        val tabs = listOf(b.tabHome, b.tabLive, b.tabSearch, b.tabFav, b.tabHist, b.tabSet)
        tabs.forEachIndexed { index, tab ->
            applyTabLook(tab, focused = tab.hasFocus(), active = index == currentTab)
        }
    }

    fun switchTab(index: Int) {
        currentTab = index
        val f: Fragment = when (index) {
            TAB_LIVE -> com.videohub.tv.ui.live.LiveFragment()
            TAB_SEARCH -> SearchFragment()
            TAB_FAV -> FavoritesFragment()
            TAB_HIST -> HistoryFragment()
            TAB_SET -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.tabContainer, f)
            .commit()
        styleTabs()
        val url = (application as App).prefs.serverUrl
        b.serverInfo.text = url?.removePrefix("http://")?.removePrefix("https://")?.trimEnd('/')
            ?: getString(R.string.coming_soon)
    }

    private fun showConnect() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullContainer, ConnectFragment(), TAG_CONNECT)
            .commit()
        setTabsFocusable(false)
    }

    /** 连接成功：撤掉连接页进首页 */
    fun onConnected() {
        supportFragmentManager.findFragmentByTag(TAG_CONNECT)?.let {
            supportFragmentManager.beginTransaction().remove(it).commit()
        }
        setTabsFocusable(true)
        switchTab(TAB_HOME)
        // 焦点曾被连接页占着；交回标签，首页渲染完会抢焦点到 Hero
        tabAt(TAB_HOME)?.requestFocus()
    }

    /** 标签下标 → 视图 */
    private fun tabAt(index: Int): View? = when (index) {
        TAB_SEARCH -> b.tabSearch
        TAB_FAV -> b.tabFav
        TAB_HIST -> b.tabHist
        TAB_SET -> b.tabSet
        else -> b.tabHome
    }

    fun pushDetail(args: DetailArgs) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullContainer, DetailFragment.newInstance(args))
            .addToBackStack(TAG_DETAIL)
            .commit()
        // 覆盖层一打开就抢占焦点，方向键永远先在本层内搜索，
        // 否则焦点会留在背后的标签页内容上，遥控按键全部落空
        setTabsFocusable(false)
        b.fullContainer.requestFocus()
    }

    fun pushLivePlayer(groups: List<com.videohub.tv.data.LiveGroup>, groupIndex: Int, channelIndex: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullContainer, com.videohub.tv.ui.live.LivePlayerFragment.newInstance(groups, groupIndex, channelIndex))
            .addToBackStack(TAG_PLAYER)
            .commit()
        setTabsFocusable(false)
        b.fullContainer.requestFocus()
    }

    fun pushPlayer() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullContainer, com.videohub.tv.ui.player.PlayerFragment())
            .addToBackStack(TAG_PLAYER)
            .commit()
        setTabsFocusable(false)
        b.fullContainer.requestFocus()
    }

    /** 设置页里的二级页（如源管理） */
    fun pushSub(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullContainer, f)
            .addToBackStack(TAG_SUB)
            .commit()
        setTabsFocusable(false)
        b.fullContainer.requestFocus()
    }

    /** 断开连接：清空返回栈回到连接页 */
    fun restartToConnect() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullContainer, ConnectFragment(), TAG_CONNECT)
            .commit()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private var screenInfoShown = false

    override fun onResume() {
        super.onResume()
        if (!screenInfoShown && (application as App).prefs.diagHud) {
            screenInfoShown = true
            showScreenInfo()
        }
    }

    /** 屏幕信息浮层（左下角，10 秒消失）：排查真机分辨率/密度问题用 */
    private fun showScreenInfo() {
        val dm = resources.displayMetrics
        val tv = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFF30D158.toInt())
            textSize = 13f
            setPadding(24, 10, 24, 10)
            text = "屏 ${dm.widthPixels}x${dm.heightPixels}px · density ${"%.2f".format(dm.density)}" +
                " · ${"%.0f".format(dm.widthPixels / dm.density)}x${"%.0f".format(dm.heightPixels / dm.density)}dp"
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        ).apply { marginStart = 40; bottomMargin = 40 }
        (window.decorView as? ViewGroup)?.addView(tv, lp)
        hudHandler.postDelayed({ (tv.parent as? ViewGroup)?.removeView(tv) }, 10_000)
    }

    // ── 遥控器按键诊断浮层（右下角绿色小字，3 秒消失）──

    /** 用户是否已按过遥控器/键盘。冷启动时系统会把默认焦点落在第一个标签上，
     *  HomeFragment 借此区分「默认焦点在标签上」（可抢到 Hero）和
     *  「用户正在扫标签」（焦点即切页，绝不能抢） */
    var userNavigated = false
        private set

    private var keyHud: TextView? = null
    private val hudHandler = Handler(Looper.getMainLooper())
    private val hudHide = Runnable { keyHud?.text = "" }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            userNavigated = true
            if ((application as App).prefs.diagHud) showKeyHud(event)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showKeyHud(e: KeyEvent) {
        val tv = keyHud ?: TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFF30D158.toInt())
            textSize = 14f
            setPadding(24, 10, 24, 10)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { marginEnd = 40; bottomMargin = 40 }
            (window.decorView as? ViewGroup)?.addView(this, lp)
            keyHud = this
        }
        tv.text = "键 ${e.keyCode} · ${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")}"
        hudHandler.removeCallbacks(hudHide)
        hudHandler.postDelayed(hudHide, 3000)
    }

    /** 沉浸全屏：电视上不显示状态栏/导航栏 */
    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val TAB_HOME = 0
        const val TAB_LIVE = 1
        const val TAB_SEARCH = 2
        const val TAB_FAV = 3
        const val TAB_HIST = 4
        const val TAB_SET = 5
        private const val TAG_CONNECT = "connect"
        private const val TAG_DETAIL = "detail"
        private const val TAG_PLAYER = "player"
        private const val TAG_SUB = "sub"
    }
}
