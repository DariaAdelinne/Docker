package com.sd.laborator.manager

import com.sd.laborator.common.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class Subscriber(val id: Int, val socket: Socket, val serviceName: String, val serviceType: String)

class MessageManagerMicroservice {
    private val subscribers = ConcurrentHashMap<Int, Subscriber>()

    private fun send(subscriber: Subscriber, message: String) {
        subscriber.socket.getOutputStream().write((message + "\n").toByteArray())
    }

    private fun broadcast(message: String, exceptId: Int) {
        subscribers.values.filter { it.id != exceptId }.forEach { sub ->
            try { send(sub, message) } catch (_: Exception) { subscribers.remove(sub.id) }
        }
    }

    private fun respondTo(destinationId: Int, message: String) {
        subscribers[destinationId]?.let { send(it, message) }
    }

    private fun handleClient(client: Socket) {
        val id = client.port
        var serviceName = "unknown-$id"
        var serviceType = "unknown"
        println("[MessageManager] Subscriber conectat: ${client.inetAddress.hostAddress}:$id")
        val reader = BufferedReader(InputStreamReader(client.inputStream))
        try {
            while (true) {
                val received = reader.readLine() ?: break
                println("[MessageManager] Primit: $received")
                val parts = received.split(" ", limit = 3)
                when (parts[0]) {
                    "REGISTER" -> {
                        serviceName = parts.getOrElse(1) { serviceName }
                        serviceType = parts.getOrElse(2) { serviceType }
                        subscribers[id] = Subscriber(id, client, serviceName, serviceType)
                        println("[MessageManager] Inregistrat $serviceName/$serviceType cu id=$id")
                        send(subscribers[id]!!, "REGISTERED $id")
                    }
                    "QUESTION" -> {
                        val destination = parts.getOrElse(1) { id.toString() }
                        val body = parts.getOrElse(2) { "" }
                        // format compatibil cu labul: QUESTION <destinatie_raspuns> <intrebare>
                        broadcast("QUESTION $id $body", exceptId = id)
                    }
                    "ANSWER" -> {
                        val destinationId = parts.getOrNull(1)?.toIntOrNull()
                        val body = parts.getOrElse(2) { "" }
                        if (destinationId != null) respondTo(destinationId, "ANSWER $body")
                    }
                    "HEARTBEAT" -> send(subscribers[id] ?: Subscriber(id, client, serviceName, serviceType), "ALIVE message-manager")
                    else -> println("[MessageManager] Tip mesaj necunoscut: ${parts[0]}")
                }
            }
        } finally {
            subscribers.remove(id)
            reader.close()
            client.close()
            println("[MessageManager] Subscriber deconectat: $serviceName/$serviceType id=$id")
        }
    }

    fun run() {
        HealthServer("message-manager", "message-manager", Ports.MESSAGE_MANAGER_HEALTH_PORT).start()
        val server = ServerSocket(Ports.MESSAGE_MANAGER_PORT)
        println("[MessageManager] Pornit pe portul ${Ports.MESSAGE_MANAGER_PORT}")
        while (true) {
            val client = server.accept()
            thread(isDaemon = false) { handleClient(client) }
        }
    }
}

fun main() = runForever { MessageManagerMicroservice().run() }
