package com.github.kiroterm.terminal

import com.github.kiroterm.KiroCliTerminalController
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget

/** Block 终端输入区聚焦：调用平台 [TerminalFocusModel.focusPrompt]。 */
internal object TerminalInputFocus {

    fun moveToPrompt(project: Project, access: TerminalAccess) {
        val app = ApplicationManager.getApplication()
        val task = Runnable { moveToPromptOnEdt(project, access.widget, access) }
        if (app.isDispatchThread) task.run() else app.invokeLater(task, ModalityState.any())
    }

    private fun moveToPromptOnEdt(project: Project, widget: TerminalWidget, access: TerminalAccess) {
        IdeFocusManager.getInstance(project).doWhenFocusSettlesDown {
            ToolWindowManager.getInstance(project)
                .getToolWindow(KiroCliTerminalController.TOOL_WINDOW_ID)
                ?.activate(null)
            if (!BlockTerminalReflection.focusPrompt(widget)) {
                IdeFocusManager.getInstance(project).requestFocus(access.focusComponent, true)
            }
        }
    }
}
