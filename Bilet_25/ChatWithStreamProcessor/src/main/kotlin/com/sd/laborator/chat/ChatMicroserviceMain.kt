package com.sd.laborator.chat

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

// SRP: primirea mesajelor text SI a fisierelor — interfata unui singur utilizator de chat
// DIP: depinde de IMessageRouterService si IStreamProcessorService prin adrese din env
class ChatMicroservice {
    private val myName          = Env.str("SERVICE_NAME",      "user")
    private val myHost          = Env.str("SERVICE_HOST",      "localhost")
    private val myChatPort      = Env.int("CHAT_PORT",         Ports.ALICE_CHAT_PORT)
    private val managerHost     = Env.str("MESSAGE_MANAGER_HOST",  "localhost")
    private val streamHost      = Env.str("STREAM_PROCESSOR_HOST", "localhost")
    private val receivedFilesDir = Env.str("FILES_DIR",        "/tmp/received_files_$myName")

    private lateinit var managerSocket: Socket
    private lateinit var managerReader: BufferedReader

    // Inregistrare cu retry pana cand serviciul e disponibil
    private suspend fun registerWithRetry(host: String, port: Int, command: String, expectedPrefix: String) {
        while (true) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    SocketLine.sendAndRead(host, port, command, timeoutMs = 2000)
                }
                if (resp?.startsWith(expectedPrefix) == true) {
                    println("[$myName] OK: $resp")
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
        val resp = managerReader.readLine()
        println("[$myName] MessageManager: $resp")
        managerSocket.soTimeout = 0
    }

    // Citeste mesaje text din MessageManager si le afiseaza
    private fun listenForMessages() {
        try {
            while (true) {
                val line = managerReader.readLine() ?: break
                val parts = line.split(" ", limit = 3)
                if (parts[0] == "MESSAGE") {
                    val from = parts.getOrElse(1) { "?" }
                    val text = parts.getOrElse(2) { "" }
                    println("[$myName] <<< Mesaj de la $from: $text")
                }
            }
        } catch (_: Exception) { }
        println("[$myName] Conexiune MessageManager pierduta.")
    }

    // Citeste o linie din DataInputStream (mix text + binar)
    private fun readHeaderLine(din: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = din.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private suspend fun handleIncoming(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val din    = DataInputStream(s.inputStream)
            val header = readHeaderLine(din)?.trim() ?: return@withContext
            val parts  = header.split(" ")

            when (parts[0].uppercase()) {
                "MESSAGE" -> {
                    // Mesaj text direct (de la MessageManager broadcast)
                    val from = parts.getOrElse(1) { "?" }
                    val text = parts.drop(2).joinToString(" ")
                    println("[$myName] <<< Mesaj de la $from: $text")
                    s.getOutputStream().write("ACK\n".toByteArray())
                }
                "FILE" -> {
                    // FILE <fromUser> <filename> <sizeBytes>  — trimis de StreamProcessor
                    val fromUser = parts.getOrElse(1) { "unknown" }
                    val filename = parts.getOrElse(2) { "file.bin" }
                    val size     = parts.getOrElse(3) { "0" }.toIntOrNull() ?: 0

                    println("[$myName] <<< Fisier primit de la $fromUser: $filename ($size bytes)")

                    // Citire flux de bytes (stream propriu-zis)
                    val data = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = din.read(data, read, size - read)
                        if (n < 0) break
                        read += n
                    }

                    // Salvare pe disc
                    val dir = File(receivedFilesDir).also { it.mkdirs() }
                    val outFile = File(dir, filename)
                    outFile.writeBytes(data)
                    println("[$myName] Fisier salvat la: ${outFile.absolutePath}")

                    s.getOutputStream().write("ACK $filename\n".toByteArray())
                }
                else -> s.getOutputStream().write("UNKNOWN_COMMAND\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        // Inregistrare in MessageManager
        launch(Dispatchers.IO) {
            registerWithRetry(managerHost, Ports.MESSAGE_MANAGER_PORT,
                "REGISTER $myName", "REGISTERED")
        }.join()

        // Inregistrare in StreamProcessor (pentru a primi fisiere)
        launch(Dispatchers.IO) {
            registerWithRetry(streamHost, Ports.STREAM_PROCESSOR_PORT,
                "REGISTER $myName $myHost $myChatPort", "REGISTERED")
        }.join()

        // Reconecteaza-se la MessageManager pentru mesaje persistente (conexiune lunga)
        withContext(Dispatchers.IO) { connectToMessageManager() }

        // Asculta mesaje text din MessageManager pe un fir separat
        launch(Dispatchers.IO) { listenForMessages() }

        // Asculta conexiuni entrante (mesaje directe + fisiere de la StreamProcessor)
        val server = ServerSocket(myChatPort)
        println("[$myName] Pornit, ascult pe portul $myChatPort")
        while (true) {
            val client = server.accept()
            // Fiecare conexiune (mesaj sau fisier) e tratata intr-o corutina separata
            launch(Dispatchers.IO) { handleIncoming(client) }
        }
    }
}

fun main() = ChatMicroservice().run()
