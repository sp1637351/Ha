package com.shashank.hostterminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.MainActivity
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.runtime.CloudflaredManager
import com.shashank.hostterminal.runtime.EnvironmentBootstrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Persistent Foreground Service with PARTIAL_WAKE_LOCK so hosted Telegram bots,
 * local HTTP web servers, and Cloudflared tunnels stay live when minimized or screen-off.
 */
class HostingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllWorkloadsAndSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_POLICY -> {
                val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
                    ?: ActiveServerState.session.value?.chatId
                syncWakeLockWithPolicy(chatId)
                updateNotification()
                return START_STICKY
            }
            else -> {
                val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
                    ?: ActiveServerState.session.value?.chatId
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        AppConfig.FOREGROUND_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(AppConfig.FOREGROUND_NOTIFICATION_ID, notification)
                }
                syncWakeLockWithPolicy(chatId)
                ActiveServerState.setServiceState(running = true, wakeLockActive = wakeLock?.isHeld == true)
                startTelemetryMonitor()
                return START_STICKY
            }
        }
    }

    private fun startTelemetryMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            combine(
                ActiveServerState.hostedProjects,
                ActiveServerState.tunnels,
                ActiveServerState.activeLocalUrl
            ) { projects, tunnels, localUrl ->
                Triple(
                    projects.count { it.status == com.shashank.hostterminal.data.ProjectRunStatus.RUNNING },
                    tunnels.values.count { it.state == com.shashank.hostterminal.data.TunnelState.ACTIVE },
                    localUrl
                )
            }.collect {
                updateNotification()
            }
        }
    }

    private fun syncWakeLockWithPolicy(chatId: String?) {
        val policy = AdminPolicyManager.getInstance(this).resolveEffectivePolicy(chatId)
        if (policy.allowBackgroundWakeLock) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
        ActiveServerState.setServiceState(running = true, wakeLockActive = wakeLock?.isHeld == true)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HostTerminal::PersistentHostingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
            // Safe release
        } finally {
            wakeLock = null
        }
    }

    private fun stopAllWorkloadsAndSelf() {
        EnvironmentBootstrapper.getInstance(this).stopAllProjects()
        CloudflaredManager.getInstance(this).stopAllTunnels()
        releaseWakeLock()
        ActiveServerState.setServiceState(running = false, wakeLockActive = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(AppConfig.FOREGROUND_NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HostingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val runningApps = ActiveServerState.getRunningProjectCount()
        val liveTunnels = ActiveServerState.getActiveTunnelCount()
        val wakeState = if (wakeLock?.isHeld == true) "WakeLock ON" else "Standard Mode"
        val summary = "$runningApps hosted • $liveTunnels tunnel(s) • $wakeState"

        return NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop Engine", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID,
                AppConfig.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps hosted bots, local web servers, and Cloudflared tunnels active in the background."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        ActiveServerState.setServiceState(running = false, wakeLockActive = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.shashank.hostterminal.service.ACTION_START"
        const val ACTION_STOP = "com.shashank.hostterminal.service.ACTION_STOP"
        const val ACTION_REFRESH_POLICY = "com.shashank.hostterminal.service.ACTION_REFRESH_POLICY"
        const val EXTRA_CHAT_ID = "extra_chat_id"

        fun start(context: Context, chatId: String? = null) {
            val intent = Intent(context, HostingForegroundService::class.java).apply {
                action = ACTION_START
                if (!chatId.isNullOrBlank()) putExtra(EXTRA_CHAT_ID, chatId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HostingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun refreshPolicy(context: Context, chatId: String?) {
            if (!ActiveServerState.serviceRunning.value) return
            val intent = Intent(context, HostingForegroundService::class.java).apply {
                action = ACTION_REFRESH_POLICY
                putExtra(EXTRA_CHAT_ID, chatId)
            }
            context.startService(intent)
        }
    }
}
