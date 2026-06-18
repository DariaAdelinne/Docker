package com.sd.laborator.client

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

// SRP: interfata cu utilizatorul — gestioneaza comenzi text, grupuri si multicast
class ChatClient {
    private val myName        = Env.str("FROM_USER",            "client")
    private val managerHost   = Env.str("MESSAGE_MANAGER_HOST", "localhost")
    private val multicastHost = Env.str("MULTICAST_HOST",       "localhost")

    private lateinit var managerSocket: Socket
    private lateinit var managerOut:    java.io.OutputStream
    private lateinit var managerReader: BufferedReader

    private fun connectToManager() {
        managerSocket = Socket(managerHost, Ports.MESSAGE_MANAGER_PORT)
        managerOut    = managerSocket.getOutputStream()
        managerReader = BufferedReader(InputStreamReader(managerSocket.inputStream))
        managerOut.write("REGISTER $myName\n".toByteArray())
        println("[Client] MessageManager: ${managerReader.readLine()}")
        managerSocket.soTimeout = 0
    }

    private fun startMessageListener() {
        Thread(Thread.currentThread().threadGroup, {
            try {
                while (true) {
                    val line  = managerReader.readLine() ?: break
                    val parts = line.split(" ", limit = 3)
                    if (parts[0] == "MESSAGE") {
                        println("\n[Client] <<< ${parts.getOrElse(1){"?"}}: ${parts.getOrElse(2){""}}")
                        print("> "); System.out.flush()
                    }
                }
            } catch (_: Exception) { }
        }, "msg-listener", 0).also { it.isDaemon = true }.start()
    }

    // Trimite comanda simpla la MulticastProcessor si returneaza raspunsul
    private suspend fun multicastCmd(command: String): String? = withContext(Dispatchers.IO) {
        SocketLine.sendAndRead(multicastHost, Ports.MULTICAST_PROCESSOR_PORT, command, timeoutMs = 5000)
    }

    fun run() = runBlocking {
        withContext(Dispatchers.IO) { connectToManager() }
        startMessageListener()

        println("=== Chat Multicast Client '$myName' ===")
        println("Comenzi:")
        println("  MSG <text>                         - mesaj text (broadcast prin MessageManager)")
        println("  CREATE <grup>                      - creeaza un subgrup nou")
        println("  JOIN <grup> <user>                 - adauga un utilizator in grup")
        println("  LEAVE <grup> <user>                - scoate un utilizator din grup")
        println("  DELETE <grup>                      - sterge un grup")
        println("  MCAST <grup> <mesaj>               - trimite mesaj MULTICAST la toti membrii grupului")
        println("  GROUPS                             - listeaza toate grupurile si membrii")
        println("  MEMBERS <grup>                     - listeaza membrii unui grup")
        println("  EXIT")
        println()

        val stdin = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("> "); System.out.flush()
            val line  = withContext(Dispatchers.IO) { stdin.readLine() }?.trim() ?: break
            if (line.isBlank()) continue
            val parts = line.split(" ", limit = 3)

            when (parts[0].uppercase()) {
                "MSG" -> {
                    val text = parts.drop(1).joinToString(" ")
                    withContext(Dispatchers.IO) { managerOut.write("MESSAGE $myName $text\n".toByteArray()) }
                    println("[Client] Mesaj trimis.")
                }
                "CREATE" -> {
                    val group = parts.getOrElse(1) { "" }
                    if (group.isBlank()) { println("[Client] Sintaxa: CREATE <grup>"); continue }
                    println("[Client] ${multicastCmd("CREATE_GROUP $group $myName")}")
                }
                "JOIN" -> {
                    // JOIN <grup> <user>
                    val subParts = parts.getOrElse(1) { "" }.split(" ", limit = 2)
                    val group = subParts.getOrElse(0) { "" }
                    val user  = subParts.getOrElse(1) { "" }.ifBlank { myName }
                    if (group.isBlank()) { println("[Client] Sintaxa: JOIN <grup> <user>"); continue }
                    println("[Client] ${multicastCmd("JOIN_GROUP $group $user")}")
                }
                "LEAVE" -> {
                    val subParts = parts.getOrElse(1) { "" }.split(" ", limit = 2)
                    val group = subParts.getOrElse(0) { "" }
                    val user  = subParts.getOrElse(1) { "" }.ifBlank { myName }
                    if (group.isBlank()) { println("[Client] Sintaxa: LEAVE <grup> <user>"); continue }
                    println("[Client] ${multicastCmd("LEAVE_GROUP $group $user")}")
                }
                "DELETE" -> {
                    val group = parts.getOrElse(1) { "" }
                    if (group.isBlank()) { println("[Client] Sintaxa: DELETE <grup>"); continue }
                    println("[Client] ${multicastCmd("DELETE_GROUP $group")}")
                }
                "MCAST" -> {
                    // MCAST <grup> <mesaj>
                    val rest   = parts.drop(1).joinToString(" ").split(" ", limit = 2)
                    val group   = rest.getOrElse(0) { "" }
                    val message = rest.getOrElse(1) { "" }
                    if (group.isBlank() || message.isBlank()) {
                        println("[Client] Sintaxa: MCAST <grup> <mesaj>"); continue
                    }
                    println("[Client] Trimit multicast la grupul '$group'...")
                    println("[Client] ${multicastCmd("MULTICAST $group $myName $message")}")
                }
                "GROUPS"  -> println("[Client] ${multicastCmd("LIST_GROUPS")}")
                "MEMBERS" -> {
                    val group = parts.getOrElse(1) { "" }
                    if (group.isBlank()) { println("[Client] Sintaxa: MEMBERS <grup>"); continue }
                    println("[Client] ${multicastCmd("LIST_MEMBERS $group")}")
                }
                "EXIT" -> break
                else   -> println("[Client] Comanda necunoscuta. Incearca MSG, CREATE, JOIN, MCAST, GROUPS, EXIT.")
            }
        }
        managerSocket.close()
        println("[Client] La revedere!")
    }
}

fun main() = ChatClient().run()
