package com.falldetector.diedaobao.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.falldetector.diedaobao.R
import com.google.android.material.card.MaterialCardView

class PermissionActivity : AppCompatActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private data class PermissionItem(
        val name: String,
        val desc: String,
        val permission: String,
        val required: Boolean = true
    )

    private val permissionItems = mutableListOf<PermissionItem>().apply {
        add(PermissionItem("📱 传感器", "检测跌倒动作", Manifest.permission.BODY_SENSORS))
        add(PermissionItem("📞 电话", "紧急呼叫", Manifest.permission.CALL_PHONE))
        add(PermissionItem("📟 读取手机状态", "通话功能", Manifest.permission.READ_PHONE_STATE))
        add(PermissionItem("💬 短信", "发送位置通知", Manifest.permission.SEND_SMS))
        add(PermissionItem("📍 精确定位", "获取准确位置", Manifest.permission.ACCESS_FINE_LOCATION))
        add(PermissionItem("📍 大致定位", "辅助定位", Manifest.permission.ACCESS_COARSE_LOCATION, false))
        // Android 10+ 后台定位（小米/华为等需要"始终允许"才能后台获取位置）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(PermissionItem("📍 始终允许定位", "后台持续获取位置（必须开启）", Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem("🔔 通知", "显示告警通知", Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            refreshUI()
        }

        setupUI()
    }

    private fun setupUI() {
        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        val title = TextView(this).apply {
            text = "🔐 权限设置"
            textSize = 28f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)

        val subtitle = TextView(this).apply {
            text = "跌倒宝需要以下权限才能正常工作"
            textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 0, 0, 32)
        }
        container.addView(subtitle)

        permissionItems.forEach { item ->
            val card = createPermissionCard(item)
            container.addView(card)
        }

        // 特殊权限卡片
        container.addView(createSpecialPermissionCard())

        val btnGrantAll = Button(this).apply {
            text = "一键申请所有权限"
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 24, 32, 24)
            setOnClickListener { requestAllPermissions() }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 32 }
        container.addView(btnGrantAll, btnParams)

        val btnDone = Button(this).apply {
            text = "完成"
            setOnClickListener { finish() }
        }
        container.addView(btnDone, btnParams)

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun createPermissionCard(item: PermissionItem): MaterialCardView {
        val granted = ContextCompat.checkSelfPermission(this, item.permission) == PackageManager.PERMISSION_GRANTED

        return MaterialCardView(this).apply {
            radius = 16f
            cardElevation = 4f
            setContentPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = params

            val layout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(context).apply {
                text = item.name + if (item.required) " *" else ""
                textSize = 18f
            }

            val descView = TextView(context).apply {
                text = item.desc + "\n状态: ${if (granted) "✅ 已授权" else "❌ 未授权"}"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
            }

            textLayout.addView(nameView)
            textLayout.addView(descView)

            val btn = Button(context).apply {
                text = if (granted) "已开启" else "去设置"
                isEnabled = !granted
                setOnClickListener {
                    if (shouldShowRequestPermissionRationale(item.permission)) {
                        permissionLauncher.launch(arrayOf(item.permission))
                    } else {
                        openAppSettings()
                    }
                }
            }

            layout.addView(textLayout)
            layout.addView(btn)
            addView(layout)
        }
    }

    /** 特殊权限：始终允许定位、锁屏显示、后台弹出界面、电池优化 */
    private fun createSpecialPermissionCard(): MaterialCardView {
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        // 检查是否有"始终允许"定位权限
        val bgLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        return MaterialCardView(this).apply {
            radius = 16f
            cardElevation = 4f
            setContentPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = params

            val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            // 标题
            val title = TextView(context).apply {
                text = "🔐 特殊权限（需手动开启）"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 8, 0, 16)
            }
            container.addView(title)

            // ========== 权限0：始终允许定位（小米/华为等必须开启！）==========
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bgLocationRow = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 8, 0, 8)
                    val titleRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val name = TextView(context).apply {
                            text = "📍 始终允许定位"
                            textSize = 15f
                            setTextColor(if (bgLocationGranted) 0xFF4CAF50.toInt() else 0xFFD32F2F.toInt())
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val btn = Button(context).apply {
                            text = if (bgLocationGranted) "✅ 已开启" else "去设置"
                            isEnabled = !bgLocationGranted
                            setOnClickListener {
                                // Android 10+ 不能直接申请后台位置，需要先有前台位置再引导到设置
                                // 先检查前台位置权限
                                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (!hasFine) {
                                    // 先申请前台位置
                                    permissionLauncher.launch(arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    ))
                                } else {
                                    // 前台位置已授予，引导到应用设置页开启"始终允许"
                                    AlertDialog.Builder(context)
                                        .setTitle("📍 始终允许定位")
                                        .setMessage("跌倒宝需要\"始终允许\"定位权限，才能在后台持续获取位置并上传。\n\n请在设置页面找到「位置权限」→ 选择「始终允许」。")
                                        .setPositiveButton("去设置") { _, _ ->
                                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            })
                                        }
                                        .setNegativeButton("稍后", null)
                                        .show()
                                }
                            }
                        }
                        addView(name)
                        addView(btn)
                    }
                    val desc = TextView(context).apply {
                        text = "⚠️ 必须开启！否则App在后台时无法获取位置，子女端看不到你的实时位置。小米/华为/OPPO等手机默认只给「使用中允许」，必须手动改为「始终允许」。"
                        textSize = 13f
                        setTextColor(0xFFD32F2F.toInt())
                    }
                    addView(titleRow)
                    addView(desc)
                }
                container.addView(bgLocationRow)
            }

            // ========== 权限1：锁屏显示权限（SYSTEM_ALERT_WINDOW）==========
            val overlayRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val name = TextView(context).apply {
                    text = "🔲 锁屏显示权限\n锁屏时弹出全屏告警界面"
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btn = Button(context).apply {
                    text = if (overlayGranted) "✅ 已开启" else "去设置"
                    isEnabled = !overlayGranted
                    setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
                addView(name)
                addView(btn)
            }
            container.addView(overlayRow)

            // ========== 权限2：后台弹出界面（厂商特殊权限）==========
            val bgRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
                val name = TextView(context).apply {
                    text = "📱 后台弹出界面"
                    textSize = 15f
                }
                val desc = TextView(context).apply {
                    text = "小米/华为/OPPO等厂商特有，后台也能弹出界面"
                    textSize = 13f
                    setTextColor(0xFF888888.toInt())
                }
                val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END }
                val tutorialBtn = Button(context).apply {
                    text = "查看教程"
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle("后台弹出界面权限教程")
                            .setMessage(
                                "这是小米/华为/OPPO等厂商的特殊权限。\n\n" +
                                "开启方法：\n" +
                                "1. 打开手机「设置」\n" +
                                "2. 找到「应用管理」或「应用与权限」\n" +
                                "3. 找到「跌倒宝」\n" +
                                "4. 找到「权限管理」\n" +
                                "5. 找到「后台弹出界面」设为「允许」\n" +
                                "6. 找到「锁屏显示」设为「允许」\n\n" +
                                "不同品牌路径略有差异"
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }
                val openBtn = Button(context).apply {
                    text = "去设置"
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }
                }
                btnRow.addView(tutorialBtn)
                btnRow.addView(openBtn)
                addView(name)
                addView(desc)
                addView(btnRow)
            }
            container.addView(bgRow)

            // ========== 权限3：电池优化白名单 ==========
            val batteryRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
                val name = TextView(context).apply {
                    text = "🔋 电池优化\n允许后台持续运行检测跌倒"
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btn = Button(context).apply {
                    text = if (batteryGranted) "✅ 已开启" else "去设置"
                    isEnabled = !batteryGranted
                    setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }
                    }
                }
                addView(name)
                addView(btn)
            }
            container.addView(batteryRow)

            // ========== 权限4：开机自启（厂商特殊权限）==========
            val autoStartRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val name = TextView(context).apply {
                        text = "🚀 开机自启"
                        textSize = 15f
                    }
                    val openBtn = Button(context).apply {
                        text = "去设置"
                        setOnClickListener {
                            // 尝试各厂商自启动管理页面（按市场份额排序）
                            val intents = listOfNotNull(
                                // 小米/红米 MIUI
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.miui.securitycenter",
                                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                                    )
                                },
                                // OPPO/Realme ColorOS
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.coloros.safecenter",
                                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                                    )
                                },
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.coloros.safecenter",
                                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                                    )
                                },
                                // Vivo/Funtouch OS
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.vivo.permissionmanager",
                                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                                    )
                                },
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.iqoo.secure",
                                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                                    )
                                },
                                // 华为/鸿蒙 EMUI
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.huawei.systemmanager",
                                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                                    )
                                },
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.huawei.systemmanager",
                                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                                    )
                                },
                                // 三星
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.samsung.android.lool",
                                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                                    )
                                },
                                // OnePlus/OxygenOS
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.oneplus.security",
                                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                                    )
                                },
                                // 荣耀
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.huawei.systemmanager",
                                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                                    )
                                },
                                // 魅族 Flyme
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.meizu.safe",
                                        "com.meizu.safe.permission.SmartPermissionsActivity"
                                    )
                                },
                                // 联想 ZUI
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.lenovo.security",
                                        "com.lenovo.security.firewall.StartupActivity"
                                    )
                                },
                                // 中兴 MyOS
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.zte.heartyservice",
                                        "com.zte.heartyservice.autoboot.BootAppListActivity"
                                    )
                                }
                            )
                            // 逐个尝试，遇到能打开的就停下
                            for (intent in intents) {
                                try {
                                    startActivity(intent)
                                    return@setOnClickListener
                                } catch (_: Exception) { }
                            }
                            // 全部失败：打开应用详情页面
                            try {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                            } catch (_: Exception) {
                                startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        }
                    }
                    addView(name)
                    addView(openBtn)
                }
                val desc = TextView(context).apply {
                    text = "⚠️ 必须开启！否则手机重启后检测服务不会自动启动，老人将失去保护"
                    textSize = 13f
                    setTextColor(0xFFD32F2F.toInt())
                }
                addView(titleRow)
                addView(desc)
            }
            container.addView(autoStartRow)

            // ========== 权限4.5：无障碍服务（远程协助用）==========
            val accRow = createAccessibilityRow()
            container.addView(accRow)

            // ========== 权限5：通知设置（锁屏通知/横幅通知）==========
            val notifRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val name = TextView(context).apply {
                        text = "🔔 通知设置"
                        textSize = 15f
                    }
                    val openBtn = Button(context).apply {
                        text = "去设置"
                        setOnClickListener {
                            // 尝试各厂商通知设置页面
                            val notifIntents = listOfNotNull(
                                // 通用：应用通知设置（Android 8+）
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        putExtra(Settings.EXTRA_CHANNEL_ID, "emergency")
                                    }
                                } else null,
                                // 应用通知总设置
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    } else {
                                        putExtra("app_package", context.packageName)
                                        putExtra("app_uid", context.applicationInfo.uid)
                                    }
                                },
                                // 小米 MIUI 通知管理
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.miui.securitycenter",
                                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                                    )
                                    putExtra("extra_pkgname", context.packageName)
                                },
                                // 华为 通知管理
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.huawei.systemmanager",
                                        "com.huawei.systemmanager.manageoverlay.dialog.UsageAccessDialog"
                                    )
                                },
                                // OPPO 通知管理
                                Intent().apply {
                                    component = android.content.ComponentName(
                                        "com.coloros.safecenter",
                                        "com.coloros.safecenter.permission.notification.NotificationPermissionActivity"
                                    )
                                }
                            )
                            for (intent in notifIntents) {
                                try {
                                    startActivity(intent)
                                    return@setOnClickListener
                                } catch (_: Exception) { }
                            }
                            // fallback
                            try {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                            } catch (_: Exception) {
                                startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        }
                    }
                    addView(name)
                    addView(openBtn)
                }
                val desc = TextView(context).apply {
                    text = "⚠️ 必须开启「锁屏通知」「横幅通知」！否则跌倒报警弹不出来"
                    textSize = 13f
                    setTextColor(0xFFD32F2F.toInt())
                }
                val tutorialBtn = Button(context).apply {
                    text = "查看教程"
                    setOnClickListener {
                        val brand = Build.MANUFACTURER.lowercase()
                        val brandGuide = when {
                            brand.contains("xiaomi") || brand.contains("redmi") ->
                                "【小米/红米 MIUI】\n1. 设置 → 通知管理 → 跌倒宝\n2. 开启「允许通知」\n3. 开启「锁屏通知」→ 全部显示\n4. 开启「横幅通知」→ 允许\n5. 优先级设为「紧急」"
                            brand.contains("huawei") || brand.contains("honor") ->
                                "【华为/荣耀 EMUI/鸿蒙】\n1. 设置 → 通知 → 跌倒宝\n2. 开启「允许通知」\n3. 开启「锁屏通知」→ 显示全部\n4. 开启「横幅通知」\n5. 通知方式设为「紧急」"
                            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                                "【OPPO/Realme/一加 ColorOS】\n1. 设置 → 通知与状态栏 → 跌倒宝\n2. 开启「允许通知」\n3. 开启「锁屏通知」\n4. 开启「横幅通知」\n5. 重要性设为「紧急」"
                            brand.contains("vivo") || brand.contains("iqoo") ->
                                "【Vivo/iQOO FunTouch】\n1. 设置 → 通知与状态栏 → 跌倒宝\n2. 开启「允许通知」\n3. 开启「锁屏通知」→ 显示通知\n4. 开启「横幅通知」\n5. 优先级设为「紧急」"
                            brand.contains("samsung") ->
                                "【三星 OneUI】\n1. 设置 → 通知 → 跌倒宝\n2. 开启「允许通知」\n3. 开启「锁屏通知」\n4. 通知类别设为「紧急」"
                            brand.contains("meizu") ->
                                "【魅族 Flyme】\n1. 设置 → 通知管理 → 跌倒宝\n2. 开启「允许通知」\n3. 开启「锁屏显示」\n4. 开启「横幅通知」\n5. 优先级设为「紧急」"
                            else ->
                                "【通用设置方法】\n1. 设置 → 应用管理 → 跌倒宝\n2. 点击「通知」\n3. 开启「允许通知」\n4. 开启「锁屏通知」\n5. 开启「横幅通知」\n6. 优先级设为「紧急」"
                        }
                        AlertDialog.Builder(context)
                            .setTitle("🔔 通知设置教程")
                            .setMessage(
                                "跌倒报警需要通知权限才能弹出！\n\n" +
                                brandGuide +
                                "\n\n💡 所有通知类型都要开，否则老人跌倒时可能看不到报警！"
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }
                addView(titleRow)
                addView(desc)
                addView(tutorialBtn)
            }
            container.addView(notifRow)

            addView(container)
        }
    }

    /** 无障碍服务权限卡片（远程协助需要，开启守护前设置好） */
    private fun createAccessibilityRow(): LinearLayout {
        val enabled = com.falldetector.diedaobao.assist.RemoteAssistService.isAccessibilityEnabled(this)
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
            
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val name = TextView(context).apply {
                    text = "🖐️ 远程协助（无障碍）"
                    textSize = 15f
                    setTextColor(if (enabled) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
                }
                val btn = Button(context).apply {
                    text = if (enabled) "✅ 已开启" else "去设置"
                    isEnabled = !enabled
                    setOnClickListener {
                        val intent = com.falldetector.diedaobao.assist.RemoteAssistManager.openAccessibilitySettings(context)
                        startActivity(intent)
                    }
                }
                addView(name)
                addView(btn)
            }
            val desc = TextView(context).apply {
                text = "可选。开启后，子女可通过亲情守护App远程操作您的手机。建议在开启守护前先设置好。开通后永久有效，不需要重复操作。"
                textSize = 13f
                setTextColor(0xFF888888.toInt())
            }
            addView(titleRow)
            addView(desc)
        }
    }

    private fun requestAllPermissions() {
        val missing = permissionItems
            .filter { ContextCompat.checkSelfPermission(this, it.permission) != PackageManager.PERMISSION_GRANTED }
            .map { it.permission }
            .toTypedArray()

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            Toast.makeText(this, "运行时权限已授权", Toast.LENGTH_SHORT).show()
        }

        // 检查悬浮窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("🔲 锁屏显示权限")
                .setMessage("需要「悬浮窗」权限才能在锁屏时弹出告警界面。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("稍后", null)
                .show()
            return
        }

        // 检查电池优化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("🔋 电池优化权限")
                    .setMessage("需要将跌倒宝设为「无限制」才能后台持续运行。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("稍后", null)
                    .show()
                return
            }
        }

        Toast.makeText(this, "所有权限已就绪！", Toast.LENGTH_LONG).show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun refreshUI() {
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
        // 记录无障碍服务状态（用于 HomeFragment 检测被杀后提示重新开启）
        val accEnabled = com.falldetector.diedaobao.assist.RemoteAssistService.isAccessibilityEnabled(this)
        if (accEnabled) {
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putBoolean("accessibility_was_enabled", true).apply()
        }
    }
}
