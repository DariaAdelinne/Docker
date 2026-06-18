package com.sd.laborator.multicast

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: gestiunea grupurilor si trimiterea multicast — nimic altceva
// OCP: pot adauga persistenta grupurilor in DB implementand IMulticastProcessorService
// DIP: serverul depinde de IMulticastProcessorService (abstractie)
class MulticastProcessorServiceImpl : IMulticastProcessorService {
    private val users  = ConcurrentHashMap<String, UserEndpoint>()
    // grup -> multime de membri (copy-on-write prin synchronized)
    private val groups = ConcurrentHashMap<String, MutableSet<String>>()

    override fun registerUser(name: String, host: String, port: Int) {
        users[name] = UserEndpoint(name, host, port)
    }
    override fun unregisterUser(name: String) { users.remove(name) }
    override fun getUser(name: String): UserEndpoint? = users[name]

    override fun createGroup(groupName: String, creatorName: String): Boolean {
        if (groups.containsKey(groupName)) return false
        groups[groupName] = mutableSetOf(creatorName)
        return true
    }

    override fun joinGroup(groupName: String, userName: String): Boolean {
        val members = groups[groupName] ?: return false
        synchronized(members) { members.add(userName) }
        return true
    }

    override fun leaveGroup(groupName: String, userName: String): Boolean {
        val members = groups[groupName] ?: return false
        synchronized(members) { members.remove(userName) }
        return true
    }

    override fun deleteGroup(groupName: String): Boolean = groups.remove(groupName) != null

    override fun getGroupMembers(groupName: String): Set<String>? =
        groups[groupName]?.let { synchronized(it) { it.toSet() } }

    override fun listGroups(): Map<String, Set<String>> =
        groups.mapValues { (_, v) -> synchronized(v) { v.toSet() } }

    // Multicast: trimite mesaj SIMULTAN catre toti membrii grupului, in corutine paralele
    override suspend fun multicast(groupName: String, fromUser: String, message: String): MulticastResult {
        val members = getGroupMembers(groupName)
            ?: return MulticastResult(groupName, fromUser, message, emptyList(), emptyList())

        val delivered = mutableListOf<String>()
        val failed    = mutableListOf<String>()
        val lock      = Any()

        // coroutineScope garanteaza ca asteapta TOATE corutinele copil inainte de a returna
        coroutineScope {
            members.forEach { memberName ->
                launch(Dispatchers.IO) {
                    val endpoint = users[memberName]
                    if (endpoint == null) {
                        synchronized(lock) { failed.add("$memberName(not-registered)") }
                        return@launch
                    }
                    try {
                        // Trimitere directa la fiecare membru din grup — aceasta e esenta multicast-ului
                        val resp = SocketLine.sendAndRead(
                            endpoint.host, endpoint.port,
                            "MULTICAST $groupName $fromUser $message",
                            timeoutMs = 3000
                        )
                        synchronized(lock) {
                            if (resp?.startsWith("ACK") == true) delivered.add(memberName)
                            else failed.add("$memberName(no-ack: $resp)")
                        }
                    } catch (e: Exception) {
                        synchronized(lock) { failed.add("$memberName(${e.message})") }
                    }
                }
            }
        }
        return MulticastResult(groupName, fromUser, message, delivered, failed)
    }
}

class MulticastProcessorMicroservice(private val service: IMulticastProcessorService) {

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim() ?: return@withContext
            val parts = line.split(" ", limit = 4)
            println("[MulticastProcessor] Primit: $line")

            val response: String = when (parts[0].uppercase()) {
                // REGISTER <name> <host> <port>
                "REGISTER" -> {
                    if (parts.size < 4) return@withContext
                    service.registerUser(parts[1], parts[2], parts[3].toInt())
                    println("[MulticastProcessor] Inregistrat: ${parts[1]} @ ${parts[2]}:${parts[3]}")
                    "REGISTERED ${parts[1]}"
                }
                // UNREGISTER <name>
                "UNREGISTER" -> {
                    service.unregisterUser(parts[1])
                    "UNREGISTERED ${parts[1]}"
                }
                // CREATE_GROUP <groupName> <creatorName>
                "CREATE_GROUP" -> {
                    if (parts.size < 3) return@withContext
                    val ok = service.createGroup(parts[1], parts[2])
                    if (ok) {
                        println("[MulticastProcessor] Grup creat: ${parts[1]} (creator: ${parts[2]})")
                        "GROUP_CREATED ${parts[1]}"
                    } else "GROUP_EXISTS ${parts[1]}"
                }
                // JOIN_GROUP <groupName> <userName>
                "JOIN_GROUP" -> {
                    if (parts.size < 3) return@withContext
                    val ok = service.joinGroup(parts[1], parts[2])
                    if (ok) {
                        println("[MulticastProcessor] ${parts[2]} a intrat in grupul ${parts[1]}")
                        "JOINED ${parts[2]} -> ${parts[1]}"
                    } else "GROUP_NOT_FOUND ${parts[1]}"
                }
                // LEAVE_GROUP <groupName> <userName>
                "LEAVE_GROUP" -> {
                    if (parts.size < 3) return@withContext
                    service.leaveGroup(parts[1], parts[2])
                    println("[MulticastProcessor] ${parts[2]} a parasit grupul ${parts[1]}")
                    "LEFT ${parts[2]} <- ${parts[1]}"
                }
                // DELETE_GROUP <groupName>
                "DELETE_GROUP" -> {
                    val ok = service.deleteGroup(parts[1])
                    if (ok) "GROUP_DELETED ${parts[1]}" else "GROUP_NOT_FOUND ${parts[1]}"
                }
                // LIST_GROUPS
                "LIST_GROUPS" -> {
                    val groups = service.listGroups()
                    if (groups.isEmpty()) "GROUPS (none)"
                    else "GROUPS " + groups.entries.joinToString("|") { (g, m) -> "$g:[${m.joinToString(",")}]" }
                }
                // LIST_MEMBERS <groupName>
                "LIST_MEMBERS" -> {
                    val members = service.getGroupMembers(parts[1])
                    if (members == null) "GROUP_NOT_FOUND ${parts[1]}"
                    else "MEMBERS ${parts[1]}:[${members.joinToString(",")}]"
                }
                // MULTICAST <groupName> <fromUser> <message>
                "MULTICAST" -> {
                    if (parts.size < 4) return@withContext
                    val result = service.multicast(parts[1], parts[2], parts[3])
                    println("[MulticastProcessor] Multicast $result")
                    "MULTICAST_DONE group=${result.group} delivered=${result.delivered} failed=${result.failed}"
                }
                else -> "UNKNOWN_COMMAND ${parts[0]}"
            }
            s.getOutputStream().write((response + "\n").toByteArray())
        }
    }

    fun run() = runBlocking {
        val server = ServerSocket(Ports.MULTICAST_PROCESSOR_PORT)
        println("[MulticastProcessor] Pornit pe portul ${Ports.MULTICAST_PROCESSOR_PORT}")
        while (true) {
            val client = server.accept()
            // Fiecare cerere (inclusiv multicast) e tratata intr-o corutina separata
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = MulticastProcessorMicroservice(MulticastProcessorServiceImpl()).run()
