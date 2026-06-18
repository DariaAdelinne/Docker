package com.sd.laborator.processors

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.IStreamProcessor
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime

// SRP: DOAR jurnalizeaza mesajele — nu le modifica
// LSP: substituie complet IStreamProcessor
// Implementeaza IStreamProcessor — logica locala de procesare
class LogProcessorImpl(private val logPath: String) : IStreamProcessor {
    override val processorName = "log-processor"
    override val processorType = "log"

    override fun process(fromUser: String, message: String): String {
        val entry = "[${LocalDateTime.now()}] $fromUser: $message"
        File(logPath).appendText(entry + "\n")
        println("[LogProcessor] Jurnalizat: $entry")
        // Nu modifica mesajul — il returneaza nemodificat
        return message
    }
}

// Server TCP care expune IStreamProcessor si se inregistreaza/dezinregistreaza in Registry
class LogProcessorServer(private val processor: IStreamProcessor) {
    private val myName        = Env.str("SERVICE_NAME",    "log-processor")
    private val myHost        = Env.str("SERVICE_HOST",    "log-processor")
    private val myPort        = Env.int("LISTEN_PORT",     Ports.LOG_PROCESSOR_PORT)
    private val registryHost  = Env.str("REGISTRY_HOST",  "localhost")

    private suspend fun registerWithRetry() {
        while (true) {
            val resp = withContext(Dispatchers.IO) {
                SocketLine.sendAndRead(registryHost, Ports.PROCESSOR_REGISTRY_PORT,
                    "REGISTER $myName $myHost $myPort ${processor.processorType}", timeoutMs = 2000)
            }
            if (resp?.startsWith("REGISTERED") == true) {
                println("[$myName] Inregistrat in ProcessorRegistry")
                return
            }
            println("[$myName] Registry indisponibil, reincerc...")
            delay(2000)
        }
    }

    private fun unregister() {
        SocketLine.sendAndRead(registryHost, Ports.PROCESSOR_REGISTRY_PORT,
            "UNREGISTER $myName", timeoutMs = 2000)
        println("[$myName] Dezinregistrat din ProcessorRegistry")
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim() ?: return@withContext
            val parts = line.split(" ", limit = 3)
            when (parts[0].uppercase()) {
                "PROCESS" -> {
                    val from    = parts.getOrElse(1) { "?" }
                    val message = parts.getOrElse(2) { "" }
                    val result  = processor.process(from, message)
                    s.getOutputStream().write("PROCESSED $result\n".toByteArray())
                }
                else -> s.getOutputStream().write("UNKNOWN_COMMAND\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        // Hook de shutdown: dezinregistreaza din Registry inainte de oprire (SIGTERM)
        Runtime.getRuntime().addShutdownHook(Thread { unregister() })

        registerWithRetry()

        val server = ServerSocket(myPort)
        println("[$myName] Pornit pe portul $myPort — jurnalizeaza toate mesajele")
        while (true) {
            val client = server.accept()
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() {
    val logPath = Env.str("LOG_PATH", "/tmp/chat_messages.log")
    LogProcessorServer(LogProcessorImpl(logPath)).run()
}
