package com.shashank.hostterminal.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.ProjectRunStatus
import com.shashank.hostterminal.data.TunnelState
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
import com.shashank.hostterminal.ui.theme.ObsidianCanvas
import com.shashank.hostterminal.ui.theme.StatusPill
import com.shashank.hostterminal.ui.theme.TextMuted
import com.shashank.hostterminal.ui.theme.TextPrimary
import com.shashank.hostterminal.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    onOpenTerminal: () -> Unit,
    onOpenHosting: () -> Unit,
    onOpenAdmin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val session by ActiveServerState.session.collectAsStateWithLifecycle()
    val projects by ActiveServerState.hostedProjects.collectAsStateWithLifecycle()
    val tunnels by ActiveServerState.tunnels.collectAsStateWithLifecycle()
    val activeLocalUrl by ActiveServerState.activeLocalUrl.collectAsStateWithLifecycle()
    val serviceRunning by ActiveServerState.serviceRunning.collectAsStateWithLifecycle()
    val wakeLockHeld by ActiveServerState.wakeLockHeld.collectAsStateWithLifecycle()

    val runningProjects = projects.count { it.status == ProjectRunStatus.RUNNING }
    val activeTunnels = tunnels.values.filter { it.state == TunnelState.ACTIVE }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Glassmorphic Hero Banner with generated ambient artwork
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(ObsidianCanvas)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_terminal_cloud_1795726471145),
                contentDescription = "HostTerminal cloud and terminal banner artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xEB060A13),
                                Color(0xB8060A13),
                                Color(0x55060A13)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = "100% AD-FREE",
                        dotColor = NeonEmerald
                    )
                    StatusPill(
                        label = if (serviceRunning) "ENGINE LIVE" else "STANDBY",
                        dotColor = if (serviceRunning) ElectricCyan else AmberSignal
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "HostTerminal",
                        style = MaterialTheme.typography.displaySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Multi-tab Linux shell, zero-code Telegram Bot & Web Cloud, and 1-command Cloudflared tunneling.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Compact Real-Time Telemetry Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricGlassTile(
                modifier = Modifier.weight(1f),
                title = "Hosted",
                value = "$runningProjects / ${projects.size}",
                icon = Icons.Filled.CloudQueue,
                accent = ElectricCyan
            )
            MetricGlassTile(
                modifier = Modifier.weight(1f),
                title = "Tunnels",
                value = "${activeTunnels.size}",
                icon = Icons.Filled.Public,
                accent = NeonEmerald
            )
            MetricGlassTile(
                modifier = Modifier.weight(1f),
                title = "WakeLock",
                value = if (wakeLockHeld) "24/7 ON" else "OFF",
                icon = Icons.Filled.Memory,
                accent = if (wakeLockHeld) NeonEmerald else AmberSignal
            )
        }

        // Primary Launch Module 1: Linux Terminal
        LaunchModuleCard(
            modifier = Modifier.testTag("home_open_terminal_card"),
            title = "Linux Multi-Tab Terminal",
            subtitle = "Independent shell sessions, ESC/CTRL/TAB/UP/DOWN/CLEAR keys & intelligent `play` Cloudflared tunnels.",
            badge = "play AUTO-TUNNEL",
            icon = Icons.Filled.Terminal,
            accentColor = ElectricCyan,
            onClick = onOpenTerminal
        )

        // Primary Launch Module 2: Bot & Web Hosting
        LaunchModuleCard(
            modifier = Modifier.testTag("home_open_hosting_card"),
            title = "Bot & Web Hosting",
            subtitle = if (session != null) {
                "Signed in as Chat ID ${session?.chatId} (@${session?.botUsername}). Deploy ZIP/code projects with auto --prefer-binary."
            } else {
                "Zero-email Telegram Bot Token + Chat ID OTP login, auto dependency resolver & persistent Chat ID backup recovery."
            },
            badge = if (session != null) "VERIFIED SESSION" else "TELEGRAM OTP AUTH",
            icon = Icons.Filled.Hub,
            accentColor = NeonEmerald,
            onClick = onOpenHosting
        )

        // Super Admin Panel Card (Unlocked when logged-in Chat ID matches SUPER_ADMIN_CHAT_ID)
        if (session?.isSuperAdmin == true) {
            LaunchModuleCard(
                modifier = Modifier.testTag("home_open_admin_card"),
                title = "Super Admin Control Panel",
                subtitle = "Manage users, Block/Unblock switches, storage/bot quotas, file explorer & ad-free upgrade plans.",
                badge = "SUPER ADMIN UNLOCKED",
                icon = Icons.Filled.AdminPanelSettings,
                accentColor = IndigoAccent,
                onClick = onOpenAdmin
            )
        }

        // Active Local Server & Live Tunnel Preview Card
        if (activeLocalUrl != null || activeTunnels.isNotEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = GlassStrokeBright
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = "Active network endpoints",
                            tint = NeonEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Active Endpoints",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                    if (activeLocalUrl != null) {
                        Text(
                            text = "Local Server: $activeLocalUrl",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = ElectricCyan
                        )
                    }
                    activeTunnels.forEach { tunnel ->
                        if (!tunnel.publicUrl.isNullOrBlank()) {
                            Text(
                                text = "Public Tunnel: ${tunnel.publicUrl}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = NeonEmerald
                            )
                        }
                    }
                }
            }
        }

        // Persistent Background Engine Control Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = GlassStrokeSubtle
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
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (serviceRunning) NeonEmerald.copy(alpha = 0.16f)
                                else ElectricCyan.copy(alpha = 0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = "Background service status",
                            tint = if (serviceRunning) NeonEmerald else ElectricCyan
                        )
                    }
                    Column {
                        Text(
                            text = if (serviceRunning) "Background Service Active" else "Background Engine Idle",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (serviceRunning) {
                                "Foreground WakeLock keeps bots & tunnels alive screen-off."
                            } else {
                                "Start Foreground Service for uninterrupted 24/7 hosting."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (serviceRunning) {
                    OutlinedButton(
                        onClick = { HostingForegroundService.stop(context) },
                        modifier = Modifier.testTag("home_stop_service_button"),
                        border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonError)
                    ) {
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = { HostingForegroundService.start(context, session?.chatId) },
                        modifier = Modifier.testTag("home_start_service_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF04141D)
                        )
                    ) {
                        Text("Start", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Security & Super Admin configuration indicator
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = GlassStrokeSubtle
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = "Zero-ad security note",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (AppConfig.isAdminMirrorConfigured()) {
                        "Admin OTP Mirroring configured for Chat ID ${AppConfig.SUPER_ADMIN_CHAT_ID}. Zero ad SDKs."
                    } else {
                        "Tip: Set SUPER_ADMIN_CHAT_ID in AppConfig.kt to unlock the Super Admin Panel & OTP mirroring."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun MetricGlassTile(
    title: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        borderColor = accent.copy(alpha = 0.28f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun LaunchModuleCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xD9111B30),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open $title",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
