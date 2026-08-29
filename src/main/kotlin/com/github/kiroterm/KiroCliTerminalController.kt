package com.github.kiroterm

import com.github.kiroterm.feature.ImagePasteFeature
import com.github.kiroterm.feature.PathInjectFeature
import com.github.kiroterm.feature.SessionFeature
import com.github.kiroterm.feature.TerminalInteractionFeature
import com.github.kiroterm.feature.TerminalKeyMapFeature
import com.github.kiroterm.terminal.TerminalAccess
import com.github.kiroterm.terminal.TerminalInputFocus
import com.github.kiroterm.terminal.TerminalScrollSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 工具窗编排：会话、路径注入、图片粘贴与终端交互增强彼此独立。
 */
class KiroCliTerminalController(
    private val project: Project,
    content: Content,
    private val panel: JPanel,
    toolbar: JPanel,
    projectDir: String,
) {
    private val session = SessionFeature(project, content, panel, projectDir)
    private val imagePaste = ImagePasteFeature()
    private val keyMaps = TerminalKeyMapFeature()
    private val placeholder = JLabel("正在自动开启 kiro-cli 会话…")

    init {
        panel.add(placeholder, BorderLayout.CENTER)
        toolbar.layout = FlowLayout(FlowLayout.LEFT, 8, 4)
        toolbar.add(toolbarButton("开启会话", "会话进行中再次点击将开启全新对话") {
            onStartSession()
        })
        toolbar.add(toolbarButton("注入路径", "向终端注入当前激活标签页路径（方括号）") {
            onInjectPath()
        })
        toolbar.add(toolbarButton("滚到底部", "将终端滚动到最底部（输入区）") {
            onScrollToBottom()
        })
        autoStartSessionIfNeeded()
    }

    fun autoStartSessionIfNeeded() {
        session.autoResumeIfNeeded(::onSessionReady)
    }

    private fun onStartSession() {
        session.start(::onSessionReady)
    }

    private fun onSessionReady(access: TerminalAccess) {
        val disposable = session.sessionDisposable() ?: return
        imagePaste.install(access, disposable)
        keyMaps.install(
            access,
            disposable,
            listOf(TerminalKeyMapFeature.shiftEnter()),
        )
        TerminalInteractionFeature.install(project, access, disposable)
        TerminalInputFocus.moveToPrompt(project, access)
    }

    private fun toolbarButton(label: String, tip: String, action: () -> Unit) = JButton(label).apply {
        toolTipText = tip
        isFocusable = false
        addActionListener { action() }
    }

    private fun onInjectPath() {
        if (!session.isLive()) {
            session.autoResumeIfNeeded { access ->
                onSessionReady(access)
                PathInjectFeature.inject(project, access)
            }
            return
        }
        PathInjectFeature.inject(project, session.terminalAccess())
    }

    private fun onScrollToBottom() {
        val access = session.terminalAccess() ?: run {
            session.autoResumeIfNeeded { ready ->
                onSessionReady(ready)
                TerminalScrollSupport.scrollToBottom(project, ready)
            }
            return
        }
        TerminalScrollSupport.scrollToBottom(project, access)
    }

    companion object {
        const val TOOL_WINDOW_ID = "com.github.kiroterm.agent"
        val CONTROLLER_KEY: Key<KiroCliTerminalController> = Key.create("kiroterm.controller")

        fun of(project: Project): KiroCliTerminalController? =
            ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)
                ?.contentManager
                ?.getContent(0)
                ?.getUserData(CONTROLLER_KEY)
    }
}
