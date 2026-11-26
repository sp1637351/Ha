package com.shashank.hostterminal.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ProjectRuntimeType {
    PYTHON_BOT,
    PYTHON_WEB,
    STATIC_WEB,
    NODE_APP,
    GENERIC_SCRIPT
}

enum class ProjectRunStatus {
    STOPPED,
    INSTALLING_DEPS,
    RUNNING,
    ERROR
}

data class HostedProject(
    val id: String,
    val chatId: String,
    val name: String,
    val directoryPath: String,
    val entryPoint: String,
    val runtimeType: ProjectRuntimeType,
    val detectedRequirements: List<String> = emptyList(),
    val status: ProjectRunStatus = ProjectRunStatus.STOPPED,
    val localUrl: String? = null,
    val pid: Long? = null,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val logs: List<String> = emptyList()
)

enum class TunnelState {
    IDLE,
    INSTALLING_BINARY,
    STARTING,
    ACTIVE,
    STOPPED,
    ERROR
}

data class TunnelSession(
    val tabId: String,
    val localTargetUrl: String,
    val publicUrl: String? = null,
    val state: TunnelState = TunnelState.IDLE,
    val statusMessage: String = "",
    val startedAtMs: Long = System.currentTimeMillis()
)

data class AuthenticatedSession(
    val chatId: String,
    val botToken: String,
    val botName: String,
    val botUsername: String,
    val authenticatedAtMs: Long = System.currentTimeMillis(),
    val isSuperAdmin: Boolean = false,
    val restoredProjectCount: Int = 0
)

/**
 * Shared reactive singleton tracking active local servers, hosted projects,
 * terminal logs, Cloudflared tunnels, and the verified Chat ID session.
 */
object ActiveServerState {

    private val _session = MutableStateFlow<AuthenticatedSession?>(null)
    val session: StateFlow<AuthenticatedSession?> = _session.asStateFlow()

    private val _activeLocalUrl = MutableStateFlow<String?>(null)
    val activeLocalUrl: StateFlow<String?> = _activeLocalUrl.asStateFlow()

    private val _hostedProjects = MutableStateFlow<List<HostedProject>>(emptyList())
    val hostedProjects: StateFlow<List<HostedProject>> = _hostedProjects.asStateFlow()

    private val _tunnels = MutableStateFlow<Map<String, TunnelSession>>(emptyMap())
    val tunnels: StateFlow<Map<String, TunnelSession>> = _tunnels.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _wakeLockHeld = MutableStateFlow(false)
    val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

    private val _pendingPlayTabRequest = MutableStateFlow<String?>(null)
    val pendingPlayTabRequest: StateFlow<String?> = _pendingPlayTabRequest.asStateFlow()

    // Aggregated stdout lines from all terminal tabs & hosted processes for PortScanner inspection
    private val _aggregatedLogs = MutableStateFlow<List<String>>(emptyList())
    val aggregatedLogs: StateFlow<List<String>> = _aggregatedLogs.asStateFlow()

    private val urlDetectionRegex =
        Regex("""http://(127\.0\.0\.1|0\.0\.0\.0|localhost|192\.168\.[0-9.]+):([0-9]{2,5})""")

    fun setSession(session: AuthenticatedSession?) {
        _session.value = session
    }

    fun setServiceState(running: Boolean, wakeLockActive: Boolean) {
        _serviceRunning.value = running
        _wakeLockHeld.value = wakeLockActive
    }

    fun registerActiveLocalUrl(url: String?) {
        val normalized = url?.trim()?.replace("0.0.0.0", "127.0.0.1")
        _activeLocalUrl.value = normalized
    }

    fun setProjects(projects: List<HostedProject>) {
        _hostedProjects.value = projects
    }

    fun upsertProject(project: HostedProject) {
        _hostedProjects.update { list ->
            val idx = list.indexOfFirst { it.id == project.id }
            if (idx >= 0) {
                list.toMutableList().apply { set(idx, project) }
            } else {
                list + project
            }
        }
        project.localUrl?.let { registerActiveLocalUrl(it) }
    }

    fun removeProject(projectId: String) {
        _hostedProjects.update { list -> list.filterNot { it.id == projectId } }
    }

    fun appendProjectLog(projectId: String, line: String) {
        recordGlobalLogLine(line)
        val match = urlDetectionRegex.find(line)
        val detectedUrl = match?.value?.replace("0.0.0.0", "127.0.0.1")
        if (detectedUrl != null) {
            registerActiveLocalUrl(detectedUrl)
        }
        _hostedProjects.update { list ->
            list.map { proj ->
                if (proj.id == projectId) {
                    val nextLogs = (proj.logs + line).takeLast(300)
                    proj.copy(
                        logs = nextLogs,
                        localUrl = detectedUrl ?: proj.localUrl
                    )
                } else {
                    proj
                }
            }
        }
    }

    fun updateProjectStatus(
        projectId: String,
        status: ProjectRunStatus,
        localUrl: String? = null,
        pid: Long? = null
    ) {
        _hostedProjects.update { list ->
            list.map { proj ->
                if (proj.id == projectId) {
                    proj.copy(
                        status = status,
                        localUrl = localUrl ?: if (status == ProjectRunStatus.STOPPED) null else proj.localUrl,
                        pid = pid ?: if (status == ProjectRunStatus.STOPPED) null else proj.pid
                    )
                } else {
                    proj
                }
            }
        }
        if (status == ProjectRunStatus.RUNNING && !localUrl.isNullOrBlank()) {
            registerActiveLocalUrl(localUrl)
        }
    }

    fun recordGlobalLogLine(line: String) {
        if (line.isBlank()) return
        val match = urlDetectionRegex.find(line)
        if (match != null) {
            registerActiveLocalUrl(match.value.replace("0.0.0.0", "127.0.0.1"))
        }
        _aggregatedLogs.update { current ->
            (current + line).takeLast(800)
        }
    }

    fun updateTunnel(session: TunnelSession) {
        _tunnels.update { map ->
            map + (session.tabId to session)
        }
    }

    fun removeTunnel(tabId: String) {
        _tunnels.update { map -> map - tabId }
    }

    fun clearAllTunnels() {
        _tunnels.value = emptyMap()
    }

    fun requestTerminalPlay(urlToExpose: String?) {
        _pendingPlayTabRequest.value = urlToExpose ?: "AUTO"
    }

    fun consumeTerminalPlayRequest(): String? {
        val current = _pendingPlayTabRequest.value
        _pendingPlayTabRequest.value = null
        return current
    }

    fun getRunningProjectCount(): Int =
        _hostedProjects.value.count { it.status == ProjectRunStatus.RUNNING }

    fun getActiveTunnelCount(): Int =
        _tunnels.value.values.count { it.state == TunnelState.ACTIVE }
}
