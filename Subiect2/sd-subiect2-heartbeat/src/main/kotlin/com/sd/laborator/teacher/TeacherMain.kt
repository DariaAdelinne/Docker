package com.sd.laborator.teacher

import com.sd.laborator.common.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class TeacherMicroservice {
    private val serviceName = Env.str("SERVICE_NAME", "teacher")
    private val managerHost = Env.str("MESSAGE_MANAGER_HOST", "localhost")
    private lateinit var managerSocket: java.net.Socket
    private lateinit var managerReader: BufferedReader

    private fun subscribeToMessageManager() {
        managerSocket = SocketLine.connect(managerHost, Ports.MESSAGE_MANAGER_PORT, timeoutMs = 5000)
        managerReader = BufferedReader(InputStreamReader(managerSocket.inputStream))
        managerSocket.getOutputStream().write("REGISTER $serviceName teacher\n".toByteArray())
        println("[Teacher] ${managerReader.readLine()}")
        managerSocket.soTimeout = Env.int("QUESTION_TIMEOUT_MS", 3000)
    }

    private fun handleQuestion(client: java.net.Socket) {
        client.use {
            val question = BufferedReader(InputStreamReader(it.inputStream)).readLine() ?: return
            println("[Teacher] Am primit de la client intrebarea: $question")
            synchronized(managerSocket) {
                managerSocket.getOutputStream().write(("QUESTION teacher $question\n").toByteArray())
                try {
                    val response = managerReader.readLine()
                    val clean = response?.removePrefix("ANSWER ") ?: "Nu a raspuns nimeni la intrebare"
                    println("[Teacher] Raspuns primit prin MessageManager: $clean")
                    it.getOutputStream().write((clean + "\n").toByteArray())
                } catch (_: SocketTimeoutException) {
                    it.getOutputStream().write("Nu a raspuns nimeni la intrebare\n".toByteArray())
                }
            }
        }
    }

    fun run() {
        HealthServer(serviceName, "teacher", Ports.TEACHER_HEALTH_PORT).start()
        subscribeToMessageManager()
        val server = ServerSocket(Ports.TEACHER_PORT)
        println("[Teacher] Pornit pe portul ${Ports.TEACHER_PORT}; astept clienti...")
        while (true) {
            val client = server.accept()
            thread(isDaemon = false) { handleQuestion(client) }
        }
    }
}

fun main() = runForever { TeacherMicroservice().run() }
