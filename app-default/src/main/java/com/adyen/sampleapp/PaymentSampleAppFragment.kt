package com.adyen.sampleapp

import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.net.Uri
import android.os.Bundle
import java.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.adyen.ipp.api.InPersonPayments
import com.adyen.ipp.api.diagnosis.DiagnosisRequest
import com.adyen.ipp.api.payment.PaymentInterface
import com.adyen.ipp.api.payment.PaymentInterfaceType
import com.adyen.ipp.api.payment.TransactionRequest
import com.adyen.ipp.api.ui.MerchantUiParameters
import com.adyen.sampleapp.databinding.FragmentPaymentBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.logcat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import org.json.JSONObject
import androidx.core.net.toUri

/**
 * 支付示例应用 Fragment - Adyen In-Person Payments SDK 演示
 *
 * 【类的作用】
 * 这是一个演示 Adyen POS 移动端 SDK 支付功能的 Fragment，展示了如何：
 * 1. 使用 NYC1 读卡器进行刷卡支付
 * 2. 使用 Tap to Pay (T2P) 进行 NFC 感应支付
 * 3. 清除当前支付会话
 * 4. 执行设备诊断
 *
 * 【主流程】
 * 1. Fragment 初始化 → 注册支付结果回调 (resultLauncher)
 * 2. 用户点击支付按钮 → 获取支付接口 (PaymentInterface)
 * 3. 构建 NEXO 请求报文 → 调用 SDK 执行交易
 * 4. SDK 启动支付 UI → 用户完成支付
 * 5. 支付结果通过 resultLauncher 回调返回
 *
 * 【SDK 参考】
 * - InPersonPayments: SDK 主入口类，提供所有支付相关 API
 * - PaymentInterface: 支付接口抽象，支持读卡器和 Tap to Pay 两种类型
 * - TransactionRequest: 交易请求，封装 NEXO 格式的支付报文
 * - DiagnosisRequest: 诊断请求，用于设备健康检查
 *
 * @see <a href="https://docs.adyen.com/point-of-sale/ipp-mobile/">Adyen IPP Mobile Documentation</a>
 */
class PaymentSampleAppFragment : Fragment() {

    /** 日志标签，用于 logcat 输出 */
    private val logTag = "Transaction"

    /** ViewBinding 实例，用于访问布局中的视图 */
    private lateinit var binding: FragmentPaymentBinding

    /**
     * 支付结果回调注册器
     *
     * 【作用】
     * 使用 Activity Result API 注册支付结果回调，当支付流程完成后，
     * SDK 会通过此回调返回支付结果。
     *
     * 【回调处理】
     * - onSuccess: 支付流程正常完成（不代表交易成功）
     *   - paymentResult.success = true → 交易成功
     *   - paymentResult.success = false → 交易失败（如卡被拒绝）
     *   - paymentResult.data: Base64 编码的 NEXO 响应报文
     * - onFailure: 支付流程异常（如用户取消、网络错误等）
     *
     * @see InPersonPayments.registerForPaymentResult
     */
    private val resultLauncher = InPersonPayments.registerForPaymentResult(this) { result ->
        val resultText = result.fold(
            onSuccess = { paymentResult ->
                // paymentResult.data 是 Base64 编码的 NEXO 响应 JSON
                val decodedResult = String(Base64.getDecoder().decode(paymentResult.data))
                val errorMsg = "Caller Payment Result: \n $decodedResult"
                logcat(tag = logTag) { errorMsg }
                binding.tvMessage.text = errorMsg
                if (paymentResult.success) "Payment Successful" else "Payment Failed"
            },
            onFailure = { error ->
                val errorMsg = "Result failed with: ${error.message}"
                logcat(tag = logTag) { errorMsg }
                binding.tvMessage.text = errorMsg
                "Payment Failed"
            },
        )
        // 支付成功 → Toast 显示 "Payment Successful"
        // 支付失败 → Toast 显示 "Payment Failed"
        Toast.makeText(requireContext(), resultText, Toast.LENGTH_LONG).show()
    }

    /** 协程 Job，用于管理协程生命周期 */
    private val job = Job()

    /** UI 协程作用域，在主线程执行协程，用于调用 SDK 的挂起函数 */
    private val uiScope = CoroutineScope(Dispatchers.Main + job)

    /**
     * 创建 Fragment 视图
     *
     * 使用 ViewBinding 加载布局文件 fragment_payment.xml
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 视图创建完成后初始化按钮点击事件
     *
     * 【按钮功能】
     * - buttonPayNyc1: 使用 NYC1 读卡器支付（蓝牙连接的外部读卡器）
     * - buttonPayT2p: 使用 Tap to Pay 支付（手机内置 NFC）
     * - buttonClearSession: 清除当前会话（用于重新初始化 SDK）
     * - buttonDiagnosis: 执行设备诊断（检查终端状态和网络连接）
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ========== NYC1 读卡器支付按钮 ==========
        // NYC1 是 Adyen 的蓝牙读卡器，支持芯片卡、磁条卡和 NFC 支付
        binding.buttonPayNyc1.setOnClickListener {
            uiScope.launch {
                // 步骤1: 获取读卡器支付接口
                // PaymentInterfaceType.createCardReaderType() 创建读卡器类型
                // getPaymentInterface() 会检查蓝牙权限并初始化读卡器连接
                InPersonPayments.getPaymentInterface(PaymentInterfaceType.createCardReaderType())
                    .fold(
                        onSuccess = { nyc1Interface ->
                            // 步骤2: 获取成功，启动支付流程
                            startPayment(nyc1Interface)
                        },
                        onFailure = {
                            // 失败原因通常是：蓝牙权限未授予、读卡器未连接等
                            Toast.makeText(
                                requireContext(),
                                R.string.toast_no_bt_permissions,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
            }
        }

        // ========== Tap to Pay 支付按钮 ==========
        // Tap to Pay 使用手机内置 NFC 功能，无需外部读卡器
        // 要求: Android 9+, NFC 功能, Google Play 服务
        binding.buttonPayT2p.setOnClickListener {
            uiScope.launch {
                // 步骤1: 获取 Tap to Pay 支付接口
                // PaymentInterfaceType.createTapToPayType() 创建 T2P 类型
                // 会检查设备 NFC 功能和相关权限
                InPersonPayments.getPaymentInterface(PaymentInterfaceType.createTapToPayType())
                    .fold(
                        onSuccess = { t2pInterface ->
                            // 步骤2: 获取成功，启动支付流程
                            startPayment(t2pInterface)
                        },
                        onFailure = {
                            // 失败原因通常是：设备不支持 NFC、NFC 未开启等
                            Toast.makeText(
                                requireContext(),
                                R.string.toast_t2p_interface_creation_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
            }
        }

        // ========== 清除会话按钮 ==========
        // 清除 SDK 内部缓存的会话信息，通常用于:
        // - 切换商户账号
        // - 解决认证问题
        // - 重新初始化 SDK
        binding.buttonClearSession.setOnClickListener {
            uiScope.launch {
                InPersonPayments.clearSession()
                logcat(logTag) { "perform clear session" }
            }
        }

        // ========== 诊断按钮 ==========
        // 执行终端诊断，检查设备状态和网络连接
        binding.buttonDiagnosis.setOnClickListener {
            uiScope.launch {
                startDiagnosisDevice()
            }
        }
    }

    /**
     * 执行设备诊断
     *
     * 【作用】
     * 向 Adyen 后端发送诊断请求，检查:
     * - 终端配置是否正确
     * - 网络连接是否正常
     * - 认证状态是否有效
     *
     * 【流程】
     * 1. 获取 Installation ID (终端唯一标识)
     * 2. 构建 NEXO 诊断请求报文
     * 3. 调用 SDK 执行诊断
     * 4. 解析并输出诊断结果
     *
     * @see InPersonPayments.performDiagnosis
     * @see DiagnosisRequest
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun startDiagnosisDevice(){
        // 步骤1: 获取 Installation ID (POI ID)
        // Installation ID 是终端在 Adyen 系统中的唯一标识
        // 格式通常为: S1F2-000xxx (由 Adyen 后台分配)
        val nexoRequest: String = generateDiagnosisNexoRequest(
            poiId = InPersonPayments.getInstallationId().getOrNull() ?: "UNKNOWN",
        )
        logcat(logTag) { "NexoRequest:\n$nexoRequest" }

        // 步骤2: 创建诊断请求对象
        val diagnosisRequest = DiagnosisRequest.create(nexoRequest).getOrThrow()

        // 步骤3: 执行诊断请求
        // SDK 会向 Adyen 后端发送请求并返回结果
        val result = InPersonPayments.performDiagnosis(diagnosisRequest)

        // 步骤4: 解析诊断结果
        logcat(logTag){ "Diagnosis result:\n${result.getOrNull()}" }
        if (result.isSuccess){
            // 诊断结果是 Base64 编码的 NEXO 响应，需要解码
            val attestationResult = String(Base64.getDecoder().decode(result.getOrThrow().data))
            logcat(logTag) { "attestationResult: \n$attestationResult" }
            val diagnosisResp = JSONObject(attestationResult).getJSONObject("SaleToPOIResponse")
                .getJSONObject("DiagnosisResponse").getJSONObject("Response")
            val errorCondition = diagnosisResp.getString("ErrorCondition")
            val result = diagnosisResp.getString("Result")
            val addResp = diagnosisResp.getString("AdditionalResponse")

            val uri = "https://dummy?$addResp".toUri()
            val attestationStatusBase64 = uri.getQueryParameter("attestationStatus")
            val attestationStatus =
                String(Base64.getDecoder().decode(attestationStatusBase64), Charsets.UTF_8)
            logcat(logTag) { "attestationStatus: \n$attestationStatus" }
            binding.tvMessage.text = "attestationStatus: \n$attestationStatus\nerrorCondition:$errorCondition\nresult:$result"

        }
    }


    /**
     * 启动支付交易
     *
     * 【作用】
     * 使用指定的支付接口（读卡器或 Tap to Pay）执行支付交易
     *
     * 【流程】
     * 1. 构建 NEXO 支付请求报文（包含金额、货币、交易ID等）
     * 2. 调用 SDK 的 performTransaction 方法
     * 3. SDK 启动支付 UI，引导用户完成支付
     * 4. 支付完成后通过 resultLauncher 回调返回结果
     *
     * 【参数说明】
     * @param paymentInterface 支付接口，可以是:
     *   - CardReader: NYC1 等蓝牙读卡器
     *   - TapToPay: 手机内置 NFC
     *
     * @see InPersonPayments.performTransaction
     * @see TransactionRequest
     * @see MerchantUiParameters
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun startPayment(paymentInterface: PaymentInterface<*>) {
        // 步骤1: 构建 NEXO 支付请求报文
        // 这里使用固定金额 5 USD 作为演示
        val nexoRequest: String = generateNexoRequest(
            requestedAmount = "5",              // 交易金额
            currency = "USD",                    // 货币代码 (ISO 4217)
            poiId = InPersonPayments.getInstallationId().getOrNull() ?: "UNKNOWN"
        )
        logcat(logTag) { "NexoRequest:\n$nexoRequest" }

        // 步骤2: 调用 SDK 执行交易
        // performTransaction 会启动支付 UI 并处理整个支付流程
        InPersonPayments.performTransaction(
            context = requireContext(),                                      // Context 用于启动支付 Activity
            paymentInterface = paymentInterface,                             // 支付接口 (读卡器/T2P)
            transactionRequest = TransactionRequest.create(nexoRequest).getOrThrow(), // NEXO 请求
            paymentLauncher = resultLauncher,                               // 结果回调
            merchantUiParameters = MerchantUiParameters.create()            // UI 参数（可自定义）
        )
        // 注意: 此方法返回后，支付流程已经启动
        // 支付结果将通过 resultLauncher 异步回调
    }

    // Kotlin 语言的特性，用于定义类的"伴生对象"
    // 用于存放不依赖实例的成员
    companion object {

        /** 日期格式化器，用于生成 NEXO 报文中的时间戳 (ISO 8601 格式) */
        private val DATE_FORMAT =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        /**
         * 生成 NEXO 诊断请求报文
         *
         * 【NEXO 协议】
         * NEXO 是 POS 终端的国际标准协议，Adyen 使用 NEXO JSON 格式进行通信
         *
         * 【报文结构】
         * - MessageHeader: 消息头，包含协议版本、消息类型等
         *   - MessageCategory: "Diagnosis" 表示诊断请求
         *   - ServiceID: 请求唯一标识（最大10位）
         *   - SaleID: 收银系统标识
         *   - POIID: 终端标识 (Installation ID)
         * - DiagnosisRequest: 诊断请求体
         *   - HostDiagnosisFlag: true 表示检查与后端的连接
         *
         * @param serviceId 服务请求ID，用于匹配请求和响应
         * @param saleId 收银系统标识
         * @param poiId 终端标识 (Installation ID)
         * @return NEXO JSON 格式的诊断请求报文
         *
         * @see <a href="https://docs.adyen.com/point-of-sale/diagnostics/request-diagnosis">Make a diagnosis request | Adyen Docs</a>
         */
        private fun generateDiagnosisNexoRequest(
            serviceId: String = UUID.randomUUID().toString(),
            saleId: String = "AndroidSampleApp",
            poiId: String,
        ): String {

            val timeStamp = DATE_FORMAT.format(Date())
            val maxServiceIdSize = 10  // NEXO 规范限制 ServiceID 最大长度

            return """
                |{
                |  "SaleToPOIRequest": {
                |    "MessageHeader": {
                |      "ProtocolVersion": "3.0",
                |      "MessageClass": "Service",
                |      "MessageCategory": "Diagnosis",
                |      "MessageType": "Request",
                |      "ServiceID": "${serviceId.take(maxServiceIdSize)}",
                |      "SaleID": "$saleId",
                |      "POIID": "$poiId"
                |    },
                |    "DiagnosisRequest": {
                |      "HostDiagnosisFlag": true
                |    }
                |  }
                |}
            """.trimMargin("|")
        }

        /**
         * 生成 NEXO 支付请求报文
         *
         * 【NEXO 支付报文结构】
         * - MessageHeader: 消息头
         *   - MessageCategory: "Payment" 表示支付请求
         *   - ServiceID: 请求唯一标识
         *   - SaleID: 收银系统标识
         *   - POIID: 终端标识
         * - PaymentRequest: 支付请求体
         *   - SaleData: 销售数据
         *     - SaleTransactionID: 商户交易ID和时间戳
         *   - PaymentTransaction: 交易信息
         *     - AmountsReq: 金额信息
         *       - Currency: 货币代码 (ISO 4217，如 USD, EUR, CNY)
         *       - RequestedAmount: 请求金额
         *
         * @param serviceId 服务请求ID，用于匹配请求和响应
         * @param saleId 收银系统标识
         * @param transactionID 商户交易ID，用于对账
         * @param poiId 终端标识 (Installation ID)
         * @param currency 货币代码 (ISO 4217)
         * @param requestedAmount 请求金额
         * @return NEXO JSON 格式的支付请求报文
         *
         * @see <a href="https://docs.adyen.com/point-of-sale/design-your-integration/terminal-api/terminal-api-reference/">Terminal API Reference</a>
         */
        private fun generateNexoRequest(
            serviceId: String = UUID.randomUUID().toString(),
            saleId: String = "AndroidSampleApp",
            transactionID: String = "SampleApp-AndroidTx",
            poiId: String,
            currency: String,
            requestedAmount: String,
        ): String {

            val timeStamp = DATE_FORMAT.format(Date())
            val maxServiceIdSize = 10  // NEXO 规范限制 ServiceID 最大长度

            return """
                |{
                |  "SaleToPOIRequest": {
                |    "MessageHeader": {
                |      "ProtocolVersion": "3.0",
                |      "MessageClass": "Service",
                |      "MessageCategory": "Payment",
                |      "MessageType": "Request",
                |      "ServiceID": "${serviceId.take(maxServiceIdSize)}",
                |      "SaleID": "$saleId",
                |      "POIID": "$poiId"
                |    },
                |    "PaymentRequest": {
                |      "SaleData": {
                |        "SaleTransactionID": {
                |          "TransactionID": "$transactionID",
                |          "TimeStamp": "$timeStamp"
                |        }
                |      },
                |      "PaymentTransaction": {
                |        "AmountsReq": {
                |          "Currency": "$currency",
                |          "RequestedAmount": $requestedAmount
                |        }
                |      }
                |    }
                |  }
                |}
            """.trimMargin("|")
        }
    }
}