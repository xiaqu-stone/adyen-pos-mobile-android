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

class MyAuthenticationService : MerchantAuthenticationService() {

    companion object {
        private const val TAG = "MyAuthenticationService"
    }

    /**
     *  ------------
     * | IMPORTANT |
     *  ------------
     *
     * This part of the code sends the `setupToken` to authenticate you and your app with Adyen.
     *
     * In this example, for simplicity and ease of use, we are using okhttp to connect directly to Adyen.
     * This is NOT how your app should be implemented! Your credentials and API Key should be kept secret and safe
     * withing your servers, and should only be used for direct server to server communication with Adyen.
     *
     * In a production environment you should send the `setupToken` to you server and forward the authentication
     * request from there to the Adyen server, and then return the `sdkData` result here.
     *
     * More information on the Docs page.
     * https://docs.adyen.com/point-of-sale/ipp-mobile/card-reader-android/integration-reader#session
     */

    // See README for how to define these.
    val apiKey = BuildConfig.EnvironmentApiKey
    val merchantAccount = BuildConfig.EnvironmentMerchantAccount

    // This app is intended to be used only against the TEST environment.
    val apiUrl = "https://softposconfig-test.adyen.com/softposconfig/v3/auth/certificate"
//    val apiUrl = "https://checkoutpos-test.adyen.com/checkoutpos/v3/auth/certificate/"

    // You can also declare this implementation somewhere else and pass it using your Dependency Injection system.
    override val authenticationProvider: AuthenticationProvider
        get() = object : AuthenticationProvider {
            // Adyen SDK 内部 在需要认证时（例如初始化Session或者Session过期时）自动生成 setupToken，并调用此方法
            // 需要把这个 setupToken 发送到 Adyen 后端 API 或者自己的服务器，然后获取到 sdkData，然后返回给 SDK
            override suspend fun authenticate(setupToken: String): Result<AuthenticationResponse> {
                Log.d(TAG, "authenticate() called with setupToken: $setupToken")
                val client = createOkHttpClient()

                // 1. 构建请求体，包含setupToken
                val jsonObject = JSONObject().apply {
                    put("merchantAccount", merchantAccount)
                    put("setupToken", setupToken)
                }
                val mediaType = "application/json".toMediaType()
                val requestBody = jsonObject.toString().toRequestBody(mediaType)

                // 2. 发送POST请求，将请求体发送给 Adyen 后端 API 或者自己的服务器
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("x-api-key", apiKey)
                    .post(requestBody)
                    .build()

                // 3. 处理响应，如果响应成功，则返回 AuthenticationResponse，否则返回错误
                return suspendCancellableCoroutine { continuation ->
                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.e(TAG, "authenticate() onFailure: ${e.message}", e)
                            continuation.resume(Result.failure(Throwable(e)))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful && response.body != null) {
                                val json = JSONObject(response.body!!.string())
                                val sdkData = json.optString("sdkData")
                                Log.d(TAG, "authenticate() success, sdkData: $sdkData")
                                // 将响应体中的 sdkData 转换为 AuthenticationResponse 并返回
                                continuation.resume(
                                    Result.success(
                                        AuthenticationResponse.create(sdkData)
                                    )
                                )
                            } else {
                                // 如果响应不成功，则返回错误
                                Log.e(TAG, "authenticate() failed, response code: ${response.code}, body: ${response.body?.string()}")
                                continuation.resume(Result.failure(Throwable("error: ${response.code}")))
                            }
                        }
                    })
                }
            }

            // 创建OkHttpClient，用于发送请求
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
