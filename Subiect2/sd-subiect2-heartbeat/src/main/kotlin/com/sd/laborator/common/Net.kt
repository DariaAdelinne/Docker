package com.sd.laborator.common

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

data class ServiceEndpoint(
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val mode: String = "PING"
)

object Env {
    fun str(name: String, defaultValue: String): String = System.getenv(name) ?: defaultValue
    fun int(name: String, defaultValue: Int): Int = (System.getenv(name) ?: defaultValue.toString()).toInt()
    fun long(name: String, defaultValue: Long): Long = (System.getenv(name) ?: defaultValue.toString()).toLong()
}

object Ports {
    const val MESSAGE_MANAGER_PORT = 1500
    const val MESSAGE_MANAGER_HEALTH_PORT = 1501
    const val TEACHER_PORT = 1600
    const val TEACHER_HEALTH_PORT = 1601
    const val STUDENT_HEALTH_PORT = 1701
    const val REPLICATION_PORT = 1800
}

object SocketLine {
    fun connect(host: String, port: Int, timeoutMs: Int = 3000): Socket {
        val socket = Socket(host, port)
        socket.soTimeout = timeoutMs
        return socket
    }

    fun sendAndRead(host: String, port: Int, line: String, timeoutMs: Int = 3000): String? {
        val socket = connect(host, port, timeoutMs)
        socket.use {
            it.getOutputStream().write((line + "\n").toByteArray())
            return BufferedReader(InputStreamReader(it.inputStream)).readLine()
        }
    }
}

class HealthServer(private val serviceName: String, private val serviceType: String, private val port: Int) {
    fun start() {
        thread(isDaemon = true, name = "health-$serviceName") {
            val server = ServerSocket(port)
            println("[$serviceName] Health server pornit pe portul $port")
            while (true) {
                val client = server.accept()
                thread(isDaemon = true) {
                    client.use {
                        val input = BufferedReader(InputStreamReader(it.inputStream)).readLine() ?: ""
                        val response = when {
                            input.startsWith("PING") -> "PONG $serviceName $serviceType"
                            input.startsWith("HEARTBEAT") -> "ALIVE $serviceName $serviceType"
                            else -> "UNKNOWN $serviceName"
                        }
                        it.getOutputStream().write((response + "\n").toByteArray())
                    }
                }
            }
        }
    }
}

fun runForever(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        System.err.println("Eroare fatala: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

fun parseServiceList(raw: String): List<ServiceEndpoint> {
    if (raw.isBlank()) return emptyList()
    return raw.split(",")
        .mapNotNull { token ->
            val parts = token.trim().split(":")
            if (parts.size < 4) null
            else ServiceEndpoint(
                name = parts[0],
                type = parts[1],
                host = parts[2],
                port = parts[3].toInt(),
                mode = parts.getOrElse(4) { "PING" }
            )
        }
}

fun now() = java.time.LocalDateTime.now().toString()
