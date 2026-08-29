package com.github.kiroterm

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class KiroTerminalToolWindowFactory : ToolWindowFactory {
    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Kiro CLI Terminal"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cm = toolWindow.contentManager
        if (cm.contentCount > 0) {
            cm.getContent(0)?.getUserData(KiroCliTerminalController.CONTROLLER_KEY)?.autoStartSessionIfNeeded()
            return
        }

        val panel = JPanel(BorderLayout())
        val toolbar = JPanel()
        panel.add(toolbar, BorderLayout.SOUTH)

        val content = cm.factory.createContent(panel, "Kiro CLI", false)
        cm.addContent(content)

        val controller = KiroCliTerminalController(
            project,
            content,
            panel,
            toolbar,
            project.basePath ?: System.getProperty("user.home"),
        )
        content.putUserData(KiroCliTerminalController.CONTROLLER_KEY, controller)
    }
}
