package com.falldetector.diedaobao.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.cloud.WSClient
import com.falldetector.diedaobao.databinding.ActivityLoginBinding
import com.falldetector.diedaobao.util.AppLogger
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isRegisterMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查是否已登录
        if (CloudBaseClient.isLoggedIn()) {
            goToMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 确认密码输入框默认隐藏（注册模式才显示）
        binding.tilConfirmPassword.visibility = View.GONE

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            if (isRegisterMode) {
                // 切换到登录模式
                isRegisterMode = false
                updateUI()
            } else {
                // 执行登录
                performLogin()
            }
        }

        binding.btnRegister.setOnClickListener {
            if (isRegisterMode) {
                // 执行注册
                performRegister()
            } else {
                // 切换到注册模式
                isRegisterMode = true
                updateUI()
            }
        }

        binding.btnSkip.setOnClickListener {
            // 暂不登录，直接进入主界面（基础功能可用）
            AlertDialog.Builder(this)
                .setTitle("温馨提示")
                .setMessage("暂不登录将无法使用以下功能：\n\n• 绑定家属\n• 接收云端通知\n• 远程协助\n\n建议登录后获得完整体验。")
                .setPositiveButton("我知道了，继续") { _, _ ->
                    goToMain()
                }
                .setNegativeButton("返回登录", null)
                .show()
        }
    }

    private fun updateUI() {
        if (isRegisterMode) {
            binding.btnLogin.text = "已有账号？去登录"
            binding.btnRegister.text = "立即注册"
            binding.tvTitle.text = "创建账号"
            binding.tvSubtitle.text = "注册后可绑定家属、接收云端通知"
            binding.tilConfirmPassword.visibility = View.VISIBLE
        } else {
            binding.btnLogin.text = "登录"
            binding.btnRegister.text = "没有账号？去注册"
            binding.tvTitle.text = "登录账号"
            binding.tvSubtitle.text = "登录后可使用云端功能"
            binding.tilConfirmPassword.visibility = View.GONE
        }
        binding.tvError.visibility = View.GONE
        binding.etConfirmPassword.text?.clear()
    }

    /**
     * 翻译英文错误信息为中文
     */
    private fun translateError(msg: String): String {
        return when {
            msg.contains("Username already exists", ignoreCase = true) -> "该用户名已被注册，请换一个"
            msg.contains("Missing username", ignoreCase = true) -> "请输入用户名和密码"
            msg.contains("Invalid username or password", ignoreCase = true) -> "用户名或密码错误，请重新输入"
            msg.contains("Registration failed", ignoreCase = true) -> "注册失败，请稍后重试"
            msg.contains("Login failed", ignoreCase = true) -> "登录失败，请稍后重试"
            msg.contains("timeout", ignoreCase = true) || msg.contains("Timeout", ignoreCase = true) -> "网络连接超时，请检查网络后重试"
            msg.contains("network", ignoreCase = true) || msg.contains("Network", ignoreCase = true) -> "网络连接失败，请检查网络"
            msg.contains("Failed to connect", ignoreCase = true) -> "无法连接服务器，请检查网络"
            else -> msg
        }
    }

    private fun performLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        // 校验：用户名为空
        if (username.isEmpty()) {
            showError("请输入用户名")
            binding.etUsername.requestFocus()
            return
        }

        // 校验：密码为空
        if (password.isEmpty()) {
            showError("请输入密码")
            binding.etPassword.requestFocus()
            return
        }

        // 校验：用户名长度
        if (username.length < 2) {
            showError("用户名至少需要2个字符")
            binding.etUsername.requestFocus()
            return
        }

        // 校验：密码长度
        if (password.length < 6) {
            showError("密码至少需要6位")
            binding.etPassword.requestFocus()
            return
        }

        // 校验：用户名不能包含特殊字符
        if (!username.matches(Regex("^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$"))) {
            showError("用户名只能包含中文、字母、数字和下划线")
            binding.etUsername.requestFocus()
            return
        }

        setLoading(true)
        hideError()

        lifecycleScope.launch {
            val result = CloudBaseClient.loginAccount(username, password)
            setLoading(false)

            result.onSuccess {
                showSuccess("🎉 登录成功，欢迎回来！")
                // 延迟跳转，让用户看到成功提示
                Handler(Looper.getMainLooper()).postDelayed({
                    goToMain()
                }, 800)
            }.onFailure { e ->
                showError(translateError(e.message ?: "登录失败，请稍后重试"))
            }
        }
    }

    private fun performRegister() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        // 校验：用户名为空
        if (username.isEmpty()) {
            showError("请输入用户名")
            binding.etUsername.requestFocus()
            return
        }

        // 校验：密码为空
        if (password.isEmpty()) {
            showError("请输入密码")
            binding.etPassword.requestFocus()
            return
        }

        // 校验：确认密码为空
        if (confirmPassword.isEmpty()) {
            showError("请再次输入密码")
            binding.etConfirmPassword.requestFocus()
            return
        }

        // 校验：用户名长度
        if (username.length < 2) {
            showError("用户名至少需要2个字符")
            binding.etUsername.requestFocus()
            return
        }

        // 校验：用户名最大长度
        if (username.length > 20) {
            showError("用户名不能超过20个字符")
            binding.etUsername.requestFocus()
            return
        }

        // 校验：密码长度
        if (password.length < 6) {
            showError("密码至少需要6位")
            binding.etPassword.requestFocus()
            return
        }

        // 校验：密码最大长度
        if (password.length > 32) {
            showError("密码不能超过32位")
            binding.etPassword.requestFocus()
            return
        }

        // 校验：两次密码一致
        if (password != confirmPassword) {
            showError("两次输入的密码不一致，请重新输入")
            binding.etConfirmPassword.requestFocus()
            return
        }

        // 校验：用户名不能包含特殊字符
        if (!username.matches(Regex("^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$"))) {
            showError("用户名只能包含中文、字母、数字和下划线")
            binding.etUsername.requestFocus()
            return
        }

        setLoading(true)
        hideError()

        lifecycleScope.launch {
            // 用 ANDROID_ID 作为 deviceId（Build.SERIAL 在 API 26+ 需要权限）
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown_${System.currentTimeMillis()}"
            val result = CloudBaseClient.registerAccount(username, password, "elder_$deviceId")
            setLoading(false)

            result.onSuccess {
                showSuccess("🎉 注册成功！正在进入...")
                // 延迟跳转，让用户看到成功提示
                Handler(Looper.getMainLooper()).postDelayed({
                    goToMain()
                }, 1000)
            }.onFailure { e ->
                showError(translateError(e.message ?: "注册失败，请稍后重试"))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvLoadingHint.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnRegister.isEnabled = !loading
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etConfirmPassword.isEnabled = !loading
        binding.btnSkip.isEnabled = !loading
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
        // 抖动动画效果
        binding.tvError.animate().apply {
            translationXBy(16f)
            duration = 50
            withEndAction {
                binding.tvError.animate().translationXBy(-32f).duration = 50
            }
        }
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    private fun showSuccess(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun goToMain() {
        // 注册/登录成功后，确保WebSocket连接
        if (CloudBaseClient.isRegistered(this)) {
            Log.i("LoginActivity", "用户已注册，启动WebSocket连接")
            WSClient.connect(this)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
