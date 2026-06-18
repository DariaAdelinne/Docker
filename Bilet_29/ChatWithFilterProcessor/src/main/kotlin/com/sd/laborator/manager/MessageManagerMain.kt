package com.sd.laborator.manager

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.IMessageRouterService
import com.sd.laborator.interfaces.Subscriber
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: rutarea mesajelor text deja filtrate — nimic altceva
// DIP: depinde de IMessageRouterService (abstractie)
class MessageRouterServiceImpl : IMessageRouterService {
    private val subs = ConcurrentHashMap<Int, Subscriber>()

    override fun subscribe(id: Int, name: String, socket: Socket) { subs[id] = Subscriber(id, name, socket) }
    override fun unsubscribe(id: Int) { subs.remove(id) }
    override fun broadcast(message: String, exceptId: Int) {
        subs.values.filter { it.id != exceptId }.forEach { sub ->
            try { sub.socket.getOutputStream().write((message + "\n").toByteArray()) }
            catch (_: Exception) { subs.remove(sub.id) }
        }
    }
    override fun respondTo(id: Int, message: String) {
        subs[id]?.socket?.getOutputStream()?.write((message + "\n").toByteArray())
    }
}

class MessageManagerMicroservice(private val router: IMessageRouterService) {

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        val id     = client.port
        val reader = BufferedReader(InputStreamReader(client.inputStream))
        println("[MessageManager] Conectat id=$id")
        try {
            while (true) {
                val line  = reader.readLine() ?: break
                val parts = line.split(" ", limit = 3)
                when (parts[0]) {
                    "REGISTER" -> {
                        val name = parts.getOrElse(1) { "user-$id" }
                        router.subscribe(id, name, client)
                        router.respondTo(id, "REGISTERED $id")
                        println("[MessageManager] Inregistrat: $name")
                    }
                    // Primeste DOAR mesaje deja filtrate (trimise de FilterProcessor)
                    "MESSAGE" -> {
                        val from = parts.getOrElse(1) { "?" }
                        val text = parts.getOrElse(2) { "" }
                        router.broadcast("MESSAGE $from $text", exceptId = id)
                    }
                    else -> println("[MessageManager] Comanda necunoscuta: ${parts[0]}")
                }
            }
        } finally {
            router.unsubscribe(id)
            client.close()
        }
    }

    fun run() = runBlocking {
        val server = ServerSocket(Ports.MESSAGE_MANAGER_PORT)
        println("[MessageManager] Pornit pe portul ${Ports.MESSAGE_MANAGER_PORT}")
        while (true) {
            val client = server.accept()
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = MessageManagerMicroservice(MessageRouterServiceImpl()).run()
