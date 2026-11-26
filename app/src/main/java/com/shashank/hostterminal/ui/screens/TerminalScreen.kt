package com.shashank.hostterminal.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.data.TunnelSession
import com.shashank.hostterminal.data.TunnelState
import com.shashank.hostterminal.runtime.CloudflaredManager
import com.shashank.hostterminal.runtime.PortScanner
import com.shashank.hostterminal.ui.theme.AmberSignal
import com.shashank.hostterminal.ui.theme.CrimsonError
import com.shashank.hostterminal.ui.theme.DeepSlateSurface
import com.shashank.hostterminal.ui.theme.ElectricCyan
import com.shashank.hostterminal.ui.theme.GlassCard
import com.shashank.hostterminal.ui.theme.GlassStrokeBright
import com.shashank.hostterminal.ui.theme.GlassStrokeSubtle
import com.shashank.hostterminal.ui.theme.JetBrainsMonoFamily
import com.shashank.hostterminal.ui.theme.NeonEmerald
import com.shashank.hostterminal.ui.theme.StatusPill
import com.shashank.hostterminal.ui.theme.TerminalCanvas
import com.shashank.hostterminal.ui.theme.TerminalPromptCyan
import com.shashank.hostterminal.ui.theme.TerminalSuccessGreen
import com.shashank.hostterminal.ui.theme.TextMuted
import com.shashank.hostterminal.ui.theme.TextPrimary
import com.shashank.hostterminal.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

private enum class LineKind { PROMPT, OUTPUT, SUCCESS, ERROR, SYSTEM }

private data class TerminalLine(
    val text: String,
    val kind: LineKind = LineKind.OUTPUT
)

private class TerminalTabState(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    initialDir: File
) {
    var workingDir by mutableStateOf(initialDir)
    val lines = mutableStateListOf<TerminalLine>()
    val history = mutableStateListOf<String>()
    var historyCursor by mutableStateOf(-1)
    var awaitingPlayUrlInput by mutableStateOf(false)
    var runningProcess: Process? = null
    var runningJob: Job? = null

    init {
        lines.add(
            TerminalLine(
                "HostTerminal Linux Shell • 100% Ad-Free • Type 'help', 'ports', or 'play'",
                LineKind.SYSTEM
            )
        )
    }
}

@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by ActiveServerState.session.collectAsStateWithLifecycle()
    val tunnels by ActiveServerState.tunnels.collectAsStateWithLifecycle()
    val pendingPlay by ActiveServerState.pendingPlayTabRequest.collectAsStateWithLifecycle()
    val policyStore by AdminPolicyManager.getInstance(context).store.collectAsStateWithLifecycle()

    val policyManager = remember { AdminPolicyManager.getInstance(context) }
    val cloudflaredManager = remember { CloudflaredManager.getInstance(context) }
    val effectivePolicy = remember(session?.chatId, policyStore) {
        policyManager.resolveEffectivePolicy(session?.chatId)
    }

    val homeDir = remember(session?.chatId) {
        val chatId = session?.chatId
        if (!chatId.isNullOrBlank()) {
            AppConfig.userProjectsDir(context, chatId)
        } else {
            context.filesDir
        }
    }

    val tabs = remember {
        mutableStateListOf(TerminalTabState(title = "tab-1", initialDir = homeDir))
    }
    var activeTabIndex by remember { mutableStateOf(0) }
    var tabCounter by remember { mutableStateOf(1) }
    var commandInput by remember { mutableStateOf("") }
    var ctrlModifierActive by remember { mutableStateOf(false) }
    var copiedNotice by remember { mutableStateOf(false) }

    val activeTab = tabs.getOrElse(activeTabIndex) { tabs.first() }
    val activeTunnel = tunnels[activeTab.id]
    val listState = rememberLazyListState()

    fun appendLine(tab: TerminalTabState, text: String, kind: LineKind = LineKind.OUTPUT) {
        tab.lines.add(TerminalLine(text, kind))
        if (tab.lines.size > AppConfig.MAX_LOG_LINES_PER_SESSION) {
            tab.lines.removeAt(0)
        }
        ActiveServerState.recordGlobalLogLine(text)
    }

    fun killRunningProcessOrTunnel(tab: TerminalTabState) {
        var killedSomething = false
        tab.runningProcess?.let { proc ->
            runCatching { proc.destroy() }
            tab.runningProcess = null
            killedSomething = true
        }
        tab.runningJob?.cancel()
        tab.runningJob = null

        if (cloudflaredManager.stopTunnel(tab.id)) {
            appendLine(tab, "^C — Cloudflared tunnel stopped cleanly.", LineKind.SYSTEM)
            killedSomething = true
        }
        if (tab.awaitingPlayUrlInput) {
            tab.awaitingPlayUrlInput = false
            appendLine(tab, "^C — Cancelled interactive play prompt.", LineKind.SYSTEM)
            killedSomething = true
        }
        if (!killedSomething) {
            appendLine(tab, "^C", LineKind.SYSTEM)
        }
    }

    fun startIntelligentPlay(tab: TerminalTabState, explicitUrl: String? = null) {
        if (!effectivePolicy.allowCloudflaredTunnel) {
            appendLine(
                tab,
                "[play] Permission denied: Cloudflared Public Tunnel is disabled by Super Admin policy.",
                LineKind.ERROR
            )
            return
        }

        if (!explicitUrl.isNullOrBlank() && explicitUrl != "AUTO") {
            val normalized = PortScanner.normalizeUserTargetInput(explicitUrl)
            if (normalized == null) {
                appendLine(
                    tab,
                    "[play] Invalid local target '$explicitUrl'. Use e.g. http://127.0.0.1:8080 or 8080",
                    LineKind.ERROR
                )
                return
            }
            appendLine(tab, "[play] Target URL locked: $normalized", LineKind.SUCCESS)
            cloudflaredManager.startTunnel(tab.id, normalized) { msg ->
                scope.launch(Dispatchers.Main) {
                    appendLine(tab, msg, if (msg.contains("live:")) LineKind.SUCCESS else LineKind.SYSTEM)
                }
            }
            return
        }

        appendLine(tab, "[play] Scanning ActiveServerState, open logs & /proc/net/tcp...", LineKind.SYSTEM)
        scope.launch {
            val detected = withContext(Dispatchers.IO) {
                PortScanner.detectActiveLocalServer(tab.lines.map { it.text })
            }
            if (detected != null) {
                appendLine(
                    tab,
                    "[play] Auto-detected ${detected.url} via ${detected.source}",
                    LineKind.SUCCESS
                )
                cloudflaredManager.startTunnel(tab.id, detected.url) { msg ->
                    scope.launch(Dispatchers.Main) {
                        appendLine(tab, msg, if (msg.contains("live:")) LineKind.SUCCESS else LineKind.SYSTEM)
                    }
                }
            } else {
                tab.awaitingPlayUrlInput = true
                appendLine(
                    tab,
                    "Enter local URL or Port to expose (e.g., http://127.0.0.1:8080): ",
                    LineKind.PROMPT
                )
            }
        }
    }

    fun executeCommand(tab: TerminalTabState, raw: String) {
        val trimmed = raw.trim()

        // Interactive fallback for `play` when no local server was auto-detected
        if (tab.awaitingPlayUrlInput) {
            tab.awaitingPlayUrlInput = false
            appendLine(tab, "> $trimmed", LineKind.PROMPT)
            if (trimmed.isEmpty() || trimmed.equals("cancel", true) || trimmed.equals("stop", true)) {
                appendLine(tab, "[play] Cancelled.", LineKind.SYSTEM)
                return
            }
            val normalized = PortScanner.normalizeUserTargetInput(trimmed)
            if (normalized == null) {
                appendLine(
                    tab,
                    "[play] Please enter a valid local URL or port (e.g. http://127.0.0.1:8080 or 8080).",
                    LineKind.ERROR
                )
                tab.awaitingPlayUrlInput = true
                appendLine(
                    tab,
                    "Enter local URL or Port to expose (e.g., http://127.0.0.1:8080): ",
                    LineKind.PROMPT
                )
                return
            }
            startIntelligentPlay(tab, normalized)
            return
        }

        if (trimmed.isEmpty()) return

        val shortDir = tab.workingDir.name.ifBlank { "~" }
        appendLine(tab, "host@android:$shortDir$ $trimmed", LineKind.PROMPT)
        tab.history.add(trimmed)
        tab.historyCursor = tab.history.size

        val parts = trimmed.split(Regex("\\s+"))
        val cmd = parts.firstOrNull().orEmpty()

        when (cmd) {
            "clear", "cls" -> {
                tab.lines.clear()
            }
            "help" -> {
                appendLine(tab, "Built-in & Linux Commands:", LineKind.SYSTEM)
                appendLine(tab, "  play [url|port]   Auto-detect active local server & start Cloudflared tunnel")
                appendLine(tab, "  stop              Kill active Cloudflared tunnel or running shell process")
                appendLine(tab, "  ports             Inspect /proc/net/tcp & ActiveServerState listening ports")
                appendLine(tab, "  cd <dir>          Change working directory")
                appendLine(tab, "  pwd / ls / cat    Run standard Android Linux shell commands")
                appendLine(tab, "  clear             Clear terminal buffer")
            }
            "play" -> {
                val arg = parts.getOrNull(1)
                startIntelligentPlay(tab, arg)
            }
            "stop" -> {
                killRunningProcessOrTunnel(tab)
            }
            "ports" -> {
                scope.launch {
                    val ports = withContext(Dispatchers.IO) { PortScanner.listListeningTcpPorts() }
                    val active = ActiveServerState.activeLocalUrl.value
                    appendLine(tab, "ActiveServerState URL: ${active ?: "none"}", LineKind.SYSTEM)
                    if (ports.isEmpty()) {
                        appendLine(tab, "No listening user-space TCP ports found in /proc/net/tcp.", LineKind.OUTPUT)
                    } else {
                        appendLine(tab, "Listening TCP ports: ${ports.joinToString(", ")}", LineKind.SUCCESS)
                    }
                }
            }
            "pwd" -> {
                appendLine(tab, tab.workingDir.absolutePath, LineKind.OUTPUT)
            }
            "cd" -> {
                val targetArg = parts.getOrNull(1)
                val targetDir = when {
                    targetArg.isNullOrBlank() || targetArg == "~" -> homeDir
                    targetArg == ".." -> tab.workingDir.parentFile ?: tab.workingDir
                    targetArg.startsWith("/") -> File(targetArg)
                    else -> File(tab.workingDir, targetArg)
                }
                if (targetDir.exists() && targetDir.isDirectory) {
                    tab.workingDir = targetDir.canonicalFile
                } else {
                    appendLine(tab, "cd: $targetArg: No such directory", LineKind.ERROR)
                }
            }
            "exit" -> {
                if (tabs.size > 1) {
                    killRunningProcessOrTunnel(tab)
                    val idx = tabs.indexOf(tab)
                    if (idx >= 0) {
                        tabs.removeAt(idx)
                        activeTabIndex = (idx - 1).coerceAtLeast(0)
                    }
                } else {
                    appendLine(tab, "Last open tab cannot be closed. Use clear to reset.", LineKind.SYSTEM)
                }
            }
            else -> {
                // Execute via real Android Linux /system/bin/sh
                tab.runningJob?.cancel()
                tab.runningJob = scope.launch(Dispatchers.IO) {
                    try {
                        val pb = ProcessBuilder("/system/bin/sh", "-c", trimmed).apply {
                            directory(tab.workingDir)
                            redirectErrorStream(true)
                            val existingPath = environment()["PATH"].orEmpty()
                            environment()["PATH"] =
                                "${AppConfig.binDir(context).absolutePath}:$existingPath"
                            environment()["HOME"] = homeDir.absolutePath
                        }
                        val proc = pb.start()
                        tab.runningProcess = proc
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            var line: String? = null
                            while (reader.readLine().also { line = it } != null) {
                                val text = line.orEmpty()
                                withContext(Dispatchers.Main) {
                                    appendLine(tab, text, LineKind.OUTPUT)
                                }
                            }
                        }
                        val code = proc.waitFor()
                        tab.runningProcess = null
                        if (code != 0) {
                            withContext(Dispatchers.Main) {
                                appendLine(tab, "[exit code $code]", LineKind.ERROR)
                            }
                        }
                    } catch (e: Exception) {
                        tab.runningProcess = null
                        withContext(Dispatchers.Main) {
                            appendLine(tab, "sh error: ${e.message}", LineKind.ERROR)
                        }
                    }
                }
            }
        }
    }

    // Handle one-tap "Expose (play)" trigger from HostingDashboardScreen
    LaunchedEffect(pendingPlay) {
        val target = ActiveServerState.consumeTerminalPlayRequest()
        if (target != null) {
            startIntelligentPlay(activeTab, if (target == "AUTO") null else target)
        }
    }

    // Auto-scroll to bottom when new terminal lines arrive
    LaunchedEffect(activeTab.lines.size, activeTabIndex) {
        if (activeTab.lines.isNotEmpty()) {
            listState.animateScrollToItem(activeTab.lines.lastIndex)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Keep Cloudflared tunnels alive in background; clean up transient interactive shell processes
            tabs.forEach { it.runningProcess?.destroy() }
        }
    }

    // Enforce Admin Policy: Terminal Access Toggle
    if (!effectivePolicy.allowTerminalAccess) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = CrimsonError.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Terminal access restricted",
                        tint = CrimsonError,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Terminal Access Revoked",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Super Admin policy has disabled Terminal Access for your account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Multi-Tab Session Manager Strip (+ New Tab)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == activeTabIndex
                val hasTunnel = tunnels[tab.id]?.state == TunnelState.ACTIVE
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) ElectricCyan.copy(alpha = 0.18f) else DeepSlateSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) ElectricCyan else GlassStrokeSubtle
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { activeTabIndex = index }
                        .testTag("terminal_tab_${index + 1}")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (hasTunnel) Icons.Filled.Public else Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = if (hasTunnel) NeonEmerald else if (isSelected) ElectricCyan else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) TextPrimary else TextSecondary
                        )
                        if (tabs.size > 1) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close ${tab.title}",
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable {
                                        killRunningProcessOrTunnel(tab)
                                        ActiveServerState.removeTunnel(tab.id)
                                        tabs.removeAt(index)
                                        if (activeTabIndex >= tabs.size) {
                                            activeTabIndex = (tabs.size - 1).coerceAtLeast(0)
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NeonEmerald.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.45f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        tabCounter++
                        tabs.add(TerminalTabState(title = "tab-$tabCounter", initialDir = homeDir))
                        activeTabIndex = tabs.lastIndex
                    }
                    .testTag("terminal_new_tab_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Open new terminal tab",
                        tint = NeonEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "New Tab",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonEmerald
                    )
                }
            }
        }

        // Clean Highlighted Cloudflared Tunnel Link Card (inside the active terminal tab)
        AnimatedVisibility(visible = activeTunnel != null && activeTunnel.state != TunnelState.IDLE) {
            activeTunnel?.let { tunnel ->
                HighlightedTunnelLinkCard(
                    tunnel = tunnel,
                    copiedNotice = copiedNotice,
                    onCopy = { url ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Cloudflared Public URL", url))
                        copiedNotice = true
                    },
                    onShare = { url ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                            putExtra(Intent.EXTRA_SUBJECT, "HostTerminal Live Tunnel")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Tunnel URL"))
                    },
                    onStop = {
                        cloudflaredManager.stopTunnel(activeTab.id)
                        appendLine(activeTab, "[play] Tunnel stopped by user.", LineKind.SYSTEM)
                    },
                    onRestart = {
                        copiedNotice = false
                        cloudflaredManager.restartTunnel(activeTab.id) { msg ->
                            scope.launch(Dispatchers.Main) {
                                appendLine(activeTab, msg, LineKind.SYSTEM)
                            }
                        }
                    }
                )
            }
        }

        // Main Terminal Output Buffer
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = TerminalCanvas,
            border = BorderStroke(1.dp, GlassStrokeSubtle)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(activeTab.lines) { _, line ->
                    val textColor = when (line.kind) {
                        LineKind.PROMPT -> TerminalPromptCyan
                        LineKind.SUCCESS -> TerminalSuccessGreen
                        LineKind.ERROR -> CrimsonError
                        LineKind.SYSTEM -> AmberSignal
                        LineKind.OUTPUT -> TextPrimary
                    }
                    Text(
                        text = line.text,
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = textColor
                        )
                    )
                }
            }
        }

        // Extra Hardware-Style Shortcut Keys Bar (ESC, CTRL, TAB, UP, DOWN, CLEAR)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ShortcutKeyButton(
                label = "ESC",
                active = false,
                testTag = "shortcut_key_esc",
                onClick = {
                    ctrlModifierActive = false
                    commandInput = ""
                    if (activeTab.awaitingPlayUrlInput) {
                        activeTab.awaitingPlayUrlInput = false
                        appendLine(activeTab, "[ESC] Cancelled prompt.", LineKind.SYSTEM)
                    }
                }
            )
            ShortcutKeyButton(
                label = "CTRL",
                active = ctrlModifierActive,
                testTag = "shortcut_key_ctrl",
                onClick = {
                    if (ctrlModifierActive) {
                        // Second tap triggers instant Ctrl+C interrupt
                        ctrlModifierActive = false
                        killRunningProcessOrTunnel(activeTab)
                    } else {
                        ctrlModifierActive = true
                    }
                }
            )
            ShortcutKeyButton(
                label = "CTRL+C",
                active = false,
                testTag = "shortcut_key_ctrl_c",
                accent = CrimsonError,
                onClick = {
                    ctrlModifierActive = false
                    killRunningProcessOrTunnel(activeTab)
                }
            )
            ShortcutKeyButton(
                label = "TAB",
                active = false,
                testTag = "shortcut_key_tab",
                onClick = {
                    val token = commandInput.substringAfterLast(" ")
                    val builtIns = listOf("play", "stop", "ports", "clear", "help", "ls", "cd", "pwd", "cat")
                    val files = activeTab.workingDir.list()?.toList().orEmpty()
                    val match = (builtIns + files).firstOrNull {
                        it.startsWith(token, ignoreCase = true) && it != token
                    }
                    if (match != null) {
                        val prefix = if (commandInput.contains(" ")) {
                            commandInput.substringBeforeLast(" ") + " "
                        } else {
                            ""
                        }
                        commandInput = prefix + match
                    }
                }
            )
            ShortcutKeyButton(
                label = "UP",
                active = false,
                testTag = "shortcut_key_up",
                onClick = {
                    if (activeTab.history.isNotEmpty()) {
                        activeTab.historyCursor = (activeTab.historyCursor - 1).coerceAtLeast(0)
                        commandInput = activeTab.history[activeTab.historyCursor]
                    }
                }
            )
            ShortcutKeyButton(
                label = "DOWN",
                active = false,
                testTag = "shortcut_key_down",
                onClick = {
                    if (activeTab.history.isNotEmpty()) {
                        if (activeTab.historyCursor < activeTab.history.lastIndex) {
                            activeTab.historyCursor++
                            commandInput = activeTab.history[activeTab.historyCursor]
                        } else {
                            activeTab.historyCursor = activeTab.history.size
                            commandInput = ""
                        }
                    }
                }
            )
            ShortcutKeyButton(
                label = "CLEAR",
                active = false,
                testTag = "shortcut_key_clear",
                accent = ElectricCyan,
                onClick = { activeTab.lines.clear() }
            )
        }

        // Command Input Prompt Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = DeepSlateSurface,
            border = BorderStroke(
                1.dp,
                if (activeTab.awaitingPlayUrlInput) NeonEmerald else GlassStrokeBright
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (activeTab.awaitingPlayUrlInput) "url>" else "$",
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (activeTab.awaitingPlayUrlInput) NeonEmerald else ElectricCyan
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (commandInput.isEmpty()) {
                        Text(
                            text = if (activeTab.awaitingPlayUrlInput) {
                                "http://127.0.0.1:8080 or 8080"
                            } else {
                                "Type command (e.g., play, ports, ls)..."
                            },
                            style = TextStyle(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.5.sp,
                                color = TextMuted
                            )
                        )
                    }
                    BasicTextField(
                        value = commandInput,
                        onValueChange = { next ->
                            if (ctrlModifierActive && next.endsWith("c", ignoreCase = true)) {
                                ctrlModifierActive = false
                                commandInput = ""
                                killRunningProcessOrTunnel(activeTab)
                            } else if (ctrlModifierActive && next.endsWith("l", ignoreCase = true)) {
                                ctrlModifierActive = false
                                commandInput = ""
                                activeTab.lines.clear()
                            } else {
                                commandInput = next
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 14.sp,
                            color = TextPrimary
                        ),
                        cursorBrush = SolidColor(ElectricCyan),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val cmdToRun = commandInput
                                commandInput = ""
                                ctrlModifierActive = false
                                executeCommand(activeTab, cmdToRun)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("terminal_command_input")
                    )
                }
                IconButton(
                    onClick = {
                        val cmdToRun = commandInput
                        commandInput = ""
                        ctrlModifierActive = false
                        executeCommand(activeTab, cmdToRun)
                    },
                    modifier = Modifier.testTag("terminal_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Execute terminal command",
                        tint = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun HighlightedTunnelLinkCard(
    tunnel: TunnelSession,
    copiedNotice: Boolean,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val isLive = tunnel.state == TunnelState.ACTIVE && !tunnel.publicUrl.isNullOrBlank()
    val isBusy = tunnel.state == TunnelState.INSTALLING_BINARY || tunnel.state == TunnelState.STARTING
    val border = when (tunnel.state) {
        TunnelState.ACTIVE -> NeonEmerald.copy(alpha = 0.75f)
        TunnelState.ERROR -> CrimsonError.copy(alpha = 0.7f)
        TunnelState.STOPPED -> TextMuted.copy(alpha = 0.45f)
        else -> ElectricCyan.copy(alpha = 0.65f)
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tunnel_link_card"),
        cornerRadius = 16.dp,
        borderColor = border,
        containerColor = if (isLive) Color(0xE6082221) else Color(0xE6111A2E)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ElectricCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = "Cloudflared tunnel status",
                            tint = if (isLive) NeonEmerald else if (tunnel.state == TunnelState.ERROR) CrimsonError else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "Cloudflared Quick Tunnel",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                }
                StatusPill(
                    label = tunnel.state.name,
                    dotColor = when (tunnel.state) {
                        TunnelState.ACTIVE -> NeonEmerald
                        TunnelState.ERROR -> CrimsonError
                        TunnelState.STOPPED -> TextMuted
                        else -> ElectricCyan
                    }
                )
            }

            if (isLive) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF041418),
                    border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = tunnel.publicUrl.orEmpty(),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = NeonEmerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Forwarding -> ${tunnel.localTargetUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                Text(
                    text = tunnel.statusMessage.ifBlank { "Target: ${tunnel.localTargetUrl}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tunnel.state == TunnelState.ERROR) CrimsonError else TextSecondary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLive) {
                    Button(
                        onClick = { onCopy(tunnel.publicUrl!!) },
                        modifier = Modifier.testTag("tunnel_copy_link_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonEmerald,
                            contentColor = Color(0xFF031B13)
                        )
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (copiedNotice) "Copied!" else "Copy Link", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { onShare(tunnel.publicUrl!!) },
                        modifier = Modifier.testTag("tunnel_share_link_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", color = ElectricCyan)
                    }
                }

                if (isLive || isBusy) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("tunnel_stop_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.65f))
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp), tint = CrimsonError)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", color = CrimsonError)
                    }
                }

                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.testTag("tunnel_restart_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, GlassStrokeBright)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restart", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ShortcutKeyButton(
    label: String,
    active: Boolean,
    testTag: String,
    accent: Color = ElectricCyan,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (active) accent.copy(alpha = 0.28f) else DeepSlateSurface,
        border = BorderStroke(1.dp, if (active) accent else GlassStrokeSubtle),
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) accent else TextPrimary
            )
        }
    }
}
