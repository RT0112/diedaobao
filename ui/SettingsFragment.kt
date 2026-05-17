package com.falldetector.diedaobao.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.falldetector.diedaobao.detect.DetectionConfig
import androidx.lifecycle.lifecycleScope
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.data.AppDatabase
import com.falldetector.diedaobao.databinding.FragmentSettingsBinding
import com.falldetector.diedaobao.notify.EmergencyNotifier
import com.falldetector.diedaobao.notify.PhoneCaller
import com.falldetector.diedaobao.notify.SmsSender
import com.falldetector.diedaobao.notify.WeChatNotifier
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val PREF_ALARM_SOUND = "alarm_sound"
        const val PREF_ALARM_VIBRATE = "alarm_vibrate"
    }

    // 标记：是否正在从偏好恢复值（防止触发 listener 回写）
    private var isRestoring = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

        // ========== 阶段1: 恢复保存的值（不触发 listener）==========
        isRestoring = true

        binding.switchAlarmSound.isChecked = prefs.getBoolean(PREF_ALARM_SOUND, true)
        binding.switchAlarmVibrate.isChecked = prefs.getBoolean(PREF_ALARM_VIBRATE, true)
        binding.switchNotifyPhone.isChecked = prefs.getBoolean(EmergencyNotifier.PREF_NOTIFY_BY_PHONE, true)
        binding.switchNotifySms.isChecked = prefs.getBoolean(EmergencyNotifier.PREF_NOTIFY_BY_SMS, true)
        binding.switchNotifyWechat.isChecked = prefs.getBoolean(EmergencyNotifier.PREF_NOTIFY_BY_WECHAT, true)
        binding.switchAutoStart.isChecked = prefs.getBoolean("auto_start", true)
        binding.switchAutoAllowAssist.isChecked = prefs.getBoolean("auto_allow_assist", true)

        isRestoring = false

        // ========== 阶段2: 注册 listener（用户手动切换才保存）==========

        binding.switchAlarmSound.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean(PREF_ALARM_SOUND, isChecked).commit()
                Toast.makeText(requireContext(), if (isChecked) "告警声音已开启" else "告警声音已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchAlarmVibrate.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean(PREF_ALARM_VIBRATE, isChecked).commit()
                Toast.makeText(requireContext(), if (isChecked) "告警震动已开启" else "告警震动已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchNotifyPhone.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean(EmergencyNotifier.PREF_NOTIFY_BY_PHONE, isChecked).commit()
            }
        }

        binding.switchNotifySms.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean(EmergencyNotifier.PREF_NOTIFY_BY_SMS, isChecked).commit()
            }
        }

        binding.switchNotifyWechat.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean(EmergencyNotifier.PREF_NOTIFY_BY_WECHAT, isChecked).commit()
            }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean("auto_start", isChecked).commit()
            }
        }

        binding.switchAutoAllowAssist.setOnCheckedChangeListener { _, isChecked ->
            if (!isRestoring) {
                prefs.edit().putBoolean("auto_allow_assist", isChecked).commit()
                Toast.makeText(requireContext(), if (isChecked) "家人请求协助时将自动同意" else "家人请求协助时需手动确认", Toast.LENGTH_SHORT).show()
            }
        }

        // ========== Webhook URL（粘贴即保存）==========
        val savedWebhook = prefs.getString(EmergencyNotifier.PREF_WEBHOOK_URL, "") ?: ""
        binding.etWebhookUrl.setText(savedWebhook)
        binding.etWebhookUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isRestoring) {
                    val url = s.toString().trim()
                    prefs.edit().putString(EmergencyNotifier.PREF_WEBHOOK_URL, url).commit()
                }
            }
        })

        // ========== 倒计时（拖动即保存）==========
        val countdown = prefs.getInt(EmergencyNotifier.PREF_COUNTDOWN_SECONDS, 30)
        binding.seekbarCountdown.progress = countdown
        binding.tvCountdownValue.text = "${countdown}秒"
        binding.seekbarCountdown.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = maxOf(10, progress)
                binding.tvCountdownValue.text = "${value}秒"
                if (fromUser && !isRestoring) {
                    prefs.edit().putInt(EmergencyNotifier.PREF_COUNTDOWN_SECONDS, value).commit()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ========== 灵敏度滑动条（v0.30.0：8 级可调）==========
        val sensitivityDescs = arrayOf(
            "", // 索引 0 不使用
            "最严格，几乎不报警（ffTime=250ms）",  // 等级 1
            "很严格（ffTime=235ms）",  // 等级 2
            "偏严格（ffTime=220ms）",  // 等级 3
            "中等，推荐（ffTime=200ms）",  // 等级 4
            "偏宽松（ffTime=185ms）",  // 等级 5
            "宽松（ffTime=170ms）",  // 等级 6
            "很宽松（ffTime=160ms）",  // 等级 7
            "最宽松，容易误报（ffTime=150ms）"  // 等级 8
        )

        val currentLevel = DetectionConfig.sensitivityLevel
        binding.seekSensitivity.progress = currentLevel - 1  // SeekBar: 0-7 → 等级 1-8
        binding.tvSensitivityLevel.text = "$currentLevel（推荐）"
        binding.tvSensitivityDesc.text = sensitivityDescs[currentLevel]

        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress + 1  // 转换为 1-8
                binding.tvSensitivityLevel.text = "$level${if (level == 4) "（推荐）" else ""}"
                binding.tvSensitivityDesc.text = sensitivityDescs[level]
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val level = (seekBar?.progress ?: 3) + 1
                DetectionConfig.applySensitivityLevel(level)
                Log.i("SettingsFragment", "灵敏度等级已保存: $level")
            }
        })

        // ========== 测试按钮 ==========
        binding.btnTestPhone.setOnClickListener { testPhoneNotification() }
        binding.btnTestSms.setOnClickListener { testSmsNotification() }
        binding.btnTestWechat.setOnClickListener { testWechatNotification() }
        binding.btnTestWebhook.setOnClickListener { testWebhook() }
        binding.btnWebhookTutorial.setOnClickListener { showWebhookTutorial() }

        // ========== 权限设置入口（独立监听器，不再嵌套！）==========
        binding.btnPermissionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        // ========== 反馈入口（v0.30.7 新增）==========
        binding.btnFeedback.setOnClickListener {
            startActivity(Intent(requireContext(), com.falldetector.diedaobao.feedback.FeedbackActivity::class.java))
        }

        // ========== 使用教程（v0.30.7 新增）==========
        binding.btnHelp.setOnClickListener {
            showHelpDialog()
        }

        // ========== 重新录制权限操作 ==========
        updatePermissionRecordStatus()
        binding.btnReRecordPermission.setOnClickListener {
            showReRecordPermissionDialog()
        }

        // ========== 关于本机（v0.30.7 新增）==========
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // ========== 重置账号 ==========
        binding.btnResetAccount.setOnClickListener {
            showResetConfirm()
        }
    }

    // ========== 权限录制管理 ==========

    /**
     * 更新权限录制状态文案
     */
    private fun updatePermissionRecordStatus() {
        val recordPrefs = requireContext().getSharedPreferences("permission_record", Context.MODE_PRIVATE)
        // v9+ key: recorded_steps_v9
        val stepsJson = recordPrefs.getString("recorded_steps_v9", null)
        val failCount = recordPrefs.getInt("replay_fail_count", 0)
        val version = recordPrefs.getInt("recording_version", 0)

        val statusText = if (stepsJson.isNullOrBlank()) {
            "未录制"
        } else {
            val count = try {
                org.json.JSONArray(stepsJson).length()
            } catch (_: Exception) { 0 }
            if (count == 0) "未录制" else "已录制${count}步(v${version})${if (failCount > 0) "，失败${failCount}次" else "，正常"}"
        }
        binding.tvPermissionRecordStatus.text = statusText
    }

    /**
     * 显示重新录制权限操作对话框
     */
    private fun showReRecordPermissionDialog() {
        val recordPrefs = requireContext().getSharedPreferences("permission_record", Context.MODE_PRIVATE)
        // v9+ key: recorded_steps_v9
        val stepsJson = recordPrefs.getString("recorded_steps_v9", null)
        val hasRecorded = !stepsJson.isNullOrBlank()

        val message = if (hasRecorded) {
            val count = try {
                org.json.JSONArray(stepsJson).length()
            } catch (_: Exception) { 0 }
            "当前已录制${count}步操作。\n\n" +
            "重新录制将清除旧记录，下次远程协助时会重新引导操作。\n\n" +
            "适用场景：\n" +
            "• 系统更新后弹窗步骤变了\n" +
            "• 自动点击总是失败\n" +
            "• 换了新手机"
        } else {
            "尚未录制权限操作步骤。\n\n" +
            "下次远程协助时，会引导您手动操作并自动记录。\n\n" +
            "录制后，后续远程协助将自动处理权限弹窗，老人无需手动操作。"
        }

        val builder = AlertDialog.Builder(requireContext())
            .setTitle("🔁 权限操作录制")
            .setMessage(message)

        if (hasRecorded) {
            builder.setPositiveButton("清除并重新录制") { _, _ ->
                // ★ 清除 v9+ 的所有录制相关 key
                recordPrefs.edit()
                    .remove("recorded_steps_v9")
                    .remove("recording_meta_v9")
                    .remove("recording_version")
                    .remove("replay_fail_count")
                    // 兼容清理旧 key
                    .remove("recorded_coords")
                    .remove("fail_count")
                    .apply()
                updatePermissionRecordStatus()
                Toast.makeText(requireContext(), "已清除录制数据，下次远程协助时重新录制", Toast.LENGTH_LONG).show()
            }
            builder.setNegativeButton("保留当前记录", null)
        } else {
            builder.setPositiveButton("知道了", null)
        }

        builder.show()
    }

    // ==================== 测试功能 ====================

    private fun testPhoneNotification() {
        val contacts = getContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(requireContext(), "请先添加紧急联系人", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = contacts[0].phone
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            PhoneCaller.call(requireContext(), phone)
            Toast.makeText(requireContext(), "正在拨打 $phone ...", Toast.LENGTH_SHORT).show()
        } else {
            PhoneCaller.dial(requireContext(), phone)
            Toast.makeText(requireContext(), "无拨号权限，已打开拨号界面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testSmsNotification() {
        val contacts = getContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(requireContext(), "请先添加紧急联系人", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "没有短信权限，请先授权", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = contacts[0].phone
        SmsSender.sendFallAlert(requireContext(), phone, 0.0, 0.0)
        Toast.makeText(requireContext(), "测试短信已发送到 $phone", Toast.LENGTH_SHORT).show()
    }

    private fun testWechatNotification() {
        val url = binding.etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(requireContext(), "请先填写Webhook地址", Toast.LENGTH_SHORT).show()
            return
        }
        testWebhookWithUrl(url)
    }

    private fun testWebhook() {
        val url = binding.etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(requireContext(), "请先填写Webhook地址", Toast.LENGTH_SHORT).show()
            return
        }
        testWebhookWithUrl(url)
    }

    private fun testWebhookWithUrl(url: String) {
        lifecycleScope.launch {
            binding.btnTestWebhook.isEnabled = false
            binding.btnTestWebhook.text = "发送中..."
            val result = WeChatNotifier.sendTestAlert(url)
            binding.btnTestWebhook.isEnabled = true
            binding.btnTestWebhook.text = "🧪 测试发送"
            if (result.success) {
                AlertDialog.Builder(requireContext())
                    .setTitle("✅ 测试成功")
                    .setMessage(result.message)
                    .setPositiveButton("好的", null)
                    .show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("❌ 测试失败")
                    .setMessage(result.message)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun showWebhookTutorial() {
        AlertDialog.Builder(requireContext())
            .setTitle("📖 企业微信机器人配置教程")
            .setMessage(
                "1. 下载「企业微信」App\n" +
                "2. 创建企业（个人也可创建）\n" +
                "3. 创建一个企业群聊\n" +
                "4. 点击群聊右上角的「三个点」\n" +
                "5. 点击「消息推送」\n" +
                "6. 完成添加，复制链接\n" +
                "7. 粘贴到上方输入框\n" +
                "8. 点「测试发送」验证\n\n" +
                "👥 如何邀请家属加入群聊：\n" +
                "• 点击群二维码，显示二维码图片\n" +
                "• 家属用微信扫码即可加入（无需安装企微）\n" +
                "• 加入后通知自动推送到微信\n" +
                "• 所有家属都能实时收到跌倒通知\n\n" +
                "⚠️ 注意：\n" +
                "• 需要用企业内部群（不是外部联系人群）\n" +
                "• 填写的是 Webhook 推送地址，不是机器人添加链接\n" +
                "• 正确格式：https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"
            )
            .setPositiveButton("明白了", null)
            .show()
    }

    // ==================== 帮助与关于 ====================

    private fun showHelpDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("📖 使用教程")
            .setMessage(
                "【跌倒宝使用指南】\n\n" +
                "🤖 自动检测\n" +
                "打开App后点击「开启守护」，跌倒宝会在后台自动监测。\n\n" +
                "⚠️ 跌倒报警流程\n" +
                "检测到跌倒 → 等待30秒 → 无人取消则通知紧急联系人\n" +
                "→ 取消倒计时即可阻止报警\n\n" +
                "📱 传感器说明\n" +
                "请将手机放在口袋或随身位置，\n" +
                "避免将手机放在桌面或固定位置检测。\n\n" +
                "🔋 省电设置\n" +
                "首次使用请在「权限管理」中开启所有权限，\n" +
                "并加入电池白名单，防止后台被杀。\n\n" +
                "📢 通知测试\n" +
                "在「设置」中点击「测试」按钮，\n" +
                "确保短信/微信通知正常收到。"
            )
            .setPositiveButton("明白了", null)
            .show()
    }

    private fun showAboutDialog() {
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { "未知" }
        val device = android.os.Build.MODEL
        val androidVersion = android.os.Build.VERSION.RELEASE

        AlertDialog.Builder(requireContext())
            .setTitle("ℹ️ 关于跌倒宝")
            .setMessage(
                "跌倒宝 v$version\n" +
                "专为独居老人设计的跌倒检测App\n\n" +
                "设备：$device\n" +
                "系统：Android $androidVersion\n\n" +
                "© 2026 跌倒宝团队\n" +
                "隐私声明：所有传感器数据仅本地处理，\n" +
                "不会上传至任何服务器。"
            )
            .setPositiveButton("好的", null)
            .show()
    }

    // ========== 重置账号 ==========
    private fun showResetConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle("重置账号")
            .setMessage("确定要清除本地注册信息吗？\n\n清除后将自动退出，需要重新注册登录。\n\n此操作不会删除云端数据。")
            .setPositiveButton("清除") { _, _ ->
                resetAccount()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetAccount() {
        try {
            val prefs = requireContext().getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Toast.makeText(requireContext(), "已清除账号信息", Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), MainActivity::class.java))
            activity?.finish()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getContacts(): List<com.falldetector.diedaobao.data.Contact> {
        return try {
            val db = AppDatabase.getInstance(requireContext())
            kotlinx.coroutines.runBlocking {
                db.contactDao().getAllContactsSync()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
