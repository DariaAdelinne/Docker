package com.sd.laborator.client

import com.sd.laborator.common.Env
import com.sd.laborator.common.Ports
import com.sd.laborator.common.SocketLine

fun main(args: Array<String>) {
    val host = Env.str("TEACHER_HOST", "localhost")
    val port = Env.int("TEACHER_PORT", Ports.TEACHER_PORT)
    val question = if (args.isNotEmpty()) args.joinToString(" ") else "Care e sensul vietii?"
    val response = SocketLine.sendAndRead(host, port, question, timeoutMs = 5000)
    println("Intrebare: $question")
    println("Raspuns: ${response ?: "nu am primit raspuns"}")
}
