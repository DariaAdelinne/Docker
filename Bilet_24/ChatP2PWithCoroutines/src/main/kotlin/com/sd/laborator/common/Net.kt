package com.sd.laborator.common

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

object Env {
    fun str(name: String, defaultValue: String): String = System.getenv(name) ?: defaultValue
    fun int(name: String, defaultValue: Int): Int = (System.getenv(name) ?: defaultValue.toString()).toInt()
}

object Ports {
    const val REGISTRY_PORT = 2000
    const val CHAT_PORT     = 3001
}

object SocketLine {
    fun sendAndRead(host: String, port: Int, line: String, timeoutMs: Int = 3000): String? {
        val socket = Socket(host, port)
        socket.soTimeout = timeoutMs
        socket.use {
            it.getOutputStream().write((line + "\n").toByteArray())
            return BufferedReader(InputStreamReader(it.inputStream)).readLine()
        }
    }
}
