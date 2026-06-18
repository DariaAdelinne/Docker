package com.sd.laborator.manager

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: rutarea mesajelor text si aplicarea pipeline-ului de procesare
// DIP: depinde de IMessageRouterService (abstractie)
//
// Pattern Observer: MessageManager este un Observer al ProcessorRegistry.
// Primeste notificari cand procesoare se inregistreaza/dezinregistreaza
// si isi actualizeaza dinamic pipeline-ul de procesare.
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

class MessageManagerMicroservice(private val router: IMessageRouterService) : IRegistryObserver {
    private val registryHost = Env.str("REGISTRY_HOST", "localhost")
    private val myHost       = Env.str("SERVICE_HOST",  "message-manager")

    // Pipeline-ul curent de procesoare active (actualizat dinamic de Observer)
    private val pipeline = mutableListOf<ProcessorInfo>()
    private val pipelineLock = Any()

    // IRegistryObserver: un procesor nou s-a inregistrat in registru
    override fun onProcessorRegistered(info: ProcessorInfo) {
        synchronized(pipelineLock) { pipeline.add(info) }
        println("[MessageManager] Procesor adaugat in pipeline: ${info.name} (${info.type})")
        router.broadcast("SYSTEM Procesor de flux activ: ${info.name} (${info.type})", exceptId = -1)
    }

    // IRegistryObserver: un procesor s-a sters din registru
    override fun onProcessorUnregistered(name: String) {
        synchronized(pipelineLock) { pipeline.removeIf { it.name == name } }
        println("[MessageManager] Procesor scos din pipeline: $name")
        router.broadcast("SYSTEM Procesor de flux oprit: $name", exceptId = -1)
    }

    // Trimite mesajul prin fiecare procesor din pipeline, in ordine
    private fun applyPipeline(fromUser: String, message: String): String {
        val current = synchronized(pipelineLock) { pipeline.toList() }
        if (current.isEmpty()) return message
        var result = message
        current.forEach { proc ->
            val resp = SocketLine.sendAndRead(proc.host, proc.port, "PROCESS $fromUser $result", timeoutMs = 3000)
            if (resp?.startsWith("PROCESSED ") == true) {
                result = resp.removePrefix("PROCESSED ")
                println("[MessageManager] ${proc.name}: '$message' -> '$result'")
            }
        }
        return result
    }

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
                        println("[MessageManager] Inregistrat: $name (id=$id)")
                    }
                    "MESSAGE" -> {
                        val from = parts.getOrElse(1) { "?" }
                        val text = parts.getOrElse(2) { "" }
                        // Aplica pipeline-ul de procesoare active inainte de broadcast
                        val processed = applyPipeline(from, text)
                        router.broadcast("MESSAGE $from $processed", exceptId = id)
                    }
                    else -> println("[MessageManager] Comanda necunoscuta: ${parts[0]}")
                }
            }
        } finally {
            router.unsubscribe(id)
            client.close()
        }
    }

    // Serverul pentru notificarile push de la ProcessorRegistry (portul de Observer)
    private fun startObserverListener() {
        Thread(Thread.currentThread().threadGroup, {
            val server = ServerSocket(Ports.MESSAGE_MANAGER_OBSERVER_PORT)
            println("[MessageManager] Observer listener pornit pe portul ${Ports.MESSAGE_MANAGER_OBSERVER_PORT}")
            while (true) {
                val client = server.accept()
                Thread {
                    client.use { s ->
                        val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim() ?: return@use
                        val parts = line.split(" ")
                        when (parts[0]) {
                            // Notificare de la Registry: procesor nou inregistrat
                            "PROCESSOR_REGISTERED" -> {
                                if (parts.size >= 5) {
                                    val info = ProcessorInfo(parts[1], parts[2], parts[3].toInt(), parts[4])
                                    onProcessorRegistered(info)
                                }
                                s.getOutputStream().write("ACK\n".toByteArray())
                            }
                            // Notificare de la Registry: procesor sters
                            "PROCESSOR_UNREGISTERED" -> {
                                onProcessorUnregistered(parts[1])
                                s.getOutputStream().write("ACK\n".toByteArray())
                            }
                            else -> s.getOutputStream().write("UNKNOWN\n".toByteArray())
                        }
                    }
                }.also { it.isDaemon = true }.start()
            }
        }, "observer-listener", 0).also { it.isDaemon = false }.start()
    }

    private suspend fun subscribeToRegistry() {
        while (true) {
            val resp = withContext(Dispatchers.IO) {
                SocketLine.sendAndRead(registryHost, Ports.PROCESSOR_REGISTRY_PORT,
                    "SUBSCRIBE $myHost ${Ports.MESSAGE_MANAGER_OBSERVER_PORT}", timeoutMs = 2000)
            }
            if (resp?.startsWith("SUBSCRIBED") == true) {
                println("[MessageManager] Subscris la ProcessorRegistry ($registryHost:${Ports.PROCESSOR_REGISTRY_PORT})")
                return
            }
            println("[MessageManager] Registry indisponibil, reincerc...")
            delay(2000)
        }
    }

    fun run() = runBlocking {
        startObserverListener()
        subscribeToRegistry()

        val server = ServerSocket(Ports.MESSAGE_MANAGER_PORT)
        println("[MessageManager] Pornit pe portul ${Ports.MESSAGE_MANAGER_PORT}")
        while (true) {
            val client = server.accept()
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = MessageManagerMicroservice(MessageRouterServiceImpl()).run()
