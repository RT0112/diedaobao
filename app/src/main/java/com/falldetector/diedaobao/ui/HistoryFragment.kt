package com.falldetector.diedaobao.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.data.FallEvent
import com.falldetector.diedaobao.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FallEventAdapter
    private val events = mutableListOf<FallEvent>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FallEventAdapter(events) { event -> showEventOptions(event) }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter

        binding.btnClearAll.setOnClickListener { showClearConfirm() }

        loadEvents()
    }

    private fun loadEvents() {
        val app = com.falldetector.diedaobao.FallDetectionApp.instance
        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.fallEventDao.getAll().collect { list ->
                events.clear()
                events.addAll(list)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        if (events.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
            binding.tvStats.text = "暂无跌倒事件"
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            
            val total = events.size
            val falsePositives = events.count { it.isFalsePositive }
            val realFalls = total - falsePositives
            binding.tvStats.text = "共 ${total} 次检测 | 真实跌倒 ${realFalls} 次 | 误报 ${falsePositives} 次"
        }
    }

    private fun showEventOptions(event: FallEvent) {
        val options = when {
            !event.isConfirmed -> arrayOf("📍 查看位置", "🗑️ 删除记录")
            event.isFalsePositive -> arrayOf("📍 查看位置", "✅ 标记为误报", "🗑️ 删除记录")
            else -> arrayOf("📍 查看位置", "🚨 真实跌倒", "🗑️ 删除记录")
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("事件详情")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "📍 查看位置" -> showLocation(event)
                    "✅ 标记为误报", "🚨 真实跌倒" -> toggleFalsePositive(event)
                    "🗑️ 删除记录" -> deleteEvent(event)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLocation(event: FallEvent) {
        if (event.latitude == 0.0 && event.longitude == 0.0) {
            Toast.makeText(requireContext(), "未记录位置信息", Toast.LENGTH_SHORT).show()
            return
        }
        val mapUrl = "https://uri.amap.com/marker?position=${event.longitude},${event.latitude}"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(mapUrl))
        startActivity(intent)
    }

    private fun toggleFalsePositive(event: FallEvent) {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = com.falldetector.diedaobao.FallDetectionApp.instance
            val updated = event.copy(isFalsePositive = !event.isFalsePositive)
            app.repository.fallEventDao.update(updated)
            Toast.makeText(requireContext(), 
                if (updated.isFalsePositive) "已标记为误报" else "已标记为真实跌倒", 
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteEvent(event: FallEvent) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定删除这条记录吗？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = com.falldetector.diedaobao.FallDetectionApp.instance
                    app.repository.fallEventDao.delete(event)
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearConfirm() {
        if (events.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("清空所有记录")
            .setMessage("确定删除所有 ${events.size} 条记录吗？此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = com.falldetector.diedaobao.FallDetectionApp.instance
                    app.repository.fallEventDao.deleteAll()
                    Toast.makeText(requireContext(), "已清空所有记录", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class FallEventAdapter(
    private val events: List<FallEvent>,
    private val onClick: (FallEvent) -> Unit
) : RecyclerView.Adapter<FallEventAdapter.ViewHolder>() {

    class ViewHolder(val binding: com.falldetector.diedaobao.databinding.ItemFallEventBinding) : 
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.falldetector.diedaobao.databinding.ItemFallEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        
        holder.binding.apply {
            tvTime.text = sdf.format(Date(event.timestamp))
            tvDetails.text = "冲击力: ${"%.1f".format(event.peakAcceleration)}g | 姿态: ${"%.0f".format(event.postureAngle)}°"
            
            when {
                !event.isConfirmed -> {
                    tvStatus.text = "⏳ 未确认"
                    tvStatus.setTextColor(0xFFFFA000.toInt())
                }
                event.isFalsePositive -> {
                    tvStatus.text = "✅ 误报"
                    tvStatus.setTextColor(0xFF4CAF50.toInt())
                }
                else -> {
                    tvStatus.text = if (event.notificationSent) "🚨 已通知" else "🚨 真实跌倒"
                    tvStatus.setTextColor(0xFFF44336.toInt())
                }
            }
            
            root.setOnClickListener { onClick(event) }
        }
    }

    override fun getItemCount() = events.size
}
