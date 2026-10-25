package com.videohub.tv.ui.search

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.databinding.FragmentSearchBinding
import com.videohub.tv.databinding.ItemSearchGroupBinding
import com.videohub.tv.ui.detail.DetailArgs
import com.videohub.tv.ui.focus.HorizontalSpacing
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.ui.home.CardAdapter
import com.videohub.tv.ui.home.CardUi
import com.videohub.tv.util.toast
import com.videohub.tv.util.hideKeyboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 搜索页：输入/热词/历史词 → 聚合搜索 → 按源分组结果 */
class SearchFragment : Fragment(R.layout.fragment_search) {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!
    private var sugJob: Job? = null
    private var inResults = false

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentSearchBinding.bind(view)

        b.btnSearch.tvFocus(1.05f)
        b.btnSearch.setOnClickListener { doSearch() }
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        // 单行 EditText 会把 ↓ 吃掉当光标移动，遥控器出不去；
        // 转发方向搜索，让 ↓ 能落到热词/搜索按钮。
        // 拼音猜测出现时，↑↓ 直接收起输入法并落到第一条猜测词条——
        // 用户输完拼音习惯按 ↑ 离开输入法去选片名，而不是把拼音当关键词搜。
        // （输入法开着时按键先被输入法吞掉，↑↓ 的 pre-ime 拦截见 SearchInput）
        b.searchInput.onPreImeDirection = { keyCode ->
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
                focusFirstSuggestion()
        }
        b.searchInput.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if ((keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    focusFirstSuggestion()
                ) {
                    return@setOnKeyListener true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return@setOnKeyListener v.focusSearch(
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
                    )?.requestFocus() != null
                }
            }
            false
        }

        // 热词 = 在追的收藏 + 固定词，收藏加载完再刷新一次
        buildChips(b.hotChips, DEFAULT_HOT)
        repo.api?.let { api ->
            viewLifecycleOwner.lifecycleScope.launch {
                val names = try {
                    api.favorites().map { it.vod_name }.distinct().take(6)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val b = _b ?: return@launch
                if (names.isNotEmpty()) buildChips(b.hotChips, names + DEFAULT_HOT)
            }
        }
        rebuildHistoryChips()

        // 清空输入框 → 回到推荐词/历史词模式；出结果后这两块是隐藏的。
        // 纯字母输入（如 wmsj）→ 防抖拉取拼音首字母猜测
        b.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val b = _b ?: return
                val kw = s?.toString()?.trim().orEmpty()
                sugJob?.cancel()
                if (kw.isEmpty()) {
                    inResults = false
                    b.results.removeAllViews()
                    b.sugWrap.isVisible = false
                    b.hotWrap.isVisible = true
                    rebuildHistoryChips()
                } else if (!inResults && Regex("[a-z0-9]{2,}").matches(kw.lowercase())) {
                    sugJob = fetchSuggestions(kw.lowercase())
                } else {
                    b.sugWrap.isVisible = false
                }
            }
        })

        // 焦点即切页：创建时焦点通常停在标签栏上（用户正在扫标签），不能抢；
        // 只有焦点悬空/在内容外壳上时才落到第一个热词，
        // 避免输入框自动弹输入法吃掉遥控器
        val cf = requireActivity().currentFocus
        if (cf == null || cf.id == R.id.tabContainer) {
            (b.hotChips.getChildAt(0) ?: b.searchInput).requestFocus()
        }
    }

    private fun buildChips(container: ViewGroup, words: List<String>) {
        container.removeAllViews()
        words.distinct().forEach { w ->
            val chip = layoutInflater.inflate(R.layout.item_chip, container, false) as TextView
            chip.text = w
            chip.tvFocus(1.08f)
            chip.setOnClickListener {
                // 先把焦点收进输入框再改文本：若焦点停在词块上，
                // 词块所在容器随文本变化被隐藏时，系统重找焦点可能级联切页
                b.searchInput.requestFocus()
                b.searchInput.setText(w)
                doSearch()
            }
            container.addView(chip)
        }
    }

    private fun rebuildHistoryChips() {
        val hist = app.prefs.searchHistory()
        b.hisWrap.isVisible = hist.isNotEmpty()
        buildChips(b.hisChips, hist)
    }

    /** 拼音猜测词条：点击按核心名（剥掉"精编版/2024"等后缀）搜索 */
    private fun buildSuggestChips(container: ViewGroup, items: List<com.videohub.tv.data.SuggestItem>) {
        container.removeAllViews()
        items.forEach { item ->
            val chip = layoutInflater.inflate(R.layout.item_chip, container, false) as TextView
            chip.text = item.query.ifEmpty { item.name }
            chip.tvFocus(1.08f)
            chip.setOnClickListener {
                // 先把焦点收进输入框再改文本，防止词块容器隐藏时焦点级联切页
                b.searchInput.requestFocus()
                b.searchInput.setText(item.query)
                doSearch()
            }
            container.addView(chip)
        }
    }

    /** 拼音猜测出现时，↑/↓ 从输入框直接落到第一条猜测词条（并收起输入法）。
     *  无猜测时返回 false，走默认焦点搜索 */
    private fun focusFirstSuggestion(): Boolean {
        val b = _b ?: return false
        if (!b.sugWrap.isVisible) return false
        b.searchInput.hideKeyboard()
        for (i in 0 until b.sugChips.childCount) {
            if (b.sugChips.getChildAt(i).requestFocus()) return true
        }
        return false
    }

    /** 纯字母关键词 → 拼音首字母猜测词条（wmsj → 完美世界…），防抖 350ms */
    private fun fetchSuggestions(kw: String): Job =
        viewLifecycleOwner.lifecycleScope.launch {
            delay(350)
            val api = repo.api ?: return@launch
            val sug = try {
                api.suggest(kw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val b = _b ?: return@launch
            // 同一核心词的多个变体只留一条（精编版/2024 都指向"完美世界"时只显示一个）
            val seen = mutableSetOf<String>()
            val items = sug?.matches?.filter { it.query.isNotEmpty() && seen.add(it.query) }.orEmpty()
            if (items.isNotEmpty()) {
                buildSuggestChips(b.sugChips, items)
                b.sugWrap.isVisible = true
                b.hotWrap.isVisible = false
                b.hisWrap.isVisible = false
            } else {
                b.sugWrap.isVisible = false
                if (!inResults) {
                    b.hotWrap.isVisible = true
                    b.hisWrap.isVisible = app.prefs.searchHistory().isNotEmpty()
                }
            }
        }

    private fun doSearch() {
        val kw = b.searchInput.text?.toString()?.trim().orEmpty()
        if (kw.isEmpty()) {
            requireContext().toast("先输入或选一个关键词")
            return
        }
        val api = repo.api ?: return
        app.prefs.addSearchHistory(kw)

        // 结果模式：收起热词/历史，让结果紧跟输入框出现在页首。
        // 必须先把焦点收拢到输入框再隐藏推荐区——若焦点还停在词块上，
        // 容器一隐藏系统会自动重找焦点，可能落到标签栏触发"焦点即切页"
        sugJob?.cancel()
        inResults = true
        b.searchInput.requestFocus()
        b.searchInput.hideKeyboard()
        b.sugWrap.isVisible = false
        b.hotWrap.isVisible = false
        b.hisWrap.isVisible = false
        b.results.removeAllViews()
        b.results.addView(hintView("正在搜索「$kw」，聚合全部源…"))
        b.root.smoothScrollTo(0, 0)

        viewLifecycleOwner.lifecycleScope.launch {
            val res = try {
                api.searchAll(kw)
            } catch (e: CancellationException) {
                // 页面销毁引发的取消必须原样抛出：吞掉会在已销毁的视图上继续执行 → NPE
                throw e
            } catch (e: Exception) {
                null
            }
            val b = _b ?: return@launch
            b.results.removeAllViews()
            if (res == null) {
                b.results.addView(hintView(getString(R.string.search_failed)))
                requireContext().toast(R.string.search_failed)
                return@launch
            }
            val okGroups = res.results.filter { it.ok && it.list.isNotEmpty() }
            if (okGroups.isEmpty()) {
                // 纯字母关键词多半是拼音首字母：聚合搜不到时给出猜测片名让用户点选
                if (Regex("[a-zA-Z0-9]+").matches(kw)) {
                    val sug = try {
                        api.suggest(kw)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val b2 = _b ?: return@launch
                    if (sug != null && sug.matches.isNotEmpty()) {
                        b2.results.addView(hintView("没搜到「$kw」的影片，试试这些片名（拼音猜测）："))
                        val row = android.widget.HorizontalScrollView(requireContext()).apply {
                            isHorizontalScrollBarEnabled = false
                        }
                        val wrap = android.widget.LinearLayout(requireContext()).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                        }
                        row.addView(wrap)
                        buildSuggestChips(wrap, sug.matches)
                        b2.results.addView(row)
                        return@launch
                    }
                }
                b.results.addView(hintView(getString(R.string.search_empty)))
                return@launch
            }
            okGroups.forEach { g ->
                val gb = ItemSearchGroupBinding.inflate(layoutInflater, b.results, false)
                gb.groupTitle.text = "${g.source_name} · ${g.site_name} · ${g.list.size} 条"
                gb.groupRow.layoutManager =
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                gb.groupRow.addItemDecoration(
                    HorizontalSpacing((14 * resources.displayMetrics.density).toInt())
                )
                val cards = g.list.take(12).map { item ->
                    CardUi(
                        title = item.vod_name,
                        imageUrl = repo.proxyImage(item.vod_pic),
                        sub = item.vod_remarks,
                        onClick = {
                            (requireActivity() as? MainActivity)?.pushDetail(
                                DetailArgs(
                                    apiUrl = g.api_url,
                                    vodId = item.vod_id,
                                    siteKey = g.site_key,
                                    siteName = g.site_name,
                                    title = item.vod_name,
                                    pic = item.vod_pic,
                                )
                            )
                        },
                    )
                }
                val adapter = CardAdapter()
                adapter.submitList(cards)
                gb.groupRow.adapter = adapter
                b.results.addView(gb.root)
            }
            val failed = res.results.count { !it.ok || it.list.isEmpty() }
            if (failed > 0) {
                b.results.addView(hintView("其余 $failed 个源超时或无结果，已自动跳过"))
            }
            // 结果从页首开始展示
            b.root.smoothScrollTo(0, 0)
        }
    }

    private fun hintView(s: String): TextView =
        TextView(requireContext()).apply {
            text = s
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, 0)
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private val DEFAULT_HOT = listOf("凡人修仙传", "三体", "庆余年")
    }
}
