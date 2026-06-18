package com.sd.laborator.interfaces

// Contextul unui mesaj care trebuie filtrat
data class MessageContext(
    val fromUser: String,
    val fromPort: Int,
    val message:  String
)

// Decizia unei reguli — implementeaza Chain of Responsibility
enum class RuleDecision {
    ACCEPT,   // regula accepta explicit => oprim lantul, mesajul trece
    REJECT,   // regula respinge explicit => oprim lantul, mesajul e blocat
    CONTINUE  // regula nu are opinie => continuam cu urmatoarea regula din lant
}

// IFilterRule — interfata pentru o regula de filtrare (veriga din Chain of Responsibility)
// ISP: separata de IFilterProcessorService
interface IFilterRule {
    val ruleName: String
    val decision: RuleDecision          // ACCEPT sau REJECT cand regula se potriveste
    fun matches(ctx: MessageContext): Boolean
    // Aplica regula: daca matches -> returneaza decision, altfel -> CONTINUE
    fun apply(ctx: MessageContext): RuleDecision =
        if (matches(ctx)) decision else RuleDecision.CONTINUE
}

// IFilterProcessorService — gestioneaza lantul de reguli si rezultatul filtrarii
// ISP: separata de IFilterRule si IMessageRouterService
interface IFilterProcessorService {
    fun loadRules(path: String)
    fun getRules(): List<IFilterRule>
    fun addRule(rule: IFilterRule)
    // Aplica lantul de reguli (Chain of Responsibility): primul match castiga
    // Daca nicio regula nu da un verdict => ACCEPT implicit
    fun evaluate(ctx: MessageContext): RuleDecision
    // Salveaza rezultatul (acceptat/respins) intr-un fisier local
    fun saveToFile(ctx: MessageContext, decision: RuleDecision, matchedRule: String)
}
