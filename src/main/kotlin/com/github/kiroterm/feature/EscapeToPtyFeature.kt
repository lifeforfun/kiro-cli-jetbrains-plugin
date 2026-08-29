package com.github.kiroterm.feature

import com.github.kiroterm.DebugAgentLog
import com.github.kiroterm.terminal.TerminalAccess
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * 插件终端内 ESC 发给 PTY，避免 IDE [Terminal.Escape] 把焦点切回编辑器
 * （例如 kiro-cli `/usage` 浮层无法关闭）。
 */
internal class EscapeToPtyFeature {

    fun install(access: TerminalAccess, parentDisposable: Disposable) {
        val dispatcher = IdeEventQueue.EventDispatcher { ev ->
            if (ev !is KeyEvent) return@EventDispatcher false
            if (ev.id != KeyEvent.KEY_PRESSED) return@EventDispatcher false
            if (ev.keyCode != KeyEvent.VK_ESCAPE) return@EventDispatcher false
            if (ev.modifiersEx != 0) return@EventDispatcher false
            if (ev.isConsumed) return@EventDispatcher false
            if (!isFocusInside(access)) return@EventDispatcher false
            access.sendBytes(ESC)
            ev.consume()
            DebugAgentLog.write("H-KEY", "EscapeToPtyFeature", "sent-esc", emptyMap())
            true
        }
        IdeEventQueue.getInstance().addDispatcher(dispatcher, parentDisposable)
        DebugAgentLog.write("H-KEY", "EscapeToPtyFeature", "installed", emptyMap())
    }

    private fun isFocusInside(access: TerminalAccess): Boolean {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        val root = access.widget.component
        return SwingUtilities.isDescendingFrom(focus, root)
            || SwingUtilities.isDescendingFrom(focus, access.focusComponent)
    }

    companion object {
        private val ESC = byteArrayOf(0x1B)
    }
}
