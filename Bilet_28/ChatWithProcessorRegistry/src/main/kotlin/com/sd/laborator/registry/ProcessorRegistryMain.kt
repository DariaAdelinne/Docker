package com.sd.laborator.registry

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: gestioneaza EXCLUSIV lista de procesoare active si notifica observatorii
// OCP: pot adauga persistenta (ex: salvare in fisier) implementand IProcessorRegistryService
// DIP: serverul depinde de IProcessorRegistryService (abstractie)
//
// Pattern Observer: ProcessorRegistryServiceImpl este Subject-ul.
// La fiecare REGISTER/UNREGISTER, notifica toti observatorii inregistrati.
class ProcessorRegistryServiceImpl : IProcessorRegistryService {
    private val processors = ConcurrentHashMap<String, ProcessorInfo>()
    // Observatorii sunt endpoint-uri TCP la care trimitem notificari push
    private val observers  = ConcurrentHashMap.newKeySet<ObserverEndpoint>()

    override fun registerProcessor(info: ProcessorInfo) { processors[info.name] = info }
    override fun unregisterProcessor(name: String): ProcessorInfo? = processors.remove(name)
    override fun getProcessor(name: String): ProcessorInfo? = processors[name]
    override fun listProcessors(): List<ProcessorInfo> = processors.values.toList()

    override fun addObserver(endpoint: ObserverEndpoint) { observers.add(endpoint) }
    override fun removeObserver(endpoint: ObserverEndpoint) { observers.remove(endpoint) }

    // Notifica toti observatorii prin TCP (push) — aceasta e esenta pattern-ului Observer
    override fun notifyObservers(event: String, info: ProcessorInfo) {
        val message = "$event ${info.name} ${info.host} ${info.port} ${info.type}"
        println("[Registry] Notific ${observers.size} observatori: $message")
        val dead = mutableListOf<ObserverEndpoint>()
        observers.forEach { obs ->
            val resp = SocketLine.sendAndRead(obs.host, obs.port, message, timeoutMs = 2000)
            if (resp == null) dead.add(obs)   // observator cazut -> scoate-l automat
        }
        dead.forEach { observers.remove(it) }
    }
}

class ProcessorRegistryMicroservice(private val registryService: IProcessorRegistryService) {

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim()
                ?: return@withContext
            val parts = line.split(" ")
            println("[Registry] Primit: $line")

            val response: String = when (parts[0].uppercase()) {
                // REGISTER <name> <host> <port> <type>  — un procesor de flux porneste
                "REGISTER" -> {
                    if (parts.size < 5) return@withContext
                    val info = ProcessorInfo(parts[1], parts[2], parts[3].toInt(), parts[4])
                    registryService.registerProcessor(info)
                    println("[Registry] Procesor inregistrat: ${info.name} (${info.type}) @ ${info.host}:${info.port}")
                    // Notifica toti observatorii (pattern Observer)
                    registryService.notifyObservers("PROCESSOR_REGISTERED", info)
                    "REGISTERED ${info.name}"
                }
                // UNREGISTER <name>  — un procesor de flux se opreste
                "UNREGISTER" -> {
                    val info = registryService.unregisterProcessor(parts[1])
                    if (info != null) {
                        println("[Registry] Procesor sters: ${info.name}")
                        // Notifica toti observatorii (pattern Observer)
                        registryService.notifyObservers("PROCESSOR_UNREGISTERED", info)
                        "UNREGISTERED ${info.name}"
                    } else "NOT_FOUND ${parts[1]}"
                }
                // SUBSCRIBE <host> <port>  — un observator vrea notificari
                "SUBSCRIBE" -> {
                    if (parts.size < 3) return@withContext
                    val obs = ObserverEndpoint(parts[1], parts[2].toInt())
                    registryService.addObserver(obs)
                    println("[Registry] Observator adaugat: ${obs.host}:${obs.port}")
                    // Trimite imediat lista curenta a procesoarelor (sync initial)
                    val current = registryService.listProcessors()
                    current.forEach { info ->
                        SocketLine.sendAndRead(obs.host, obs.port,
                            "PROCESSOR_REGISTERED ${info.name} ${info.host} ${info.port} ${info.type}",
                            timeoutMs = 2000)
                    }
                    "SUBSCRIBED observers_count=${registryService.listProcessors().size}"
                }
                // UNSUBSCRIBE <host> <port>
                "UNSUBSCRIBE" -> {
                    if (parts.size < 3) return@withContext
                    registryService.removeObserver(ObserverEndpoint(parts[1], parts[2].toInt()))
                    "UNSUBSCRIBED"
                }
                // LIST  — listeaza procesoarele active
                "LIST" -> {
                    val procs = registryService.listProcessors()
                    if (procs.isEmpty()) "PROCESSORS (none)"
                    else "PROCESSORS " + procs.joinToString("|") { "${it.name}:${it.type}@${it.host}:${it.port}" }
                }
                else -> "UNKNOWN_COMMAND ${parts[0]}"
            }
            s.getOutputStream().write((response + "\n").toByteArray())
        }
    }

    fun run() = runBlocking {
        val server = ServerSocket(Ports.PROCESSOR_REGISTRY_PORT)
        println("[Registry] ProcessorRegistry pornit pe portul ${Ports.PROCESSOR_REGISTRY_PORT}")
        while (true) {
            val client = server.accept()
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = ProcessorRegistryMicroservice(ProcessorRegistryServiceImpl()).run()
