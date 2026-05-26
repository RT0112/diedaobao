package com.falldetector.diedaobao.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.cloud.CloudBaseClient
import com.falldetector.diedaobao.cloud.WSClient
import com.falldetector.diedaobao.databinding.ActivityMainBinding
import com.falldetector.diedaobao.service.FallDetectionService
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val allPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.BODY_SENSORS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Android 10+ 后台定位（小米等需要"始终允许"）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }

        // 检查是否需要引导权限设置
        if (!hasAllPermissions()) {
            showPermissionGuideDialog()
        }

        // 兜底：确保WebSocket已连接（如果已注册但WS未连接）
        if (CloudBaseClient.isRegistered(this) && !WSClient.isWSConnected()) {
            Log.i("MainActivity", "WebSocket未连接，自动重连")
            WSClient.connect(this)
        }
    }

    /**
     * 显示权限引导对话框 - 有"去设置"按钮
     */
    private fun showPermissionGuideDialog() {
        val missing = getMissingPermissions()
        val message = buildString {
            appendLine("跌倒宝需要以下权限才能正常工作：")
            appendLine()
            missing.forEach { (name, desc) ->
                appendLine("• $name - $desc")
            }
            appendLine()
            append("请点击「去设置」授权这些权限")
        }

        AlertDialog.Builder(this)
            .setTitle("🔐 权限设置")
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(this, PermissionActivity::class.java))
            }
            .setNegativeButton("稍后") { _, _ ->
                Toast.makeText(this, "部分功能可能无法使用，请尽快完成权限设置", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun getMissingPermissions(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()

        // 运行时权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED)
            result.add("📱 传感器" to "检测跌倒")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            result.add("📞 电话" to "紧急呼叫")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            result.add("💬 短信" to "发送通知")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            result.add("📍 定位" to "获取位置")
        // 后台定位（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            result.add("📍 始终允许定位" to "后台获取位置（必须）")

        // 特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this))
                result.add("🔲 悬浮窗" to "锁屏弹窗")
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                result.add("🔋 电池优化" to "后台运行")
        }

        return result
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_test1 -> Test1Fragment()
                R.id.nav_history -> HistoryFragment()
                R.id.nav_contacts -> ContactsFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> HomeFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }
    }

    fun startService() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "请先完成权限设置", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, PermissionActivity::class.java))
            return
        }
        val intent = Intent(this, FallDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    fun stopService() {
        val intent = Intent(this, FallDetectionService::class.java).apply {
            action = FallDetectionService.ACTION_STOP
        }
        startService(intent)
    }

    fun hasAllPermissions(): Boolean {
        val runtimeOk = allPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        return runtimeOk && overlayOk && batteryOk
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
