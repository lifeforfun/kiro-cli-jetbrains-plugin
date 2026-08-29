package com.github.kiroterm.feature

import com.github.kiroterm.DebugAgentLog
import com.github.kiroterm.terminal.TerminalAccess
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.jediterm.terminal.ui.TerminalPanel
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyListener

/** 一条快捷键 → 发往 PTY 的字节序列。 */
internal data class KeyMap(
    val id: String,
    val match: (KeyEvent) -> Boolean,
    val bytes: ByteArray,
    /** 吞掉配套 KEY_TYPED，避免终端再处理一次（如 Shift+Enter 的 \n/\r）。 */
    val swallowTyped: (KeyEvent) -> Boolean = { false },
)

/**
 * 统一接管插件终端内的快捷键：挂到 JediTerm [TerminalPanel] 的 customKeyListeners，
 * 反射前插以先于内置 TerminalKeyHandler 消费；未匹配的按键原样放行。
 *
 * 终端按键不走 IDE 快捷键系统，外部只需传入 [KeyMap] 列表。
 */
internal class TerminalKeyMapFeature {

    @Volatile private var installedPanel: TerminalPanel? = null

    fun install(access: TerminalAccess, parentDisposable: Disposable, maps: List<KeyMap>) {
        if (maps.isEmpty()) return
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
        INSTALL_DELAYS_MS.forEach { delayMs ->
            alarm.addRequest({ tryInstall(access, parentDisposable, maps, delayMs) }, delayMs)
        }
    }

    private fun tryInstall(
        access: TerminalAccess,
        parentDisposable: Disposable,
        maps: List<KeyMap>,
        delayMs: Long,
    ) {
        val panel = access.terminalPanelOrNull() ?: return
        if (installedPanel === panel) return
        installedPanel = panel

        val listener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isConsumed) return
                val hit = maps.firstOrNull { it.match(e) } ?: return
                e.consume()
                access.sendBytes(hit.bytes)
                DebugAgentLog.write("H-KEY", "TerminalKeyMapFeature", "sent", mapOf("id" to hit.id))
            }

            override fun keyTyped(e: KeyEvent) {
                if (e.isConsumed) return
                if (maps.any { it.swallowTyped(e) }) e.consume()
            }
        }

        val prepended = prependCustomKeyListener(panel, listener)
        if (!prepended) {
            panel.addCustomKeyListener(listener)
        }
        Disposer.register(parentDisposable) {
            panel.removeCustomKeyListener(listener)
            if (installedPanel === panel) installedPanel = null
        }
        DebugAgentLog.write(
            "H-KEY",
            "TerminalKeyMapFeature",
            "installed",
            mapOf(
                "prepended" to prepended,
                "delayMs" to delayMs,
                "maps" to maps.map { it.id },
            ),
        )
    }

    private fun prependCustomKeyListener(panel: TerminalPanel, listener: KeyListener): Boolean = try {
        val field = TerminalPanel::class.java.getDeclaredField("myCustomKeyListeners")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val listeners = field.get(panel) as? MutableList<KeyListener>
        if (listeners != null) {
            listeners.add(0, listener)
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        private val INSTALL_DELAYS_MS = listOf(0L, 300L, 800L, 1_500L, 3_000L, 5_000L, 8_000L)

        /** Shift+Enter → TUI 换行序列 ESC[13;2u */
        fun shiftEnter(): KeyMap = KeyMap(
            id = "shift-enter",
            match = { e ->
                e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown &&
                    !e.isControlDown && !e.isAltDown && !e.isMetaDown
            },
            bytes = "\u001B[13;2u".toByteArray(Charsets.US_ASCII),
            swallowTyped = { e ->
                e.isShiftDown && (e.keyChar == '\n' || e.keyChar == '\r')
            },
        )
    }
}
