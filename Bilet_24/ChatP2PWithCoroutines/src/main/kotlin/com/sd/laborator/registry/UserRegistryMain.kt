package com.sd.laborator.registry

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.IUserRegistryService
import com.sd.laborator.interfaces.UserInfo
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: acest microserviciu se ocupa EXCLUSIV de mentinerea listei de utilizatori activi.
// OCP: logica de stocare e in UserRegistryServiceImpl; pot schimba cu DB fara sa modific serverul.
// DIP: serverul depinde de IUserRegistryService, nu de implementarea concreta.
class UserRegistryServiceImpl : IUserRegistryService {
    private val users = ConcurrentHashMap<String, UserInfo>()

    override fun register(name: String, host: String, port: Int) {
        users[name] = UserInfo(name, host, port)
    }

    override fun lookup(name: String): UserInfo? = users[name]

    override fun unregister(name: String) {
        users.remove(name)
    }

    override fun listAll(): List<UserInfo> = users.values.toList()
}

class UserRegistryMicroservice(private val registryService: IUserRegistryService) {

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use {
            val reader = BufferedReader(InputStreamReader(it.inputStream))
            val line = reader.readLine()?.trim() ?: return@withContext
            val parts = line.split(" ")
            println("[Registry] Primit: $line")

            val response = when (parts[0].uppercase()) {
                "REGISTER" -> {
                    if (parts.size < 4) {
                        "ERROR sintaxa: REGISTER <name> <host> <port>"
                    } else {
                        registryService.register(parts[1], parts[2], parts[3].toInt())
                        println("[Registry] Inregistrat: ${parts[1]} @ ${parts[2]}:${parts[3]}")
                        "REGISTERED ${parts[1]}"
                    }
                }
                "LOOKUP" -> {
                    if (parts.size < 2) {
                        "ERROR sintaxa: LOOKUP <name>"
                    } else {
                        val u = registryService.lookup(parts[1])
                        if (u != null) "FOUND ${u.host} ${u.port}" else "NOT_FOUND"
                    }
                }
                "UNREGISTER" -> {
                    if (parts.size < 2) {
                        "ERROR sintaxa: UNREGISTER <name>"
                    } else {
                        registryService.unregister(parts[1])
                        println("[Registry] Dezinregistrat: ${parts[1]}")
                        "UNREGISTERED ${parts[1]}"
                    }
                }
                "LIST" -> {
                    val all = registryService.listAll()
                    if (all.isEmpty()) "USERS (none)"
                    else "USERS " + all.joinToString(",") { "${it.name}:${it.host}:${it.port}" }
                }
                else -> "UNKNOWN_COMMAND ${parts[0]}"
            }
            it.getOutputStream().write((response + "\n").toByteArray())
        }
    }

    fun run() = runBlocking {
        val server = ServerSocket(Ports.REGISTRY_PORT)
        println("[Registry] UserRegistry pornit pe portul ${Ports.REGISTRY_PORT}")
        while (true) {
            val client = server.accept()
            // Fiecare conexiune e tratata intr-o corutina separata pe Dispatchers.IO
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = UserRegistryMicroservice(UserRegistryServiceImpl()).run()
