package com.shashank.hostterminal.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.data.AuthenticatedSession
import com.shashank.hostterminal.data.BackupManager
import com.shashank.hostterminal.network.OtpVerifyResult
import com.shashank.hostterminal.network.TelegramAuthManager
import com.shashank.hostterminal.network.TelegramBotInfo
import com.shashank.hostterminal.service.HostingForegroundService
import com.shashank.hostterminal.ui.theme.AmberSignal
import com.shashank.hostterminal.ui.theme.CrimsonError
import com.shashank.hostterminal.ui.theme.ElectricCyan
import com.shashank.hostterminal.ui.theme.GlassCard
import com.shashank.hostterminal.ui.theme.GlassStrokeBright
import com.shashank.hostterminal.ui.theme.GlassStrokeSubtle
import com.shashank.hostterminal.ui.theme.JetBrainsMonoFamily
import com.shashank.hostterminal.ui.theme.NeonEmerald
import com.shashank.hostterminal.ui.theme.StatusPill
import com.shashank.hostterminal.ui.theme.TextMuted
import com.shashank.hostterminal.ui.theme.TextPrimary
import com.shashank.hostterminal.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TelegramLoginScreen(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val authManager = remember { TelegramAuthManager() }
    val policyManager = remember { AdminPolicyManager.getInstance(context) }
    val backupManager = remember { BackupManager(context) }

    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var batteryOptIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    var botToken by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var userChatId by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }

    var botLookupLoading by remember { mutableStateOf(false) }
    var verifiedBot by remember { mutableStateOf<TelegramBotInfo?>(null) }
    var botLookupError by remember { mutableStateOf<String?>(null) }

    var isPollingOtp by remember { mutableStateOf(false) }
    var otpSent by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adminMirrored by remember { mutableStateOf(false) }
    var pollingJob by remember { mutableStateOf<Job?>(null) }

    val legacyPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        storageGranted = hasStorageAccess(context)
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* handled gracefully */ }

    // Refresh permission status whenever returning from Android System Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = hasStorageAccess(context)
                batteryOptIgnored = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pollingJob?.cancel()
        }
    }

    // Request runtime notification permission (API 33+) & legacy storage permissions on first entry
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !storageGranted) {
            legacyPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // Asynchronously call getMe whenever a valid-looking Bot Token is entered
    LaunchedEffect(botToken) {
        verifiedBot = null
        botLookupError = null
        otpSent = false
        val trimmed = botToken.trim()
        if (trimmed.isEmpty()) {
            botLookupLoading = false
            return@LaunchedEffect
        }
        if (!authManager.isTokenSyntaxPlausible(trimmed)) {
            botLookupLoading = false
            return@LaunchedEffect
        }
        botLookupLoading = true
        delay(450L)
        val result = authManager.lookupBot(trimmed)
        botLookupLoading = false
        result.onSuccess { info ->
            verifiedBot = info
            botLookupError = null
        }.onFailure { err ->
            verifiedBot = null
            botLookupError = err.message ?: "Unable to verify Bot Token"
        }
    }

    fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(fallback)
            }
        } else {
            legacyPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations(context)) {
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ElectricCyan,
        unfocusedBorderColor = Color(0xFF2B3E5E),
        focusedLabelColor = ElectricCyan,
        unfocusedLabelColor = TextSecondary,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = ElectricCyan
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Telegram OTP Login",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Zero-email authentication • Indexed strictly by User Chat ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            StatusPill(
                label = "NO EMAIL",
                dotColor = ElectricCyan
            )
        }

        // Step 1: Storage Permissions Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = if (storageGranted) NeonEmerald.copy(alpha = 0.45f) else AmberSignal.copy(alpha = 0.55f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (storageGranted) Icons.Filled.CheckCircle else Icons.Filled.FolderOpen,
                        contentDescription = "Storage permission status",
                        tint = if (storageGranted) NeonEmerald else AmberSignal,
                        modifier = Modifier.size(26.dp)
                    )
                    Column {
                        Text(
                            text = if (storageGranted) "Storage Access Granted" else "Storage Access Required",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                "All-files access (Android 11+) for ZIP/code project hosting & backups."
                            } else {
                                "Read/Write storage access for ZIP/code project hosting & backups."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                if (!storageGranted) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { requestStoragePermissions() },
                        modifier = Modifier.testTag("grant_storage_permission_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberSignal,
                            contentColor = Color(0xFF1F1202)
                        )
                    ) {
                        Text("Grant", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Step 2: Bot Token & User Chat ID Form
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = GlassStrokeBright
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. Connect Your Telegram Bot & Chat ID",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )

                OutlinedTextField(
                    value = botToken,
                    onValueChange = { botToken = it },
                    label = { Text("Telegram Bot Token (from @BotFather)") },
                    placeholder = { Text("123456789:AAH...", color = TextMuted) },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Filled.Key, contentDescription = "Bot token", tint = ElectricCyan)
                    },
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (tokenVisible) "Hide token" else "Show token",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bot_token_input")
                )

                // Live getMe lookup status badge
                if (botLookupLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ElectricCyan
                        )
                        Text(
                            text = "Calling Telegram getMe API...",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    }
                }

                AnimatedVisibility(visible = verifiedBot != null) {
                    verifiedBot?.let { bot ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            borderColor = NeonEmerald.copy(alpha = 0.55f),
                            containerColor = NeonEmerald.copy(alpha = 0.10f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SmartToy,
                                    contentDescription = "Verified bot profile",
                                    tint = NeonEmerald
                                )
                                Column {
                                    Text(
                                        text = bot.firstName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "@${bot.username} • Verified via getMe",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NeonEmerald
                                    )
                                }
                            }
                        }
                    }
                }

                if (botLookupError != null) {
                    Text(
                        text = botLookupError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonError
                    )
                }

                OutlinedTextField(
                    value = userChatId,
                    onValueChange = { input ->
                        userChatId = input.filter { it.isDigit() || it == '-' }
                    },
                    label = { Text("Your Numeric User Chat ID (Primary Key)") },
                    placeholder = { Text("e.g., 1092837465", color = TextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = {
                        Icon(Icons.Filled.Security, contentDescription = "User Chat ID", tint = NeonEmerald)
                    },
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("user_chat_id_input")
                )

                // Revoked Token Recovery info callout
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = "Revoked token recovery",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Revoked old token? Log in with ANY new valid Bot Token + your existing Chat ID to auto-restore all backed-up projects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Start & Verify Action Button
                val canStartVerify = verifiedBot != null && authManager.isChatIdValid(userChatId) && !isPollingOtp
                Button(
                    onClick = {
                        val bot = verifiedBot ?: return@Button
                        val cleanChatId = userChatId.trim()
                        if (policyManager.isUserBlocked(cleanChatId)) {
                            errorMessage = "Access Denied: Chat ID $cleanChatId is blocked by the Super Admin."
                            return@Button
                        }
                        errorMessage = null
                        otpSent = false
                        isPollingOtp = true
                        statusMessage = "Opening @${bot.username} in Telegram..."

                        // Open Telegram directly: https://t.me/<bot_username>?start=auth
                        val deepLink = authManager.buildStartDeepLink(bot.username)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
                        }

                        pollingJob?.cancel()
                        pollingJob = scope.launch {
                            val result = authManager.pollStrictStartAndDispatchOtp(
                                botToken = botToken.trim(),
                                expectedChatId = cleanChatId,
                                botInfo = bot,
                                onStatusUpdate = { msg -> statusMessage = msg }
                            )
                            isPollingOtp = false
                            result.onSuccess { challenge ->
                                otpSent = true
                                adminMirrored = challenge.adminMirrorDelivered
                                statusMessage = "6-digit OTP sent to Chat ID ${challenge.chatId}. Tap the code in Telegram to copy!"
                            }.onFailure { err ->
                                errorMessage = err.message ?: "OTP trigger failed"
                            }
                        }
                    },
                    enabled = canStartVerify,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_and_verify_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF04141D)
                    )
                ) {
                    if (isPollingOtp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF04141D)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Waiting for /start from Chat ID...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start & Verify in Telegram", fontWeight = FontWeight.Bold)
                    }
                }

                if (statusMessage != null) {
                    Text(
                        text = statusMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonError
                    )
                }
            }
        }

        // Step 3: 6-Digit OTP Verification Card
        AnimatedVisibility(visible = otpSent || isPollingOtp) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = NeonEmerald.copy(alpha = 0.55f)
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
                        Text(
                            text = "2. Enter 6-Digit Telegram OTP",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        if (adminMirrored) {
                            StatusPill(label = "ADMIN MIRRORED", dotColor = ElectricCyan)
                        }
                    }

                    OutlinedTextField(
                        value = otpInput,
                        onValueChange = { otpInput = it.filter(Char::isDigit).take(6) },
                        label = { Text("6-Digit OTP Code") },
                        placeholder = { Text("123456", color = TextMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        leadingIcon = {
                            Icon(Icons.Filled.Lock, contentDescription = "OTP Code", tint = NeonEmerald)
                        },
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = JetBrainsMonoFamily,
                            color = TextPrimary
                        ),
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("otp_code_input")
                    )

                    Button(
                        onClick = {
                            val cleanChatId = userChatId.trim()
                            if (policyManager.isUserBlocked(cleanChatId)) {
                                errorMessage = "Login denied: Your Chat ID has been blocked by Super Admin."
                                return@Button
                            }
                            when (val res = authManager.verifyOtp(cleanChatId, otpInput)) {
                                is OtpVerifyResult.Success -> {
                                    errorMessage = null
                                    scope.launch {
                                        val restoredCount = backupManager.restoreUserProjectsIfNeeded(cleanChatId)
                                        policyManager.registerOrUpdateUser(
                                            chatId = cleanChatId,
                                            botUsername = res.challenge.botInfo.username,
                                            botName = res.challenge.botInfo.firstName,
                                            isOnline = true
                                        )
                                        val isSuperAdmin = AppConfig.isSuperAdmin(cleanChatId)
                                        ActiveServerState.setSession(
                                            AuthenticatedSession(
                                                chatId = cleanChatId,
                                                botToken = res.challenge.botToken,
                                                botName = res.challenge.botInfo.firstName,
                                                botUsername = res.challenge.botInfo.username,
                                                isSuperAdmin = isSuperAdmin,
                                                restoredProjectCount = restoredCount
                                            )
                                        )
                                        val loaded = backupManager.loadUserProjects(cleanChatId)
                                        ActiveServerState.setProjects(loaded)

                                        // Prompt for Ignore Battery Optimizations & start persistent Foreground Service
                                        if (!isIgnoringBatteryOptimizations(context)) {
                                            requestIgnoreBatteryOptimizations()
                                        }
                                        HostingForegroundService.start(context, cleanChatId)
                                        onAuthenticated()
                                    }
                                }
                                is OtpVerifyResult.Invalid -> {
                                    errorMessage = "Incorrect OTP. ${res.attemptsLeft} attempt(s) remaining."
                                }
                                OtpVerifyResult.Expired -> {
                                    errorMessage = "OTP expired after 5 minutes. Tap Start & Verify to request a new code."
                                    otpSent = false
                                }
                                OtpVerifyResult.NoChallenge -> {
                                    errorMessage = "Tap 'Start & Verify in Telegram' and send /start from your Chat ID first."
                                }
                            }
                        },
                        enabled = otpInput.length == 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("verify_otp_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonEmerald,
                            contentColor = Color(0xFF031B13)
                        )
                    ) {
                        Text("Verify OTP & Launch Hosting Engine", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Battery Optimization Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = GlassStrokeSubtle
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = "Battery optimization exemption",
                        tint = if (batteryOptIgnored) NeonEmerald else ElectricCyan
                    )
                    Column {
                        Text(
                            text = if (batteryOptIgnored) "24/7 Battery Exemption Active" else "Unrestricted Background Execution",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Prevents Android Doze from suspending bots & tunnels when screen is off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                if (!batteryOptIgnored) {
                    OutlinedButton(
                        onClick = { requestIgnoreBatteryOptimizations() },
                        modifier = Modifier.testTag("battery_opt_button"),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
                    ) {
                        Text("Allow", color = ElectricCyan)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun hasStorageAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        read && write
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else {
        true
    }
}
