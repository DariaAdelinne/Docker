package com.sd.laborator.processors

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.IStreamProcessor
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

// SRP: DOAR transforma mesajele in uppercase — nimic altceva
// LSP: substituie complet IStreamProcessor
// OCP: pot adauga alt tip de transformare (ex: LowerCase) fara sa modific serverul
class UpperCaseProcessorImpl : IStreamProcessor {
    override val processorName = "uppercase-processor"
    override val processorType = "uppercase"

    override fun process(fromUser: String, message: String): String {
        val result = message.uppercase()
        println("[UpperCaseProcessor] Transformat: '$message' -> '$result'")
        return result
    }
}

class UpperCaseProcessorServer(private val processor: IStreamProcessor) {
    private val myName       = Env.str("SERVICE_NAME",   "uppercase-processor")
    private val myHost       = Env.str("SERVICE_HOST",   "uppercase-processor")
    private val myPort       = Env.int("LISTEN_PORT",    Ports.UPPERCASE_PROCESSOR_PORT)
    private val registryHost = Env.str("REGISTRY_HOST", "localhost")

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
        Runtime.getRuntime().addShutdownHook(Thread { unregister() })

        registerWithRetry()

        val server = ServerSocket(myPort)
        println("[$myName] Pornit pe portul $myPort — transforma mesajele in UPPERCASE")
        while (true) {
            val client = server.accept()
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = UpperCaseProcessorServer(UpperCaseProcessorImpl()).run()
