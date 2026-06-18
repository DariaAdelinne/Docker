package com.sd.laborator.client

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

// SRP: interfata cu utilizatorul — trimite mesaje PRIN CensorProcessor si gestioneaza dictionarul
class ChatClient {
    private val myName       = Env.str("FROM_USER",           "client")
    private val managerHost  = Env.str("MESSAGE_MANAGER_HOST","localhost")
    private val censorHost   = Env.str("CENSOR_HOST",         "localhost")

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

    // Trimite o comanda la CensorProcessor si returneaza raspunsul
    private suspend fun censorCmd(command: String): String? = withContext(Dispatchers.IO) {
        SocketLine.sendAndRead(censorHost, Ports.CENSOR_PROCESSOR_PORT, command, timeoutMs = 4000)
    }

    fun run() = runBlocking {
        withContext(Dispatchers.IO) { connectToManager() }
        startMessageListener()

        println("=== Chat Censor Client '$myName' ===")
        println("Comenzi:")
        println("  MSG <text>           - trimite mesaj (trecut prin CensorProcessor automat)")
        println("  TEST <text>          - testeaza cenzura fara a trimite")
        println("  ADD <cuvant>         - adauga un cuvant in dictionarul de cenzura")
        println("  REMOVE <cuvant>      - scoate un cuvant din dictionar")
        println("  WORDS                - afiseaza dictionarul curent")
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
                    // Mesajul este trimis la CensorProcessor, nu direct la MessageManager
                    val resp = censorCmd("SEND $myName $text")
                    println("[Client] $resp")
                }
                "TEST" -> {
                    val text = parts.getOrElse(1) { "" }
                    println("[Client] ${censorCmd("TEST $text")}")
                }
                "ADD" -> {
                    val word = parts.getOrElse(1) { "" }
                    if (word.isBlank()) { println("[Client] Sintaxa: ADD <cuvant>"); continue }
                    println("[Client] ${censorCmd("ADD_WORD $word")}")
                }
                "REMOVE" -> {
                    val word = parts.getOrElse(1) { "" }
                    if (word.isBlank()) { println("[Client] Sintaxa: REMOVE <cuvant>"); continue }
                    println("[Client] ${censorCmd("REMOVE_WORD $word")}")
                }
                "WORDS" -> println("[Client] ${censorCmd("LIST_WORDS")}")
                "EXIT"  -> break
                else    -> println("[Client] Comanda necunoscuta. Incearca MSG, TEST, ADD, REMOVE, WORDS, EXIT.")
            }
        }
        managerSocket.close()
        println("[Client] La revedere!")
    }
}

fun main() = ChatClient().run()
