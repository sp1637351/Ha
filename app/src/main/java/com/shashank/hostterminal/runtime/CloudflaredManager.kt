package com.shashank.hostterminal.runtime

import android.content.Context
import android.os.Build
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.TunnelSession
import com.shashank.hostterminal.data.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Manages automatic installation of the ARM64 (or ABI-matched) `cloudflared` binary into
 * filesDir/bin/cloudflared and manages per-tab Cloudflared Quick Tunnels with clean
 * TryCloudflare URL extraction and noise filtering.
 */
class CloudflaredManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val readerJobs = ConcurrentHashMap<String, Job>()
    private val lastTargets = ConcurrentHashMap<String, String>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val tryCloudflareRegex =
        Regex("""https://[a-zA-Z0-9-]+\.trycloudflare\.com""")

    fun isBinaryInstalled(): Boolean {
        val bin = AppConfig.cloudflaredBinary(appContext)
        return bin.exists() && bin.length() > 1_000_000L && bin.canExecute()
    }

    suspend fun ensureBinaryInstalled(
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = AppConfig.cloudflaredBinary(appContext)
        if (targetFile.exists() && targetFile.length() > 1_000_000L) {
            targetFile.setExecutable(true, false)
            return@withContext Result.success(targetFile)
        }

        val downloadUrl = selectDownloadUrlForDeviceAbi()
        onProgress("Downloading cloudflared binary for ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}...")

        runCatching {
            val tempFile = File(targetFile.parentFile, "cloudflared.download")
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "HostTerminal-Android/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Failed to download cloudflared (HTTP ${response.code})")
                }
                val body = response.body ?: error("Empty response while downloading cloudflared")
                val totalBytes = body.contentLength()
                var downloaded = 0L
                var lastPct = -1

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt()
                                if (pct >= lastPct + 20) {
                                    lastPct = pct
                                    onProgress("Installing cloudflared... $pct%")
                                }
                            }
                        }
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile.setExecutable(true, false)
            onProgress("cloudflared ready at ${targetFile.absolutePath}")
            targetFile
        }
    }

    fun startTunnel(
        tabId: String,
        localTargetUrl: String,
        onTerminalMessage: (String) -> Unit
    ) {
        stopTunnel(tabId, silent = true)
        val normalizedUrl = PortScanner.normalizeLocalUrl(localTargetUrl)
        lastTargets[tabId] = normalizedUrl

        ActiveServerState.updateTunnel(
            TunnelSession(
                tabId = tabId,
                localTargetUrl = normalizedUrl,
                state = TunnelState.INSTALLING_BINARY,
                statusMessage = "Checking cloudflared binary..."
            )
        )

        val job = scope.launch {
            val binResult = ensureBinaryInstalled { progress ->
                onTerminalMessage("[play] $progress")
                ActiveServerState.updateTunnel(
                    TunnelSession(
                        tabId = tabId,
                        localTargetUrl = normalizedUrl,
                        state = TunnelState.INSTALLING_BINARY,
                        statusMessage = progress
                    )
                )
            }

            if (binResult.isFailure) {
                val err = binResult.exceptionOrNull()?.message ?: "Unable to install cloudflared"
                onTerminalMessage("[play] Error: $err")
                ActiveServerState.updateTunnel(
                    TunnelSession(
                        tabId = tabId,
                        localTargetUrl = normalizedUrl,
                        state = TunnelState.ERROR,
                        statusMessage = err
                    )
                )
                return@launch
            }

            val binFile = binResult.getOrThrow()
            onTerminalMessage("[play] Starting tunnel: cloudflared tunnel --url $normalizedUrl")
            ActiveServerState.updateTunnel(
                TunnelSession(
                    tabId = tabId,
                    localTargetUrl = normalizedUrl,
                    state = TunnelState.STARTING,
                    statusMessage = "Negotiating TryCloudflare quick tunnel..."
                )
            )

            try {
                // Safe argument list execution — no shell concatenation
                val pb = ProcessBuilder(
                    listOf(
                        binFile.absolutePath,
                        "tunnel",
                        "--url",
                        normalizedUrl,
                        "--no-autoupdate"
                    )
                ).apply {
                    directory(appContext.filesDir)
                    redirectErrorStream(true)
                    environment()["HOME"] = appContext.filesDir.absolutePath
                    environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                }

                val process = pb.start()
                activeProcesses[tabId] = process

                var discoveredUrl: String? = null
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        val currentLine = line.orEmpty()
                        val match = tryCloudflareRegex.find(currentLine)
                        if (match != null && discoveredUrl == null) {
                            discoveredUrl = match.value
                            onTerminalMessage("[play] Public tunnel live: $discoveredUrl")
                            ActiveServerState.updateTunnel(
                                TunnelSession(
                                    tabId = tabId,
                                    localTargetUrl = normalizedUrl,
                                    publicUrl = discoveredUrl,
                                    state = TunnelState.ACTIVE,
                                    statusMessage = "Tunnel active • Forwarding $normalizedUrl"
                                )
                            )
                        } else if (currentLine.contains("failed", ignoreCase = true) ||
                            currentLine.contains("ERR", ignoreCase = false)
                        ) {
                            // Only surface critical tunnel errors, filter out routine noisy INF logs
                            if (discoveredUrl == null) {
                                onTerminalMessage("[cloudflared] ${currentLine.take(140)}")
                            }
                        }
                    }
                }

                val exitCode = process.waitFor()
                if (activeProcesses[tabId] === process) {
                    activeProcesses.remove(tabId)
                    val current = ActiveServerState.tunnels.value[tabId]
                    if (current?.state != TunnelState.STOPPED) {
                        ActiveServerState.updateTunnel(
                            TunnelSession(
                                tabId = tabId,
                                localTargetUrl = normalizedUrl,
                                publicUrl = discoveredUrl,
                                state = if (exitCode == 0) TunnelState.STOPPED else TunnelState.ERROR,
                                statusMessage = if (exitCode == 0) {
                                    "Tunnel closed cleanly"
                                } else {
                                    "Tunnel exited (code $exitCode). Verify local server is running at $normalizedUrl."
                                }
                            )
                        )
                        onTerminalMessage("[play] Tunnel exited (code $exitCode)")
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to execute cloudflared binary"
                onTerminalMessage("[play] Execution error: $msg")
                ActiveServerState.updateTunnel(
                    TunnelSession(
                        tabId = tabId,
                        localTargetUrl = normalizedUrl,
                        state = TunnelState.ERROR,
                        statusMessage = msg
                    )
                )
            }
        }
        readerJobs[tabId] = job
    }

    fun restartTunnel(tabId: String, onTerminalMessage: (String) -> Unit) {
        val target = lastTargets[tabId]
            ?: ActiveServerState.tunnels.value[tabId]?.localTargetUrl
            ?: ActiveServerState.activeLocalUrl.value
            ?: "http://127.0.0.1:8080"
        onTerminalMessage("[play] Restarting tunnel for $target...")
        startTunnel(tabId, target, onTerminalMessage)
    }

    fun stopTunnel(tabId: String, silent: Boolean = false): Boolean {
        val hadProcess = activeProcesses.remove(tabId)?.let { proc ->
            runCatching { proc.destroy() }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    proc.destroyForcibly()
                }
            }
            true
        } ?: false
        readerJobs.remove(tabId)?.cancel()

        val existing = ActiveServerState.tunnels.value[tabId]
        if (existing != null && !silent) {
            ActiveServerState.updateTunnel(
                existing.copy(
                    state = TunnelState.STOPPED,
                    statusMessage = "Tunnel stopped"
                )
            )
        }
        return hadProcess || existing?.state == TunnelState.ACTIVE || existing?.state == TunnelState.STARTING
    }

    fun stopAllTunnels() {
        val keys = activeProcesses.keys.toList()
        keys.forEach { stopTunnel(it, silent = true) }
        ActiveServerState.clearAllTunnels()
    }

    private fun selectDownloadUrlForDeviceAbi(): String {
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        return when {
            abis.any { it.contains("arm64") || it.contains("aarch64") } -> AppConfig.CLOUDFLARED_URL_ARM64
            abis.any { it.contains("x86_64") || it.contains("amd64") } -> AppConfig.CLOUDFLARED_URL_AMD64
            else -> AppConfig.CLOUDFLARED_URL_ARM
        }
    }

    companion object {
        @Volatile
        private var instance: CloudflaredManager? = null

        fun getInstance(context: Context): CloudflaredManager {
            return instance ?: synchronized(this) {
                instance ?: CloudflaredManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
