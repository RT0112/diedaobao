package com.falldetector.diedaobao.ui

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.falldetector.diedaobao.util.AppLogger
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.falldetector.diedaobao.assist.RemoteAssistManager
import com.falldetector.diedaobao.assist.RemoteAssistService
import com.falldetector.diedaobao.databinding.FragmentHomeBinding
import com.falldetector.diedaobao.service.BootWorker
import com.falldetector.diedaobao.service.FallDetectionService
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.ui.RemoteAssistActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupGuardianSection()
        setupBindCodeSection()

        // 定时刷新守护状态
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                updateGuardianStatus()
                delay(500)
            }
        }

        // 启动远程协助请求轮询
        if (CloudBaseClient.isRegistered(requireContext())) {
            setupRemoteAssist()
        }
    }

    // ==================== 跌倒守护区域 ====================

    private fun setupGuardianSection() {
        binding.btnToggle.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (FallDetectionService.isRunning) {
                (activity as? MainActivity)?.stopService()
                BootWorker.cancel(requireContext())
                prefs.edit().putBoolean("guard_ever_started", false).apply()  // v18.2: 用户主动关闭
            } else {
                // 开启守护前检查权限
                val activity = activity as? MainActivity
                if (activity != null && !activity.hasAllPermissions()) {
                    Toast.makeText(requireContext(), "请先完成权限设置", Toast.LENGTH_LONG).show()
                    startActivity(Intent(requireContext(), PermissionActivity::class.java))
                    return@setOnClickListener
                }

                (activity as? MainActivity)?.startService()
                BootWorker.schedule(requireContext())
                prefs.edit().putBoolean("guard_ever_started", true).apply()  // v18.2: 用户主动开启
            }
            updateGuardianStatus()
        }
    }

    private var lastAutoRestartTime = 0L  // v0.30.7: 防止频繁重启

    private fun updateGuardianStatus() {
        val isRunning = FallDetectionService.isRunning
        val userStopped = FallDetectionService.userStopped

        // v0.30.7: 如果服务被系统杀死（非用户主动停止），自动重启
        // 关键：进程被杀后 JVM 重置，isRunning=false, userStopped=false
        // guard_ever_started 在 SharedPreferences 中，进程重启后仍在
        if (!isRunning && !userStopped) {
            val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            val everStarted = prefs.getBoolean("guard_ever_started", false)
            val needRestart = prefs.getBoolean("service_need_restart", false)
            if (everStarted || needRestart) {
                val now = System.currentTimeMillis()
                // 5秒内不重复重启，防止死循环
                if (now - lastAutoRestartTime > 5000) {
                    lastAutoRestartTime = now
                    AppLogger.w("HomeFragment", "检测到守护服务被系统杀死，自动重启 (guard_ever_started=$everStarted, need_restart=$needRestart)")
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null) {
                        mainActivity.startService()
                        AppLogger.i("HomeFragment", "自动重启服务已调用")
                    } else {
                        AppLogger.e("HomeFragment", "无法重启：activity 不是 MainActivity")
                    }
                }
            }
        }

        val currentRunning = FallDetectionService.isRunning
        binding.tvStatus.text = if (currentRunning) "守护已开启" else "未启动"
        binding.tvStatusDesc.text = if (currentRunning) "跌倒宝正在守护您的安全" else "点击下方按钮开启守护"
        binding.btnToggle.text = if (currentRunning) "关闭守护" else "开启守护"
        binding.btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (currentRunning) android.graphics.Color.parseColor("#F44336") else android.graphics.Color.parseColor("#4CAF50")
        )
        binding.tvStatus.setTextColor(
            if (currentRunning) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#F44336")
        )
    }

    // ==================== 家属绑定码区域 ====================

    private fun setupBindCodeSection() {
        if (!CloudBaseClient.isRegistered(requireContext())) {
            binding.cardBind.visibility = View.GONE
            return
        }

        binding.cardBind.visibility = View.VISIBLE

        // 恢复缓存的绑定码
        val cachedCode = CloudBaseClient.getCachedBindCode(requireContext())
        if (cachedCode != null) {
            binding.tvBindCode.text = formatBindCode(cachedCode)
            binding.tvBindHint.text = "有效期5分钟，请告知家属"
            binding.btnBindCode.text = "刷新绑定码"
        }

        binding.btnBindCode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.btnBindCode.isEnabled = false
                binding.btnBindCode.text = "生成中..."

                val code = CloudBaseClient.generateBindCode(requireContext())
                if (code != null) {
                    binding.tvBindCode.text = formatBindCode(code)
                    binding.tvBindHint.text = "有效期5分钟，请告知家属"
                    Toast.makeText(requireContext(), "绑定码已生成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "生成失败，请检查网络", Toast.LENGTH_LONG).show()
                }

                binding.btnBindCode.isEnabled = true
                binding.btnBindCode.text = "刷新绑定码"
            }
        }

        // 启动时自动生成（如果还没有缓存）
        if (cachedCode == null) {
            viewLifecycleOwner.lifecycleScope.launch {
                delay(800)
                val code = CloudBaseClient.generateBindCode(requireContext())
                if (code != null) {
                    binding.tvBindCode.text = formatBindCode(code)
                    binding.tvBindHint.text = "有效期5分钟，请告知家属"
                    binding.btnBindCode.text = "刷新绑定码"
                }
            }
        }
    }

    /**
     * 格式化绑定码显示：601177 → 601 177
     */
    private fun formatBindCode(code: String): String {
        return if (code.length == 6) {
            "${code.substring(0, 3)} ${code.substring(3)}"
        } else {
            code
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回来刷新状态
        updateGuardianStatus()
        // 检查无障碍服务是否被系统杀掉（HyperOS等ROM常见问题）
        checkAccessibilityStatus()
        // v19.7.5: 确保轮询在运行（APK更新/系统回收后轮询可能停了）
        RemoteAssistManager.ensurePolling(requireContext())
    }

    /**
     * 检查无障碍服务是否被系统杀掉
     * HyperOS/MIUI 等ROM在App被划掉后会禁用无障碍服务
     * 检测到后弹窗提示用户重新开启
     */
    private fun checkAccessibilityStatus() {
        if (!isAdded || activity == null) return
        val wasEnabled = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("accessibility_was_enabled", false)
        val isEnabled = RemoteAssistService.isAccessibilityEnabled(requireContext())

        // 记录：曾经开启过
        if (isEnabled) {
            requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("accessibility_was_enabled", true).apply()
            accessibilityWarningShown = false
            return
        }

        // 曾经开启但现在被关了 → 提示用户
        if (wasEnabled && !isEnabled && !accessibilityWarningShown) {
            accessibilityWarningShown = true
            AppLogger.w("HomeFragment", "无障碍服务被系统关闭，提示用户重新开启")
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ 远程协助功能已关闭")
                .setMessage(
                    "手机系统自动关闭了跌倒宝的远程协助功能。\n\n" +
                    "这不影响跌倒检测，但家人将无法远程协助您。\n\n" +
                    "点击「去开启」重新打开即可，只需3秒。"
                )
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        val intent = RemoteAssistManager.openAccessibilitySettings(requireContext())
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "无法打开设置，请手动在系统设置中开启", Toast.LENGTH_LONG).show()
                    }
                    accessibilityWarningShown = false  // 下次onResume再检查
                }
                .setNegativeButton("暂时不用") { _, _ ->
                    // 不再重复弹，但下次onResume还会检查
                }
                .setCancelable(false)
                .show()
        }
    }

    // ==================== 远程协助 ====================

    private var accessibilityWarningShown = false  // 防止重复弹窗
    private val assistRequestListener: (RemoteAssistManager.AssistRequest) -> Unit = { request ->
        if (isAdded && activity != null) {
            handleAssistRequest(request)
        }
    }

    private fun handleAssistRequest(request: RemoteAssistManager.AssistRequest) {
        AppLogger.i("HomeFragment", "收到协助请求 from=${request.fromName}")

        // v19.7: HomeFragment 只发通知，不 startActivity
        // FallDetectionService 已经会 startActivity + 发通知
        // 双方都 startActivity 会导致 RemoteAssistActivity 被触发两次 → 两个授权弹窗
        // HomeFragment 只补发通知作为 fallback（Service 被杀时通知仍可见）
        val assistIntent = Intent(requireContext(), RemoteAssistActivity::class.java).apply {
            putExtra("from_name", request.fromName)
            putExtra("from_id", request.fromId)
            putExtra("remaining_seconds", request.remainingSeconds)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            requireContext(), 0, assistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(requireContext(), "remote_assist_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("📞 远程协助请求")
            .setContentText("${request.fromName} 请求协助操作您的手机")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(request.remainingSeconds * 1000L)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(3001, notification)

        // v19.7: 如果 FallDetectionService 没运行，HomeFragment 负责 startActivity
        // 如果 Service 在运行，由 Service 统一负责（避免双 startActivity）
        val serviceRunning = com.falldetector.diedaobao.service.FallDetectionService.isRunning
        if (!serviceRunning) {
            try {
                startActivity(assistIntent)
                AppLogger.i("HomeFragment", "Service未运行，HomeFragment负责startActivity")
            } catch (e: Exception) {
                AppLogger.w("HomeFragment", "startActivity被拦截: ${e.message}")
            }
        } else {
            AppLogger.i("HomeFragment", "Service运行中，由Service负责startActivity")
        }
    }

    private fun setupRemoteAssist() {
        // v19: 用 addAssistRequestListener，不会被 FallDetectionService 覆盖
        RemoteAssistManager.addAssistRequestListener(assistRequestListener)
        RemoteAssistManager.ensurePolling(requireContext()) // v19.7.5: 用ensurePolling替代startPolling
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // v19: 移除监听器，防止 Fragment 销毁后仍收到回调
        RemoteAssistManager.removeAssistRequestListener(assistRequestListener)
        _binding = null
    }
}
