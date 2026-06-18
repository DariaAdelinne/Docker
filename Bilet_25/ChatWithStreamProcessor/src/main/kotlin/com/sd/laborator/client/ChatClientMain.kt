package com.sd.laborator.client

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

// SRP: interfata cu utilizatorul — trimite mesaje text SI fisiere, nimic altceva
class ChatClient {
    private val myName       = Env.str("FROM_USER",            "client")
    private val managerHost  = Env.str("MESSAGE_MANAGER_HOST", "localhost")
    private val streamHost   = Env.str("STREAM_PROCESSOR_HOST","localhost")

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

    // Trimite mesaj text prin MessageManager (broadcast la toti)
    private suspend fun sendTextMessage(text: String) = withContext(Dispatchers.IO) {
        managerOut.write("MESSAGE $myName $text\n".toByteArray())
        println("[Client] Mesaj trimis: $text")
    }

    // Incarca fisierul si il trimite prin StreamProcessor catre targetUser
    private suspend fun sendFile(targetUser: String, filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            println("[Client] Fisierul nu exista: $filePath")
            return@withContext
        }
        val data     = file.readBytes()
        val filename = file.name
        println("[Client] Trimit '$filename' (${data.size} bytes) catre $targetUser prin StreamProcessor...")

        val socket = Socket(streamHost, Ports.STREAM_PROCESSOR_PORT)
        socket.use { s ->
            val out = DataOutputStream(s.getOutputStream())
            // Header text: UPLOAD <from> <to> <filename> <size>
            val header = "UPLOAD $myName $targetUser $filename ${data.size}\n"
            out.write(header.toByteArray())
            // Flux de bytes propriu-zis
            out.write(data)
            out.flush()
            val resp = BufferedReader(InputStreamReader(s.inputStream)).readLine()
            println("[Client] Raspuns StreamProcessor: $resp")
        }
    }

    // Afiseaza istoricul transferurilor din StreamProcessor
    private suspend fun showHistory() = withContext(Dispatchers.IO) {
        val socket = Socket(streamHost, Ports.STREAM_PROCESSOR_PORT)
        socket.use { s ->
            s.getOutputStream().write("HISTORY\n".toByteArray())
            val reader = BufferedReader(InputStreamReader(s.inputStream))
            println("[Client] --- Istoric transferuri ---")
            var line = reader.readLine()
            while (line != null) {
                println("  $line")
                line = reader.readLine()
            }
        }
    }

    // Asculta mesajele text inbound de la MessageManager si le afiseaza
    private fun startMessageListener() {
        Thread(Thread.currentThread().threadGroup, {
            try {
                while (true) {
                    val line = managerReader.readLine() ?: break
                    val parts = line.split(" ", limit = 3)
                    if (parts[0] == "MESSAGE") {
                        println("\n[Client] <<< ${parts.getOrElse(1){"?"}}: ${parts.getOrElse(2){""}}")
                        print("> "); System.out.flush()
                    }
                }
            } catch (_: Exception) { }
        }, "msg-listener", 0).also { it.isDaemon = true }.start()
    }

    fun run() = runBlocking {
        withContext(Dispatchers.IO) { connectToManager() }
        startMessageListener()

        println("=== Chat Client '$myName' ===")
        println("Comenzi:")
        println("  MSG <text>                    - trimite mesaj text (broadcast)")
        println("  FILE <utilizator> <cale_fisier> - trimite fisier prin StreamProcessor")
        println("  HISTORY                       - istoricul transferurilor de fisiere")
        println("  EXIT")
        println()

        val stdin = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("> "); System.out.flush()
            val line = withContext(Dispatchers.IO) { stdin.readLine() }?.trim() ?: break
            if (line.isBlank()) continue
            val parts = line.split(" ", limit = 3)
            when (parts[0].uppercase()) {
                "MSG"     -> sendTextMessage(parts.drop(1).joinToString(" "))
                "FILE"    -> {
                    val target = parts.getOrElse(1) { "" }
                    val path   = parts.getOrElse(2) { "" }
                    if (target.isBlank() || path.isBlank()) println("[Client] Sintaxa: FILE <utilizator> <cale>")
                    else sendFile(target, path)
                }
                "HISTORY" -> showHistory()
                "EXIT"    -> break
                else      -> println("[Client] Comanda necunoscuta. Incearca MSG, FILE, HISTORY, EXIT.")
            }
        }
        managerSocket.close()
        println("[Client] La revedere!")
    }
}

fun main() = ChatClient().run()
