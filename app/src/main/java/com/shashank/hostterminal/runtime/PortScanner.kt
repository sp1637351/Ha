package com.shashank.hostterminal.runtime

import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.ProjectRunStatus
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class DetectedLocalServer(
    val url: String,
    val port: Int,
    val source: String
)

/**
 * Multi-layer local server & port detector used by the intelligent `play` command:
 * 1. Inspects shared ActiveServerState (running hosted projects & active local URL)
 * 2. Scans stdout logs from all open terminal/hosting sessions via Regex
 * 3. Reads /proc/net/tcp & /proc/net/tcp6 listening TCP sockets (hex -> decimal port)
 */
object PortScanner {

    private val localUrlRegex =
        Regex("""http://(127\.0\.0\.1|0\.0\.0\.0|localhost|192\.168\.[0-9.]+):([0-9]{2,5})""")

    private val preferredPorts = listOf(8080, 8000, 5000, 3000, 8888, 8081, 4000, 9000)

    fun detectActiveLocalServer(extraTerminalLines: List<String> = emptyList()): DetectedLocalServer? {
        // 1. Check ActiveServerState running hosted projects first (most authoritative)
        val runningProjectUrl = ActiveServerState.hostedProjects.value
            .firstOrNull { it.status == ProjectRunStatus.RUNNING && !it.localUrl.isNullOrBlank() }
            ?.localUrl
        if (!runningProjectUrl.isNullOrBlank()) {
            val port = extractPort(runningProjectUrl)
            if (port != null && isPortReachable(port)) {
                return DetectedLocalServer(
                    url = normalizeLocalUrl(runningProjectUrl),
                    port = port,
                    source = "ActiveServerState (Hosted Project)"
                )
            }
        }

        // Check general ActiveServerState singleton URL
        val singletonUrl = ActiveServerState.activeLocalUrl.value
        if (!singletonUrl.isNullOrBlank()) {
            val port = extractPort(singletonUrl)
            if (port != null && isPortReachable(port)) {
                return DetectedLocalServer(
                    url = normalizeLocalUrl(singletonUrl),
                    port = port,
                    source = "ActiveServerState"
                )
            }
        }

        // 2. Scan stdout logs from all open terminal tabs and hosting sessions (newest first)
        val combinedLogs = (ActiveServerState.aggregatedLogs.value + extraTerminalLines).asReversed()
        for (line in combinedLogs) {
            val match = localUrlRegex.find(line) ?: continue
            val rawUrl = match.value
            val port = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            if (port in 1..65535 && isPortReachable(port)) {
                val normalized = normalizeLocalUrl(rawUrl)
                ActiveServerState.registerActiveLocalUrl(normalized)
                return DetectedLocalServer(
                    url = normalized,
                    port = port,
                    source = "Session Stdout Log"
                )
            }
        }

        // 3. Scan /proc/net/tcp and /proc/net/tcp6 listening sockets (state 0A = TCP_LISTEN)
        val listeningPorts = listListeningTcpPorts()
        if (listeningPorts.isNotEmpty()) {
            val prioritized = listeningPorts.sortedWith(
                compareBy<Int> { port ->
                    val idx = preferredPorts.indexOf(port)
                    if (idx >= 0) idx else 100
                }.thenBy { it }
            )
            for (port in prioritized) {
                if (isPortReachable(port)) {
                    val url = "http://127.0.0.1:$port"
                    ActiveServerState.registerActiveLocalUrl(url)
                    return DetectedLocalServer(
                        url = url,
                        port = port,
                        source = "/proc/net/tcp socket scan"
                    )
                }
            }
        }

        return null
    }

    fun listListeningTcpPorts(): List<Int> {
        val ports = linkedSetOf<Int>()
        parseProcNetFile("/proc/net/tcp", ports)
        parseProcNetFile("/proc/net/tcp6", ports)
        return ports.filter { it in 1024..65535 && it != 5555 }.sorted()
    }

    private fun parseProcNetFile(path: String, outPorts: MutableSet<Int>) {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return
        runCatching {
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val tokens = line.trim().split(Regex("\\s+"))
                    if (tokens.size >= 4) {
                        val localAddr = tokens[1]
                        val state = tokens[3]
                        // 0A hex = TCP_LISTEN (10)
                        if (state.equals("0A", ignoreCase = true)) {
                            val hexPort = localAddr.substringAfter(":", "")
                            val port = hexPort.toIntOrNull(16)
                            if (port != null && port in 1..65535) {
                                outPorts.add(port)
                            }
                        }
                    }
                }
            }
        }
    }

    fun normalizeUserTargetInput(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        // Pure numeric port e.g. "8080"
        val portOnly = trimmed.toIntOrNull()
        if (portOnly != null && portOnly in 1..65535) {
            return "http://127.0.0.1:$portOnly"
        }

        // host:port without scheme e.g. "127.0.0.1:8080" or "localhost:3000"
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }

        val strictLocalRegex = Regex(
            """^https?://(127\.0\.0\.1|0\.0\.0\.0|localhost|192\.168\.[0-9.]+|10\.[0-9.]+):([0-9]{1,5})(/[^\s]*)?$"""
        )
        if (!strictLocalRegex.matches(withScheme)) return null
        val port = extractPort(withScheme) ?: return null
        if (port !in 1..65535) return null
        return normalizeLocalUrl(withScheme)
    }

    fun normalizeLocalUrl(url: String): String =
        url.trim().replace("0.0.0.0", "127.0.0.1").trimEnd('/')

    private fun extractPort(url: String): Int? {
        val m = Regex(""":([0-9]{1,5})""").find(url)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isPortReachable(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 180)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
