package com.sd.laborator.censor

import com.sd.laborator.common.*
import com.sd.laborator.interfaces.CensorResult
import com.sd.laborator.interfaces.ICensorProcessorService
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

// SRP: cenzurarea textului pe baza unui dictionar — nimic altceva
// OCP: pot schimba logica de cenzura (ex: regex, stemming) implementand ICensorProcessorService
// DIP: serverul depinde de ICensorProcessorService (abstractie)
class CensorProcessorServiceImpl : ICensorProcessorService {
    // ConcurrentHashMap<word, word> folosit ca Set thread-safe
    private val bannedWords = ConcurrentHashMap.newKeySet<String>()

    override fun loadDictionary(path: String) {
        val file = File(path)
        if (!file.exists()) {
            println("[CensorService] Fisierul dictionar nu exista: $path")
            return
        }
        val loaded = file.readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        bannedWords.addAll(loaded)
        println("[CensorService] Dictionar incarcat: ${bannedWords.size} cuvinte din $path")
    }

    override fun getDictionary(): Set<String> = bannedWords.toSet()

    override fun addWord(word: String) { bannedWords.add(word.trim().lowercase()) }

    override fun removeWord(word: String): Boolean = bannedWords.remove(word.trim().lowercase())

    // Inlocuieste fiecare cuvant interzis cu "x" * lungime_cuvant
    // Compara case-insensitive; pastreaza punctuatia din jurul cuvantului
    override fun censor(text: String): CensorResult {
        val replaced = mutableListOf<String>()
        // Imparte textul in tokeni (cuvinte + delimitatori) pastrand spatiile/punctuatia
        val result = text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { token ->
            val clean = token.trim().lowercase().trimEnd('.', ',', '!', '?', ';', ':')
            if (clean.isNotBlank() && bannedWords.contains(clean)) {
                replaced.add(clean)
                "x".repeat(token.length)
            } else token
        }
        return CensorResult(text, result, replaced)
    }
}

// Procesorul de flux care sta intre clienti si MessageManager
// Intercepteaza fiecare mesaj, il cenzureaza, apoi il transmite mai departe
class CensorProcessorMicroservice(private val censorService: ICensorProcessorService) {
    private val managerHost = Env.str("MESSAGE_MANAGER_HOST", "localhost")

    // Mentine o singura conexiune persistenta la MessageManager pentru a trimite mesaje censurate
    private lateinit var managerSocket: Socket
    private lateinit var managerOut:    java.io.OutputStream

    private fun connectToManager() {
        managerSocket = Socket(managerHost, Ports.MESSAGE_MANAGER_PORT)
        managerOut    = managerSocket.getOutputStream()
        // Se inregistreaza ca "censor-processor" in manager (pentru a putea trimite MESSAGE)
        managerOut.write("REGISTER censor-processor\n".toByteArray())
        val resp = BufferedReader(InputStreamReader(managerSocket.inputStream)).readLine()
        println("[CensorProcessor] Conectat la MessageManager: $resp")
        managerSocket.soTimeout = 0
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim()
                ?: return@withContext
            val parts = line.split(" ", limit = 3)

            when (parts[0].uppercase()) {
                // SEND <fromUser> <mesaj>  — clientul trimite un mesaj prin procesor
                "SEND" -> {
                    val from    = parts.getOrElse(1) { "unknown" }
                    val rawText = parts.getOrElse(2) { "" }

                    // Cenzureaza fluxul de text
                    val result = censorService.censor(rawText)

                    if (result.replacedWords.isNotEmpty()) {
                        println("[CensorProcessor] Cuvinte censurate in mesajul lui $from: ${result.replacedWords}")
                    }

                    // Transmite mesajul cenzurat la MessageManager (care il va da broadcast)
                    synchronized(managerOut) {
                        managerOut.write("MESSAGE $from ${result.censored}\n".toByteArray())
                    }

                    val response = if (result.replacedWords.isEmpty()) "SENT_CLEAN"
                                   else "SENT_CENSORED replaced=${result.replacedWords}"
                    s.getOutputStream().write((response + "\n").toByteArray())
                }
                // ADD_WORD <cuvant>  — adauga un cuvant in dictionar la runtime
                "ADD_WORD" -> {
                    val word = parts.getOrElse(1) { "" }
                    if (word.isBlank()) {
                        s.getOutputStream().write("ERROR missing word\n".toByteArray())
                    } else {
                        censorService.addWord(word)
                        println("[CensorProcessor] Cuvant adaugat in dictionar: $word")
                        s.getOutputStream().write("WORD_ADDED $word\n".toByteArray())
                    }
                }
                // REMOVE_WORD <cuvant>  — scoate un cuvant din dictionar la runtime
                "REMOVE_WORD" -> {
                    val word = parts.getOrElse(1) { "" }
                    val ok   = censorService.removeWord(word)
                    val resp = if (ok) "WORD_REMOVED $word" else "WORD_NOT_FOUND $word"
                    println("[CensorProcessor] $resp")
                    s.getOutputStream().write(("$resp\n").toByteArray())
                }
                // LIST_WORDS  — afiseaza dictionarul curent
                "LIST_WORDS" -> {
                    val words = censorService.getDictionary()
                    val resp  = if (words.isEmpty()) "DICTIONARY (empty)"
                                else "DICTIONARY [${words.sorted().joinToString(", ")}]"
                    s.getOutputStream().write(("$resp\n").toByteArray())
                }
                // TEST <text>  — testeaza cenzura fara a trimite la MessageManager
                "TEST" -> {
                    val text   = parts.drop(1).joinToString(" ")
                    val result = censorService.censor(text)
                    s.getOutputStream().write("CENSORED: ${result.censored}\n".toByteArray())
                }
                else -> s.getOutputStream().write("UNKNOWN_COMMAND ${parts[0]}\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        val dictionaryPath = Env.str("DICTIONARY_PATH", "/app/banned_words.txt")
        censorService.loadDictionary(dictionaryPath)

        withContext(Dispatchers.IO) { connectToManager() }

        val server = ServerSocket(Ports.CENSOR_PROCESSOR_PORT)
        println("[CensorProcessor] Pornit pe portul ${Ports.CENSOR_PROCESSOR_PORT}")
        println("[CensorProcessor] Dictionar: ${censorService.getDictionary().size} cuvinte interzise")
        while (true) {
            val client = server.accept()
            // Fiecare mesaj primit e procesat intr-o corutina separata
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() = CensorProcessorMicroservice(CensorProcessorServiceImpl()).run()
