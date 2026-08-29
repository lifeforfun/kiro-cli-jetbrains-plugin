package com.github.kiroterm

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import java.util.concurrent.TimeUnit

object TerminalLauncher {
    private val LOG = Logger.getInstance(TerminalLauncher::class.java)

    @Volatile private var cachedShellEnv: Map<String, String>? = null

    data class LaunchSpec(
        val shellCommand: List<String>,
        val resumedSessionId: String?,
        /** 注入 PTY：用户 shell 环境 + 插件覆盖项。kiro-cli 不经 login shell，须显式带上。 */
        val envVariables: Map<String, String>,
    )

    fun buildLaunchSpec(
        project: Project,
        preferredSessionId: String? = null,
        resume: Boolean = true,
    ): LaunchSpec {
        val resumed = if (resume) {
            KiroCliSessionStore.findLastSessionId(project.basePath, preferredSessionId)
        } else {
            null
        }
        val command = buildCommand(resolveKiroCliExecutable(), resumed, resume)
        val env = buildProcessEnv()
        LOG.info(
            "kiroterm launch: project=${project.basePath} resumed=$resumed " +
                "command=$command editor=${env["EDITOR"]} visual=${env["VISUAL"]}",
        )
        return LaunchSpec(command, resumed, env)
    }

    /** 解析 kiro-cli：KIRO_CLI_PATH 或 IDE 从用户 shell 还原的 PATH。 */
    private fun resolveKiroCliExecutable(): String {
        readEnv("KIRO_CLI_PATH")?.let { return it }
        PathEnvironmentVariableUtil.findInPath("kiro-cli")?.absolutePath?.let { return it }
        PathEnvironmentVariableUtil.findInPath("kiro-cli-chat")?.absolutePath?.let { return it }
        return "kiro-cli"
    }

    /**
     * 启动 chat TUI。
     * - 新会话：`kiro-cli chat --tui`
     * - 有 ID：`kiro-cli chat --tui --resume-id <id>`
     * - 无 ID 但 resume：`kiro-cli chat --tui --resume`（本目录最近一次）
     */
    private fun buildCommand(exe: String, resumed: String?, resume: Boolean): List<String> = buildList {
        add(exe)
        add("chat")
        add("--tui")
        when {
            !resumed.isNullOrBlank() -> {
                add("--resume-id")
                add(resumed)
            }
            resume -> add("--resume")
        }
    }

    private fun buildProcessEnv(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(EnvironmentUtil.getEnvironmentMap())
        putAll(probeLoginShellEnv())
        put("NO_HYPERLINK", "1")
        if (this["EDITOR"].isNullOrBlank()) readEnv("EDITOR")?.let { put("EDITOR", it) }
        if (this["VISUAL"].isNullOrBlank()) readEnv("VISUAL")?.let { put("VISUAL", it) }
    }

    private fun probeLoginShellEnv(): Map<String, String> {
        cachedShellEnv?.let { return it }
        synchronized(this) {
            cachedShellEnv?.let { return it }
            val loaded = loadLoginShellEnv()
            cachedShellEnv = loaded
            return loaded
        }
    }

    private fun loadLoginShellEnv(): Map<String, String> {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        return try {
            val proc = ProcessBuilder(shell, "-lc", "/usr/bin/printenv")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val done = proc.waitFor(8, TimeUnit.SECONDS)
            if (!done) {
                proc.destroyForcibly()
                LOG.warn("kiroterm: login shell env probe timed out (shell=$shell)")
                return emptyMap()
            }
            parsePrintenv(out).also {
                LOG.info("kiroterm: login shell env probe ok size=${it.size} editor=${it["EDITOR"]}")
            }
        } catch (e: Exception) {
            LOG.warn("kiroterm: login shell env probe failed", e)
            emptyMap()
        }
    }

    private fun parsePrintenv(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            map[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return map
    }

    private fun readEnv(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: EnvironmentUtil.getValue(name)?.takeIf { it.isNotBlank() }
}
