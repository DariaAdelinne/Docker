package com.sd.laborator.chat

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.IChatService
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

// SRP: acest microserviciu se ocupa EXCLUSIV de chat P2P (primire mesaje + trimitere directa).
// DIP: implementeaza IChatService; registrul e accesat prin SocketLine (abstractie).
// LSP: ChatMicroservice poate substitui complet IChatService oriunde e nevoie.
class ChatMicroservice : IChatService {
    private val myName     = Env.str("SERVICE_NAME", "user")
    private val myHost     = Env.str("SERVICE_HOST", "localhost")
    private val myPort     = Env.int("LISTEN_PORT",  Ports.CHAT_PORT)
    private val registryHost = Env.str("REGISTRY_HOST", "localhost")

    // Inregistrare cu retry pana cand registry-ul este disponibil
    private suspend fun registerWithRetry() {
        while (true) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    SocketLine.sendAndRead(registryHost, Ports.REGISTRY_PORT,
                        "REGISTER $myName $myHost $myPort", timeoutMs = 2000)
                }
                if (resp?.startsWith("REGISTERED") == true) {
                    println("[$myName] Inregistrat in UserRegistry ($registryHost:${Ports.REGISTRY_PORT})")
                    return
                }
            } catch (e: Exception) {
                println("[$myName] Registry nu e disponibil, reincerc in 2s... (${e.message})")
            }
            delay(2000)
        }
    }

    // IChatService: trimite mesaj direct la targetUser dupa ce ii afla adresa din registry
    override suspend fun sendMessage(targetUser: String, message: String) {
        val lookupResp = withContext(Dispatchers.IO) {
            SocketLine.sendAndRead(registryHost, Ports.REGISTRY_PORT, "LOOKUP $targetUser", timeoutMs = 2000)
        } ?: run {
            println("[$myName] Nu am putut contacta registry-ul.")
            return
        }

        if (lookupResp == "NOT_FOUND") {
            println("[$myName] Utilizatorul '$targetUser' nu este activ in registry.")
            return
        }

        // FOUND <host> <port>
        val parts = lookupResp.split(" ")
        val host = parts[1]; val port = parts[2].toInt()
        println("[$myName] Gasit $targetUser @ $host:$port. Trimit mesaj P2P direct...")

        val ack = withContext(Dispatchers.IO) {
            SocketLine.sendAndRead(host, port, "MESSAGE $myName $message", timeoutMs = 3000)
        }
        println("[$myName] ACK de la $targetUser: $ack")
    }

    // IChatService: callback la primirea unui mesaj
    override fun onMessageReceived(from: String, text: String) {
        println("[$myName] <<< Mesaj de la $from: $text")
    }

    private suspend fun handleIncoming(client: Socket) = withContext(Dispatchers.IO) {
        client.use {
            val line = BufferedReader(InputStreamReader(it.inputStream)).readLine()?.trim() ?: return@withContext
            val parts = line.split(" ", limit = 3)
            when (parts[0].uppercase()) {
                "MESSAGE" -> {
                    val from = parts.getOrElse(1) { "unknown" }
                    val text = parts.getOrElse(2) { "" }
                    onMessageReceived(from, text)
                    it.getOutputStream().write("ACK\n".toByteArray())
                }
                else -> it.getOutputStream().write("UNKNOWN_COMMAND\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        registerWithRetry()
        val server = ServerSocket(myPort)
        println("[$myName] Ascult conexiuni P2P pe portul $myPort")
        while (true) {
            val client = server.accept()
            // Fiecare mesaj P2P primit e tratat intr-o corutina separata
            launch(Dispatchers.IO) { handleIncoming(client) }
        }
    }
}

fun main() = ChatMicroservice().run()
