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
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class PaymentSampleAppFragment : Fragment() {

    private val logTag = "Transaction"

    private lateinit var binding: FragmentPaymentBinding

    private val resultLauncher = InPersonPayments.registerForPaymentResult(this) { result ->
        val resultText = result.fold(
            onSuccess = { paymentResult ->
                val decodedResult = String(Base64.getDecoder().decode(paymentResult.data))
                logcat(tag = logTag) { "Caller Payment Result: \n $decodedResult" }
                if (paymentResult.success) "Payment Successful" else "Payment Failed"
            },
            onFailure = { error ->
                logcat(tag = logTag) { "Result failed with: ${error.message}" }
                "Payment Failed"
            },
        )
        Toast.makeText(requireContext(), resultText, Toast.LENGTH_LONG).show()
    }

    private val job = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonPayNyc1.setOnClickListener {
            uiScope.launch {
                InPersonPayments.getPaymentInterface(PaymentInterfaceType.createCardReaderType())
                    .fold(
                        onSuccess = { nyc1Interface ->
                            startPayment(nyc1Interface)
                        },
                        onFailure = {
                            Toast.makeText(
                                requireContext(),
                                R.string.toast_no_bt_permissions,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
            }
        }
        binding.buttonPayT2p.setOnClickListener {
            uiScope.launch {
                InPersonPayments.getPaymentInterface(PaymentInterfaceType.createTapToPayType())
                    .fold(
                        onSuccess = { t2pInterface ->
                            startPayment(t2pInterface)
                        },
                        onFailure = {
                            Toast.makeText(
                                requireContext(),
                                R.string.toast_t2p_interface_creation_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
            }
        }

        binding.buttonClearSession.setOnClickListener {
            uiScope.launch {
                InPersonPayments.clearSession()
                logcat(logTag){"perform clear session"}
            }
        }

        binding.buttonDiagnosis.setOnClickListener {
            uiScope.launch {
                startDiagnosisDevice()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun startDiagnosisDevice(){
        val nexoRequest: String = generateDiagnosisNexoRequest(
            poiId = InPersonPayments.getInstallationId().getOrNull() ?: "UNKNOWN",
        )
        logcat(logTag) { "NexoRequest:\n$nexoRequest" }

        val diagnosisRequest = DiagnosisRequest.create(nexoRequest).getOrThrow()

        val result = InPersonPayments.performDiagnosis(diagnosisRequest)

        logcat(logTag){ "Diagnosis result:\n${result.getOrNull()}" }
        if (result.isSuccess){
            val attestationResult = String(Base64.getDecoder().decode(result.getOrThrow().data))
            logcat(logTag){ "attestationResult: \n$attestationResult" }
            val diagnosisResp = JSONObject(attestationResult).getJSONObject("SaleToPOIResponse")
                .getJSONObject("DiagnosisResponse").getJSONObject("Response")
            val errorCondition = diagnosisResp.getString("ErrorCondition")
            val result = diagnosisResp.getString("Result")
            val addResp = diagnosisResp.getString("AdditionalResponse")

            val uri = "https://dummy?$addResp".toUri()
            val attestationStatusBase64 = uri.getQueryParameter("attestationStatus")
            val attestationStatus = String(Base64.getDecoder().decode(attestationStatusBase64),Charsets.UTF_8)
            logcat(logTag){ "attestationStatus: \n$attestationStatus" }
            Toast.makeText(requireContext(), "attestationStatus:$attestationStatus", Toast.LENGTH_LONG).show()
        }
    }


    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun startPayment(paymentInterface: PaymentInterface<*>) {
        val nexoRequest: String = generateNexoRequest(
            requestedAmount = "5",
            currency = "USD",
            poiId = InPersonPayments.getInstallationId().getOrNull() ?: "UNKNOWN"
        )
        logcat(logTag) { "NexoRequest:\n$nexoRequest" }

        InPersonPayments.performTransaction(
            context = requireContext(),
            paymentInterface = paymentInterface,
            transactionRequest = TransactionRequest.create(nexoRequest).getOrThrow(),
            paymentLauncher = resultLauncher,
            merchantUiParameters = MerchantUiParameters.create()
        )
    }

    companion object {

        private val DATE_FORMAT =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        private fun generateDiagnosisNexoRequest(
            serviceId: String = UUID.randomUUID().toString(),
            saleId: String = "AndroidSampleApp",
            poiId: String,
        ): String {

            val timeStamp = DATE_FORMAT.format(Date())
            val maxServiceIdSize = 10

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

        private fun generateNexoRequest(
            serviceId: String = UUID.randomUUID().toString(),
            saleId: String = "AndroidSampleApp",
            transactionID: String = "SampleApp-AndroidTx",
            poiId: String,
            currency: String,
            requestedAmount: String,
        ): String {

            val timeStamp = DATE_FORMAT.format(Date())
            val maxServiceIdSize = 10

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