package com.videohub.tv.ui.connect

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.Repo
import com.videohub.tv.databinding.FragmentConnectBinding
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.util.toast
import kotlinx.coroutines.launch

/** 首启连接页：输入服务器地址 → /api/health 校验 → 进首页 */
class ConnectFragment : Fragment(R.layout.fragment_connect) {

    private var _b: FragmentConnectBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentConnectBinding.bind(view)

        b.addrInput.setText(app.prefs.serverUrl ?: DEFAULT_ADDR)
        b.btnDefault.text = getString(R.string.connect_default_chip, DEFAULT_ADDR)

        b.btnConnect.tvFocus(1.05f)
        b.btnDefault.tvFocus(1.08f)
        b.btnConnect.setOnClickListener { connect() }
        b.btnDefault.setOnClickListener {
            b.addrInput.setText(DEFAULT_ADDR)
            connect()
        }
        b.addrInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connect()
                true
            } else false
        }

        // 焦点默认给「连接」：避免 EditText 抢焦点后系统输入法吃掉遥控器方向键
        b.btnConnect.requestFocus()
    }

    private fun connect() {
        val base = Repo.normalize(b.addrInput.text?.toString().orEmpty())
        if (base.isEmpty()) {
            status("请输入服务器地址", error = true)
            return
        }
        status("正在连接 ${base.trimEnd('/')} …")
        b.btnConnect.isEnabled = false
        lifecycleScope.launch {
            val ok = runCatching { app.repo.apiFor(base).health() }.getOrNull()?.status == "ok"
            b.btnConnect.isEnabled = true
            if (ok) {
                app.prefs.serverUrl = base
                requireContext().toast("连接成功")
                (requireActivity() as MainActivity).onConnected()
            } else {
                status("无法连接：请确认电视与服务器在同一网络，且服务已启动", error = true)
            }
        }
    }

    private fun status(msg: String, error: Boolean = false) {
        b.statusText.text = msg
        val color = if (error) ContextCompat.getColor(requireContext(), R.color.err_red)
        else ContextCompat.getColor(requireContext(), R.color.text_secondary)
        b.statusText.setTextColor(color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        /** 与手机 APP 一致的默认局域网地址 */
        const val DEFAULT_ADDR = "http://192.168.101.18:8000"
    }
}
