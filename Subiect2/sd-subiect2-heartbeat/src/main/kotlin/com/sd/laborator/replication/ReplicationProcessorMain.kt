package com.sd.laborator.replication

import com.sd.laborator.common.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class ReplicationProcessor {
    private val serviceName = Env.str("SERVICE_NAME", "replication-processor")
    private val logPath = Env.str("REPLICATION_LOG", "/tmp/replication_requests.log")

    private fun handle(client: java.net.Socket) {
        client.use {
            val msg = BufferedReader(InputStreamReader(it.inputStream)).readLine() ?: return
            val record = "${now()} $msg"
            println("[ReplicationProcessor] Cerere primita: $record")
            File(logPath).appendText(record + "\n")
            it.getOutputStream().write("REPLICATION_REQUEST_ACCEPTED\n".toByteArray())
        }
    }

    fun run() {
        HealthServer(serviceName, "replication-processor", 1801).start()
        val server = ServerSocket(Ports.REPLICATION_PORT)
        println("[ReplicationProcessor] Pornit pe portul ${Ports.REPLICATION_PORT}")
        while (true) {
            val client = server.accept()
            thread(isDaemon = false) { handle(client) }
        }
    }
}

fun main() = runForever { ReplicationProcessor().run() }
