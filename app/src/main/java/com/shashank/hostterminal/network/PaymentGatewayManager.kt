package com.shashank.hostterminal.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.data.MonetizationConfig
import com.shashank.hostterminal.data.TierPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CheckoutLaunchResult(
    val transactionRef: String,
    val methodLabel: String,
    val externalIntent: Intent?,
    val message: String
)

/**
 * Modular, 100% AD-FREE Upgrade & Payment Gateway controller.
 * Supports Admin-configured UPI ID, Razorpay Key ID, and Custom Payment Webhooks.
 */
class PaymentGatewayManager(private val appContext: Context) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun getConfig(): MonetizationConfig =
        AdminPolicyManager.getInstance(appContext).store.value.monetization

    fun isUpgradeFlowEnabled(): Boolean = getConfig().enabled

    fun buildUpiPaymentIntent(
        chatId: String,
        plan: TierPlan,
        transactionRef: String
    ): Intent? {
        val config = getConfig()
        val upiId = config.upiId.trim()
        if (upiId.isBlank()) return null

        val uri = Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", upiId)
            .appendQueryParameter("pn", config.merchantName.ifBlank { "HostTerminal" })
            .appendQueryParameter("tr", transactionRef)
            .appendQueryParameter("tn", "HostTerminal ${plan.name} (ID:$chatId)")
            .appendQueryParameter("am", "${plan.priceInr}.00")
            .appendQueryParameter("cu", "INR")
            .build()

        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun prepareCheckout(chatId: String, plan: TierPlan): CheckoutLaunchResult {
        val config = getConfig()
        val txRef = "HT-${UUID.randomUUID().toString().take(8).uppercase()}"

        if (config.upiId.isNotBlank()) {
            val upiIntent = buildUpiPaymentIntent(chatId, plan, txRef)
            return CheckoutLaunchResult(
                transactionRef = txRef,
                methodLabel = "UPI Direct (${config.upiId})",
                externalIntent = upiIntent,
                message = "UPI checkout ready for ₹${plan.priceInr}/${plan.billingCycle}"
            )
        }

        if (config.razorpayKeyId.isNotBlank()) {
            val maskedKey = config.razorpayKeyId.take(10) + "..."
            return CheckoutLaunchResult(
                transactionRef = txRef,
                methodLabel = "Razorpay ($maskedKey)",
                externalIntent = null,
                message = "Razorpay order $txRef initialized for ₹${plan.priceInr}"
            )
        }

        return CheckoutLaunchResult(
            transactionRef = txRef,
            methodLabel = "Webhook Checkout",
            externalIntent = null,
            message = "Order $txRef created for ${plan.name}"
        )
    }

    /**
     * Submits transaction confirmation to the Admin's Custom Payment Webhook (if configured)
     * and applies the upgraded storage & bot quotas to the user's Chat ID.
     */
    suspend fun verifyAndActivatePlan(
        chatId: String,
        plan: TierPlan,
        transactionRef: String,
        paymentProofId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val policyManager = AdminPolicyManager.getInstance(appContext)
        val config = policyManager.store.value.monetization

        if (plan.priceInr == 0) {
            policyManager.applyPlanToUser(chatId, plan.id)
            return@withContext Result.success("Switched to ${plan.name}")
        }

        val cleanProof = paymentProofId.trim()
        if (cleanProof.length < 4) {
            return@withContext Result.failure(
                IllegalArgumentException("Enter a valid UTR / Payment Reference ID to verify")
            )
        }

        val webhookUrl = config.customWebhookUrl.trim()
        if (webhookUrl.isNotBlank()) {
            if (!webhookUrl.startsWith("https://")) {
                return@withContext Result.failure(
                    IllegalArgumentException("Admin webhook must use HTTPS")
                )
            }
            val webhookResult = runCatching {
                val payload = JsonObject().apply {
                    addProperty("chat_id", chatId)
                    addProperty("plan_id", plan.id)
                    addProperty("plan_name", plan.name)
                    addProperty("amount_inr", plan.priceInr)
                    addProperty("transaction_ref", transactionRef)
                    addProperty("payment_proof_id", cleanProof)
                    addProperty("razorpay_key_configured", config.razorpayKeyId.isNotBlank())
                }
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Payment verification webhook rejected transaction (HTTP ${response.code})")
                    }
                    "Verified via payment webhook"
                }
            }
            if (webhookResult.isFailure) {
                return@withContext Result.failure(webhookResult.exceptionOrNull()!!)
            }
        }

        policyManager.applyPlanToUser(chatId, plan.id)
        Result.success("${plan.name} activated! New limits applied to Chat ID $chatId.")
    }
}
