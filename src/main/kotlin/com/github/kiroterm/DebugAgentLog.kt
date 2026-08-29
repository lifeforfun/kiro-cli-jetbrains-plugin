package com.github.kiroterm

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// #region agent log
internal object DebugAgentLog {
    private val LOG_PATH = Path.of("/tmp/kiroterm-debug.log")

    fun write(hypothesisId: String, location: String, message: String, data: Map<String, Any?>) {
        try {
            val dataJson = data.entries.joinToString(",") { (k, v) ->
                val value = when (v) {
                    null -> "null"
                    is Boolean, is Number -> v.toString()
                    else -> "\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                }
                "\"$k\":$value"
            }
            val line =
                """{"sessionId":"kiroterm","hypothesisId":"$hypothesisId","location":"$location","message":"$message","timestamp":${System.currentTimeMillis()},"data":{$dataJson}}"""
            Files.writeString(LOG_PATH, "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
        }
    }
}
// #endregion
