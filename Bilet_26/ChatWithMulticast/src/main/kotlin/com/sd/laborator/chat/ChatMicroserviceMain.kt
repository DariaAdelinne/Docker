package com.sd.laborator.chat

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

// SRP: primirea mesajelor text si multicast — interfata unui singur utilizator de chat
// DIP: comunica cu MessageManager si MulticastProcessor prin adrese din env
class ChatMicroservice {
    private val myName       = Env.str("SERVICE_NAME",          "user")
    private val myHost       = Env.str("SERVICE_HOST",          "localhost")
    private val myChatPort   = Env.int("CHAT_PORT",             3001)
    private val managerHost  = Env.str("MESSAGE_MANAGER_HOST",  "localhost")
    private val multicastHost = Env.str("MULTICAST_HOST",       "localhost")

    private lateinit var managerSocket: Socket
    private lateinit var managerReader: BufferedReader

    private suspend fun registerWithRetry(host: String, port: Int, command: String, expectedPrefix: String) {
        while (true) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    SocketLine.sendAndRead(host, port, command, timeoutMs = 2000)
                }
                if (resp?.startsWith(expectedPrefix) == true) {
                    println("[$myName] Inregistrat: $resp")
                    return
                }
            } catch (e: Exception) {
                println("[$myName] $host:$port indisponibil, reincerc... (${e.message})")
            }
            delay(2000)
        }
    }

    private fun connectToMessageManager() {
        managerSocket = Socket(managerHost, Ports.MESSAGE_MANAGER_PORT)
        managerReader = BufferedReader(InputStreamReader(managerSocket.inputStream))
        managerSocket.getOutputStream().write("REGISTER $myName\n".toByteArray())
        println("[$myName] MessageManager: ${managerReader.readLine()}")
        managerSocket.soTimeout = 0
    }

    private fun listenForTextMessages() {
        try {
            while (true) {
                val line  = managerReader.readLine() ?: break
                val parts = line.split(" ", limit = 3)
                if (parts[0] == "MESSAGE") {
                    val from = parts.getOrElse(1) { "?" }
                    val text = parts.getOrElse(2) { "" }
                    println("[$myName] <<< [TEXT] $from: $text")
                }
            }
        } catch (_: Exception) { }
        println("[$myName] Conexiune MessageManager pierduta.")
    }

    private suspend fun handleIncoming(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim() ?: return@withContext
            val parts = line.split(" ", limit = 4)
            when (parts[0].uppercase()) {
                "MULTICAST" -> {
                    // MULTICAST <groupName> <fromUser> <message>
                    val group   = parts.getOrElse(1) { "?" }
                    val from    = parts.getOrElse(2) { "?" }
                    val message = parts.getOrElse(3) { "" }
                    println("[$myName] <<< [MULTICAST grup=$group] $from: $message")
                    s.getOutputStream().write("ACK\n".toByteArray())
                }
                "MESSAGE" -> {
                    val from    = parts.getOrElse(1) { "?" }
                    val message = parts.getOrElse(2) { "" }
                    println("[$myName] <<< [DIRECT] $from: $message")
                    s.getOutputStream().write("ACK\n".toByteArray())
                }
                else -> s.getOutputStream().write("UNKNOWN_COMMAND\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        // Inregistrare in MessageManager (pentru mesaje text broadcast)
        launch(Dispatchers.IO) {
            registerWithRetry(managerHost, Ports.MESSAGE_MANAGER_PORT, "REGISTER $myName", "REGISTERED")
        }.join()

        // Inregistrare in MulticastProcessor (pentru multicast pe grupuri)
        launch(Dispatchers.IO) {
            registerWithRetry(multicastHost, Ports.MULTICAST_PROCESSOR_PORT,
                "REGISTER $myName $myHost $myChatPort", "REGISTERED")
        }.join()

        // Conexiune persistenta la MessageManager pentru mesaje text inbound
        withContext(Dispatchers.IO) { connectToMessageManager() }
        launch(Dispatchers.IO) { listenForTextMessages() }

        // Asculta conexiuni entrante (MULTICAST sau MESSAGE direct)
        val server = ServerSocket(myChatPort)
        println("[$myName] Pornit, ascult pe portul $myChatPort")
        while (true) {
            val client = server.accept()
            // Fiecare mesaj primit e o corutina separata
            launch(Dispatchers.IO) { handleIncoming(client) }
        }
    }
}

fun main() = ChatMicroservice().run()
