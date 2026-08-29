package com.github.kiroterm.feature

import com.github.kiroterm.DebugAgentLog
import com.github.kiroterm.EditorContextCollector
import com.github.kiroterm.terminal.TerminalAccess
import com.github.kiroterm.terminal.TerminalInputFocus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * 功能二：注入路径。仅响应显式按钮点击。
 */
object PathInjectFeature {

    fun inject(project: Project, access: TerminalAccess?, attempt: Int = 0) {
        val ref = EditorContextCollector.collect(project)
        DebugAgentLog.write(
            "H-INJ",
            "PathInjectFeature",
            "collect",
            mapOf("hasRef" to (ref != null), "path" to ref?.relativePath, "attempt" to attempt),
        )
        if (ref == null) return
        val notation = ref.toPathNotation()
        ApplicationManager.getApplication().invokeLater {
            val terminal = access
            if (terminal == null) {
                if (attempt < 20) {
                    Alarm(Alarm.ThreadToUse.SWING_THREAD).addRequest(
                        { inject(project, access, attempt + 1) },
                        200,
                    )
                }
                return@invokeLater
            }
            terminal.sendString("\n$notation\n")
            TerminalInputFocus.moveToPrompt(project, terminal)
            DebugAgentLog.write("H-INJ", "PathInjectFeature", "sent", mapOf("notation" to notation))
        }
    }
}
