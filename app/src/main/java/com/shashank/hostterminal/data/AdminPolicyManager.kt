package com.shashank.hostterminal.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shashank.hostterminal.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class FeaturePermissions(
    val allowTerminalAccess: Boolean = true,
    val allowCloudflaredTunnel: Boolean = true,
    val allowBackgroundWakeLock: Boolean = true
)

data class GlobalPolicyDefaults(
    val storageQuotaMb: Long = AppConfig.QUOTA_500_MB,
    val maxHostedProjects: Int = AppConfig.BOTS_3,
    val permissions: FeaturePermissions = FeaturePermissions()
)

data class UserQuotaOverride(
    val storageQuotaMb: Long? = null,
    val maxHostedProjects: Int? = null,
    val allowTerminalAccess: Boolean? = null,
    val allowCloudflaredTunnel: Boolean? = null,
    val allowBackgroundWakeLock: Boolean? = null
)

data class UserAccountRecord(
    val chatId: String,
    val lastBotUsername: String = "",
    val lastBotName: String = "",
    val registeredAtMs: Long = System.currentTimeMillis(),
    val lastSeenAtMs: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val isOnline: Boolean = false,
    val activePlanId: String = "free",
    val overrides: UserQuotaOverride = UserQuotaOverride()
)

data class EffectiveUserPolicy(
    val chatId: String,
    val isBlocked: Boolean,
    val storageQuotaMb: Long,
    val maxHostedProjects: Int,
    val allowTerminalAccess: Boolean,
    val allowCloudflaredTunnel: Boolean,
    val allowBackgroundWakeLock: Boolean,
    val activePlanName: String
)

data class TierPlan(
    val id: String,
    val name: String,
    val description: String,
    val priceInr: Int,
    val billingCycle: String = "month",
    val storageQuotaMb: Long,
    val maxHostedProjects: Int,
    val allowCloudflaredTunnel: Boolean = true,
    val allowBackgroundWakeLock: Boolean = true
)

data class MonetizationConfig(
    val enabled: Boolean = false,
    val razorpayKeyId: String = "",
    val upiId: String = "",
    val merchantName: String = "HostTerminal Cloud",
    val customWebhookUrl: String = "",
    val plans: List<TierPlan> = defaultPlans()
) {
    companion object {
        fun defaultPlans(): List<TierPlan> = listOf(
            TierPlan(
                id = "free",
                name = "Starter Free",
                description = "Ad-free core terminal, 100 MB storage & 1 hosted project.",
                priceInr = 0,
                billingCycle = "forever",
                storageQuotaMb = AppConfig.QUOTA_100_MB,
                maxHostedProjects = AppConfig.BOTS_1
            ),
            TierPlan(
                id = "pro",
                name = "Pro Hosting",
                description = "2 GB storage, up to 10 live bots/web apps & 24/7 WakeLock.",
                priceInr = 199,
                billingCycle = "month",
                storageQuotaMb = AppConfig.QUOTA_2_GB,
                maxHostedProjects = AppConfig.BOTS_10
            ),
            TierPlan(
                id = "unlimited",
                name = "Cloud Unlimited",
                description = "Unlimited storage, unlimited bots, priority tunnels & backups.",
                priceInr = 499,
                billingCycle = "month",
                storageQuotaMb = AppConfig.QUOTA_UNLIMITED,
                maxHostedProjects = AppConfig.BOTS_UNLIMITED
            )
        )
    }
}

data class AdminPolicyStore(
    val globalDefaults: GlobalPolicyDefaults = GlobalPolicyDefaults(),
    val users: Map<String, UserAccountRecord> = emptyMap(),
    val monetization: MonetizationConfig = MonetizationConfig()
)

/**
 * Persistent policy, quota, user access, and ad-free monetization controller.
 * Indexes all accounts strictly by Telegram User Chat ID (primary key).
 */
class AdminPolicyManager private constructor(private val appContext: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val policyFile: File
        get() = File(AppConfig.configDir(appContext), "admin_policies.json")

    private val _store = MutableStateFlow(loadFromDisk())
    val store: StateFlow<AdminPolicyStore> = _store.asStateFlow()

    @Volatile
    private var onUserBlockedCallback: ((String) -> Unit)? = null

    fun setOnUserBlockedListener(listener: (String) -> Unit) {
        onUserBlockedCallback = listener
    }

    @Synchronized
    private fun loadFromDisk(): AdminPolicyStore {
        return try {
            if (!policyFile.exists()) {
                AdminPolicyStore()
            } else {
                val json = policyFile.readText()
                gson.fromJson(json, AdminPolicyStore::class.java) ?: AdminPolicyStore()
            }
        } catch (_: Exception) {
            AdminPolicyStore()
        }
    }

    @Synchronized
    private fun persist(newStore: AdminPolicyStore) {
        _store.value = newStore
        try {
            policyFile.parentFile?.mkdirs()
            policyFile.writeText(gson.toJson(newStore))
        } catch (_: Exception) {
            // Keep in-memory state consistent even if disk write fails transiently
        }
    }

    fun registerOrUpdateUser(
        chatId: String,
        botUsername: String,
        botName: String,
        isOnline: Boolean = true
    ): UserAccountRecord {
        val key = chatId.trim()
        val current = _store.value
        val existing = current.users[key]
        val updated = if (existing != null) {
            existing.copy(
                lastBotUsername = botUsername.ifBlank { existing.lastBotUsername },
                lastBotName = botName.ifBlank { existing.lastBotName },
                lastSeenAtMs = System.currentTimeMillis(),
                isOnline = isOnline
            )
        } else {
            UserAccountRecord(
                chatId = key,
                lastBotUsername = botUsername,
                lastBotName = botName,
                registeredAtMs = System.currentTimeMillis(),
                lastSeenAtMs = System.currentTimeMillis(),
                isOnline = isOnline
            )
        }
        persist(current.copy(users = current.users + (key to updated)))
        return updated
    }

    fun setUserOnline(chatId: String, online: Boolean) {
        val key = chatId.trim()
        val current = _store.value
        val existing = current.users[key] ?: return
        persist(
            current.copy(
                users = current.users + (key to existing.copy(
                    isOnline = online,
                    lastSeenAtMs = System.currentTimeMillis()
                ))
            )
        )
    }

    fun isUserBlocked(chatId: String): Boolean {
        if (AppConfig.isSuperAdmin(chatId)) return false
        return _store.value.users[chatId.trim()]?.isBlocked == true
    }

    fun setUserBlocked(chatId: String, blocked: Boolean) {
        if (AppConfig.isSuperAdmin(chatId)) return
        val key = chatId.trim()
        val current = _store.value
        val existing = current.users[key] ?: UserAccountRecord(chatId = key)
        val updated = existing.copy(
            isBlocked = blocked,
            isOnline = if (blocked) false else existing.isOnline
        )
        persist(current.copy(users = current.users + (key to updated)))
        if (blocked) {
            if (ActiveServerState.session.value?.chatId == key) {
                ActiveServerState.setSession(null)
            }
            onUserBlockedCallback?.invoke(key)
        }
    }

    fun updateGlobalDefaults(defaults: GlobalPolicyDefaults) {
        val current = _store.value
        persist(current.copy(globalDefaults = defaults))
    }

    fun updateUserOverrides(chatId: String, overrides: UserQuotaOverride) {
        val key = chatId.trim()
        val current = _store.value
        val existing = current.users[key] ?: UserAccountRecord(chatId = key)
        persist(current.copy(users = current.users + (key to existing.copy(overrides = overrides))))
    }

    fun applyPlanToUser(chatId: String, planId: String) {
        val key = chatId.trim()
        val current = _store.value
        val plan = current.monetization.plans.firstOrNull { it.id == planId } ?: return
        val existing = current.users[key] ?: UserAccountRecord(chatId = key)
        val updated = existing.copy(
            activePlanId = plan.id,
            overrides = existing.overrides.copy(
                storageQuotaMb = plan.storageQuotaMb,
                maxHostedProjects = plan.maxHostedProjects,
                allowCloudflaredTunnel = plan.allowCloudflaredTunnel,
                allowBackgroundWakeLock = plan.allowBackgroundWakeLock
            )
        )
        persist(current.copy(users = current.users + (key to updated)))
    }

    fun updateMonetizationConfig(config: MonetizationConfig) {
        val current = _store.value
        persist(current.copy(monetization = config))
    }

    fun upsertTierPlan(plan: TierPlan) {
        val current = _store.value
        val list = current.monetization.plans.toMutableList()
        val idx = list.indexOfFirst { it.id == plan.id }
        if (idx >= 0) {
            list[idx] = plan
        } else {
            list.add(plan)
        }
        persist(current.copy(monetization = current.monetization.copy(plans = list)))
    }

    fun deleteTierPlan(planId: String) {
        if (planId == "free") return
        val current = _store.value
        val filtered = current.monetization.plans.filterNot { it.id == planId }
        persist(current.copy(monetization = current.monetization.copy(plans = filtered)))
    }

    fun resolveEffectivePolicy(chatId: String?): EffectiveUserPolicy {
        val current = _store.value
        val defaults = current.globalDefaults
        if (chatId.isNullOrBlank()) {
            return EffectiveUserPolicy(
                chatId = "guest",
                isBlocked = false,
                storageQuotaMb = defaults.storageQuotaMb,
                maxHostedProjects = defaults.maxHostedProjects,
                allowTerminalAccess = defaults.permissions.allowTerminalAccess,
                allowCloudflaredTunnel = defaults.permissions.allowCloudflaredTunnel,
                allowBackgroundWakeLock = defaults.permissions.allowBackgroundWakeLock,
                activePlanName = "Default"
            )
        }
        if (AppConfig.isSuperAdmin(chatId)) {
            return EffectiveUserPolicy(
                chatId = chatId,
                isBlocked = false,
                storageQuotaMb = AppConfig.QUOTA_UNLIMITED,
                maxHostedProjects = AppConfig.BOTS_UNLIMITED,
                allowTerminalAccess = true,
                allowCloudflaredTunnel = true,
                allowBackgroundWakeLock = true,
                activePlanName = "Super Admin"
            )
        }

        val user = current.users[chatId.trim()]
        val plan = current.monetization.plans.firstOrNull { it.id == user?.activePlanId }
        val baseStorage = if (current.monetization.enabled && plan != null) {
            plan.storageQuotaMb
        } else {
            defaults.storageQuotaMb
        }
        val baseProjects = if (current.monetization.enabled && plan != null) {
            plan.maxHostedProjects
        } else {
            defaults.maxHostedProjects
        }

        val ov = user?.overrides
        return EffectiveUserPolicy(
            chatId = chatId.trim(),
            isBlocked = user?.isBlocked == true,
            storageQuotaMb = ov?.storageQuotaMb ?: baseStorage,
            maxHostedProjects = ov?.maxHostedProjects ?: baseProjects,
            allowTerminalAccess = ov?.allowTerminalAccess ?: defaults.permissions.allowTerminalAccess,
            allowCloudflaredTunnel = ov?.allowCloudflaredTunnel
                ?: plan?.allowCloudflaredTunnel
                ?: defaults.permissions.allowCloudflaredTunnel,
            allowBackgroundWakeLock = ov?.allowBackgroundWakeLock
                ?: plan?.allowBackgroundWakeLock
                ?: defaults.permissions.allowBackgroundWakeLock,
            activePlanName = plan?.name ?: "Standard"
        )
    }

    fun calculateUserDiskUsageBytes(chatId: String): Long {
        val projBytes = directorySizeBytes(AppConfig.userProjectsDir(appContext, chatId))
        val backupBytes = directorySizeBytes(AppConfig.userBackupsDir(appContext, chatId))
        return projBytes + backupBytes
    }

    fun calculateUserProjectsUsageBytes(chatId: String): Long =
        directorySizeBytes(AppConfig.userProjectsDir(appContext, chatId))

    fun isStorageQuotaExceeded(chatId: String, additionalBytes: Long = 0L): Boolean {
        val policy = resolveEffectivePolicy(chatId)
        if (policy.storageQuotaMb == AppConfig.QUOTA_UNLIMITED) return false
        val limitBytes = policy.storageQuotaMb * 1024L * 1024L
        return (calculateUserProjectsUsageBytes(chatId) + additionalBytes) > limitBytes
    }

    fun isProjectCountLimitReached(chatId: String, currentCount: Int): Boolean {
        val policy = resolveEffectivePolicy(chatId)
        if (policy.maxHostedProjects == AppConfig.BOTS_UNLIMITED) return false
        return currentCount >= policy.maxHostedProjects
    }

    companion object {
        @Volatile
        private var instance: AdminPolicyManager? = null

        fun getInstance(context: Context): AdminPolicyManager {
            return instance ?: synchronized(this) {
                instance ?: AdminPolicyManager(context.applicationContext).also { instance = it }
            }
        }

        fun directorySizeBytes(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            var total = 0L
            file.listFiles()?.forEach { child ->
                total += directorySizeBytes(child)
            }
            return total
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format("%.2f MB", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }

        fun formatQuotaLabel(quotaMb: Long): String = when (quotaMb) {
            AppConfig.QUOTA_UNLIMITED -> "Unlimited"
            in 1024L..Long.MAX_VALUE -> "${quotaMb / 1024L} GB"
            else -> "$quotaMb MB"
        }

        fun formatBotLimitLabel(limit: Int): String =
            if (limit == AppConfig.BOTS_UNLIMITED) "Unlimited" else "$limit"
    }
}
