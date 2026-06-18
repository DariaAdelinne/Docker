package com.sd.laborator.filter

import com.sd.laborator.common.*
import com.sd.laborator.filter.rules.CatchAllRule
import com.sd.laborator.filter.rules.RuleParser
import com.sd.laborator.interfaces.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime

// SRP: gestioneaza lantul de reguli si persista rezultatele filtrarii
// OCP: pot adauga noi tipuri de reguli (ex: TimeRule) fara sa modific aceasta clasa
// DIP: depinde de IFilterProcessorService si IFilterRule (abstractii)
class FilterProcessorServiceImpl(
    private val acceptedLogPath: String,
    private val rejectedLogPath: String
) : IFilterProcessorService {

    // Lantul de reguli (Chain of Responsibility) — ordinea conteaza
    private val rules = mutableListOf<IFilterRule>()
    private val lock  = Any()

    override fun loadRules(path: String) {
        val file = File(path)
        if (!file.exists()) {
            println("[FilterService] Fisierul de reguli nu exista: $path — se foloseste ALLOW ALL implicit")
            synchronized(lock) { rules.add(CatchAllRule(RuleDecision.ACCEPT)) }
            return
        }
        val loaded = file.readLines().mapNotNull { RuleParser.parse(it) }
        synchronized(lock) { rules.addAll(loaded) }
        println("[FilterService] ${rules.size} reguli incarcate din $path:")
        rules.forEachIndexed { i, r -> println("  ${i + 1}. ${r.ruleName}") }
    }

    override fun getRules(): List<IFilterRule> = synchronized(lock) { rules.toList() }

    override fun addRule(rule: IFilterRule) { synchronized(lock) { rules.add(rule) } }

    // Chain of Responsibility: parcurge lantul de reguli; primul ACCEPT/REJECT castiga
    override fun evaluate(ctx: MessageContext): RuleDecision {
        val chain = synchronized(lock) { rules.toList() }
        for (rule in chain) {
            val decision = rule.apply(ctx)
            if (decision != RuleDecision.CONTINUE) {
                println("[FilterService] Regula '${rule.ruleName}' -> $decision | ${ctx.fromUser}:${ctx.fromPort} '${ctx.message}'")
                return decision
            }
        }
        // Nicio regula nu a dat verdict => ACCEPT implicit
        println("[FilterService] Nicio regula nu s-a potrivit -> ACCEPT implicit")
        return RuleDecision.ACCEPT
    }

    // Salveaza mesajul in fisierul corespunzator (acceptat sau respins)
    override fun saveToFile(ctx: MessageContext, decision: RuleDecision, matchedRule: String) {
        val timestamp = LocalDateTime.now()
        val entry = "[$timestamp] [$decision] [$matchedRule] ${ctx.fromUser}:${ctx.fromPort} -> '${ctx.message}'\n"
        val path  = if (decision == RuleDecision.ACCEPT) acceptedLogPath else rejectedLogPath
        synchronized(this) {
            File(path).also { it.parentFile?.mkdirs() }.appendText(entry)
        }
    }
}

// Serverul TCP al FilterProcessor — sta intre client si MessageManager
// Fiecare mesaj este evaluat de lantul de reguli inainte sa fie transmis mai departe
class FilterProcessorMicroservice(private val filterService: IFilterProcessorService) {
    private val managerHost = Env.str("MESSAGE_MANAGER_HOST", "localhost")

    private lateinit var managerSocket: Socket
    private lateinit var managerOut:    java.io.OutputStream

    private fun connectToManager() {
        managerSocket = Socket(managerHost, Ports.MESSAGE_MANAGER_PORT)
        managerOut    = managerSocket.getOutputStream()
        managerOut.write("REGISTER filter-processor\n".toByteArray())
        val resp = BufferedReader(InputStreamReader(managerSocket.inputStream)).readLine()
        println("[FilterProcessor] Conectat la MessageManager: $resp")
        managerSocket.soTimeout = 0
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            val line  = BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim()
                ?: return@withContext
            val parts = line.split(" ", limit = 4)

            when (parts[0].uppercase()) {
                // FILTER <fromUser> <fromPort> <message>
                "FILTER" -> {
                    if (parts.size < 4) {
                        s.getOutputStream().write("ERROR sintaxa: FILTER <user> <port> <msg>\n".toByteArray())
                        return@withContext
                    }
                    val ctx = MessageContext(
                        fromUser = parts[1],
                        fromPort = parts[2].toIntOrNull() ?: 0,
                        message  = parts[3]
                    )

                    // Aplica lantul de reguli (Chain of Responsibility)
                    val decision    = filterService.evaluate(ctx)
                    val matchedRule = filterService.getRules()
                        .firstOrNull { it.apply(ctx) != RuleDecision.CONTINUE }?.ruleName ?: "implicit"

                    // Salveaza rezultatul in fisier local
                    filterService.saveToFile(ctx, decision, matchedRule)

                    if (decision == RuleDecision.ACCEPT) {
                        // Transmite mesajul (acceptat) la MessageManager pentru broadcast
                        synchronized(managerOut) {
                            managerOut.write("MESSAGE ${ctx.fromUser} ${ctx.message}\n".toByteArray())
                        }
                        s.getOutputStream().write("ACCEPTED\n".toByteArray())
                    } else {
                        // Mesaj respins — NU se trimite la MessageManager, se salveaza doar in rejected.log
                        s.getOutputStream().write("REJECTED regula=$matchedRule\n".toByteArray())
                    }
                }
                // LIST_RULES  — afiseaza lantul de reguli curent
                "LIST_RULES" -> {
                    val rules = filterService.getRules()
                    val resp  = if (rules.isEmpty()) "RULES (none)"
                                else "RULES\n" + rules.mapIndexed { i, r -> "  ${i+1}. ${r.ruleName}" }.joinToString("\n")
                    s.getOutputStream().write(("$resp\n").toByteArray())
                }
                else -> s.getOutputStream().write("UNKNOWN_COMMAND\n".toByteArray())
            }
        }
    }

    fun run() = runBlocking {
        val rulesPath    = Env.str("RULES_PATH",    "/app/rules.txt")
        val acceptedLog  = Env.str("ACCEPTED_LOG",  "/tmp/accepted.log")
        val rejectedLog  = Env.str("REJECTED_LOG",  "/tmp/rejected.log")

        filterService.loadRules(rulesPath)
        withContext(Dispatchers.IO) { connectToManager() }

        val server = ServerSocket(Ports.FILTER_PROCESSOR_PORT)
        println("[FilterProcessor] Pornit pe portul ${Ports.FILTER_PROCESSOR_PORT}")
        println("[FilterProcessor] Acceptate -> $acceptedLog | Respinse -> $rejectedLog")
        while (true) {
            val client = server.accept()
            // Fiecare mesaj e evaluat intr-o corutina separata
            launch(Dispatchers.IO) { handleClient(client) }
        }
    }
}

fun main() {
    val acceptedLog = Env.str("ACCEPTED_LOG", "/tmp/accepted.log")
    val rejectedLog = Env.str("REJECTED_LOG", "/tmp/rejected.log")
    FilterProcessorMicroservice(FilterProcessorServiceImpl(acceptedLog, rejectedLog)).run()
}
