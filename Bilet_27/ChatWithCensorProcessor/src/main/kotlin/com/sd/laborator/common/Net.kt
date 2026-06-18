package com.sd.laborator.common

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

object Env {
    fun str(name: String, default: String): String = System.getenv(name) ?: default
    fun int(name: String, default: Int):    Int    = (System.getenv(name) ?: default.toString()).toInt()
}

object Ports {
    const val MESSAGE_MANAGER_PORT  = 1500
    const val CENSOR_PROCESSOR_PORT = 1700
}

object SocketLine {
    fun sendAndRead(host: String, port: Int, line: String, timeoutMs: Int = 4000): String? {
        val s = Socket(host, port).also { it.soTimeout = timeoutMs }
        s.use {
            it.getOutputStream().write((line + "\n").toByteArray())
            return BufferedReader(InputStreamReader(it.inputStream)).readLine()
        }
    }
}
