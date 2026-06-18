package com.sd.laborator.stream

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.FileTransferRecord
import com.sd.laborator.interfaces.IStreamProcessorService
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: procesarea fluxurilor de fisiere — nimic altceva
// OCP: pot adauga compresie/criptare implementand IStreamProcessorService fara sa ating serverul
// DIP: serverul depinde de IStreamProcessorService (abstractie)
class StreamProcessorServiceImpl : IStreamProcessorService {
    data class UserEndpoint(val host: String, val port: Int)

    private val users   = ConcurrentHashMap<String, UserEndpoint>()
    private val history = mutableListOf<FileTransferRecord>()
    private val lock    = Any()

    override fun registerUser(name: String, host: String, port: Int) {
        users[name] = UserEndpoint(host, port)
    }

    override fun unregisterUser(name: String) { users.remove(name) }

    override fun forwardStream(fromUser: String, targetUser: String, filename: String, data: ByteArray): FileTransferRecord {
        val endpoint = users[targetUser]
        val record: FileTransferRecord

        if (endpoint == null) {
            record = FileTransferRecord(fromUser, targetUser, filename, data.size.toLong(), "TARGET_NOT_FOUND")
        } else {
            record = try {
                // Deschide conexiune directa catre utilizatorul tinta si trimite fluxul de bytes
                val socket = Socket(endpoint.host, endpoint.port)
                socket.use { s ->
                    val out = DataOutputStream(s.getOutputStream())
                    // Header: FILE <from> <filename> <size>\n
                    val header = "FILE $fromUser $filename ${data.size}\n"
                    out.write(header.toByteArray())
                    // Flux de bytes propriu-zis (stream)
                    out.write(data)
                    out.flush()
                    // Asteapta confirmare
                    val ack = BufferedReader(InputStreamReader(s.inputStream)).readLine()
                    if (ack?.startsWith("ACK") == true)
                        FileTransferRecord(fromUser, targetUser, filename, data.size.toLong(), "OK")
                    else
                        FileTransferRecord(fromUser, targetUser, filename, data.size.toLong(), "ERROR: $ack")
                }
            } catch (e: Exception) {
                FileTransferRecord(fromUser, targetUser, filename, data.size.toLong(), "ERROR: ${e.message}")
            }
        }
        synchronized(lock) { history.add(record) }
        return record
    }

    override fun getHistory(): List<FileTransferRecord> = synchronized(lock) { history.toList() }
}

class StreamProcessorMicroservice(private val service: IStreamProcessorService) {

    // Protocolul de upload:
    //   Linia 1 (text): REGISTER <name> <host> <port>  -> inregistreaza utilizatorul
    //   Linia 1 (text): UPLOAD <fromUser> <targetUser> <filename> <sizeBytes>\n
    //   Urmeaza: <sizeBytes> bytes de date brute (flux)
    //   Linia 1 (text): HISTORY  -> returneaza istoricul
    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val din    = DataInputStream(s.inputStream)
            val header = readLine(din) ?: return@withContext
            val parts  = header.trim().split(" ")

            when (parts[0].uppercase()) {
                "REGISTER" -> {
                    // REGISTER <name> <host> <port>
                    if (parts.size < 4) {
                        s.getOutputStream().write("ERROR sintaxa REGISTER\n".toByteArray())
                        return@withContext
                    }
                    service.registerUser(parts[1], parts[2], parts[3].toInt())
                    println("[StreamProcessor] Inregistrat utilizator ${parts[1]} @ ${parts[2]}:${parts[3]}")
                    s.getOutputStream().write("REGISTERED ${parts[1]}\n".toByteArray())
                }
                "UPLOAD" -> {
                    // UPLOAD <fromUser> <targetUser> <filename> <sizeBytes>
                    if (parts.size < 5) {
                        s.getOutputStream().write("ERROR sintaxa UPLOAD\n".toByteArray())
                        return@withContext
                    }
                    val fromUser   = parts[1]
                    val targetUser = parts[2]
                    val filename   = parts[3]
                    val size       = parts[4].toLongOrNull() ?: 0L

                    println("[StreamProcessor] Upload: $fromUser -> $targetUser | $filename ($size bytes)")

                    // Citire flux de bytes de la sender
                    val data = ByteArray(size.toInt())
                    var read = 0
                    while (read < size) {
                        val n = din.read(data, read, (size - read).toInt())
                        if (n < 0) break
                        read += n
                    }

                    // Forwardeaza fluxul catre utilizatorul tinta
                    val result = service.forwardStream(fromUser, targetUser, filename, data)
                    println("[StreamProcessor] Rezultat transfer: $result")
                    s.getOutputStream().write("RESULT ${result.status}\n".toByteArray())
                }
                "HISTORY" -> {
                    val h = service.getHistory()
                    val out = if (h.isEmpty()) "HISTORY (none)\n"
                    else h.joinToString(separator = "\n", postfix = "\n") {
                        "  ${it.fromUser} -> ${it.targetUser} | ${it.filename} | ${it.sizeBytes}B | ${it.status}"
                    }
                    s.getOutputStream().write(("HISTORY\n$out").toByteArray())
                }
                else -> s.getOutputStream().write("UNKNOWN_COMMAND\n".toByteArray())
            }
        }
    }

    // Citeste o linie terminata cu '\n' dintr-un DataInputStream (care e si stream binar)
    private fun readLine(din: DataInputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (true) {
            c = din.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    fun run() = runBlocking {
        val server = ServerSocket(Ports.STREAM_PROCESSOR_PORT)
        println("[StreamProcessor] Pornit pe portul ${Ports.STREAM_PROCESSOR_PORT}")
        while (true) {
            val client = server.accept()
            // Fiecare transfer de fisier e procesat intr-o corutina separata
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = StreamProcessorMicroservice(StreamProcessorServiceImpl()).run()
