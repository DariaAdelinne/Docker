package com.sd.laborator.chat

import com.sd.laborator.common.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

// SRP: primeste si afiseaza mesajele filtrate de FilterProcessor
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
                    println("[$myName] MessageManager: ${managerReader.readLine()}")
                    managerSocket.soTimeout = 0
                }
                return
            } catch (e: Exception) {
                println("[$myName] MessageManager indisponibil, reincerc... (${e.message})")
                delay(2000)
            }
        }
    }

    fun run() = runBlocking {
        connectWithRetry()
        launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line  = managerReader.readLine() ?: break
                    val parts = line.split(" ", limit = 3)
                    if (parts[0] == "MESSAGE")
                        println("[$myName] <<< ${parts.getOrElse(1){"?"}}: ${parts.getOrElse(2){""}}")
                }
            } catch (_: Exception) { }
            println("[$myName] Conexiune pierduta.")
        }
        println("[$myName] Pornit, ascult mesaje filtrate")
        while (true) delay(60_000)
    }
}

fun main() = ChatMicroservice().run()
