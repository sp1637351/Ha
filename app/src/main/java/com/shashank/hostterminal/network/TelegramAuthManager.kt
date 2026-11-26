package com.shashank.hostterminal.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.shashank.hostterminal.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TelegramBotInfo(
    val id: Long,
    val firstName: String,
    val username: String
)

data class PendingOtpChallenge(
    val chatId: String,
    val botToken: String,
    val botInfo: TelegramBotInfo,
    val otpCode: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val adminMirrorDelivered: Boolean,
    val attemptsRemaining: Int = AppConfig.MAX_OTP_ATTEMPTS
)

sealed class OtpVerifyResult {
    data class Success(val challenge: PendingOtpChallenge) : OtpVerifyResult()
    data class Invalid(val attemptsLeft: Int) : OtpVerifyResult()
    data object Expired : OtpVerifyResult()
    data object NoChallenge : OtpVerifyResult()
}

/**
 * Zero-email Telegram Bot Token + strict User Chat ID authentication & OTP engine.
 * - Calls Telegram Bot API getMe, getUpdates, and sendMessage via OkHttp + Gson.
 * - Strictly ignores /start commands from any chat.id != entered User Chat ID.
 * - Sends tap-to-copy <code>OTP</code> HTML messages and mirrors login/OTP alerts to SUPER_ADMIN_CHAT_ID.
 */
class TelegramAuthManager {

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeChallenge: PendingOtpChallenge? = null

    fun buildStartDeepLink(botUsername: String): String {
        val clean = botUsername.trim().removePrefix("@")
        return "https://t.me/$clean?start=auth"
    }

    fun isTokenSyntaxPlausible(token: String): Boolean {
        val trimmed = token.trim()
        return trimmed.matches(Regex("^[0-9]{6,15}:[A-Za-z0-9_-]{25,60}$"))
    }

    fun isChatIdValid(chatId: String): Boolean {
        return chatId.trim().matches(Regex("^-?[0-9]{5,20}$"))
    }

    suspend fun lookupBot(botToken: String): Result<TelegramBotInfo> = withContext(Dispatchers.IO) {
        val cleanToken = botToken.trim()
        if (!isTokenSyntaxPlausible(cleanToken)) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid Telegram Bot Token"))
        }
        runCatching {
            val request = Request.Builder()
                .url("${AppConfig.TELEGRAM_API_BASE}/bot$cleanToken/getMe")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Telegram rejected Bot Token (HTTP ${response.code})")
                }
                val root = gson.fromJson(bodyStr, JsonObject::class.java)
                if (root?.get("ok")?.asBoolean != true) {
                    val desc = root?.get("description")?.asString ?: "Invalid bot token"
                    error(desc)
                }
                val result = root.getAsJsonObject("result")
                    ?: error("Missing bot profile in Telegram response")
                TelegramBotInfo(
                    id = result.get("id")?.asLong ?: 0L,
                    firstName = result.get("first_name")?.asString ?: "Telegram Bot",
                    username = result.get("username")?.asString ?: ""
                )
            }
        }
    }

    /**
     * Polls Telegram `getUpdates` strictly waiting for `/start` from `expectedChatId`.
     * All updates from other chat IDs are strictly ignored.
     * Upon match, generates a cryptographically secure 6-digit OTP, sends it to the user
     * in tap-to-copy HTML format, and mirrors a notification to AppConfig.SUPER_ADMIN_CHAT_ID.
     */
    suspend fun pollStrictStartAndDispatchOtp(
        botToken: String,
        expectedChatId: String,
        botInfo: TelegramBotInfo,
        timeoutMs: Long = 180_000L,
        onStatusUpdate: (String) -> Unit
    ): Result<PendingOtpChallenge> = withContext(Dispatchers.IO) {
        val cleanToken = botToken.trim()
        val targetChatId = expectedChatId.trim()
        if (!isChatIdValid(targetChatId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid numeric User Chat ID"))
        }

        runCatching {
            // Advance past stale updates so only a fresh /start command triggers OTP
            var nextOffset = fetchLatestUpdateOffset(cleanToken)
            val deadline = System.currentTimeMillis() + timeoutMs
            onStatusUpdate("Listening for /start strictly from Chat ID $targetChatId...")

            while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
                val updatesUrl = buildString {
                    append("${AppConfig.TELEGRAM_API_BASE}/bot$cleanToken/getUpdates?timeout=15&allowed_updates=%5B%22message%22%5D")
                    if (nextOffset > 0L) {
                        append("&offset=$nextOffset")
                    }
                }
                val request = Request.Builder().url(updatesUrl).get().build()

                val matched = httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code == 409) {
                            // Webhook conflict: delete webhook so long polling succeeds
                            clearWebhook(cleanToken)
                        }
                        false
                    } else {
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val resultArray = root?.getAsJsonArray("result")
                        var foundStrictMatch = false

                        if (resultArray != null) {
                            for (elem in resultArray) {
                                val updateObj = elem.asJsonObject
                                val updateId = updateObj.get("update_id")?.asLong ?: 0L
                                if (updateId >= nextOffset) {
                                    nextOffset = updateId + 1
                                }

                                val message = updateObj.getAsJsonObject("message") ?: continue
                                val chat = message.getAsJsonObject("chat") ?: continue
                                val incomingChatId = chat.get("id")?.asLong?.toString() ?: continue
                                val text = message.get("text")?.asString?.trim().orEmpty()

                                // STRICT CHAT ID CHECK: Ignore every /start from non-matching chat.id
                                if (incomingChatId == targetChatId && text.startsWith("/start")) {
                                    foundStrictMatch = true
                                    break
                                }
                            }
                        }
                        foundStrictMatch
                    }
                }

                if (matched) {
                    val otp = generateSecureSixDigitOtp()
                    onStatusUpdate("Verified /start from Chat ID $targetChatId. Sending OTP...")

                    val userHtml = buildUserOtpHtmlMessage(
                        botUsername = botInfo.username,
                        chatId = targetChatId,
                        otp = otp
                    )
                    sendHtmlMessage(cleanToken, targetChatId, userHtml).getOrThrow()

                    // Simultaneous Admin OTP Mirroring
                    val mirrored = mirrorLoginToSuperAdmin(
                        botToken = cleanToken,
                        userChatId = targetChatId,
                        botUsername = botInfo.username,
                        otp = otp
                    )

                    val now = System.currentTimeMillis()
                    val challenge = PendingOtpChallenge(
                        chatId = targetChatId,
                        botToken = cleanToken,
                        botInfo = botInfo,
                        otpCode = otp,
                        createdAtMs = now,
                        expiresAtMs = now + AppConfig.OTP_EXPIRY_MS,
                        adminMirrorDelivered = mirrored
                    )
                    activeChallenge = challenge
                    return@runCatching challenge
                }

                delay(800L)
            }
            error("Timed out waiting for /start from Chat ID $targetChatId. Open Telegram and tap Start.")
        }
    }

    fun verifyOtp(chatId: String, enteredCode: String): OtpVerifyResult {
        val current = activeChallenge ?: return OtpVerifyResult.NoChallenge
        if (current.chatId != chatId.trim()) return OtpVerifyResult.NoChallenge

        if (System.currentTimeMillis() > current.expiresAtMs) {
            activeChallenge = null
            return OtpVerifyResult.Expired
        }

        val cleanedInput = enteredCode.trim()
        if (constantTimeEquals(current.otpCode, cleanedInput)) {
            activeChallenge = null
            return OtpVerifyResult.Success(current)
        }

        val remaining = (current.attemptsRemaining - 1).coerceAtLeast(0)
        activeChallenge = if (remaining == 0) null else current.copy(attemptsRemaining = remaining)
        return OtpVerifyResult.Invalid(remaining)
    }

    private fun fetchLatestUpdateOffset(cleanToken: String): Long {
        return try {
            val request = Request.Builder()
                .url("${AppConfig.TELEGRAM_API_BASE}/bot$cleanToken/getUpdates?offset=-1&limit=1")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L
                val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                val arr = root?.getAsJsonArray("result")
                if (arr != null && arr.size() > 0) {
                    val lastId = arr[arr.size() - 1].asJsonObject.get("update_id")?.asLong ?: 0L
                    lastId + 1
                } else {
                    0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun clearWebhook(cleanToken: String) {
        try {
            val req = Request.Builder()
                .url("${AppConfig.TELEGRAM_API_BASE}/bot$cleanToken/deleteWebhook")
                .get()
                .build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) {
            // Ignore cleanup failure
        }
    }

    private fun sendHtmlMessage(
        cleanToken: String,
        targetChatId: String,
        htmlText: String
    ): Result<Unit> = runCatching {
        val payload = JsonObject().apply {
            addProperty("chat_id", targetChatId)
            addProperty("text", htmlText)
            addProperty("parse_mode", "HTML")
            addProperty("disable_web_page_preview", true)
        }
        val request = Request.Builder()
            .url("${AppConfig.TELEGRAM_API_BASE}/bot$cleanToken/sendMessage")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Failed to send Telegram message (HTTP ${response.code})")
            }
            val root = gson.fromJson(body, JsonObject::class.java)
            if (root?.get("ok")?.asBoolean != true) {
                error(root?.get("description")?.asString ?: "Telegram sendMessage failed")
            }
        }
    }

    private fun mirrorLoginToSuperAdmin(
        botToken: String,
        userChatId: String,
        botUsername: String,
        otp: String
    ): Boolean {
        if (!AppConfig.isAdminMirrorConfigured()) return false
        val adminChatId = AppConfig.SUPER_ADMIN_CHAT_ID.trim()
        if (adminChatId == userChatId) return true // Already sent directly to admin

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val adminHtml = buildString {
            append("🛡️ <b>HostTerminal Auth Mirror</b>\n\n")
            append("👤 <b>User Chat ID:</b> <code>${escapeHtml(userChatId)}</code>\n")
            append("🤖 <b>Bot Username:</b> @${escapeHtml(botUsername)}\n")
            append("🔑 <b>Generated OTP:</b> <code>${escapeHtml(otp)}</code>\n")
            append("🕒 <b>Timestamp:</b> ${escapeHtml(timestamp)}")
        }
        return sendHtmlMessage(botToken, adminChatId, adminHtml).isSuccess
    }

    private fun buildUserOtpHtmlMessage(
        botUsername: String,
        chatId: String,
        otp: String
    ): String = buildString {
        append("🔐 <b>HostTerminal Verification Code</b>\n\n")
        append("⚡ Tap the code below to copy it instantly:\n")
        append("👉 <code>${escapeHtml(otp)}</code>\n\n")
        append("🆔 <b>Chat ID:</b> <code>${escapeHtml(chatId)}</code>\n")
        append("🤖 <b>Verified Bot:</b> @${escapeHtml(botUsername)}\n")
        append("⏳ <i>Valid for 5 minutes • 100% Ad-Free Hosting</i>")
    }

    private fun generateSecureSixDigitOtp(): String {
        val number = 100000 + secureRandom.nextInt(900000)
        return number.toString()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun escapeHtml(raw: String): String =
        raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
