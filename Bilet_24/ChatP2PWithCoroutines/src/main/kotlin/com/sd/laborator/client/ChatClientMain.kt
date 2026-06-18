package com.sd.laborator.client

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

// SRP: clientul se ocupa EXCLUSIV de interfata cu utilizatorul si trimiterea de comenzi.
// Clientul face el insusi lookup in registry si trimite P2P direct (fara a trece printr-un router central).
class ChatClient {
    private val myName       = Env.str("FROM_USER",     "client")
    private val registryHost = Env.str("REGISTRY_HOST", "localhost")

    private suspend fun sendMessage(targetUser: String, message: String) {
        // Pasul 1: verifica daca utilizatorul e activ si afla adresa lui
        val lookupResp = withContext(Dispatchers.IO) {
            SocketLine.sendAndRead(registryHost, Ports.REGISTRY_PORT, "LOOKUP $targetUser", timeoutMs = 2000)
        }
        if (lookupResp == null || lookupResp == "NOT_FOUND") {
            println("[Client] Utilizatorul '$targetUser' nu este activ.")
            return
        }
        // Pasul 2: initiaza comunicare directa P2P (fara MessageManager central)
        val parts = lookupResp.split(" ") // FOUND host port
        val host = parts[1]; val port = parts[2].toInt()
        println("[Client] $targetUser gasit @ $host:$port. Trimit P2P direct...")
        val ack = withContext(Dispatchers.IO) {
            SocketLine.sendAndRead(host, port, "MESSAGE $myName $message", timeoutMs = 3000)
        }
        println("[Client] Raspuns: $ack")
    }

    private suspend fun listUsers() {
        val resp = withContext(Dispatchers.IO) {
            SocketLine.sendAndRead(registryHost, Ports.REGISTRY_PORT, "LIST", timeoutMs = 2000)
        }
        println("[Client] $resp")
    }

    fun run() = runBlocking {
        println("=== Chat P2P Client pornit ca '$myName' ===")
        println("Comenzi disponibile:")
        println("  SEND <utilizator> <mesaj>  - trimite mesaj direct catre un utilizator activ")
        println("  LIST                       - listeaza utilizatorii activi din registry")
        println("  EXIT                       - iese din aplicatie")
        println()

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("> ")
            System.out.flush()
            val line = withContext(Dispatchers.IO) { reader.readLine() }?.trim() ?: break
            if (line.isBlank()) continue
            val parts = line.split(" ", limit = 3)
            when (parts[0].uppercase()) {
                "SEND" -> {
                    val target  = parts.getOrElse(1) { "" }
                    val message = parts.getOrElse(2) { "" }
                    if (target.isBlank() || message.isBlank()) {
                        println("[Client] Sintaxa: SEND <utilizator> <mesaj>")
                    } else {
                        sendMessage(target, message)
                    }
                }
                "LIST" -> listUsers()
                "EXIT" -> break
                else   -> println("[Client] Comanda necunoscuta: '${parts[0]}'. Incearca SEND, LIST sau EXIT.")
            }
        }
        println("[Client] La revedere!")
    }
}

fun main() = ChatClient().run()
