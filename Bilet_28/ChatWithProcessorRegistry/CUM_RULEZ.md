# Bilet 28 — Chat cu Registru de Procesoare de Flux (pattern Observer)

## Arhitectura

```
                    +-----------------------+
                    |   ProcessorRegistry   |  port 1600  <- Subject (Observer pattern)
                    |   (registru activ)    |
                    +----+------+------+----+
                         |      |      |
              REGISTER   |      |      | notifica observatorii (push TCP)
                         v      |      v
              +-----------+     |  +-------------------+
              |LogProcessor|    |  |UpperCaseProcessor  |
              | port 1701  |    |  |    port 1702       |
              +-----------+     |  +-------------------+
                                |
                   PROCESSOR_REGISTERED (push)
                                |
                                v
                    +-------------------+
                    |  MessageManager   |  port 1500  <- Observer
                    |  (pipeline dinamic)|  port 1501  <- primeste notificari
                    +--------+----------+
                             |
                    broadcast (dupa pipeline)
                             |
                    +--------+--------+
                    |                 |
               [user-alice]      [user-bob]
```

**Fluxul unui mesaj prin pipeline:**
```
Client -> MessageManager:  MESSAGE profesor Buna ziua
MessageManager -> LogProcessor:       PROCESS profesor Buna ziua -> PROCESSED Buna ziua
MessageManager -> UpperCaseProcessor: PROCESS profesor Buna ziua -> PROCESSED BUNA ZIUA
MessageManager -> broadcast:          MESSAGE profesor BUNA ZIUA
```

---

## Diagrama de clase (text)

```
<<interface>>
IRegistryObserver                        <- Observer din pattern-ul Observer
+onProcessorRegistered(info: ProcessorInfo)
+onProcessorUnregistered(name: String)
        ^
        | implements
        |
MessageManagerMicroservice               <- Observer concret

---

<<interface>>
IProcessorRegistryService                <- Subject din pattern-ul Observer
+registerProcessor(info)
+unregisterProcessor(name): ProcessorInfo?
+getProcessor(name): ProcessorInfo?
+listProcessors(): List<ProcessorInfo>
+addObserver(endpoint: ObserverEndpoint)
+removeObserver(endpoint: ObserverEndpoint)
+notifyObservers(event, info)            <- trimite notificari push la toti observatorii
        |
        | implements
        v
ProcessorRegistryServiceImpl
- processors: ConcurrentHashMap<String, ProcessorInfo>
- observers:  ConcurrentHashMap.KeySet<ObserverEndpoint>
+ notifyObservers():
    observers.forEach { SocketLine.sendAndRead(obs.host, obs.port, event) }
    // observatorii cazuti sunt scosi automat

<<pojo>>
ProcessorInfo(name, host, port, type)
ObserverEndpoint(host, port)

ProcessorRegistryMicroservice
- registryService: IProcessorRegistryService   <- DIP
+ handleClient(socket): suspend
+ run()   <- runBlocking { launch(IO) }

---

<<interface>>
IStreamProcessor                         <- ISP: separata de IProcessorRegistryService
+process(fromUser, message): String
+processorName: String
+processorType: String
        ^
        | implements
        |
LogProcessorImpl                         <- LSP: substituie complet IStreamProcessor
+ process(): jurnalizeaza, returneaza mesajul nemodificat

UpperCaseProcessorImpl                   <- LSP: substituie complet IStreamProcessor
+ process(): returneaza message.uppercase()

LogProcessorServer / UpperCaseProcessorServer
- processor: IStreamProcessor            <- DIP
+ registerWithRetry(): suspend
+ unregister()   <- apelat din ShutdownHook (dezinregistrare automata)
+ handleClient(): PROCESS -> processor.process() -> PROCESSED
+ run()   <- runBlocking { launch(IO) }

---

MessageManagerMicroservice : IRegistryObserver
- router: IMessageRouterService          <- DIP
- pipeline: MutableList<ProcessorInfo>   (actualizat dinamic de Observer)
+ onProcessorRegistered(): adauga in pipeline, notifica userii
+ onProcessorUnregistered(): scoate din pipeline, notifica userii
+ applyPipeline(): trimite mesajul prin fiecare procesor din pipeline
+ startObserverListener(): porneste server pe port 1501 pentru notificari push
+ subscribeToRegistry(): trimite SUBSCRIBE la Registry
```

---

## Principii SOLID

| Principiu | Cum e respectat |
|---|---|
| **S** (Single Responsibility) | `ProcessorRegistry` DOAR gestioneaza registrul. `LogProcessor` DOAR jurnalizeaza. `UpperCaseProcessor` DOAR transforma. `MessageManager` ruteaza + aplica pipeline. `ChatMicroservice` DOAR primeste. |
| **O** (Open/Closed) | Pot adauga un nou tip de procesor (ex: CensorProcessor, TranslateProcessor) implementand `IStreamProcessor` si pornind un nou container — fara sa modific codul existent. |
| **L** (Liskov) | `LogProcessorImpl` si `UpperCaseProcessorImpl` substituie complet `IStreamProcessor`. |
| **I** (Interface Segregation) | `IRegistryObserver` (primire notificari) e separata de `IProcessorRegistryService` (gestiune registru). `IStreamProcessor` (procesare mesaj) e separata de ambele. |
| **D** (Dependency Inversion) | `MessageManagerMicroservice` depinde de `IMessageRouterService` si `IRegistryObserver`. `ProcessorRegistryMicroservice` depinde de `IProcessorRegistryService`. Serverele procesoarelor depind de `IStreamProcessor`. |

---

## Pattern Observer — cum functioneaza

```
1. MessageManager porneste
   -> startObserverListener() pe portul 1501
   -> SUBSCRIBE message-manager 1501  (trimis la Registry)

2. LogProcessor porneste
   -> REGISTER log-processor log-processor 1701 log  (trimis la Registry)
   Registry -> notifyObservers():
      -> PROCESSOR_REGISTERED log-processor log-processor 1701 log  (push la portul 1501)
   MessageManager.onProcessorRegistered():
      -> adauga LogProcessor in pipeline
      -> broadcast "SYSTEM Procesor de flux activ: log-processor (log)"

3. Mesaj trimis de client:
   MESSAGE profesor Buna ziua
   -> applyPipeline(): trimite la LogProcessor -> PROCESSED Buna ziua
   -> broadcast: MESSAGE profesor Buna ziua  (logat, dar nemodificat)

4. UpperCaseProcessor porneste (similar cu pasul 2)
   -> pipeline devine [LogProcessor, UpperCaseProcessor]

5. Mesaj nou:
   MESSAGE profesor Buna ziua
   -> LogProcessor:       PROCESSED Buna ziua
   -> UpperCaseProcessor: PROCESSED BUNA ZIUA
   -> broadcast:          MESSAGE profesor BUNA ZIUA

6. UpperCaseProcessor se opreste (SIGTERM)
   -> ShutdownHook: UNREGISTER uppercase-processor
   Registry -> notifyObservers(): PROCESSOR_UNREGISTERED uppercase-processor
   MessageManager.onProcessorUnregistered():
      -> scoate UpperCaseProcessor din pipeline
      -> broadcast "SYSTEM Procesor de flux oprit: uppercase-processor"
```

---

## Cum rulez

### Pasul 1 — Porneste toate serviciile
```bash
cd ChatWithProcessorRegistry
docker compose up --build
```

Vei vedea in ordine:
```
processor-registry | [Registry] ProcessorRegistry pornit pe portul 1600
message-manager    | [MessageManager] Subscris la ProcessorRegistry
log-processor      | [LogProcessor] Inregistrat in ProcessorRegistry
message-manager    | [MessageManager] Procesor adaugat in pipeline: log-processor (log)
uppercase-processor| [UpperCaseProcessor] Inregistrat in ProcessorRegistry
message-manager    | [MessageManager] Procesor adaugat in pipeline: uppercase-processor (uppercase)
```

### Pasul 2 — Deschide clientul (terminal nou)
```bash
docker compose run --rm chat-client
```

### Pasul 3 — Testeaza

```
> PROCS
[Client] PROCESSORS log-processor:log@log-processor:1701|uppercase-processor:uppercase@uppercase-processor:1702

> MSG Buna ziua, salut din chat!
```
Alice si Bob vad: `<<< profesor: BUNA ZIUA, SALUT DIN CHAT!`  
Fisierul `/tmp/chat_messages.log` din containerul `log-processor` contine:
`[2026-06-16T...] profesor: Buna ziua, salut din chat!`

### Pasul 4 — Opreste un procesor la runtime (demonstreaza Observer)

Intr-un terminal nou:
```bash
docker compose stop uppercase-processor
```

In clientul deschis apare automat:
```
*** SISTEM: Procesor de flux oprit: uppercase-processor
```

Acum mesajele nu mai sunt transformate in uppercase:
```
> MSG Salut dupa oprire
```
Alice si Bob vad: `<<< profesor: Salut dupa oprire`  (nu mai e UPPERCASE)

---

## Erori frecvente

| Simptom | Cauza | Rezolvare |
|---|---|---|
| "reincerc..." la MessageManager | Registry nu e ready | normal, asteapta ~3s |
| Pipeline gol, mesaje directe | procesoarele nu s-au inregistrat inca | asteapta ~5s dupa start |
| `PROCESSORS (none)` | procesoarele nu sunt pornite | verifica logurile containerelor |
