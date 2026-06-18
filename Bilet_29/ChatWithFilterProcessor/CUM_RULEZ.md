# Bilet 29 — Chat cu Procesor de Flux pentru Filtrare (Chain of Responsibility)

## Arhitectura

```
[chat-client (port 4000)]          [chat-client (port 9500)]
         |                                   |
         | FILTER profesor 4000 <msg>        | FILTER intrus 9500 <msg>
         v                                   v
+-------------------------------------------+
|           FilterProcessor :1700           |
|                                           |
|  Chain of Responsibility (reguli in ordine)|
|  1. ALLOW USER admin          -> CONTINUE |
|  2. DENY  PORT_RANGE 9000-9999-> REJECT --|-----> rejected.log
|  3. ALLOW PORT_RANGE 3000-5000-> ACCEPT --|--+
|  4. DENY  KEYWORD spam        -> CONTINUE |  |
|  5. ALLOW ALL                 -> ACCEPT   |  |
+-------------------------------------------+  |
                                               |
                        +---------+            |
                        |accepted.log|<--------+
                        +---------+            |
                                               v
                              [MessageManager :1500]
                                      |
                              broadcast la alice, bob
```

---

## Diagrama de clase (text)

```
<<interface>>
IFilterRule                          <- veriga din Chain of Responsibility
+ruleName: String
+decision: RuleDecision              <- ACCEPT sau REJECT cand regula se potriveste
+matches(ctx: MessageContext): Boolean
+apply(ctx): RuleDecision            <- matches -> decision, altfel -> CONTINUE
        ^
        | implements (LSP: toate substituie complet IFilterRule)
        |
PortRangeRule(minPort, maxPort, decision)
  + matches(): ctx.fromPort in [min, max]

UserRule(username, decision)
  + matches(): ctx.fromUser == username (case-insensitive)

KeywordRule(keyword, decision)
  + matches(): ctx.message.contains(keyword)

CatchAllRule(decision)
  + matches(): true  (catch-all, mereu se potriveste)

object RuleParser                    <- Factory pentru parsarea rules.txt
  + parse(line: String): IFilterRule?
    "ALLOW PORT_RANGE 3000 5000" -> PortRangeRule(3000, 5000, ACCEPT)
    "DENY  USER spammer"         -> UserRule("spammer", REJECT)
    ...

---

enum class RuleDecision { ACCEPT, REJECT, CONTINUE }

data class MessageContext(fromUser, fromPort, message)

---

<<interface>>
IFilterProcessorService
+loadRules(path)
+getRules(): List<IFilterRule>
+addRule(rule)
+evaluate(ctx): RuleDecision         <- parcurge lantul, returneaza primul ACCEPT/REJECT
+saveToFile(ctx, decision, matchedRule)
        |
        | implements
        v
FilterProcessorServiceImpl
- rules: MutableList<IFilterRule>    (lantul de responsabilitate, ordinea conteaza)
- acceptedLogPath, rejectedLogPath
+ evaluate():
    for (rule in rules) {
        val d = rule.apply(ctx)
        if (d != CONTINUE) return d   // primul match castiga
    }
    return ACCEPT                     // implicit

FilterProcessorMicroservice
- filterService: IFilterProcessorService   <- DIP
+ handleClient(FILTER ...):
    evaluate(ctx) == ACCEPT -> saveToFile(ACCEPT) + forward to MessageManager
    evaluate(ctx) == REJECT -> saveToFile(REJECT) + raspunde REJECTED
+ run()   <- runBlocking { launch(IO) per mesaj }

---

<<interface>>
IMessageRouterService
MessageRouterServiceImpl / MessageManagerMicroservice  (la fel ca lab 8)
```

---

## Regulile din rules.txt

```
# Procesate IN ORDINE, primul match castiga (Chain of Responsibility)

ALLOW USER admin           # admin trece mereu indiferent de port
DENY  PORT_RANGE 9000 9999 # porturile 9000-9999 sunt blocate
ALLOW PORT_RANGE 3000 5000 # porturile 3000-5000 sunt acceptate
DENY  KEYWORD spam         # mesajele cu "spam" sunt blocate
ALLOW ALL                  # orice altceva este acceptat implicit
```

**Cum functioneaza lantul pentru portul 4000:**
1. `ALLOW USER admin` → CONTINUE (nu e admin)
2. `DENY PORT_RANGE 9000-9999` → CONTINUE (4000 nu e in gama)
3. `ALLOW PORT_RANGE 3000-5000` → **ACCEPT** ← primul match, oprire

**Cum functioneaza lantul pentru portul 9500:**
1. `ALLOW USER admin` → CONTINUE
2. `DENY PORT_RANGE 9000-9999` → **REJECT** ← primul match, oprire

---

## Principii SOLID

| Principiu | Cum e respectat |
|---|---|
| **S** (Single Responsibility) | `PortRangeRule` verifica DOAR portul. `UserRule` verifica DOAR username-ul. `KeywordRule` verifica DOAR continutul. `FilterProcessorServiceImpl` aplica lantul si salveaza in fisier. `MessageManager` DOAR ruteaza. |
| **O** (Open/Closed) | Pot adauga un nou tip de regula (ex: `TimeRule` — accepta doar intre 9-17) implementand `IFilterRule` si adaugand o linie in `rules.txt`, fara sa modific codul existent. |
| **L** (Liskov) | Toate regulile (`PortRangeRule`, `UserRule`, `KeywordRule`, `CatchAllRule`) substituie complet `IFilterRule`. |
| **I** (Interface Segregation) | `IFilterRule` (logica unui filtru) e separata de `IFilterProcessorService` (gestionarea lantului). `IMessageRouterService` e separata de ambele. |
| **D** (Dependency Inversion) | `FilterProcessorMicroservice` depinde de `IFilterProcessorService`. `MessageManagerMicroservice` depinde de `IMessageRouterService`. Niciuna nu instantiaza direct implementarile. |

---

## Cum rulez

### Pasul 1 — Porneste serviciile
```bash
cd ChatWithFilterProcessor
docker compose up --build
```

Vei vedea regulile incarcate:
```
filter-processor | [FilterService] 5 reguli incarcate din /app/rules.txt:
filter-processor |   1. ACCEPT_USER[admin]
filter-processor |   2. REJECT_PORT_RANGE[9000-9999]
filter-processor |   3. ACCEPT_PORT_RANGE[3000-5000]
filter-processor |   4. REJECT_KEYWORD[spam]
filter-processor |   5. ACCEPT_ALL
```

### Pasul 2 — Client ACCEPTAT (port 4000, in gama 3000-5000)
```bash
docker compose run --rm --profile allowed chat-client-allowed
```
```
> RULES
  1. ACCEPT_USER[admin]
  2. REJECT_PORT_RANGE[9000-9999]
  3. ACCEPT_PORT_RANGE[3000-5000]
  4. REJECT_KEYWORD[spam]
  5. ACCEPT_ALL

> MSG Buna ziua tuturor!
[Client] Mesaj trimis (ACCEPTAT de filtru)
```
Alice si Bob vad: `<<< profesor: Buna ziua tuturor!`

Fisierul `/tmp/accepted.log` din `filter-processor`:
```
[2026-06-16T...] [ACCEPT] [ACCEPT_PORT_RANGE[3000-5000]] profesor:4000 -> 'Buna ziua tuturor!'
```

### Pasul 3 — Client RESPINS (port 9500, in gama 9000-9999)
```bash
docker compose run --rm --profile blocked chat-client-blocked
```
```
> MSG Incerc sa trimit
[Client] Mesaj RESPINS de filtru: REJECTED regula=REJECT_PORT_RANGE[9000-9999]
```
Alice si Bob NU vad nimic.

Fisierul `/tmp/rejected.log` din `filter-processor`:
```
[2026-06-16T...] [REJECT] [REJECT_PORT_RANGE[9000-9999]] intrus:9500 -> 'Incerc sa trimit'
```

### Pasul 4 — Mesaj cu keyword interzis (port acceptat, dar mesaj spam)
```
> MSG acest mesaj contine spam si nu va trece
[Client] Mesaj RESPINS de filtru: REJECTED regula=REJECT_KEYWORD[spam]
```

---

## Fisierele de rezultate

| Fisier | Continut |
|---|---|
| `/tmp/accepted.log` | Toate mesajele acceptate (timestamp + regula + user:port + mesaj) |
| `/tmp/rejected.log` | Toate mesajele respinse (timestamp + regula care a respins + detalii) |

Poti inspecta din host:
```bash
docker compose exec filter-processor cat /tmp/accepted.log
docker compose exec filter-processor cat /tmp/rejected.log
```

---

## Erori frecvente

| Simptom | Cauza | Rezolvare |
|---|---|---|
| "reincerc..." la pornire | MessageManager nu e ready | normal, asteapta ~3s |
| Toate mesajele sunt respinse | regula DENY ALL la final | schimba cu ALLOW ALL in rules.txt |
| `rules.txt` nu se gaseste | RULES_PATH gresit | e setat in docker-compose la `/app/rules.txt` |



Daca nu merge ceva cu cache/docker

sudo docker-compose build --no-cache
sudo docker-compose up
