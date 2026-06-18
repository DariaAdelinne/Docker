package com.sd.laborator.client

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

// SRP: interfata cu utilizatorul — trimite mesaje prin FilterProcessor
// Declara propriul port (FROM_PORT) care va fi evaluat de regulile de filtrare
class ChatClient {
    private val myName      = Env.str("FROM_USER",             "client")
    private val myPort      = Env.int("FROM_PORT",             4000)   // portul declarat, evaluat de reguli
    private val managerHost = Env.str("MESSAGE_MANAGER_HOST",  "localhost")
    private val filterHost  = Env.str("FILTER_HOST",           "localhost")

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

    // Trimite mesajul la FilterProcessor (nu direct la MessageManager)
    private suspend fun sendMessage(text: String) = withContext(Dispatchers.IO) {
        // Protocolul: FILTER <fromUser> <fromPort> <message>
        val resp = SocketLine.sendAndRead(filterHost, Ports.FILTER_PROCESSOR_PORT,
            "FILTER $myName $myPort $text", timeoutMs = 4000)
        when {
            resp?.startsWith("ACCEPTED")  == true -> println("[Client] Mesaj trimis (ACCEPTAT de filtru)")
            resp?.startsWith("REJECTED")  == true -> println("[Client] Mesaj RESPINS de filtru: $resp")
            else -> println("[Client] Raspuns neasteptat: $resp")
        }
    }

    private suspend fun listRules() = withContext(Dispatchers.IO) {
        val resp = SocketLine.sendAndRead(filterHost, Ports.FILTER_PROCESSOR_PORT, "LIST_RULES", timeoutMs = 3000)
        println("[Client] $resp")
    }

    fun run() = runBlocking {
        withContext(Dispatchers.IO) { connectToManager() }
        startMessageListener()

        println("=== Chat Client '$myName' (port declarat: $myPort) ===")
        println("Mesajele trec prin FilterProcessor inainte de a fi distribuite.")
        println("Comenzi:")
        println("  MSG <text>   - trimite mesaj (evaluat de lantul de reguli)")
        println("  RULES        - afiseaza lantul de reguli din FilterProcessor")
        println("  EXIT")
        println()

        val stdin = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("> "); System.out.flush()
            val line  = withContext(Dispatchers.IO) { stdin.readLine() }?.trim() ?: break
            if (line.isBlank()) continue
            val parts = line.split(" ", limit = 2)
            when (parts[0].uppercase()) {
                "MSG"   -> {
                    val text = parts.getOrElse(1) { "" }
                    if (text.isBlank()) println("[Client] Sintaxa: MSG <text>") else sendMessage(text)
                }
                "RULES" -> listRules()
                "EXIT"  -> break
                else    -> println("[Client] Comanda necunoscuta. Incearca MSG, RULES sau EXIT.")
            }
        }
        managerSocket.close()
        println("[Client] La revedere!")
    }
}

fun main() = ChatClient().run()
