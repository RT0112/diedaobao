package com.falldetector.diedaobao.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.databinding.FragmentTest1Binding
import com.falldetector.diedaobao.detect.DiagnosticInfo
import com.falldetector.diedaobao.notify.EmergencyNotifier
import com.falldetector.diedaobao.service.FallDetectionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Test1Fragment : Fragment() {

    private var _binding: FragmentTest1Binding? = null
    private val binding get() = _binding!!

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var periodicJob: Job? = null
    
    // 日志分类
    private val errorLogs = mutableListOf<String>()   // 错误日志
    private val fallLogs = mutableListOf<String>()     // 跌倒触发日志
    private val normalLogs = mutableListOf<String>()   // 正常日志
    private var currentFilter = "all"  // all / error / fall / normal

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTest1Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupLogFilter()
        startPeriodicUpdate()
        startDecisionLogWatcher()
        logMsg("━━━ 测试页面加载 v0.20.0 ━━━", "normal")
    }

    override fun onDestroyView() {
        periodicJob?.cancel()
        periodicJob = null
        decisionLogJob?.cancel()
        decisionLogJob = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupButtons() {
        binding.btnSimulateFall.setOnClickListener {
            // vFix: 获取当前实际位置传给 ConfirmActivity
            // 否则模拟跌倒的位置可能是 (0,0) 或过时的 getLastKnownLocation
            var lat = 0.0
            var lng = 0.0
            try {
                val ctx = requireContext()
                if (ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    loc?.let {
                        lat = it.latitude
                        lng = it.longitude
                    }
                }
            } catch (e: Exception) {
                // 忽略，位置为0也没关系（至少不会静默用旧数据）
            }

            val intent = Intent(requireContext(), ConfirmActivity::class.java).apply {
                putExtra("peak_acc", 2.8f)
                putExtra("posture_angle", 80f)
                putExtra("confidence", 0.85f)
                putExtra("detection_method", "test_simulate")
                putExtra("ml_probability", 0.85f)
                putExtra("latitude", lat)
                putExtra("longitude", lng)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        binding.btnTestNotify.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    EmergencyNotifier.triggerEmergency(requireContext(), 0.0, 0.0)
                    logMsg("✅ 测试通知已发送", "normal")
                } catch (e: Exception) {
                    logMsg("❌ 测试通知失败: ${e.message}", "error")
                }
            }
        }

        binding.btnCopyLog.setOnClickListener {
            val b = _binding ?: return@setOnClickListener
            val text = b.tvLog.text.toString()
            if (text.isBlank()) {
                if (isAdded) Toast.makeText(requireContext(), "日志为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("跌倒宝诊断日志", text))
            if (isAdded) Toast.makeText(requireContext(), "日志已复制", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLog.setOnClickListener {
            val b = _binding ?: return@setOnClickListener
            b.tvLog.setText("")
            errorLogs.clear()
            fallLogs.clear()
            normalLogs.clear()
            logMsg("日志已清空", "normal")
        }
        
        // 新增：标注误报按钮
        binding.btnMarkFalsePositive?.setOnClickListener {
            val b = _binding ?: return@setOnClickListener
            val lastFall = fallLogs.lastOrNull()
            if (lastFall != null) {
                val marked = "$lastFall >>> [用户标注：误报]"
                fallLogs[fallLogs.size - 1] = marked
                logMsg("📝 已标注上一次跌倒为误报", "normal")
                refreshLogDisplay()
            } else {
                if (isAdded) Toast.makeText(requireContext(), "没有最近的跌倒记录", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupLogFilter() {
        // 日志过滤 RadioGroup（需要在 XML 中添加）
        binding.radioGroupFilter?.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.rb_error -> "error"
                R.id.rb_fall -> "fall"
                R.id.rb_normal -> "normal"
                else -> "all"
            }
            refreshLogDisplay()
        }
    }

    private var decisionLogJob: Job? = null
    private var lastDecisionLog = ""
    
    private fun startDecisionLogWatcher() {
        // 监听 FallDetector 的决策链日志，实时显示到 UI
        decisionLogJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                try {
                    val log = FallDetectionService.getDecisionLog()
                    if (log.isNotBlank() && log != lastDecisionLog) {
                        lastDecisionLog = log
                        // 分类：跌倒触发 vs 正常决策
                        val type = if (log.contains("✅") || log.contains("跌倒")) "fall" else "normal"
                        logMsg(log, type)
                    }
                } catch (e: Exception) {
                    // 服务未运行时忽略
                }
                delay(200)  // 200ms 轮询
            }
        }
    }

    private fun startPeriodicUpdate() {
        periodicJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val b = _binding ?: break
                updateServiceStatus(b)
                updateDiagnosticPanel(b)
                updateCurrentValues(b)
                delay(500)
            }
        }
    }

    private fun updateServiceStatus(b: FragmentTest1Binding) {
        try {
            val running = FallDetectionService.isRunning
            b.tvServiceStatus.text = if (running) "🟢 服务运行中" else "🔴 服务未启动"
        } catch (e: Exception) {
            b.tvServiceStatus.text = "⚪ 服务状态未知"
        }
    }

    private fun updateDiagnosticPanel(b: FragmentTest1Binding) {
        val diag = try {
            FallDetectionService.getDiagnosticInfo()
        } catch (e: Exception) {
            null
        } ?: return

        val decisionText = buildString {
            append("状态：${diag.detectionState}\n")
            append("FF时间：${diag.ffTimeMs}ms\n")
            append("冲击：${String.format("%.1f", diag.peakValue)}g\n")
            append("速度：${String.format("%.2f", diag.peakVelocity)}m/s\n")
            append("ML：raw=${String.format("%.3f", diag.mlProbability)}\n")
            
            // v0.25.16: 显示物理评分详情
            if (diag.physScore > 0.01f) {
                append("\n物理评分：\n")
                append("  FF分=${String.format("%.2f", diag.ffScore)}\n")
                append("  冲击分=${String.format("%.2f", diag.impScore)}\n")
                append("  速度分=${String.format("%.2f", diag.velScore)}\n")
                append("  物理总分=${String.format("%.2f", diag.physScore)}\n")
                append("  加权=${String.format("%.2f", diag.weightedScore)}\n")
            }
            
            append("\n决策链：\n")
            append("  Step1(触发)：${if (diag.step1_triggered) "✅" else "❌"}\n")
            append("  Step2(窗口)：${if (diag.step2_windowOk) "✅" else "❌"}\n")
            append("  Step3(ML)：${String.format("%.2f", diag.step3_mlRaw)}\n")
            append("  Step4(ML高)：${if (diag.step4_mlHigh) "✅" else "❌"}\n")
            append("  Step5(ML低)：${if (diag.step5_mlLow) "✅" else "❌"}\n")
            append("  Step6(物理)：FF=${if (diag.step6_physFf) "✅" else "❌"} Q=${if (diag.step6_physQ) "✅" else "❌"} V=${if (diag.step6_physV) "✅" else "❌"} Imp=${if (diag.step6_physImp) "✅" else "❌"}\n")
            append("  Step7(结果)：${if (diag.step7_result) "🚨 跌倒!" else "未触发"}\n")
            
            if (diag.decisionPath.isNotBlank()) {
                append("\n决策路径：${diag.decisionPath}\n")
            }
            
            if (diag.decisionSummary.isNotBlank()) {
                append("\n摘要：${diag.decisionSummary}")
            }
        }
        b.tvDecisionState.text = decisionText
        
        // 检测到跌倒 → 自动记录到 fallLogs
        if (diag.step7_result && fallLogs.none { it.contains(diag.decisionSummary) }) {
            val fallLog = "[跌倒触发] ${diag.decisionSummary}\n 传感器：${b.tvCurrentValues.text}"
            fallLogs.add(fallLog)
            logMsg("🚨 跌倒已记录（如误报请点击「标注误报」）", "fall")
        }
    }

    private fun updateCurrentValues(b: FragmentTest1Binding) {
        val diag = try {
            FallDetectionService.getDiagnosticInfo()
        } catch (e: Exception) {
            null
        } ?: return

        val text = buildString {
            append("dyn=${String.format("%.2f", diag.dynamicAcc)}g | ")
            append("mag=${String.format("%.2f", diag.accMag)}g | ")
            append("失重=${diag.freefallTimeMs}ms")
            if (diag.ffMerged) append(" | 已合并")
            if (diag.overweightDetected) append(" | 超重")
        }
        b.tvCurrentValues.text = text
    }

    private fun logMsg(msg: String, type: String = "normal") {
        val b = _binding ?: return
        val ts = dateFormat.format(Date())
        val fullMsg = "[$ts] $msg"
        
        // 分类存储
        when (type) {
            "error" -> errorLogs.add(fullMsg)
            "fall" -> fallLogs.add(fullMsg)
            else -> normalLogs.add(fullMsg)
        }
        
        // 刷新显示
        refreshLogDisplay()
    }
    
    private fun refreshLogDisplay() {
        val b = _binding ?: return
        val allLogs = when (currentFilter) {
            "error" -> errorLogs
            "fall" -> fallLogs
            "normal" -> normalLogs
            else -> errorLogs + fallLogs + normalLogs
        }
        b.tvLog.setText(allLogs.takeLast(300).joinToString("\n"))
        b.tvLog.post {
            if (_binding != null) {
                b.tvLog.setSelection(b.tvLog.text.length)
            }
        }
    }
}
