package com.shashank.hostterminal.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.data.BackupManager
import com.shashank.hostterminal.data.FeaturePermissions
import com.shashank.hostterminal.data.GlobalPolicyDefaults
import com.shashank.hostterminal.data.TierPlan
import com.shashank.hostterminal.data.UserAccountRecord
import com.shashank.hostterminal.data.UserQuotaOverride
import com.shashank.hostterminal.data.UserStorageSnapshot
import com.shashank.hostterminal.service.HostingForegroundService
import com.shashank.hostterminal.ui.theme.AmberSignal
import com.shashank.hostterminal.ui.theme.CrimsonError
import com.shashank.hostterminal.ui.theme.ElectricCyan
import com.shashank.hostterminal.ui.theme.GlassCard
import com.shashank.hostterminal.ui.theme.GlassStrokeBright
import com.shashank.hostterminal.ui.theme.GlassStrokeSubtle
import com.shashank.hostterminal.ui.theme.IndigoAccent
import com.shashank.hostterminal.ui.theme.JetBrainsMonoFamily
import com.shashank.hostterminal.ui.theme.NeonEmerald
import com.shashank.hostterminal.ui.theme.StatusPill
import com.shashank.hostterminal.ui.theme.TextMuted
import com.shashank.hostterminal.ui.theme.TextPrimary
import com.shashank.hostterminal.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.UUID

private enum class AdminSection(val label: String) {
    USERS("Users"),
    DEFAULTS("Defaults"),
    STORAGE("Storage"),
    PLANS("Plans")
}

@Composable
fun AdminPanelScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by ActiveServerState.session.collectAsStateWithLifecycle()
    val policyManager = remember { AdminPolicyManager.getInstance(context) }
    val backupManager = remember { BackupManager(context) }
    val store by policyManager.store.collectAsStateWithLifecycle()

    if (session?.isSuperAdmin != true) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = IndigoAccent.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Super Admin access required",
                        tint = IndigoAccent,
                        modifier = Modifier.size(42.dp)
                    )
                    Text(
                        text = "Super Admin Access Only",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Sign in with Telegram User Chat ID matching SUPER_ADMIN_CHAT_ID in AppConfig.kt to unlock the control panel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
        return
    }

    var activeSection by remember { mutableStateOf(AdminSection.USERS) }
    var editingUser by remember { mutableStateOf<UserAccountRecord?>(null) }
    var storageSnapshots by remember { mutableStateOf<List<UserStorageSnapshot>>(emptyList()) }
    var adminToast by remember { mutableStateOf<String?>(null) }
    var showAddPlanDialog by remember { mutableStateOf(false) }

    fun reloadStorage() {
        scope.launch {
            storageSnapshots = backupManager.inspectAllUsersStorage()
        }
    }

    LaunchedEffect(activeSection) {
        if (activeSection == AdminSection.STORAGE || activeSection == AdminSection.USERS) {
            reloadStorage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Super Admin Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AdminPanelSettings,
                    contentDescription = "Super Admin Panel",
                    tint = IndigoAccent,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = "Super Admin Console",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Chat ID ${session?.chatId} • Zero-Ad Policy Controller",
                        style = MaterialTheme.typography.labelSmall,
                        color = IndigoAccent
                    )
                }
            }
            StatusPill(label = "ADMIN", dotColor = IndigoAccent)
        }

        // Section Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminSection.entries.forEach { section ->
                val icon = when (section) {
                    AdminSection.USERS -> Icons.Filled.People
                    AdminSection.DEFAULTS -> Icons.Filled.Tune
                    AdminSection.STORAGE -> Icons.Filled.Storage
                    AdminSection.PLANS -> Icons.Filled.Payments
                }
                FilterChip(
                    selected = activeSection == section,
                    onClick = { activeSection = section },
                    label = { Text(section.label) },
                    leadingIcon = {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = IndigoAccent.copy(alpha = 0.25f),
                        selectedLabelColor = TextPrimary,
                        selectedLeadingIconColor = IndigoAccent
                    ),
                    modifier = Modifier.testTag("admin_tab_${section.name.lowercase()}")
                )
            }
        }

        if (adminToast != null) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                borderColor = NeonEmerald.copy(alpha = 0.5f),
                containerColor = NeonEmerald.copy(alpha = 0.12f)
            ) {
                Text(
                    text = adminToast.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonEmerald,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        when (activeSection) {
            AdminSection.USERS -> {
                val userList = store.users.values.sortedByDescending { it.lastSeenAtMs }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (userList.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "No registered Chat IDs yet. Users appear here automatically once OTP-verified.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(18.dp)
                                )
                            }
                        }
                    } else {
                        items(userList, key = { it.chatId }) { user ->
                            val usage = policyManager.calculateUserDiskUsageBytes(user.chatId)
                            val effective = policyManager.resolveEffectivePolicy(user.chatId)
                            val isSelfAdmin = AppConfig.isSuperAdmin(user.chatId)
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                borderColor = if (user.isBlocked) CrimsonError.copy(alpha = 0.6f) else GlassStrokeSubtle
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Chat ID: ${user.chatId}",
                                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "@${user.lastBotUsername.ifBlank { "unknown" }} • Disk: ${AdminPolicyManager.formatBytes(usage)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                        StatusPill(
                                            label = when {
                                                user.isBlocked -> "BLOCKED"
                                                user.isOnline -> "ONLINE"
                                                else -> "IDLE"
                                            },
                                            dotColor = when {
                                                user.isBlocked -> CrimsonError
                                                user.isOnline -> NeonEmerald
                                                else -> TextMuted
                                            }
                                        )
                                    }

                                    Text(
                                        text = "Quota: ${AdminPolicyManager.formatQuotaLabel(effective.storageQuotaMb)} • Max Bots: ${AdminPolicyManager.formatBotLimitLabel(effective.maxHostedProjects)} • Tunnel: ${if (effective.allowCloudflaredTunnel) "ON" else "OFF"} • WakeLock: ${if (effective.allowBackgroundWakeLock) "ON" else "OFF"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { editingUser = user },
                                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                                        ) {
                                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricCyan)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Limits & Perms", color = ElectricCyan)
                                        }

                                        if (!isSelfAdmin) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Block,
                                                    contentDescription = "Block user",
                                                    tint = if (user.isBlocked) CrimsonError else TextMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = if (user.isBlocked) "Blocked" else "Block",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (user.isBlocked) CrimsonError else TextSecondary
                                                )
                                                Switch(
                                                    checked = user.isBlocked,
                                                    onCheckedChange = { shouldBlock ->
                                                        policyManager.setUserBlocked(user.chatId, shouldBlock)
                                                        adminToast = if (shouldBlock) {
                                                            "Blocked Chat ID ${user.chatId} and halted active processes."
                                                        } else {
                                                            "Unblocked Chat ID ${user.chatId}."
                                                        }
                                                    },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = Color.White,
                                                        checkedTrackColor = CrimsonError
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AdminSection.DEFAULTS -> {
                val defaults = store.globalDefaults
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = GlassStrokeBright
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "Global Default Resource Limits",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )

                                Text("Default Storage Quota", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                                QuotaPresetRow(
                                    selectedMb = defaults.storageQuotaMb,
                                    onSelect = { newMb ->
                                        policyManager.updateGlobalDefaults(defaults.copy(storageQuotaMb = newMb))
                                        adminToast = "Global storage quota set to ${AdminPolicyManager.formatQuotaLabel(newMb)}"
                                    }
                                )

                                Text("Default Max Hosted Bots / Projects", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                                BotLimitPresetRow(
                                    selectedMax = defaults.maxHostedProjects,
                                    onSelect = { newMax ->
                                        policyManager.updateGlobalDefaults(defaults.copy(maxHostedProjects = newMax))
                                        adminToast = "Global max projects set to ${AdminPolicyManager.formatBotLimitLabel(newMax)}"
                                    }
                                )

                                PolicyToggleRow(
                                    title = "Allow Terminal Access",
                                    subtitle = "Enable multi-tab Linux shell for standard users.",
                                    checked = defaults.permissions.allowTerminalAccess,
                                    onCheckedChange = { enabled ->
                                        policyManager.updateGlobalDefaults(
                                            defaults.copy(permissions = defaults.permissions.copy(allowTerminalAccess = enabled))
                                        )
                                    }
                                )
                                PolicyToggleRow(
                                    title = "Allow Cloudflared Public Tunnel (`play`)",
                                    subtitle = "Allow users to expose local servers via TryCloudflare.",
                                    checked = defaults.permissions.allowCloudflaredTunnel,
                                    onCheckedChange = { enabled ->
                                        policyManager.updateGlobalDefaults(
                                            defaults.copy(permissions = defaults.permissions.copy(allowCloudflaredTunnel = enabled))
                                        )
                                    }
                                )
                                PolicyToggleRow(
                                    title = "Allow 24/7 Background WakeLock",
                                    subtitle = "Permit Foreground Service PARTIAL_WAKE_LOCK screen-off hosting.",
                                    checked = defaults.permissions.allowBackgroundWakeLock,
                                    onCheckedChange = { enabled ->
                                        policyManager.updateGlobalDefaults(
                                            defaults.copy(permissions = defaults.permissions.copy(allowBackgroundWakeLock = enabled))
                                        )
                                        HostingForegroundService.refreshPolicy(context, session?.chatId)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            AdminSection.STORAGE -> {
                val totalDisk = storageSnapshots.sumOf { it.totalProjectsBytes + it.totalBackupsBytes }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Consumption: ${AdminPolicyManager.formatBytes(totalDisk)}",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = ElectricCyan
                    )
                    IconButton(onClick = { reloadStorage() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh storage explorer", tint = ElectricCyan)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (storageSnapshots.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "No user project folders or backups on disk yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(18.dp)
                                )
                            }
                        }
                    } else {
                        items(storageSnapshots, key = { it.chatId }) { snap ->
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                borderColor = GlassStrokeSubtle
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Chat ID: ${snap.chatId}",
                                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "Projects: ${AdminPolicyManager.formatBytes(snap.totalProjectsBytes)} • Backups: ${AdminPolicyManager.formatBytes(snap.totalBackupsBytes)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    backupManager.backupAllUserProjects(snap.chatId).onSuccess { count ->
                                                        adminToast = "Manually backed up $count project(s) for Chat ID ${snap.chatId}."
                                                        reloadStorage()
                                                    }
                                                }
                                            },
                                            border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.55f))
                                        ) {
                                            Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonEmerald)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Backup", color = NeonEmerald)
                                        }
                                    }

                                    snap.projectFolders.forEach { folder ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = ElectricCyan,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Column {
                                                    Text(folder.name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                                    Text(
                                                        text = "${folder.fileCount} files • ${AdminPolicyManager.formatBytes(folder.sizeBytes)} • entry: ${folder.entryPoint}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextMuted
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        backupManager.deleteUserProject(snap.chatId, folder.name, deleteBackupToo = false)
                                                        adminToast = "Deleted folder ${folder.name} for Chat ID ${snap.chatId}."
                                                        reloadStorage()
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.DeleteOutline,
                                                    contentDescription = "Delete project folder",
                                                    tint = CrimsonError
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AdminSection.PLANS -> {
                val mon = store.monetization
                var razorpayKey by remember(mon.razorpayKeyId) { mutableStateOf(mon.razorpayKeyId) }
                var upiId by remember(mon.upiId) { mutableStateOf(mon.upiId) }
                var merchantName by remember(mon.merchantName) { mutableStateOf(mon.merchantName) }
                var webhookUrl by remember(mon.customWebhookUrl) { mutableStateOf(mon.customWebhookUrl) }

                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IndigoAccent,
                    unfocusedBorderColor = Color(0xFF2B3E5E),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = if (mon.enabled) NeonEmerald.copy(alpha = 0.55f) else GlassStrokeSubtle
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                PolicyToggleRow(
                                    title = "Enable Upgrade Plans & Payment Gateway",
                                    subtitle = "100% Ad-Free monetization. Users exceeding limits see an Upgrade checkout sheet.",
                                    checked = mon.enabled,
                                    onCheckedChange = { on ->
                                        policyManager.updateMonetizationConfig(mon.copy(enabled = on))
                                        adminToast = if (on) "Upgrade Plans enabled." else "Upgrade Plans disabled."
                                    }
                                )

                                OutlinedTextField(
                                    value = razorpayKey,
                                    onValueChange = { razorpayKey = it },
                                    label = { Text("Razorpay Key ID (e.g., rzp_live_...)") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = upiId,
                                    onValueChange = { upiId = it },
                                    label = { Text("Merchant UPI ID (e.g., hosting@upi)") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = merchantName,
                                    onValueChange = { merchantName = it },
                                    label = { Text("Merchant Display Name") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = webhookUrl,
                                    onValueChange = { webhookUrl = it },
                                    label = { Text("Custom Payment Webhook URL (HTTPS)") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick = {
                                        policyManager.updateMonetizationConfig(
                                            mon.copy(
                                                razorpayKeyId = razorpayKey.trim(),
                                                upiId = upiId.trim(),
                                                merchantName = merchantName.trim(),
                                                customWebhookUrl = webhookUrl.trim()
                                            )
                                        )
                                        adminToast = "Saved payment gateway credentials."
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent, contentColor = Color.White)
                                ) {
                                    Text("Save Payment Gateway Config", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Configured Tier Plans", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            OutlinedButton(
                                onClick = { showAddPlanDialog = true },
                                border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.6f))
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonEmerald)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Tier", color = NeonEmerald)
                            }
                        }
                    }

                    items(mon.plans, key = { it.id }) { plan ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = GlassStrokeSubtle
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "${plan.name} • ${if (plan.priceInr == 0) "Free" else "₹${plan.priceInr}/${plan.billingCycle}"}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${AdminPolicyManager.formatQuotaLabel(plan.storageQuotaMb)} • ${AdminPolicyManager.formatBotLimitLabel(plan.maxHostedProjects)} Bots",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NeonEmerald
                                    )
                                    Text(plan.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                                if (plan.id != "free") {
                                    IconButton(onClick = { policyManager.deleteTierPlan(plan.id) }) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete plan", tint = CrimsonError)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-User Limits & Feature Permissions Override Dialog
    editingUser?.let { user ->
        UserOverridesDialog(
            user = user,
            defaults = store.globalDefaults,
            onDismiss = { editingUser = null },
            onSave = { overrides ->
                policyManager.updateUserOverrides(user.chatId, overrides)
                HostingForegroundService.refreshPolicy(context, user.chatId)
                adminToast = "Updated overrides for Chat ID ${user.chatId}."
                editingUser = null
            }
        )
    }

    // Add Custom Tier Plan Dialog
    if (showAddPlanDialog) {
        AddTierPlanDialog(
            onDismiss = { showAddPlanDialog = false },
            onSave = { newPlan ->
                policyManager.upsertTierPlan(newPlan)
                adminToast = "Added custom tier plan '${newPlan.name}'."
                showAddPlanDialog = false
            }
        )
    }
}

@Composable
private fun QuotaPresetRow(
    selectedMb: Long,
    onSelect: (Long) -> Unit
) {
    val options = listOf(
        AppConfig.QUOTA_100_MB to "100 MB",
        AppConfig.QUOTA_500_MB to "500 MB",
        AppConfig.QUOTA_2_GB to "2 GB",
        AppConfig.QUOTA_UNLIMITED to "Unlimited"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mb, label) ->
            FilterChip(
                selected = selectedMb == mb,
                onClick = { onSelect(mb) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun BotLimitPresetRow(
    selectedMax: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(
        AppConfig.BOTS_1 to "1 Bot",
        AppConfig.BOTS_3 to "3 Bots",
        AppConfig.BOTS_10 to "10 Bots",
        AppConfig.BOTS_UNLIMITED to "Unlimited"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (limit, label) ->
            FilterChip(
                selected = selectedMax == limit,
                onClick = { onSelect(limit) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun PolicyToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04141D),
                checkedTrackColor = ElectricCyan
            )
        )
    }
}

@Composable
private fun UserOverridesDialog(
    user: UserAccountRecord,
    defaults: GlobalPolicyDefaults,
    onDismiss: () -> Unit,
    onSave: (UserQuotaOverride) -> Unit
) {
    var quotaMb by remember { mutableStateOf(user.overrides.storageQuotaMb ?: defaults.storageQuotaMb) }
    var maxBots by remember { mutableStateOf(user.overrides.maxHostedProjects ?: defaults.maxHostedProjects) }
    var allowTerm by remember { mutableStateOf(user.overrides.allowTerminalAccess ?: defaults.permissions.allowTerminalAccess) }
    var allowTunnel by remember { mutableStateOf(user.overrides.allowCloudflaredTunnel ?: defaults.permissions.allowCloudflaredTunnel) }
    var allowWakeLock by remember { mutableStateOf(user.overrides.allowBackgroundWakeLock ?: defaults.permissions.allowBackgroundWakeLock) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Overrides: Chat ID ${user.chatId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Storage Quota", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                QuotaPresetRow(selectedMb = quotaMb, onSelect = { quotaMb = it })

                Text("Max Hosted Projects", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                BotLimitPresetRow(selectedMax = maxBots, onSelect = { maxBots = it })

                PolicyToggleRow("Terminal Access", "Allow Linux shell", allowTerm) { allowTerm = it }
                PolicyToggleRow("Cloudflared Tunnel", "Allow `play` public URLs", allowTunnel) { allowTunnel = it }
                PolicyToggleRow("24/7 WakeLock", "Allow screen-off lock", allowWakeLock) { allowWakeLock = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        UserQuotaOverride(
                            storageQuotaMb = quotaMb,
                            maxHostedProjects = maxBots,
                            allowTerminalAccess = allowTerm,
                            allowCloudflaredTunnel = allowTunnel,
                            allowBackgroundWakeLock = allowWakeLock
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald, contentColor = Color(0xFF031B13))
            ) {
                Text("Apply Limits")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun AddTierPlanDialog(
    onDismiss: () -> Unit,
    onSave: (TierPlan) -> Unit
) {
    var name by remember { mutableStateOf("Developer Plus") }
    var description by remember { mutableStateOf("500 MB storage & 3 bots with 24/7 WakeLock.") }
    var priceStr by remember { mutableStateOf("99") }
    var quotaMb by remember { mutableStateOf(AppConfig.QUOTA_500_MB) }
    var maxBots by remember { mutableStateOf(AppConfig.BOTS_3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Create Custom Tier Plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Plan Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceStr,
                    onValueChange = { priceStr = it.filter(Char::isDigit) },
                    label = { Text("Price (INR / month)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Storage Limit", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                QuotaPresetRow(selectedMb = quotaMb, onSelect = { quotaMb = it })
                Text("Max Bots Limit", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                BotLimitPresetRow(selectedMax = maxBots, onSelect = { maxBots = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim().ifBlank { "Custom Tier" }
                    onSave(
                        TierPlan(
                            id = "tier_${UUID.randomUUID().toString().take(6)}",
                            name = cleanName,
                            description = description.trim(),
                            priceInr = priceStr.toIntOrNull() ?: 99,
                            storageQuotaMb = quotaMb,
                            maxHostedProjects = maxBots
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color(0xFF04141D))
            ) {
                Text("Save Plan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
