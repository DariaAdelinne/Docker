package com.sd.laborator.filter.rules

import com.sd.laborator.interfaces.IFilterRule
import com.sd.laborator.interfaces.MessageContext
import com.sd.laborator.interfaces.RuleDecision

// SRP: fiecare clasa implementeaza O SINGURA regula de filtrare
// LSP: toate substituie complet IFilterRule
// OCP: pot adauga noi tipuri de reguli fara sa modific codul existent

// Regula de gama de porturi: accepta/respinge daca fromPort e in [min, max]
class PortRangeRule(
    private val minPort: Int,
    private val maxPort: Int,
    override val decision: RuleDecision
) : IFilterRule {
    override val ruleName = "${decision.name}_PORT_RANGE[$minPort-$maxPort]"
    override fun matches(ctx: MessageContext): Boolean =
        ctx.fromPort in minPort..maxPort
}

// Regula de utilizator: accepta/respinge un anume username
class UserRule(
    private val username: String,
    override val decision: RuleDecision
) : IFilterRule {
    override val ruleName = "${decision.name}_USER[$username]"
    override fun matches(ctx: MessageContext): Boolean =
        ctx.fromUser.equals(username, ignoreCase = true)
}

// Regula de cuvant cheie: accepta/respinge daca mesajul contine cuvantul
class KeywordRule(
    private val keyword: String,
    override val decision: RuleDecision
) : IFilterRule {
    override val ruleName = "${decision.name}_KEYWORD[$keyword]"
    override fun matches(ctx: MessageContext): Boolean =
        ctx.message.contains(keyword, ignoreCase = true)
}

// Regula implicita (catch-all): accepta sau respinge orice
class CatchAllRule(override val decision: RuleDecision) : IFilterRule {
    override val ruleName = "${decision.name}_ALL"
    override fun matches(ctx: MessageContext): Boolean = true
}

// Factory: parseaza o linie din rules.txt si creeaza regula corespunzatoare
// OCP: pot adauga noi tipuri de reguli adaugand un nou `when` branch
object RuleParser {
    fun parse(line: String): IFilterRule? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        val parts    = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return null

        val decision = when (parts[0].uppercase()) {
            "ALLOW" -> RuleDecision.ACCEPT
            "DENY"  -> RuleDecision.REJECT
            else    -> return null
        }

        return when (parts[1].uppercase()) {
            "PORT_RANGE" -> {
                if (parts.size < 4) return null
                PortRangeRule(parts[2].toInt(), parts[3].toInt(), decision)
            }
            "USER"    -> if (parts.size < 3) null else UserRule(parts[2], decision)
            "KEYWORD" -> if (parts.size < 3) null else KeywordRule(parts[2], decision)
            "ALL"     -> CatchAllRule(decision)
            else      -> null
        }
    }
}
