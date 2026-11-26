package com.shashank.hostterminal

import android.content.Context
import java.io.File

/**
 * Central application configuration for HostTerminal.
 * 100% AD-FREE architecture — zero advertising SDKs or ad identifiers.
 */
object AppConfig {

    /**
     * Configure your Telegram numeric Chat ID here to unlock the Super Admin Control Panel
     * and receive mirrored OTP registration/login notifications.
     */
    const val SUPER_ADMIN_CHAT_ID = "YOUR_ADMIN_CHAT_ID"

    const val APP_NAME = "HostTerminal"
    const val TELEGRAM_API_BASE = "https://api.telegram.org"

    // Official Cloudflare Tunnel static binary release URLs
    const val CLOUDFLARED_URL_ARM64 =
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
    const val CLOUDFLARED_URL_ARM =
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm"
    const val CLOUDFLARED_URL_AMD64 =
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"

    const val NOTIFICATION_CHANNEL_ID = "hostterminal_engine_channel"
    const val NOTIFICATION_CHANNEL_NAME = "HostTerminal Background Engine"
    const val FOREGROUND_NOTIFICATION_ID = 4109

    const val OTP_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes
    const val MAX_OTP_ATTEMPTS = 5
    const val MAX_LOG_LINES_PER_SESSION = 600

    // Storage quota presets in MB (-1L represents Unlimited)
    const val QUOTA_100_MB = 100L
    const val QUOTA_500_MB = 500L
    const val QUOTA_2_GB = 2048L
    const val QUOTA_UNLIMITED = -1L

    // Max hosted projects/bots presets (-1 represents Unlimited)
    const val BOTS_1 = 1
    const val BOTS_3 = 3
    const val BOTS_10 = 10
    const val BOTS_UNLIMITED = -1

    fun isSuperAdmin(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return chatId.trim() == SUPER_ADMIN_CHAT_ID.trim()
    }

    fun isAdminMirrorConfigured(): Boolean {
        val trimmed = SUPER_ADMIN_CHAT_ID.trim()
        return trimmed.isNotEmpty() &&
            trimmed != "YOUR_ADMIN_CHAT_ID" &&
            trimmed.matches(Regex("^-?[0-9]{5,20}$"))
    }

    fun sanitizePathSegment(raw: String, fallback: String = "default"): String {
        val cleaned = raw.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("\\.{2,}"), "_")
            .trim('.', '_', '-')
        return if (cleaned.isBlank()) fallback else cleaned.take(64)
    }

    fun binDir(context: Context): File =
        File(context.filesDir, "bin").apply { mkdirs() }

    fun cloudflaredBinary(context: Context): File =
        File(binDir(context), "cloudflared")

    fun projectsRoot(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    fun userProjectsDir(context: Context, chatId: String): File =
        File(projectsRoot(context), sanitizePathSegment(chatId, "unknown")).apply { mkdirs() }

    fun backupsRoot(context: Context): File =
        File(context.filesDir, "backups").apply { mkdirs() }

    fun userBackupsDir(context: Context, chatId: String): File =
        File(backupsRoot(context), sanitizePathSegment(chatId, "unknown")).apply { mkdirs() }

    fun configDir(context: Context): File =
        File(context.filesDir, "config").apply { mkdirs() }
}
