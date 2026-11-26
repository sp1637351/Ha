package com.shashank.hostterminal.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.data.BackupManager
import com.shashank.hostterminal.data.HostedProject
import com.shashank.hostterminal.data.ProjectRunStatus
import com.shashank.hostterminal.data.TierPlan
import com.shashank.hostterminal.network.CheckoutLaunchResult
import com.shashank.hostterminal.network.PaymentGatewayManager
import com.shashank.hostterminal.runtime.EnvironmentBootstrapper
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
import com.shashank.hostterminal.ui.theme.TerminalCanvas
import com.shashank.hostterminal.ui.theme.TextMuted
import com.shashank.hostterminal.ui.theme.TextPrimary
import com.shashank.hostterminal.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HostingDashboardScreen(
    onOpenTerminalForPlay: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by ActiveServerState.session.collectAsStateWithLifecycle()
    val projects by ActiveServerState.hostedProjects.collectAsStateWithLifecycle()
    val policyStore by AdminPolicyManager.getInstance(context).store.collectAsStateWithLifecycle()

    val policyManager = remember { AdminPolicyManager.getInstance(context) }
    val backupManager = remember { BackupManager(context) }
    val bootstrapper = remember { EnvironmentBootstrapper.getInstance(context) }
    val paymentManager = remember { PaymentGatewayManager(context) }

    val chatId = session?.chatId.orEmpty()
    val effectivePolicy = remember(chatId, policyStore) {
        policyManager.resolveEffectivePolicy(chatId)
    }

    var diskUsedBytes by remember { mutableStateOf(0L) }
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var bannerIsError by remember { mutableStateOf(false) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var expandedLogsProjectId by remember { mutableStateOf<String?>(null) }

    fun refreshDiskUsage() {
        if (chatId.isNotBlank()) {
            diskUsedBytes = policyManager.calculateUserProjectsUsageBytes(chatId)
        }
    }

    LaunchedEffect(chatId) {
        if (chatId.isNotBlank()) {
            val loaded = backupManager.loadUserProjects(chatId)
            ActiveServerState.setProjects(loaded)
            refreshDiskUsage()
            val restored = session?.restoredProjectCount ?: 0
            if (restored > 0) {
                bannerIsError = false
                bannerMessage = "Recovered $restored project(s) linked to Chat ID $chatId from persistent backup."
            }
        }
    }

    fun checkQuotaBeforeUpload(): Boolean {
        if (policyManager.isProjectCountLimitReached(chatId, projects.size)) {
            bannerIsError = true
            bannerMessage = "Project limit reached (${projects.size}/${AdminPolicyManager.formatBotLimitLabel(effectivePolicy.maxHostedProjects)})."
            if (policyStore.monetization.enabled) {
                showUpgradeDialog = true
            }
            return false
        }
        if (policyManager.isStorageQuotaExceeded(chatId)) {
            bannerIsError = true
            bannerMessage = "Storage quota exceeded (${AdminPolicyManager.formatBytes(diskUsedBytes)} / ${AdminPolicyManager.formatQuotaLabel(effectivePolicy.storageQuotaMb)})."
            if (policyStore.monetization.enabled) {
                showUpgradeDialog = true
            }
            return false
        }
        return true
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null || chatId.isBlank()) return@rememberLauncherForActivityResult
        if (!checkQuotaBeforeUpload()) return@rememberLauncherForActivityResult
        scope.launch {
            backupManager.importZipProject(chatId, uri).onSuccess { proj ->
                ActiveServerState.upsertProject(proj)
                refreshDiskUsage()
                bannerIsError = false
                bannerMessage = "Extracted ${proj.name} (entry: ${proj.entryPoint}) & backed up under backups/$chatId/."
            }.onFailure { err ->
                bannerIsError = true
                bannerMessage = err.message ?: "ZIP extraction failed"
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null || chatId.isBlank()) return@rememberLauncherForActivityResult
        if (!checkQuotaBeforeUpload()) return@rememberLauncherForActivityResult
        scope.launch {
            backupManager.importSingleCodeFile(chatId, uri).onSuccess { proj ->
                ActiveServerState.upsertProject(proj)
                refreshDiskUsage()
                bannerIsError = false
                bannerMessage = "Uploaded ${proj.entryPoint} into ${proj.name} & synced backup."
            }.onFailure { err ->
                bannerIsError = true
                bannerMessage = err.message ?: "File upload failed"
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            // Account & Quota Header Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = GlassStrokeBright
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Chat ID: $chatId",
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                color = TextPrimary
                            )
                            Text(
                                text = "@${session?.botUsername.orEmpty()} • Plan: ${effectivePolicy.activePlanName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan
                            )
                        }
                        IconButton(
                            onClick = {
                                policyManager.setUserOnline(chatId, false)
                                ActiveServerState.setSession(null)
                                onLoggedOut()
                            },
                            modifier = Modifier.testTag("hosting_logout_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Logout,
                                contentDescription = "Sign out of Telegram session",
                                tint = CrimsonError
                            )
                        }
                    }

                    // Storage Quota Progress Bar
                    val quotaBytes = if (effectivePolicy.storageQuotaMb == AppConfig.QUOTA_UNLIMITED) {
                        -1L
                    } else {
                        effectivePolicy.storageQuotaMb * 1024L * 1024L
                    }
                    val progress = if (quotaBytes > 0L) {
                        (diskUsedBytes.toFloat() / quotaBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0.08f
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Storage: ${AdminPolicyManager.formatBytes(diskUsedBytes)} / ${AdminPolicyManager.formatQuotaLabel(effectivePolicy.storageQuotaMb)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "Projects: ${projects.size} / ${AdminPolicyManager.formatBotLimitLabel(effectivePolicy.maxHostedProjects)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = if (progress > 0.85f) AmberSignal else NeonEmerald,
                            trackColor = Color(0xFF1E293B)
                        )
                    }

                    if (policyStore.monetization.enabled) {
                        OutlinedButton(
                            onClick = { showUpgradeDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open_upgrade_plans_button"),
                            border = BorderStroke(1.dp, IndigoAccent.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = IndigoAccent)
                        ) {
                            Icon(Icons.Filled.Upgrade, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Upgrade Storage & Limits (100% Ad-Free)")
                        }
                    }
                }
            }
        }

        // Status / Recovery Banner
        if (bannerMessage != null) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    borderColor = if (bannerIsError) CrimsonError.copy(alpha = 0.6f) else NeonEmerald.copy(alpha = 0.5f),
                    containerColor = if (bannerIsError) CrimsonError.copy(alpha = 0.12f) else NeonEmerald.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = bannerMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bannerIsError) CrimsonError else NeonEmerald,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Smart Uploader Controls (ZIP Archive + Single Code File + Quick Starter)
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = GlassStrokeSubtle
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Deploy Bot or Web Project",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("upload_zip_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF04141D)
                            )
                        ) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload ZIP", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("upload_file_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonEmerald)
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Single File")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!checkQuotaBeforeUpload()) return@OutlinedButton
                                scope.launch {
                                    backupManager.createQuickStarterProject(chatId, isStaticWeb = true).onSuccess { proj ->
                                        ActiveServerState.upsertProject(proj)
                                        refreshDiskUsage()
                                        bannerIsError = false
                                        bannerMessage = "Created live HTML starter '${proj.name}'. Tap Run, then Expose (`play`)!"
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_starter_web_button"),
                            border = BorderStroke(1.dp, GlassStrokeSubtle)
                        ) {
                            Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Starter HTML", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                        }
                        OutlinedButton(
                            onClick = {
                                if (!checkQuotaBeforeUpload()) return@OutlinedButton
                                scope.launch {
                                    backupManager.createQuickStarterProject(chatId, isStaticWeb = false).onSuccess { proj ->
                                        ActiveServerState.upsertProject(proj)
                                        refreshDiskUsage()
                                        bannerIsError = false
                                        bannerMessage = "Created Python bot starter '${proj.name}' with requirements.txt."
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_starter_bot_button"),
                            border = BorderStroke(1.dp, GlassStrokeSubtle)
                        ) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Starter Bot", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                        }
                    }
                }
            }
        }

        // Empty State or Deployed Projects List
        if (projects.isEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = GlassStrokeSubtle
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RocketLaunch,
                            contentDescription = "No projects deployed",
                            tint = ElectricCyan,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No Hosted Projects Yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Upload a ZIP archive or single .py / .html / .js file above, or spin up a Starter HTML server to test instant `play` tunneling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            items(projects, key = { it.id }) { project ->
                HostedProjectCard(
                    project = project,
                    logsExpanded = expandedLogsProjectId == project.id,
                    onToggleLogs = {
                        expandedLogsProjectId = if (expandedLogsProjectId == project.id) null else project.id
                    },
                    onStartStop = {
                        if (project.status == ProjectRunStatus.RUNNING) {
                            bootstrapper.stopProject(project.id)
                        } else {
                            bootstrapper.startProject(project)
                        }
                    },
                    onInstallDeps = {
                        scope.launch {
                            bootstrapper.installRequirements(project).onSuccess { msg ->
                                bannerIsError = false
                                bannerMessage = msg
                            }.onFailure { err ->
                                bannerIsError = true
                                bannerMessage = err.message
                            }
                        }
                    },
                    onExposeWithPlay = {
                        ActiveServerState.requestTerminalPlay(project.localUrl)
                        onOpenTerminalForPlay()
                    },
                    onBackup = {
                        scope.launch {
                            backupManager.backupSingleProject(chatId, File(project.directoryPath)).onSuccess { zip ->
                                bannerIsError = false
                                bannerMessage = "Backed up ${project.name} -> backups/$chatId/${zip.name}"
                            }
                        }
                    },
                    onDelete = {
                        bootstrapper.stopProject(project.id)
                        scope.launch {
                            backupManager.deleteUserProject(chatId, project.name, deleteBackupToo = false)
                            ActiveServerState.removeProject(project.id)
                            refreshDiskUsage()
                        }
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }

    // Ad-Free Modular Upgrade & Payment Checkout Dialog
    if (showUpgradeDialog) {
        UpgradePlansDialog(
            chatId = chatId,
            plans = policyStore.monetization.plans,
            paymentManager = paymentManager,
            onDismiss = { showUpgradeDialog = false },
            onUpgraded = { msg ->
                bannerIsError = false
                bannerMessage = msg
                showUpgradeDialog = false
            }
        )
    }
}

@Composable
private fun HostedProjectCard(
    project: HostedProject,
    logsExpanded: Boolean,
    onToggleLogs: () -> Unit,
    onStartStop: () -> Unit,
    onInstallDeps: () -> Unit,
    onExposeWithPlay: () -> Unit,
    onBackup: () -> Unit,
    onDelete: () -> Unit
) {
    val isRunning = project.status == ProjectRunStatus.RUNNING
    val statusColor = when (project.status) {
        ProjectRunStatus.RUNNING -> NeonEmerald
        ProjectRunStatus.INSTALLING_DEPS -> AmberSignal
        ProjectRunStatus.ERROR -> CrimsonError
        ProjectRunStatus.STOPPED -> TextMuted
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isRunning) NeonEmerald.copy(alpha = 0.55f) else GlassStrokeSubtle
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Entry: ${project.entryPoint} • ${project.runtimeType.name.replace('_', ' ')}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
                StatusPill(
                    label = project.status.name,
                    dotColor = statusColor
                )
            }

            if (project.detectedRequirements.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    project.detectedRequirements.forEach { pkg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ElectricCyan.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = pkg,
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (!project.localUrl.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = NeonEmerald.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE: ${project.localUrl}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonEmerald
                        )
                        TextButton(onClick = onExposeWithPlay) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Expose (play)", color = ElectricCyan)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onStartStop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) CrimsonError else NeonEmerald,
                        contentColor = if (isRunning) Color.White else Color(0xFF031B13)
                    )
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRunning) "Stop" else "Start", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onInstallDeps,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Filled.Build, contentDescription = "Install dependencies", modifier = Modifier.size(16.dp), tint = ElectricCyan)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Deps", color = ElectricCyan)
                }

                IconButton(onClick = onBackup) {
                    Icon(Icons.Filled.Backup, contentDescription = "Backup project", tint = TextSecondary)
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete project", tint = CrimsonError)
                }
            }

            TextButton(onClick = onToggleLogs) {
                Text(
                    text = if (logsExpanded) "Hide Console Output (${project.logs.size})" else "View Console Output (${project.logs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
            }

            AnimatedVisibility(visible = logsExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TerminalCanvas)
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (project.logs.isEmpty()) {
                            "No logs yet. Start project or install dependencies."
                        } else {
                            project.logs.takeLast(14).joinToString("\n")
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun UpgradePlansDialog(
    chatId: String,
    plans: List<TierPlan>,
    paymentManager: PaymentGatewayManager,
    onDismiss: () -> Unit,
    onUpgraded: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPlan by remember { mutableStateOf(plans.firstOrNull { it.priceInr > 0 } ?: plans.firstOrNull()) }
    var checkoutSession by remember { mutableStateOf<CheckoutLaunchResult?>(null) }
    var paymentRefInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text("Upgrade Storage & Limits", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "100% Ad-Free Tier Plans • Applied immediately to Chat ID $chatId",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonEmerald
                )
                plans.forEach { plan ->
                    val isSelected = selectedPlan?.id == plan.id
                    Surface(
                        onClick = {
                            selectedPlan = plan
                            checkoutSession = null
                            errorMsg = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) ElectricCyan.copy(alpha = 0.14f) else Color(0xFF17233B),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) ElectricCyan else GlassStrokeSubtle
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(plan.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                Text(
                                    text = if (plan.priceInr == 0) "Free" else "₹${plan.priceInr}/${plan.billingCycle}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricCyan
                                )
                            }
                            Text(
                                text = "${AdminPolicyManager.formatQuotaLabel(plan.storageQuotaMb)} • ${AdminPolicyManager.formatBotLimitLabel(plan.maxHostedProjects)} Bots",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonEmerald
                            )
                            Text(plan.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }

                if (checkoutSession != null) {
                    Text(
                        text = "${checkoutSession?.methodLabel} • Ref: ${checkoutSession?.transactionRef}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                    OutlinedTextField(
                        value = paymentRefInput,
                        onValueChange = { paymentRefInput = it },
                        label = { Text("Enter UTR / Razorpay Payment ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMsg != null) {
                    Text(errorMsg.orEmpty(), style = MaterialTheme.typography.bodySmall, color = CrimsonError)
                }
            }
        },
        confirmButton = {
            val plan = selectedPlan
            if (plan != null && checkoutSession == null) {
                Button(
                    onClick = {
                        if (plan.priceInr == 0) {
                            scope.launch {
                                paymentManager.verifyAndActivatePlan(chatId, plan, "FREE", "FREE")
                                    .onSuccess { onUpgraded(it) }
                            }
                        } else {
                            val result = paymentManager.prepareCheckout(chatId, plan)
                            checkoutSession = result
                            result.externalIntent?.let { intent ->
                                runCatching { context.startActivity(intent) }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color(0xFF04141D))
                ) {
                    Text(if (plan.priceInr == 0) "Select Free" else "Proceed to Pay")
                }
            } else if (plan != null && checkoutSession != null) {
                Button(
                    onClick = {
                        scope.launch {
                            paymentManager.verifyAndActivatePlan(
                                chatId = chatId,
                                plan = plan,
                                transactionRef = checkoutSession!!.transactionRef,
                                paymentProofId = paymentRefInput
                            ).onSuccess { msg ->
                                onUpgraded(msg)
                            }.onFailure { err ->
                                errorMsg = err.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald, contentColor = Color(0xFF031B13))
                ) {
                    Text("Verify & Activate")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
