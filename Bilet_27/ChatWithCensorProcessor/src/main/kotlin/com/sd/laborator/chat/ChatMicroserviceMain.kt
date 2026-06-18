package com.sd.laborator.chat

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

// SRP: primirea si afisarea mesajelor — interfata unui singur utilizator de chat
// DIP: comunica cu MessageManager prin adresa din env
class ChatMicroservice {
    private val myName      = Env.str("SERVICE_NAME",         "user")
    private val managerHost = Env.str("MESSAGE_MANAGER_HOST", "localhost")

    private lateinit var managerSocket: Socket
    private lateinit var managerReader: BufferedReader

    private suspend fun connectWithRetry() {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    managerSocket = Socket(managerHost, Ports.MESSAGE_MANAGER_PORT)
                    managerReader = BufferedReader(InputStreamReader(managerSocket.inputStream))
                    managerSocket.getOutputStream().write("REGISTER $myName\n".toByteArray())
                    val resp = managerReader.readLine()
                    println("[$myName] MessageManager: $resp")
                    managerSocket.soTimeout = 0
                }
                return
            } catch (e: Exception) {
                println("[$myName] MessageManager indisponibil, reincerc... (${e.message})")
                delay(2000)
            }
        }
    }

    private suspend fun handleIncoming(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim() ?: return@withContext
            val parts = line.split(" ", limit = 3)
            if (parts[0] == "MESSAGE") {
                val from    = parts.getOrElse(1) { "?" }
                val message = parts.getOrElse(2) { "" }
                println("[$myName] <<< $from: $message")
                s.getOutputStream().write("ACK\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        connectWithRetry()
        // Asculta mesajele inbound de la MessageManager (broadcast)
        launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line  = managerReader.readLine() ?: break
                    val parts = line.split(" ", limit = 3)
                    if (parts[0] == "MESSAGE") {
                        val from    = parts.getOrElse(1) { "?" }
                        val message = parts.getOrElse(2) { "" }
                        println("[$myName] <<< $from: $message")
                    }
                }
            } catch (_: Exception) { }
            println("[$myName] Conexiune MessageManager pierduta.")
        }
        println("[$myName] Pornit, ascult mesaje de la MessageManager")
        // Pasiv — primeste mesaje doar prin conexiunea persistenta la MessageManager
        // (fara ServerSocket propriu pentru simplitate in aceasta arhitectura)
    }
}

fun main() {
    ChatMicroservice().run()
    // Tine procesul viu
    Thread.currentThread().join()
}
