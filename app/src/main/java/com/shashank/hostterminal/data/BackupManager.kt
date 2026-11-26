package com.shashank.hostterminal.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shashank.hostterminal.AppConfig
import com.shashank.hostterminal.runtime.EnvironmentBootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val chatId: String,
    val updatedAtMs: Long,
    val projects: List<BackupProjectMeta>
)

data class BackupProjectMeta(
    val projectName: String,
    val backupZipName: String,
    val sizeBytes: Long,
    val backedUpAtMs: Long
)

data class UserStorageSnapshot(
    val chatId: String,
    val projectFolders: List<ProjectFolderInfo>,
    val backupArchives: List<File>,
    val totalProjectsBytes: Long,
    val totalBackupsBytes: Long
)

data class ProjectFolderInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val entryPoint: String
)

/**
 * Optional cloud sync hook interface so enterprise/self-hosted admins can mirror
 * user backup ZIPs to an external webhook or S3-compatible endpoint.
 */
interface CloudBackupSyncHook {
    suspend fun pushBackup(chatId: String, archiveFile: File): Result<String>
}

class WebhookCloudSyncHook(private val webhookUrl: String) : CloudBackupSyncHook {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun pushBackup(chatId: String, archiveFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            if (!webhookUrl.startsWith("https://")) {
                return@withContext Result.failure(IllegalArgumentException("Cloud sync hook requires HTTPS URL"))
            }
            runCatching {
                val request = Request.Builder()
                    .url(webhookUrl)
                    .header("X-HostTerminal-Chat-Id", AppConfig.sanitizePathSegment(chatId))
                    .header("X-HostTerminal-Archive", archiveFile.name)
                    .post(archiveFile.asRequestBody("application/zip".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Cloud sync webhook returned HTTP ${response.code}")
                    }
                    "Synced ${archiveFile.name} (HTTP ${response.code})"
                }
            }
        }
}

/**
 * Manages Chat ID indexed project storage, ZIP-Slip-safe extraction,
 * single-file uploads, automatic persistent backups under filesDir/backups/<chat_id>/,
 * and automatic project restoration when a user recovers with a new Bot Token.
 */
class BackupManager(private val appContext: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    var cloudSyncHook: CloudBackupSyncHook? = null

    suspend fun importZipProject(
        chatId: String,
        uri: Uri,
        customProjectName: String? = null
    ): Result<HostedProject> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri) ?: "project_${System.currentTimeMillis()}.zip"
            val baseName = customProjectName?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast(".")
            val safeName = AppConfig.sanitizePathSegment(baseName, "zip_project")

            val targetDir = File(AppConfig.userProjectsDir(appContext, chatId), safeName)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            val canonicalTargetRoot = targetDir.canonicalFile
            var extractedFiles = 0
            val maxBytes = 250L * 1024L * 1024L // 250 MB safety cap per zip expansion
            var totalWritten = 0L

            appContext.contentResolver.openInputStream(uri)?.use { rawInput ->
                ZipInputStream(BufferedInputStream(rawInput)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (!entryName.startsWith("__MACOSX") && !entryName.endsWith(".DS_Store")) {
                            val outFile = File(canonicalTargetRoot, entryName).canonicalFile
                            // Strict ZIP-Slip path confinement check
                            if (!outFile.path.startsWith(canonicalTargetRoot.path + File.separator) &&
                                outFile.path != canonicalTargetRoot.path
                            ) {
                                throw SecurityException("Blocked unsafe ZIP entry path: $entryName")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                    val buffer = ByteArray(8192)
                                    var count: Int
                                    while (zis.read(buffer).also { count = it } != -1) {
                                        totalWritten += count
                                        if (totalWritten > maxBytes) {
                                            throw IllegalStateException("ZIP archive exceeds maximum safe extraction size")
                                        }
                                        bos.write(buffer, 0, count)
                                    }
                                }
                                extractedFiles++
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: error("Unable to open selected ZIP archive")

            if (extractedFiles == 0) {
                error("ZIP archive contained no extractable files")
            }

            // Flatten single nested root directory if archive wrapped everything in one folder
            flattenSingleWrapperFolderIfNeeded(targetDir)

            val project = buildHostedProjectFromDirectory(chatId, targetDir)
            backupSingleProject(chatId, targetDir)
            project
        }
    }

    suspend fun importSingleCodeFile(
        chatId: String,
        uri: Uri,
        customProjectName: String? = null
    ): Result<HostedProject> = withContext(Dispatchers.IO) {
        runCatching {
            val originalName = queryDisplayName(uri) ?: "main.py"
            val safeFileName = AppConfig.sanitizePathSegment(originalName, "main.py")
            val projName = AppConfig.sanitizePathSegment(
                customProjectName?.takeIf { it.isNotBlank() } ?: safeFileName.substringBeforeLast("."),
                "script_project"
            )
            val targetDir = File(AppConfig.userProjectsDir(appContext, chatId), projName)
            targetDir.mkdirs()

            val destinationFile = File(targetDir, safeFileName).canonicalFile
            if (!destinationFile.path.startsWith(targetDir.canonicalPath + File.separator)) {
                throw SecurityException("Invalid file name")
            }

            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to read selected file")

            val project = buildHostedProjectFromDirectory(chatId, targetDir)
            backupSingleProject(chatId, targetDir)
            project
        }
    }

    suspend fun createQuickStarterProject(
        chatId: String,
        isStaticWeb: Boolean
    ): Result<HostedProject> = withContext(Dispatchers.IO) {
        runCatching {
            val suffix = (System.currentTimeMillis() % 10000).toString()
            val folderName = if (isStaticWeb) "web_site_$suffix" else "telegram_bot_$suffix"
            val targetDir = File(AppConfig.userProjectsDir(appContext, chatId), folderName).apply {
                mkdirs()
            }

            if (isStaticWeb) {
                File(targetDir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                      <title>HostTerminal Live Site</title>
                      <style>
                        body {
                          margin: 0; font-family: system-ui, -apple-system, sans-serif;
                          background: radial-gradient(circle at top right, #0f2942, #070b14 65%);
                          color: #e2e8f0; min-height: 100vh; display: grid; place-items: center;
                        }
                        .card {
                          background: rgba(15, 23, 42, 0.72); border: 1px solid rgba(34, 211, 238, 0.35);
                          border-radius: 20px; padding: 32px; max-width: 480px; text-align: center;
                          box-shadow: 0 24px 60px rgba(0,0,0,0.45);
                        }
                        .pill {
                          display: inline-block; padding: 6px 14px; border-radius: 999px;
                          background: rgba(16, 185, 129, 0.16); color: #34d399;
                          font-size: 12px; font-weight: 700; letter-spacing: 0.08em;
                        }
                        h1 { margin: 16px 0 8px; font-size: 28px; color: #f8fafc; }
                        p { color: #94a3b8; line-height: 1.6; }
                      </style>
                    </head>
                    <body>
                      <div class="card">
                        <span class="pill">HOSTTERMINAL LIVE • 100% AD-FREE</span>
                        <h1>Local Web Server Active</h1>
                        <p>Hosted directly from Android via HostTerminal. Run <code>play</code> in the Terminal tab to publish this site over Cloudflared.</p>
                      </div>
                    </body>
                    </html>
                    """.trimIndent()
                )
            } else {
                File(targetDir, "requirements.txt").writeText("requests>=2.31.0\npython-dotenv>=1.0.0\n")
                File(targetDir, "bot.py").writeText(
                    """
                    import http.server
                    import socketserver
                    import os

                    PORT = int(os.environ.get("PORT", "8080"))

                    class StatusHandler(http.server.SimpleHTTPRequestHandler):
                        def do_GET(self):
                            self.send_response(200)
                            self.send_header("Content-Type", "application/json")
                            self.end_headers()
                            self.wfile.write(b'{"status":"online","engine":"HostTerminal Bot Worker"}')

                    print(f"Bot health server listening on http://127.0.0.1:{PORT}", flush=True)
                    with socketserver.TCPServer(("127.0.0.1", PORT), StatusHandler) as httpd:
                        httpd.serve_forever()
                    """.trimIndent()
                )
            }

            val project = buildHostedProjectFromDirectory(chatId, targetDir)
            backupSingleProject(chatId, targetDir)
            project
        }
    }

    suspend fun backupSingleProject(chatId: String, projectDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val backupDir = AppConfig.userBackupsDir(appContext, chatId)
                val safeProjectName = AppConfig.sanitizePathSegment(projectDir.name, "project")
                val zipFile = File(backupDir, "${safeProjectName}.zip")
                zipDirectory(projectDir, zipFile)
                updateManifest(chatId)
                cloudSyncHook?.pushBackup(chatId, zipFile)
                zipFile
            }
        }

    suspend fun backupAllUserProjects(chatId: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val userDir = AppConfig.userProjectsDir(appContext, chatId)
            val dirs = userDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            var count = 0
            for (dir in dirs) {
                backupSingleProject(chatId, dir)
                count++
            }
            updateManifest(chatId)
            count
        }
    }

    /**
     * Revoked Bot Token / Reinstall Recovery:
     * Restores all backed-up projects linked to this User Chat ID if any are missing from projects/.
     */
    suspend fun restoreUserProjectsIfNeeded(chatId: String): Int = withContext(Dispatchers.IO) {
        val backupDir = AppConfig.userBackupsDir(appContext, chatId)
        val projectsDir = AppConfig.userProjectsDir(appContext, chatId)
        val zipFiles = backupDir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?: emptyList()
        var restored = 0

        for (zip in zipFiles) {
            val projectName = AppConfig.sanitizePathSegment(zip.nameWithoutExtension)
            val targetDir = File(projectsDir, projectName)
            if (!targetDir.exists() || targetDir.listFiles().isNullOrEmpty()) {
                runCatching {
                    unzipSafe(zip, targetDir)
                    restored++
                }
            }
        }
        restored
    }

    suspend fun loadUserProjects(chatId: String): List<HostedProject> = withContext(Dispatchers.IO) {
        val userDir = AppConfig.userProjectsDir(appContext, chatId)
        val existingByPath = ActiveServerState.hostedProjects.value.associateBy { it.directoryPath }
        val dirs = userDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        dirs.map { dir ->
            val running = existingByPath[dir.absolutePath]
            if (running != null) {
                running
            } else {
                buildHostedProjectFromDirectory(chatId, dir)
            }
        }
    }

    suspend fun deleteUserProject(chatId: String, projectName: String, deleteBackupToo: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = AppConfig.sanitizePathSegment(projectName)
            val projDir = File(AppConfig.userProjectsDir(appContext, chatId), safeName)
            val deletedProj = if (projDir.exists()) projDir.deleteRecursively() else true
            if (deleteBackupToo) {
                val backupZip = File(AppConfig.userBackupsDir(appContext, chatId), "$safeName.zip")
                if (backupZip.exists()) backupZip.delete()
                updateManifest(chatId)
            }
            deletedProj
        }

    suspend fun inspectAllUsersStorage(): List<UserStorageSnapshot> = withContext(Dispatchers.IO) {
        val projectChatIds = AppConfig.projectsRoot(appContext)
            .listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        val backupChatIds = AppConfig.backupsRoot(appContext)
            .listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        val allChatIds = (projectChatIds + backupChatIds).distinct().sorted()

        allChatIds.map { chatId ->
            val projRoot = AppConfig.userProjectsDir(appContext, chatId)
            val backupRoot = AppConfig.userBackupsDir(appContext, chatId)

            val folders = projRoot.listFiles()?.filter { it.isDirectory }?.map { folder ->
                ProjectFolderInfo(
                    name = folder.name,
                    path = folder.absolutePath,
                    sizeBytes = AdminPolicyManager.directorySizeBytes(folder),
                    fileCount = countFiles(folder),
                    entryPoint = EnvironmentBootstrapper.detectEntryPoint(folder)
                )
            } ?: emptyList()

            val archives = backupRoot.listFiles()?.filter { it.isFile && it.extension == "zip" }
                ?: emptyList()

            UserStorageSnapshot(
                chatId = chatId,
                projectFolders = folders,
                backupArchives = archives,
                totalProjectsBytes = folders.sumOf { it.sizeBytes },
                totalBackupsBytes = archives.sumOf { it.length() }
            )
        }
    }

    private fun buildHostedProjectFromDirectory(chatId: String, dir: File): HostedProject {
        val entryPoint = EnvironmentBootstrapper.detectEntryPoint(dir)
        val runtimeType = EnvironmentBootstrapper.classifyRuntime(dir, entryPoint)
        val reqs = EnvironmentBootstrapper.detectPythonRequirements(dir)
        return HostedProject(
            id = UUID.nameUUIDFromBytes("${chatId}:${dir.name}".toByteArray()).toString(),
            chatId = chatId,
            name = dir.name,
            directoryPath = dir.absolutePath,
            entryPoint = entryPoint,
            runtimeType = runtimeType,
            detectedRequirements = reqs,
            status = ProjectRunStatus.STOPPED,
            lastUpdatedMs = dir.lastModified()
        )
    }

    private fun flattenSingleWrapperFolderIfNeeded(targetDir: File) {
        val children = targetDir.listFiles()?.filterNot { it.name.startsWith(".") } ?: return
        if (children.size == 1 && children.first().isDirectory) {
            val nested = children.first()
            nested.listFiles()?.forEach { child ->
                val dest = File(targetDir, child.name)
                child.renameTo(dest)
            }
            nested.deleteRecursively()
        }
    }

    private fun zipDirectory(sourceDir: File, outputZip: File) {
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
            val basePath = sourceDir.canonicalPath
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relativePath = file.canonicalPath.removePrefix(basePath).trimStart(File.separatorChar)
                if (relativePath.isNotBlank()) {
                    zos.putNextEntry(ZipEntry(relativePath))
                    FileInputStream(file).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun unzipSafe(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(canonicalRoot, entry.name).canonicalFile
                if (!outFile.path.startsWith(canonicalRoot.path + File.separator) &&
                    outFile.path != canonicalRoot.path
                ) {
                    throw SecurityException("Blocked unsafe backup entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun updateManifest(chatId: String) {
        val backupDir = AppConfig.userBackupsDir(appContext, chatId)
        val metas = backupDir.listFiles()
            ?.filter { it.isFile && it.extension == "zip" }
            ?.map { zip ->
                BackupProjectMeta(
                    projectName = zip.nameWithoutExtension,
                    backupZipName = zip.name,
                    sizeBytes = zip.length(),
                    backedUpAtMs = zip.lastModified()
                )
            } ?: emptyList()

        val manifest = BackupManifest(
            chatId = chatId,
            updatedAtMs = System.currentTimeMillis(),
            projects = metas
        )
        File(backupDir, "manifest.json").writeText(gson.toJson(manifest))
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }
}
