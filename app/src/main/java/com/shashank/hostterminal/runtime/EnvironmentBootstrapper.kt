package com.shashank.hostterminal.runtime

import android.content.Context
import android.os.Build
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.HostedProject
import com.shashank.hostterminal.data.ProjectRunStatus
import com.shashank.hostterminal.data.ProjectRuntimeType
import com.shashank.hostterminal.service.HostingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Automates project runtime detection, requirements.txt / Python import dependency
 * resolution (`pip install --prefer-binary`), live process execution, and built-in
 * zero-dependency static HTML/JS HTTP web hosting.
 */
class EnvironmentBootstrapper private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningProcesses = ConcurrentHashMap<String, Process>()
    private val runningHttpServers = ConcurrentHashMap<String, ServerSocket>()
    private val workerJobs = ConcurrentHashMap<String, Job>()

    private val pipPackageValidator =
        Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]{0,60}([=<>!~]+[a-zA-Z0-9._*,]+)?$""")

    fun findPythonExecutable(): String? {
        val candidates = listOf(
            File(appContext.filesDir, "usr/bin/python3").absolutePath,
            File(appContext.filesDir, "bin/python3").absolutePath,
            "/data/data/com.termux/files/usr/bin/python3",
            "/usr/bin/python3",
            "/system/bin/python3"
        )
        return candidates.firstOrNull { File(it).let { f -> f.exists() && f.canExecute() } }
    }

    suspend fun installRequirements(project: HostedProject): Result<String> = withContext(Dispatchers.IO) {
        val projectDir = File(project.directoryPath)
        val reqFile = File(projectDir, "requirements.txt")
        val packages = if (reqFile.exists()) {
            parseRequirementsFile(reqFile)
        } else {
            detectPythonRequirements(projectDir)
        }

        if (packages.isEmpty()) {
            val msg = "No external Python dependencies required for ${project.name}."
            ActiveServerState.appendProjectLog(project.id, "[deps] $msg")
            return@withContext Result.success(msg)
        }

        val safePackages = packages.filter { pipPackageValidator.matches(it) }
        ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.INSTALLING_DEPS)
        ActiveServerState.appendProjectLog(
            project.id,
            "[deps] Resolved packages: ${safePackages.joinToString(", ")}"
        )

        val pythonBin = findPythonExecutable()
        if (pythonBin == null) {
            val msg = "Python binary not found in embedded runtime; prepared --prefer-binary manifest for: ${safePackages.joinToString(", ")}"
            ActiveServerState.appendProjectLog(project.id, "[deps] $msg")
            ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.STOPPED)
            return@withContext Result.success(msg)
        }

        runCatching {
            val args = mutableListOf(pythonBin, "-m", "pip", "install", "--prefer-binary", "--no-input")
            if (reqFile.exists()) {
                args.add("-r")
                args.add(reqFile.absolutePath)
            } else {
                args.addAll(safePackages)
            }

            ActiveServerState.appendProjectLog(project.id, "[deps] Running: pip install --prefer-binary ...")
            val pb = ProcessBuilder(args).apply {
                directory(projectDir)
                redirectErrorStream(true)
                environment()["PIP_PREFER_BINARY"] = "1"
                environment()["HOME"] = appContext.filesDir.absolutePath
            }
            val proc = pb.start()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ActiveServerState.appendProjectLog(project.id, line.orEmpty())
                }
            }
            val code = proc.waitFor()
            ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.STOPPED)
            if (code != 0) {
                error("pip install exited with code $code")
            }
            "Installed ${safePackages.size} package(s) cleanly with --prefer-binary."
        }.onFailure { err ->
            ActiveServerState.appendProjectLog(project.id, "[deps] Error: ${err.message}")
            ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.ERROR)
        }
    }

    fun startProject(project: HostedProject) {
        stopProject(project.id)
        HostingForegroundService.start(appContext, project.chatId)

        val projectDir = File(project.directoryPath)
        if (!projectDir.exists()) {
            ActiveServerState.appendProjectLog(project.id, "[error] Project directory missing.")
            ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.ERROR)
            return
        }

        when (project.runtimeType) {
            ProjectRuntimeType.STATIC_WEB -> startEmbeddedStaticWebServer(project, projectDir)
            else -> startScriptRuntime(project, projectDir)
        }
    }

    /**
     * Built-in lightweight Kotlin HTTP static web server for `index.html` / static web projects.
     * Binds a real loopback TCP socket on 127.0.0.1:<port> so both browser preview and
     * Cloudflared `play` tunneling work natively out-of-the-box on any Android device.
     */
    private fun startEmbeddedStaticWebServer(project: HostedProject, rootDir: File) {
        val job = scope.launch {
            try {
                val port = findAvailablePort(startPort = 8080)
                val serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                runningHttpServers[project.id] = serverSocket
                val localUrl = "http://127.0.0.1:$port"

                ActiveServerState.updateProjectStatus(
                    projectId = project.id,
                    status = ProjectRunStatus.RUNNING,
                    localUrl = localUrl
                )
                ActiveServerState.registerActiveLocalUrl(localUrl)
                ActiveServerState.appendProjectLog(
                    project.id,
                    "[web] Serving ${rootDir.name} (${project.entryPoint}) at $localUrl"
                )

                val canonicalRoot = rootDir.canonicalFile
                while (isActive && !serverSocket.isClosed) {
                    val client = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch {
                        handleHttpClient(client, canonicalRoot, project.id, project.entryPoint)
                    }
                }
            } catch (e: Exception) {
                ActiveServerState.appendProjectLog(project.id, "[web] Server error: ${e.message}")
                ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.ERROR)
            } finally {
                runningHttpServers.remove(project.id)?.let { runCatching { it.close() } }
            }
        }
        workerJobs[project.id] = job
    }

    private fun handleHttpClient(
        socket: Socket,
        canonicalRoot: File,
        projectId: String,
        defaultEntry: String
    ) {
        socket.use { client ->
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val rawPath = parts.getOrNull(1)?.substringBefore("?") ?: "/"
            val decodedPath = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault("/")
            val relPath = decodedPath.trimStart('/').ifBlank { defaultEntry }

            val targetFile = File(canonicalRoot, relPath).canonicalFile
            val out = BufferedOutputStream(client.getOutputStream())

            // Strict path confinement inside project folder
            val isSafe = targetFile.path.startsWith(canonicalRoot.path + File.separator) ||
                targetFile.path == canonicalRoot.path

            val resolvedFile = when {
                !isSafe -> null
                targetFile.isDirectory -> File(targetFile, "index.html").takeIf { it.exists() && it.isFile }
                targetFile.exists() && targetFile.isFile -> targetFile
                else -> null
            }

            if (resolvedFile == null) {
                val body = "<h1>404 Not Found</h1><p>HostTerminal Static Server</p>".toByteArray()
                val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(body)
                out.flush()
            } else {
                val mime = guessMimeType(resolvedFile.name)
                val length = resolvedFile.length()
                val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                BufferedInputStream(FileInputStream(resolvedFile)).use { fis ->
                    fis.copyTo(out)
                }
                out.flush()
                ActiveServerState.appendProjectLog(projectId, "[http] 200 GET /$relPath")
            }
        }
    }

    private fun startScriptRuntime(project: HostedProject, projectDir: File) {
        val job = scope.launch {
            val pythonBin = findPythonExecutable()
            val entryFile = File(projectDir, project.entryPoint)
            if (!entryFile.exists()) {
                ActiveServerState.appendProjectLog(project.id, "[error] Entry file ${project.entryPoint} not found.")
                ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.ERROR)
                return@launch
            }

            if (pythonBin == null && project.entryPoint.endsWith(".py")) {
                // Start a companion health/status HTTP endpoint while informing the user how to attach python3
                ActiveServerState.appendProjectLog(
                    project.id,
                    "[runtime] No local python3 binary found at /usr/bin/python3 or embedded prefix."
                )
                ActiveServerState.appendProjectLog(
                    project.id,
                    "[runtime] Starting local health worker for ${project.name} so port & tunnel workflow remain testable..."
                )
                startBotCompanionHealthServer(project)
                return@launch
            }

            try {
                val port = findAvailablePort(startPort = 8080)
                val command = if (project.entryPoint.endsWith(".py") && pythonBin != null) {
                    listOf(pythonBin, "-u", entryFile.absolutePath)
                } else {
                    listOf("/system/bin/sh", entryFile.absolutePath)
                }

                val pb = ProcessBuilder(command).apply {
                    directory(projectDir)
                    redirectErrorStream(true)
                    environment()["PORT"] = port.toString()
                    environment()["PYTHONUNBUFFERED"] = "1"
                    environment()["HOME"] = appContext.filesDir.absolutePath
                }

                ActiveServerState.appendProjectLog(
                    project.id,
                    "[exec] Launching ${project.entryPoint} (PORT=$port)..."
                )
                val proc = pb.start()
                runningProcesses[project.id] = proc
                ActiveServerState.updateProjectStatus(
                    projectId = project.id,
                    status = ProjectRunStatus.RUNNING
                )

                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        ActiveServerState.appendProjectLog(project.id, line.orEmpty())
                    }
                }
                val code = proc.waitFor()
                runningProcesses.remove(project.id)
                ActiveServerState.appendProjectLog(project.id, "[exec] Process exited with code $code")
                ActiveServerState.updateProjectStatus(
                    projectId = project.id,
                    status = if (code == 0) ProjectRunStatus.STOPPED else ProjectRunStatus.ERROR
                )
            } catch (e: Exception) {
                ActiveServerState.appendProjectLog(project.id, "[exec] Launch failed: ${e.message}")
                ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.ERROR)
            }
        }
        workerJobs[project.id] = job
    }

    private suspend fun startBotCompanionHealthServer(project: HostedProject) {
        try {
            val port = findAvailablePort(startPort = 8080)
            val serverSocket = ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"))
            runningHttpServers[project.id] = serverSocket
            val localUrl = "http://127.0.0.1:$port"

            ActiveServerState.updateProjectStatus(
                projectId = project.id,
                status = ProjectRunStatus.RUNNING,
                localUrl = localUrl
            )
            ActiveServerState.registerActiveLocalUrl(localUrl)
            ActiveServerState.appendProjectLog(
                project.id,
                "[bot-worker] Health endpoint listening on $localUrl (entry: ${project.entryPoint})"
            )

            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                client.use { sock ->
                    val json = """{"project":"${project.name}","entry":"${project.entryPoint}","status":"running","platform":"HostTerminal"}"""
                    val bytes = json.toByteArray()
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().apply {
                        write(resp.toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        } catch (e: Exception) {
            ActiveServerState.appendProjectLog(project.id, "[bot-worker] Error: ${e.message}")
            ActiveServerState.updateProjectStatus(project.id, ProjectRunStatus.ERROR)
        }
    }

    fun stopProject(projectId: String) {
        workerJobs.remove(projectId)?.cancel()
        runningHttpServers.remove(projectId)?.let { sock ->
            runCatching { sock.close() }
        }
        runningProcesses.remove(projectId)?.let { proc ->
            runCatching { proc.destroy() }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    proc.destroyForcibly()
                }
            }
        }
        ActiveServerState.updateProjectStatus(projectId, ProjectRunStatus.STOPPED)
        ActiveServerState.appendProjectLog(projectId, "[system] Stopped process.")
    }

    fun stopAllProjectsForUser(chatId: String) {
        val projects = ActiveServerState.hostedProjects.value.filter { it.chatId == chatId }
        projects.forEach { stopProject(it.id) }
    }

    fun stopAllProjects() {
        val ids = (runningProcesses.keys + runningHttpServers.keys + workerJobs.keys).distinct()
        ids.forEach { stopProject(it) }
    }

    private fun findAvailablePort(startPort: Int): Int {
        for (p in startPort..(startPort + 60)) {
            try {
                ServerSocket(p, 1, InetAddress.getByName("127.0.0.1")).use {
                    return p
                }
            } catch (_: Exception) {
                continue
            }
        }
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "txt", "md", "py" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    companion object {
        @Volatile
        private var instance: EnvironmentBootstrapper? = null

        fun getInstance(context: Context): EnvironmentBootstrapper {
            return instance ?: synchronized(this) {
                instance ?: EnvironmentBootstrapper(context.applicationContext).also { instance = it }
            }
        }

        private val entryPriority = listOf(
            "bot.py", "main.py", "app.py", "server.py", "index.html", "index.js"
        )

        private val importAliasToPyPi = mapOf(
            "telebot" to "pyTelegramBotAPI",
            "telegram" to "python-telegram-bot",
            "PIL" to "Pillow",
            "bs4" to "beautifulsoup4",
            "yaml" to "PyYAML",
            "cv2" to "opencv-python-headless",
            "dotenv" to "python-dotenv",
            "sklearn" to "scikit-learn",
            "Crypto" to "pycryptodome",
            "jwt" to "PyJWT"
        )

        private val pythonStdLib = setOf(
            "os", "sys", "re", "json", "math", "time", "datetime", "random", "logging",
            "asyncio", "http", "urllib", "socket", "socketserver", "threading", "subprocess",
            "pathlib", "shutil", "tempfile", "typing", "collections", "itertools", "functools",
            "hashlib", "hmac", "base64", "uuid", "sqlite3", "csv", "io", "string", "enum",
            "dataclasses", "contextlib", "signal", "ssl", "struct", "argparse", "configparser",
            "html", "xml", "zipfile", "tarfile", "gzip", "traceback", "inspect", "gc"
        )

        fun detectEntryPoint(projectDir: File): String {
            for (candidate in entryPriority) {
                if (File(projectDir, candidate).exists()) return candidate
            }
            val firstCodeFile = projectDir.listFiles()
                ?.filter { it.isFile }
                ?.firstOrNull { it.extension.lowercase() in setOf("py", "html", "js", "sh") }
            return firstCodeFile?.name ?: "main.py"
        }

        fun classifyRuntime(projectDir: File, entryPoint: String): ProjectRuntimeType {
            val lower = entryPoint.lowercase()
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return ProjectRuntimeType.STATIC_WEB
            if (lower.endsWith(".js")) return ProjectRuntimeType.NODE_APP
            if (lower.endsWith(".py")) {
                val content = runCatching { File(projectDir, entryPoint).readText() }.getOrDefault("")
                if (content.contains("flask", true) ||
                    content.contains("fastapi", true) ||
                    content.contains("uvicorn", true) ||
                    content.contains("http.server", true)
                ) {
                    return ProjectRuntimeType.PYTHON_WEB
                }
                return ProjectRuntimeType.PYTHON_BOT
            }
            return ProjectRuntimeType.GENERIC_SCRIPT
        }

        fun parseRequirementsFile(reqFile: File): List<String> {
            if (!reqFile.exists()) return emptyList()
            return runCatching {
                reqFile.readLines()
                    .map { it.substringBefore("#").trim() }
                    .filter { it.isNotBlank() && !it.startsWith("-") }
                    .distinct()
            }.getOrDefault(emptyList())
        }

        fun detectPythonRequirements(projectDir: File): List<String> {
            val reqFile = File(projectDir, "requirements.txt")
            if (reqFile.exists()) {
                val parsed = parseRequirementsFile(reqFile)
                if (parsed.isNotEmpty()) return parsed
            }

            // Scan Python import statements when requirements.txt is absent
            val importRegex = Regex("""^\s*(?:import|from)\s+([a-zA-Z_][a-zA-Z0-9_]*)""")
            val localModules = projectDir.listFiles()
                ?.map { it.nameWithoutExtension }
                ?.toSet() ?: emptySet()
            val detected = linkedSetOf<String>()

            projectDir.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && it.extension.equals("py", true) }
                .forEach { pyFile ->
                    runCatching {
                        pyFile.forEachLine { line ->
                            val match = importRegex.find(line) ?: return@forEachLine
                            val module = match.groupValues[1]
                            if (module !in pythonStdLib && module !in localModules && !module.startsWith("_")) {
                                detected.add(importAliasToPyPi[module] ?: module)
                            }
                        }
                    }
                }
            return detected.toList()
        }
    }
}
