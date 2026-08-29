package com.github.kiroterm

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 解析 kiro-cli 项目会话 ID。
 *
 * 优先：`kiro-cli chat --list-sessions -f json`（cwd=项目根）
 * 回退：扫描 `~/.kiro/sessions/cli` 下 json 中 cwd 匹配的最近会话
 * 绑定：`~/.kiro/plugin-bound/<md5(project)>/.plugin-bound-session`
 */
object KiroCliSessionStore {

    private val LOG = Logger.getInstance(KiroCliSessionStore::class.java)

    private val homeDir: Path
        get() = Path.of(System.getProperty("user.home"))

    private val cache = ConcurrentHashMap<String, CachedSession>()
    private val activeSession = ConcurrentHashMap<String, String>()

    fun findLastSessionId(projectPath: String?, preferredSessionId: String? = null): String? {
        if (projectPath.isNullOrBlank()) return null
        val key = normalizePath(projectPath)
        val workspaceHash = md5Hex(key)

        preferredSessionId?.takeIf { it.isNotBlank() }?.let { preferred ->
            if (sessionExists(projectPath, preferred)) {
                remember(workspaceHash, preferred)
                logResolve(projectPath, preferred, "preferred")
                return preferred
            }
        }

        activeSession[workspaceHash]?.let { remembered ->
            if (sessionExists(projectPath, remembered)) {
                logResolve(projectPath, remembered, "active")
                return remembered
            }
            activeSession.remove(workspaceHash)
        }

        readPersistedSession(workspaceHash)?.let { persisted ->
            if (sessionExists(projectPath, persisted)) {
                remember(workspaceHash, persisted)
                logResolve(projectPath, persisted, "persisted")
                return persisted
            }
            clearPersistedSession(workspaceHash)
        }

        cache[workspaceHash]?.let { cached ->
            if (System.currentTimeMillis() - cached.cachedAtMs < CACHE_TTL_MS) {
                if (sessionExists(projectPath, cached.sessionId)) {
                    remember(workspaceHash, cached.sessionId)
                    logResolve(projectPath, cached.sessionId, "cache")
                    return cached.sessionId
                }
                cache.remove(workspaceHash)
            }
        }

        val sessionId = listSessionsViaCli(key)?.firstOrNull()
            ?: findLastSessionIdFromDisk(key)
        if (sessionId != null) {
            remember(workspaceHash, sessionId)
            logResolve(projectPath, sessionId, "scan")
        } else {
            LOG.info("kiroterm session resolve: project=$projectPath source=none sessionId=null")
        }
        return sessionId
    }

    fun recordActiveSession(projectPath: String?, sessionId: String?) {
        if (projectPath.isNullOrBlank() || sessionId.isNullOrBlank()) return
        remember(md5Hex(normalizePath(projectPath)), sessionId)
        LOG.info("kiroterm session record: project=$projectPath sessionId=$sessionId")
    }

    fun invalidateCache(projectPath: String?) {
        if (projectPath.isNullOrBlank()) return
        val workspaceHash = md5Hex(normalizePath(projectPath))
        cache.remove(workspaceHash)
        activeSession.remove(workspaceHash)
        clearPersistedSession(workspaceHash)
        LOG.info("kiroterm session invalidate: project=$projectPath")
    }

    fun adoptDiscoveredSession(
        projectPath: String?,
        boundSessionId: String?,
        sessionStartedAtMs: Long = 0L,
        requireFreshSession: Boolean = false,
    ): String? {
        if (projectPath.isNullOrBlank()) return null
        val workspaceHash = md5Hex(normalizePath(projectPath))

        if (!boundSessionId.isNullOrBlank() && sessionExists(projectPath, boundSessionId)) {
            remember(workspaceHash, boundSessionId)
            LOG.info("kiroterm session adopt: project=$projectPath bound=$boundSessionId action=keep-bound")
            return boundSessionId
        }

        val discovered = listSessionsViaCli(normalizePath(projectPath))?.firstOrNull()
            ?: findLastSessionIdFromDisk(normalizePath(projectPath))
        if (discovered != null) {
            if (requireFreshSession && !isSessionFresh(discovered, sessionStartedAtMs)) {
                LOG.info("kiroterm session adopt: project=$projectPath discovered=$discovered action=skip-stale-new-chat")
                return null
            }
            remember(workspaceHash, discovered)
            LOG.info("kiroterm session adopt: project=$projectPath discovered=$discovered action=record")
            return discovered
        }

        boundSessionId?.let { remember(workspaceHash, it) }
        return boundSessionId
    }

    fun sessionExists(projectPath: String?, sessionId: String): Boolean {
        if (projectPath.isNullOrBlank() || sessionId.isBlank()) return false
        if (sessionMetaPath(sessionId)?.let { Files.isRegularFile(it) } == true) return true
        return listSessionsViaCli(normalizePath(projectPath))?.contains(sessionId) == true
    }

    private fun remember(workspaceHash: String, sessionId: String) {
        cache[workspaceHash] = CachedSession(sessionId, System.currentTimeMillis())
        activeSession[workspaceHash] = sessionId
        writePersistedSession(workspaceHash, sessionId)
    }

    private fun boundRoot(workspaceHash: String): Path =
        homeDir.resolve(".kiro/plugin-bound").resolve(workspaceHash)

    private fun persistedSessionFile(workspaceHash: String): Path =
        boundRoot(workspaceHash).resolve(PERSISTED_SESSION_FILE)

    private fun readPersistedSession(workspaceHash: String): String? {
        val file = persistedSessionFile(workspaceHash)
        if (!Files.isRegularFile(file)) return null
        return try {
            Files.readString(file).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writePersistedSession(workspaceHash: String, sessionId: String) {
        try {
            Files.createDirectories(boundRoot(workspaceHash))
            Files.writeString(persistedSessionFile(workspaceHash), sessionId)
        } catch (_: Exception) {
        }
    }

    private fun clearPersistedSession(workspaceHash: String) {
        try {
            Files.deleteIfExists(persistedSessionFile(workspaceHash))
        } catch (_: Exception) {
        }
    }

    /** `kiro-cli chat --list-sessions -f json` → newest sessionId first. */
    private fun listSessionsViaCli(projectPath: String): List<String>? {
        val exe = resolveKiroCli()
        return try {
            val proc = ProcessBuilder(exe, "chat", "--list-sessions", "-f", "json")
                .directory(java.io.File(projectPath))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val done = proc.waitFor(8, TimeUnit.SECONDS)
            if (!done) {
                proc.destroyForcibly()
                LOG.warn("kiroterm: list-sessions timed out")
                return null
            }
            if (proc.exitValue() != 0) return null
            parseListSessionsJson(out)
        } catch (e: Exception) {
            LOG.warn("kiroterm: list-sessions failed", e)
            null
        }
    }

    // list-sessions JSON: [{cwd, sessions:[{sessionId, updatedAt}, ...]}]，已按 cwd 过滤
    private fun parseListSessionsJson(text: String): List<String> {
        val entries = mutableListOf<Pair<String, String>>()
        for (m in SESSION_BLOCK.findAll(text)) {
            entries.add(m.groupValues[1] to m.groupValues[2])
        }
        if (entries.isNotEmpty()) {
            return entries
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
        }
        return SESSION_ID_ONLY.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    private fun findLastSessionIdFromDisk(projectPath: String): String? {
        val cliRoot = homeDir.resolve(".kiro/sessions/cli")
        if (!Files.isDirectory(cliRoot)) return null
        var bestId: String? = null
        var bestUpdated = ""
        try {
            Files.list(cliRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .forEach { file ->
                        val meta = readCliSessionJson(file) ?: return@forEach
                        if (normalizePath(meta.cwd) != projectPath) return@forEach
                        if (meta.updatedAt >= bestUpdated) {
                            bestUpdated = meta.updatedAt
                            bestId = meta.sessionId
                        }
                    }
            }
        } catch (e: Exception) {
            LOG.warn("kiroterm: disk session scan failed", e)
        }
        return bestId
    }

    private fun sessionMetaPath(sessionId: String): Path? {
        val direct = homeDir.resolve(".kiro/sessions/cli").resolve("$sessionId.json")
        return if (Files.isRegularFile(direct)) direct else null
    }

    private fun isSessionFresh(sessionId: String, sessionStartedAtMs: Long): Boolean {
        if (sessionStartedAtMs <= 0L) return true
        val metaFile = sessionMetaPath(sessionId) ?: return true
        val threshold = sessionStartedAtMs - FRESH_SESSION_GRACE_MS
        return try {
            Files.getLastModifiedTime(metaFile).toMillis() >= threshold
        } catch (_: Exception) {
            true
        }
    }

    private fun readCliSessionJson(file: Path): DiskSession? {
        return try {
            val text = Files.readString(file)
            val id = SESSION_ID_SNAKE.find(text)?.groupValues?.get(1) ?: return null
            val cwd = CWD_FIELD.find(text)?.groupValues?.get(1) ?: return null
            val updated = UPDATED_SNAKE.find(text)?.groupValues?.get(1).orEmpty()
            DiskSession(id, cwd, updated)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveKiroCli(): String {
        System.getenv("KIRO_CLI_PATH")?.takeIf { it.isNotBlank() }?.let { return it }
        PathEnvironmentVariableUtil.findInPath("kiro-cli")?.absolutePath?.let { return it }
        return "kiro-cli"
    }

    private fun logResolve(projectPath: String, sessionId: String, source: String) {
        LOG.info("kiroterm session resolve: project=$projectPath source=$source sessionId=$sessionId")
    }

    private fun normalizePath(path: String): String = path.trimEnd('/', '\\')

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private data class CachedSession(val sessionId: String, val cachedAtMs: Long)
    private data class DiskSession(val sessionId: String, val cwd: String, val updatedAt: String)

    private val SESSION_BLOCK =
        """"sessionId"\s*:\s*"([^"]+)"[\s\S]*?"updatedAt"\s*:\s*"([^"]*)"""".toRegex()
    private val SESSION_ID_ONLY = """"sessionId"\s*:\s*"([^"]+)"""".toRegex()
    private val SESSION_ID_SNAKE = """"session_id"\s*:\s*"([^"]+)"""".toRegex()
    private val CWD_FIELD = """"cwd"\s*:\s*"([^"]+)"""".toRegex()
    private val UPDATED_SNAKE = """"updated_at"\s*:\s*"([^"]+)"""".toRegex()

    private const val CACHE_TTL_MS = 30_000L
    private const val FRESH_SESSION_GRACE_MS = 5_000L
    private const val PERSISTED_SESSION_FILE = ".plugin-bound-session"
}
