package com.adyen.sampleapp

import android.util.Log
import com.adyen.ipp.api.authentication.AuthenticationProvider
import com.adyen.ipp.api.authentication.AuthenticationResponse
import com.adyen.ipp.api.authentication.MerchantAuthenticationService
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level
import org.json.JSONObject

/**
 * 商户认证服务 - Adyen SDK 会话认证实现
 *
 * 【类的作用】
 * 这是 MerchantAuthenticationService 的具体实现，负责处理 Adyen SDK 的认证流程。
 * 当 SDK 需要建立会话或会话过期时，会自动调用此服务进行认证。
 *
 * 【主流程】
 * 1. SDK 内部生成 setupToken（包含设备信息和认证请求）
 * 2. SDK 调用 authenticationProvider.authenticate(setupToken)
 * 3. 本服务将 setupToken 发送到 Adyen 后端 API
 * 4. Adyen 返回 sdkData（加密的会话凭证）
 * 5. 本服务将 sdkData 封装为 AuthenticationResponse 返回给 SDK
 * 6. SDK 使用 sdkData 完成会话初始化
 *
 * 【安全警告】
 * ⚠️ 本示例为演示目的，直接从客户端调用 Adyen API。
 * ⚠️ 生产环境中，API Key 应保存在服务器端，客户端应通过自己的后端中转请求。
 *
 * 【SDK 参考】
 * - MerchantAuthenticationService: 认证服务抽象基类
 * - AuthenticationProvider: 认证提供者接口
 * - AuthenticationResponse: 认证响应封装
 *
 * @see <a href="https://docs.adyen.com/point-of-sale/ipp-mobile/card-reader-android/integration-reader#session">Session Authentication | Adyen Docs</a>
 */
class MyAuthenticationService : MerchantAuthenticationService() {

    companion object {
        /** 日志标签 */
        private const val TAG = "MyAuthenticationService"
    }

    // ========== 配置参数 ==========
    // 这些值从 BuildConfig 读取，配置方式请参考 README

    /** Adyen API 密钥，用于后端 API 认证 */
    val apiKey = BuildConfig.EnvironmentApiKey

    /** 商户账号，用于标识商户身份 */
    val merchantAccount = BuildConfig.EnvironmentMerchantAccount

    /**
     * Adyen 认证 API 地址
     *
     * 【环境说明】
     * - 测试环境: https://softposconfig-test.adyen.com/softposconfig/v3/auth/certificate
     * - 生产环境: https://softposconfig-live.adyen.com/softposconfig/v3/auth/certificate
     *
     * 本示例仅用于测试环境
     */
    val apiUrl = "https://softposconfig-test.adyen.com/softposconfig/v3/auth/certificate"

    /**
     * 认证提供者实现
     *
     * 【作用】
     * 提供 AuthenticationProvider 接口的具体实现，SDK 通过此接口获取认证凭证。
     * 可以使用依赖注入框架（如 Hilt、Koin）在其他地方声明并注入。
     *
     * 【调用时机】
     * - SDK 首次初始化时
     * - 会话过期需要刷新时
     * - 切换终端/商户账号时
     */
    override val authenticationProvider: AuthenticationProvider
        get() = object : AuthenticationProvider {

            /**
             * 执行认证请求
             *
             * 【作用】
             * 将 SDK 生成的 setupToken 发送到 Adyen 后端，获取会话凭证 sdkData。
             *
             * 【流程】
             * 1. 构建 JSON 请求体（包含 merchantAccount 和 setupToken）
             * 2. 发送 POST 请求到 Adyen 认证 API
             * 3. 解析响应，提取 sdkData
             * 4. 封装为 AuthenticationResponse 返回
             *
             * 【参数说明】
             * @param setupToken SDK 生成的认证令牌，包含:
             *   - 设备唯一标识
             *   - 应用签名信息
             *   - 时间戳和随机数
             *
             * @return Result<AuthenticationResponse>
             *   - 成功: 包含 sdkData 的 AuthenticationResponse
             *   - 失败: 包含错误信息的 Result.failure
             *
             * @see AuthenticationResponse
             */
            override suspend fun authenticate(setupToken: String): Result<AuthenticationResponse> {
                Log.d(TAG, "authenticate() called with setupToken: $setupToken")
                val client = createOkHttpClient()

                // 步骤1: 构建请求体
                // 包含商户账号和 SDK 生成的 setupToken
                val jsonObject = JSONObject().apply {
                    put("merchantAccount", merchantAccount)
                    put("setupToken", setupToken)
                }
                val mediaType = "application/json".toMediaType()
                val requestBody = jsonObject.toString().toRequestBody(mediaType)

                // 步骤2: 构建并发送 POST 请求
                // x-api-key 用于 API 认证（生产环境应在服务器端添加）
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("x-api-key", apiKey)
                    .post(requestBody)
                    .build()

                // 步骤3: 异步处理响应
                // 使用 suspendCancellableCoroutine 将回调转换为协程
                return suspendCancellableCoroutine { continuation ->
                    client.newCall(request).enqueue(object : Callback {

                        // ========== 请求失败处理 ==========
                        // 网络错误、超时等情况
                        override fun onFailure(call: Call, e: IOException) {
                            Log.e(TAG, "authenticate() onFailure: ${e.message}", e)
                            continuation.resume(Result.failure(Throwable(e)))
                        }

                        // ========== 响应处理 ==========
                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful && response.body != null) {
                                // 步骤4: 解析响应，提取 sdkData
                                // sdkData 是加密的会话凭证，SDK 用于后续的支付操作
                                val json = JSONObject(response.body!!.string())
                                val sdkData = json.optString("sdkData")
                                Log.d(TAG, "authenticate() success, sdkData: $sdkData")

                                // 步骤5: 封装为 AuthenticationResponse 并返回
                                continuation.resume(
                                    Result.success(
                                        AuthenticationResponse.create(sdkData)
                                    )
                                )
                            } else {
                                // 认证失败：可能是 API Key 无效、商户账号错误等
                                Log.e(TAG, "authenticate() failed, response code: ${response.code}, body: ${response.body?.string()}")
                                continuation.resume(Result.failure(Throwable("error: ${response.code}")))
                            }
                        }
                    })
                }
            }

            /**
             * 创建 OkHttpClient 实例
             *
             * 【作用】
             * 创建配置了日志拦截器的 HTTP 客户端，用于发送认证请求。
             *
             * 【日志级别】
             * Level.BODY: 打印完整的请求和响应内容（仅用于调试）
             *
             * @return 配置好的 OkHttpClient 实例
             */
            private fun createOkHttpClient(): OkHttpClient {
                val logging = HttpLoggingInterceptor().apply {
                    setLevel(Level.BODY)
                }
                return OkHttpClient.Builder().apply {
                    addInterceptor(logging)
                }.build()
            }
        }
}
