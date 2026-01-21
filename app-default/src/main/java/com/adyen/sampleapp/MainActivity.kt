package com.adyen.sampleapp

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import com.adyen.ipp.cardreader.api.ui.DeviceManagementActivity
import com.adyen.sampleapp.databinding.ActivityMainBinding
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview

/**
 * 主界面 Activity - Adyen POS 示例应用入口
 *
 * 【类的作用】
 * 这是示例应用的主 Activity，作为应用的入口页面，负责:
 * 1. 初始化应用界面和导航组件
 * 2. 提供设备管理入口（连接蓝牙读卡器）
 *
 * 【界面结构】
 * - 顶部: 连接设备按钮（跳转到 DeviceManagementActivity）
 * - 中部: NavHostFragment（承载 PaymentSampleAppFragment 等子页面）
 *
 * 【SDK 参考】
 * - DeviceManagementActivity: Adyen SDK 提供的设备管理界面，用于扫描和连接蓝牙读卡器
 *
 * @see PaymentSampleAppFragment
 * @see DeviceManagementActivity
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class MainActivity : AppCompatActivity() {

    /** 应用栏配置，用于 Navigation 组件的导航 UI */
    private lateinit var appBarConfiguration: AppBarConfiguration

    /** ViewBinding 实例，用于访问布局中的视图 */
    private lateinit var binding: ActivityMainBinding

    /**
     * Activity 创建时初始化
     *
     * 【流程】
     * 1. 设置 FLAG_SECURE 安全标志
     * 2. 加载布局文件
     * 3. 初始化 Navigation 导航组件
     * 4. 设置"连接设备"按钮点击事件
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ========== 安全设置 ==========
        // FLAG_SECURE: 禁止截屏、录屏、投屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // ========== 布局初始化 ==========
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ========== 导航组件初始化 ==========
        // 使用 Jetpack Navigation 组件管理 Fragment 导航
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)

        // ========== 设备连接按钮 ==========
        // 点击后跳转到 Adyen SDK 提供的设备管理界面
        // 用于扫描、配对和连接 NYC1 等蓝牙读卡器
        binding.buttonConnectDevice.setOnClickListener {
            DeviceManagementActivity.start(this)
        }
    }

    /**
     * 处理向上导航
     *
     * 【作用】
     * 当用户点击应用栏的返回按钮时，使用 Navigation 组件进行导航。
     * 如果 Navigation 无法处理，则回退到默认的 Activity 返回行为。
     *
     * @return true 表示导航已处理，false 表示未处理
     */
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}