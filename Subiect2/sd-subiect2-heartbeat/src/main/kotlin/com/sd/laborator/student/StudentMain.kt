package com.sd.laborator.student

import com.sd.laborator.common.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

class StudentMicroservice {
    private val serviceName = Env.str("SERVICE_NAME", "student")
    private val managerHost = Env.str("MESSAGE_MANAGER_HOST", "localhost")
    private val databasePath = Env.str("QUESTIONS_DB", "questions_database.txt")
    private val qa = mutableMapOf<String, String>()
    private lateinit var managerSocket: java.net.Socket

    private fun loadDatabase() {
        val lines = File(databasePath).readLines().filter { it.isNotBlank() }
        for (i in lines.indices step 2) {
            if (i + 1 < lines.size) qa[lines[i].trim()] = lines[i + 1].trim()
        }
        println("[Student:$serviceName] Am incarcat ${qa.size} perechi intrebare/raspuns")
    }

    private fun subscribe() {
        managerSocket = SocketLine.connect(managerHost, Ports.MESSAGE_MANAGER_PORT, timeoutMs = 0)
        managerSocket.getOutputStream().write("REGISTER $serviceName student\n".toByteArray())
        val reader = BufferedReader(InputStreamReader(managerSocket.inputStream))
        println("[Student:$serviceName] ${reader.readLine()}")
        while (true) {
            val msg = reader.readLine() ?: break
            val parts = msg.split(" ", limit = 3)
            if (parts[0] == "QUESTION") {
                val destination = parts[1]
                val question = parts.getOrElse(2) { "" }.trim()
                val answer = qa[question]
                if (answer != null) {
                    println("[Student:$serviceName] Stiu raspunsul pentru '$question': $answer")
                    managerSocket.getOutputStream().write("ANSWER $destination $answer\n".toByteArray())
                } else {
                    println("[Student:$serviceName] Nu stiu raspunsul pentru '$question'")
                }
            }
        }
        println("[Student:$serviceName] MessageManager s-a oprit")
        exitProcess(1)
    }

    fun run() {
        HealthServer(serviceName, "student", Ports.STUDENT_HEALTH_PORT).start()
        loadDatabase()
        subscribe()
    }
}

fun main() = runForever { StudentMicroservice().run() }
