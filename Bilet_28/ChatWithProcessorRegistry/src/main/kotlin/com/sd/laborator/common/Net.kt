package com.sd.laborator.common

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

object Env {
    fun str(name: String, default: String): String = System.getenv(name) ?: default
    fun int(name: String, default: Int):    Int    = (System.getenv(name) ?: default.toString()).toInt()
}

object Ports {
    const val MESSAGE_MANAGER_PORT          = 1500
    const val MESSAGE_MANAGER_OBSERVER_PORT = 1501   // primeste notificari de la registry
    const val PROCESSOR_REGISTRY_PORT       = 1600
    const val LOG_PROCESSOR_PORT            = 1701
    const val UPPERCASE_PROCESSOR_PORT      = 1702
}

object SocketLine {
    fun sendAndRead(host: String, port: Int, line: String, timeoutMs: Int = 4000): String? {
        return try {
            val s = Socket(host, port).also { it.soTimeout = timeoutMs }
            s.use {
                it.getOutputStream().write((line + "\n").toByteArray())
                BufferedReader(InputStreamReader(it.inputStream)).readLine()
            }
        } catch (_: Exception) { null }
    }
}
