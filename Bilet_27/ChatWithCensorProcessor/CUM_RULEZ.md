# Bilet 27 — Chat cu Procesor de Flux Cenzura (coroutine)


OPRESTE TOT CE E CU DOCKER DINAINTE
docker stop $(docker ps -q)
sudo docker stop $(sudo docker ps -q)


PORNESTE DOCKER
docker compose up --build

in alt terminal



> MSG salut
[Client] SENT_CLEAN
>
[Client] <<< profesor: salut

>WORDS
[Client] DICTIONARY [groaznic, idiot, nasol, prost, rau, spam, teribil, urat]
> MSG nasol

[Client] <<< profesor: xxxxx
> [Client] SENT_CENSORED replaced=[nasol]














## Arhitectura

```
[chat-client]
      |
      | MSG "mesajul asta e rau si spam"
      |
      v
[CensorProcessor :1700]
  incarca banned_words.txt la pornire
  "rau"  (3 litere) -> "xxx"
  "spam" (4 litere) -> "xxxx"
  mesaj cenzurat: "mesajul asta e xxx si xxxx"
      |
      | MESSAGE profesor "mesajul asta e xxx si xxxx"
      v
[MessageManager :1500] ---broadcast---> [user-alice]
                                  \---> [user-bob]
```

**Fluxul tuturor mesajelor trece obligatoriu prin CensorProcessor.**
MessageManager primeste si distribuie DOAR versiunile deja censurate.

---

## Diagrama de clase (text)

```
<<interface>>
IMessageRouterService
+subscribe(id, name, socket)
+unsubscribe(id)
+broadcast(message, exceptId)
+respondTo(id, message)
        |
        | implements
        v
MessageRouterServiceImpl
- subs: ConcurrentHashMap<Int, Subscriber>

MessageManagerMicroservice
- router: IMessageRouterService     <- DIP
+ handleClient(socket): suspend
+ run()   <- runBlocking { launch(IO) }

---

<<interface>>
ICensorProcessorService
+loadDictionary(path: String)          <- incarca fisierul txt
+getDictionary(): Set<String>
+addWord(word: String)                 <- adauga la runtime
+removeWord(word: String): Boolean
+censor(text: String): CensorResult   <- inlocuieste cuvintele interzise
        |
        | implements
        v
CensorProcessorServiceImpl
- bannedWords: ConcurrentHashMap.KeySet<String>   (thread-safe)
+ censor(text):
    text.split(tokens).map { token ->
        if (token.lowercase() in bannedWords) "x".repeat(token.length)
        else token
    }.join()

<<pojo>>
CensorResult(original: String, censored: String, replacedWords: List<String>)

CensorProcessorMicroservice
- censorService: ICensorProcessorService   <- DIP
- managerHost: String
- managerSocket: Socket    (conexiune persistenta la MessageManager)
+ handleClient(socket): suspend
    SEND   -> censor() -> forward to MessageManager
    ADD_WORD / REMOVE_WORD / LIST_WORDS / TEST
+ run()   <- runBlocking { launch(IO) per cerere }

---

ChatMicroservice
- myName, managerHost
+ connectWithRetry(): suspend    <- delay() coroutine
+ run()   <- asculta broadcast de la MessageManager

ChatClient
- myName, managerHost, censorHost
+ censorCmd(command): suspend    <- withContext(IO)
+ run()   <- MSG trimite la CensorProcessor, nu direct la MessageManager
```

---

## Principii SOLID

| Principiu | Cum e respectat |
|---|---|
| **S** (Single Responsibility) | `CensorProcessor` DOAR cenzureaza text. `MessageManager` DOAR ruteaza mesaje. `ChatMicroservice` DOAR primeste si afiseaza. `ChatClient` DOAR interfata utilizator. |
| **O** (Open/Closed) | Pot inlocui `CensorProcessorServiceImpl` cu o versiune cu regex sau ML implementand `ICensorProcessorService`, fara sa modific serverul TCP. |
| **L** (Liskov) | `CensorProcessorServiceImpl` substituie complet `ICensorProcessorService`. `MessageRouterServiceImpl` substituie complet `IMessageRouterService`. |
| **I** (Interface Segregation) | `ICensorProcessorService` (cenzura) e complet separata de `IMessageRouterService` (rutare). |
| **D** (Dependency Inversion) | `CensorProcessorMicroservice` depinde de `ICensorProcessorService`. `MessageManagerMicroservice` depinde de `IMessageRouterService`. |

---

## Corutine — unde si cum

| Loc | Cum se foloseste |
|---|---|
| `MessageManagerMicroservice.run()` | `runBlocking { launch(Dispatchers.IO) { handleClient(c) } }` |
| `CensorProcessorMicroservice.run()` | identic — fiecare cerere de cenzura e o corutina |
| `ChatMicroservice.connectWithRetry()` | `delay(2000)` (suspend) in loc de `Thread.sleep` |
| `ChatMicroservice.run()` | `launch(Dispatchers.IO)` pentru listener broadcast |
| `ChatClient.censorCmd()` | `withContext(Dispatchers.IO)` pentru socket |

---

## Dictionarul de cuvinte interzise

Fisierul `banned_words.txt` (cate un cuvant pe linie, case-insensitive):
```
spam
rau
urat
prost
idiot
groaznic
teribil
nasol
```

**Logica de inlocuire** — "x" * lungime_cuvant:
- `"rau"` (3 litere) → `"xxx"`
- `"spam"` (4 litere) → `"xxxx"`
- `"groaznic"` (8 litere) → `"xxxxxxxx"`

---

## Cum rulez

### Pasul 1 — Porneste serviciile
```bash
cd ChatWithCensorProcessor
docker compose up --build
```

Vei vedea:
```
censor-processor | [CensorService] Dictionar incarcat: 8 cuvinte din /app/banned_words.txt
censor-processor | [CensorProcessor] Pornit pe portul 1700
```

### Pasul 2 — Deschide clientul (terminal nou)
```bash
docker compose run --rm chat-client
```

### Pasul 3 — Testeaza

**Test cenzura (fara trimitere):**
```
> TEST acest mesaj e rau si groaznic
[Client] CENSORED: acest mesaj e xxx si xxxxxxxx
```

**Trimite mesaj (trecut automat prin cenzura):**
```
> MSG Buna ziua, sper ca nu e nasol afara
[Client] SENT_CENSORED replaced=[nasol]
```
Alice si Bob vad: `<<< profesor: Buna ziua, sper ca nu e xxxxx afara`

**Mesaj curat (nicio inlocuire):**
```
> MSG Buna dimineata tuturor
[Client] SENT_CLEAN
```
Alice si Bob vad: `<<< profesor: Buna dimineata tuturor`

**Gestioneaza dictionarul la runtime:**
```
> WORDS
[Client] DICTIONARY [groaznic, idiot, nasol, prost, rau, spam, teribil, urat]

> ADD toxic
[Client] WORD_ADDED toxic

> TEST apa e toxic
[Client] CENSORED: apa e xxxxx

> REMOVE idiot
[Client] WORD_REMOVED idiot

> WORDS
[Client] DICTIONARY [groaznic, nasol, prost, rau, spam, teribil, toxic, urat]
```

---

## Erori frecvente

| Simptom | Cauza | Rezolvare |
|---|---|---|
| "reincerc..." la pornire | MessageManager nu e ready | normal, asteapta ~3s |
| `banned_words.txt` nu se gaseste | DICTIONARY_PATH gresit | e setat in docker-compose la `/app/banned_words.txt` |
| Mesajul nu e cenzurat | cuvantul nu e in dictionar | foloseste `ADD <cuvant>` sau editeaza `banned_words.txt` |
