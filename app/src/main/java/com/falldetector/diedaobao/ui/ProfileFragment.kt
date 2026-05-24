package com.falldetector.diedaobao.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateUserInfo()
        setupListeners()
    }

    private fun updateUserInfo() {
        val b = _binding ?: return

        // 读取用户信息
        val prefs = requireContext().getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        val username = CloudBaseClient.getUsername()
        val accountId = CloudBaseClient.getAccountId()
        val isLoggedIn = CloudBaseClient.isLoggedIn()

        if (isLoggedIn && username.isNotEmpty()) {
            b.tvUsername.text = username
        } else {
            b.tvUsername.text = "未登录"
        }

        b.tvAccountId.text = if (accountId != null && accountId.isNotEmpty()) "账号ID: $accountId" else "账号ID: 未分配"

        // 绑定状态
        val isRegistered = CloudBaseClient.isRegistered(requireContext())
        b.tvBindingInfo.text = if (isRegistered) "已注册设备，可绑定家属" else "未注册设备"
        b.tvBindingInfo.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isRegistered) android.R.color.holo_green_dark else android.R.color.darker_gray
            )
        )
    }

    private fun setupListeners() {
        val b = _binding ?: return

        // 修改个人信息
        b.btnEditProfile.setOnClickListener {
            showEditProfileDialog()
        }

        // 权限设置
        b.btnPermissionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        // 意见反馈
        b.btnFeedback.setOnClickListener {
            startActivity(Intent(requireContext(), com.falldetector.diedaobao.feedback.FeedbackActivity::class.java))
        }

        // 设置（跳转到SettingsActivity加载SettingsFragment）
        b.btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        // 关于
        b.btnAbout.setOnClickListener {
            showAbout()
        }

        // 退出登录
        b.btnLogout.setOnClickListener {
            showLogoutConfirm()
        }
    }

    private fun showEditProfileDialog() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentName = prefs.getString("user_name", "") ?: ""
        val currentPhone = prefs.getString("user_phone", "") ?: ""

        val nameEdit = android.widget.EditText(requireContext()).apply {
            hint = "姓名"
            setText(currentName)
            setSingleLine()
        }
        val phoneEdit = android.widget.EditText(requireContext()).apply {
            hint = "手机号"
            setText(currentPhone)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine()
        }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(nameEdit)
            addView(phoneEdit)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("修改个人信息")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newName = nameEdit.text.toString().trim()
                val newPhone = phoneEdit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit()
                        .putString("user_name", newName)
                        .putString("user_phone", newPhone)
                        .apply()
                    Toast.makeText(requireContext(), "信息已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "姓名不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAbout() {
        if (!isAdded) return
        val context = context ?: return
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "未知"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("关于跌倒宝")
            .setMessage(
                "跌倒宝 v$version\n\n" +
                "专为独居老人设计的跌倒检测App\n\n" +
                "🛡️ 守护老人安全\n" +
                "🚨 跌倒自动报警\n" +
                "👨‍👩‍👧 家属远程协助\n" +
                "📍 位置实时追踪\n\n" +
                "© 2026 跌倒宝团队"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showLogoutConfirm() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("退出登录")
            .setMessage("确定要退出当前账号吗？\n\n退出后需要重新登录才能使用云端功能。")
            .setPositiveButton("退出") { _, _ ->
                CloudBaseClient.logout()
                Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
                // 跳转到登录页面
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                requireActivity().finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateUserInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
