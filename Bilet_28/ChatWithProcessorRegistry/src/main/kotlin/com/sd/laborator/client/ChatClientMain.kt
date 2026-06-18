package com.sd.laborator.client

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

// SRP: interfata cu utilizatorul — trimite mesaje si interogheaza registrul
class ChatClient {
    private val myName       = Env.str("FROM_USER",            "client")
    private val managerHost  = Env.str("MESSAGE_MANAGER_HOST", "localhost")
    private val registryHost = Env.str("REGISTRY_HOST",        "localhost")

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
                    when (parts[0]) {
                        "MESSAGE" -> {
                            println("\n[Client] <<< ${parts.getOrElse(1){"?"}}: ${parts.getOrElse(2){""}}")
                            print("> "); System.out.flush()
                        }
                        "SYSTEM" -> {
                            println("\n[Client] *** SISTEM: ${parts.drop(1).joinToString(" ")}")
                            print("> "); System.out.flush()
                        }
                    }
                }
            } catch (_: Exception) { }
        }, "msg-listener", 0).also { it.isDaemon = true }.start()
    }

    private suspend fun registryCmd(cmd: String): String? = withContext(Dispatchers.IO) {
        SocketLine.sendAndRead(registryHost, Ports.PROCESSOR_REGISTRY_PORT, cmd, timeoutMs = 3000)
    }

    fun run() = runBlocking {
        withContext(Dispatchers.IO) { connectToManager() }
        startMessageListener()

        println("=== Chat Client '$myName' cu Processor Registry ===")
        println("Comenzi:")
        println("  MSG <text>       - trimite mesaj (trecut prin pipeline-ul procesoarelor active)")
        println("  PROCS            - listeaza procesoarele active din registru")
        println("  EXIT")
        println()

        val stdin = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("> "); System.out.flush()
            val line  = withContext(Dispatchers.IO) { stdin.readLine() }?.trim() ?: break
            if (line.isBlank()) continue
            val parts = line.split(" ", limit = 2)
            when (parts[0].uppercase()) {
                "MSG" -> {
                    val text = parts.getOrElse(1) { "" }
                    if (text.isBlank()) { println("[Client] Sintaxa: MSG <text>"); continue }
                    withContext(Dispatchers.IO) { managerOut.write("MESSAGE $myName $text\n".toByteArray()) }
                }
                "PROCS" -> println("[Client] ${registryCmd("LIST")}")
                "EXIT"  -> break
                else    -> println("[Client] Comanda necunoscuta. Incearca MSG, PROCS sau EXIT.")
            }
        }
        managerSocket.close()
        println("[Client] La revedere!")
    }
}

fun main() = ChatClient().run()
