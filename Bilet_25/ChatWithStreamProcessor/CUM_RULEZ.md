# Bilet 25 — Chat cu Procesor de Flux (coroutine)

Ce am facut eu:

Pas 1:  java si SDK
- poti rula jav -version sa vezi ce veriune activa ai
  proiectul nostru cere java 17 si ai 2 variante
1. Nu e recomadat, te duci in pom si schimbi in <jvmTarget>1.8</jvmTarget> sau ce versiune ai tu
2. Recomandat te duci in file -> project structure si setezisdk la 17
   (daca nu il gasesti rulezi aceste comenzi ca sa il instalezi
   sudo apt update
   sudo apt install openjdk-17-jdk)

Pas 2:  Docker
verifici daca ai docker instalat si ce versiune
(docker --version si docker-compose --version )

in cazul putin probabil in care nu il ai si nu iti merg anumite comenzi cu docker faci asta
(Pasul 1 ? Instaleaz? docker-compose separat
sudo apt-get install -y docker-compose

Pasul 2 ? Verifici c? Docker merge
sudo systemctl start docker
sudo docker ps)

apoi  daca o sa incerci niste comenzi si nu iti merg faci asta (

sudo apt-get remove docker-compose
sudo curl -L "https://github.com/docker/compose/releases/download/v2.24.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
docker-compose --version)


Pas 3: aici pornesti dockerul,  stergi ce e in dockere deja pornit si iti pornesti dockerul
# 2. Pornire Docker
sudo systemctl start docker
sudo systemctl enable docker


sudo docker-compose down -v
sudo docker-compose up --build



Pas 4 : dupa asta deschizi alt terminal si rulezi

sudo docker-compose run --rm chat-client
si apoi ce apare in lista aia

MSG Salut tuturor!

FILE alice /test-files/hello.txt

HISTORY
EXIT


































## Arhitectura

```
[chat-client]
      |
      |--- MSG <text> -----------> [MessageManager :1500] -----> [user-alice :3001]
      |                                                    \----> [user-bob   :3001]
      |
      |--- FILE alice hello.txt --> [StreamProcessor :1700]
                                          |
                                          | flux de bytes (stream)
                                          v
                                    [user-alice :3001]
                                    (salveaza fisierul pe disc)
```

**Ce e nou fata de lab 8:**
- `StreamProcessor` — microserviciu nou care primeste un fisier (flux de bytes) de la sender
  si il redirecteaza direct catre destinatar, fara ca MessageManager sa fie implicat
- Coroutine in loc de thread-uri pentru toate accept-loop-urile

---

## Diagrama de clase (text)

```
<<interface>>
IMessageRouterService
+subscribe(id, name, socket)
+unsubscribe(id)
+broadcast(message, exceptId)
+respondTo(id, message)
+getAll(): List<Subscriber>
        |
        | implements
        v
MessageRouterServiceImpl
- subs: ConcurrentHashMap<Int, Subscriber>

<<pojo>>
Subscriber(id: Int, name: String, socket: Socket)

MessageManagerMicroservice
- router: IMessageRouterService     <- DIP
+ handleClient(socket): suspend     <- withContext(IO)
+ run()                             <- runBlocking { launch(IO) }

---

<<interface>>
IStreamProcessorService
+registerUser(name, host, port)
+unregisterUser(name)
+forwardStream(fromUser, targetUser, filename, data: ByteArray): FileTransferRecord
+getHistory(): List<FileTransferRecord>
        |
        | implements
        v
StreamProcessorServiceImpl
- users:   ConcurrentHashMap<String, UserEndpoint>
- history: MutableList<FileTransferRecord>

<<pojo>>
FileTransferRecord(fromUser, targetUser, filename, sizeBytes, status)

StreamProcessorMicroservice
- service: IStreamProcessorService   <- DIP
+ handleClient(socket): suspend      <- withContext(IO), citeste header + flux bytes
+ run()                              <- runBlocking { launch(IO) }

---

ChatMicroservice
- myName, myHost, myChatPort
- managerHost, streamHost
+ registerWithRetry(...): suspend    <- delay() coroutine in loc de Thread.sleep
+ listenForMessages()                <- citeste continuu din MessageManager
+ handleIncoming(socket): suspend    <- trateaza MESSAGE sau FILE (cu flux bytes)
+ run()                              <- runBlocking { launch(IO) pentru fiecare conexiune }

ChatClient
- myName, managerHost, streamHost
+ sendTextMessage(text): suspend
+ sendFile(targetUser, filePath): suspend   <- citeste fisier, trimite header + flux bytes
+ showHistory(): suspend
+ run()                                     <- runBlocking { ... }
```

---

## Principii SOLID

| Principiu | Cum e respectat |
|---|---|
| **S** (Single Responsibility) | `MessageManager` ruteaza DOAR text. `StreamProcessor` proceseaza DOAR fluxuri de fisiere. `ChatMicroservice` primeste DOAR mesaje/fisiere. `ChatClient` face DOAR interfata utilizator. |
| **O** (Open/Closed) | Pot inlocui `StreamProcessorServiceImpl` cu o versiune cu compresie/criptare implementand `IStreamProcessorService`, fara sa modific serverul TCP. |
| **L** (Liskov) | `MessageRouterServiceImpl` si `StreamProcessorServiceImpl` substituie complet interfetele lor. |
| **I** (Interface Segregation) | `IMessageRouterService` (text routing) e complet separata de `IStreamProcessorService` (file streaming). |
| **D** (Dependency Inversion) | `MessageManagerMicroservice` depinde de `IMessageRouterService`. `StreamProcessorMicroservice` depinde de `IStreamProcessorService`. Niciuna nu instantiaza direct implementarile. |

---

## Corutine — unde si cum

| Loc | Cum se foloseste |
|---|---|
| `MessageManagerMicroservice.run()` | `runBlocking { while(true) { launch(Dispatchers.IO) { handleClient(c) } } }` |
| `StreamProcessorMicroservice.run()` | identic — fiecare transfer de fisier e o corutina |
| `ChatMicroservice.run()` | `launch(IO)` pentru listener mesaje + `launch(IO)` pentru fiecare conexiune entranta |
| `ChatMicroservice.registerWithRetry()` | `delay(2000)` (suspend) in loc de `Thread.sleep` |
| `ChatMicroservice.handleIncoming()` | `withContext(Dispatchers.IO)` pentru citire flux bytes |
| `ChatClient.sendFile()` | `withContext(Dispatchers.IO)` pentru citire fisier + socket |

---

## Cum rulez

### Pasul 1 — Pregateste un fisier de test
```bash
# Fisierul test-files/hello.txt e deja in proiect
# Poti adauga orice alt fisier in test-files/
```

### Pasul 2 — Porneste serviciile
```bash
cd ChatWithStreamProcessor
docker compose up --build
```

Astepti sa vezi:
```
message-manager  | [MessageManager] Pornit pe portul 1500
stream-processor | [StreamProcessor] Pornit pe portul 1700
user-alice       | [alice] Pornit, ascult pe portul 3001
user-bob         | [bob]   Pornit, ascult pe portul 3001
```

### Pasul 3 — Deschide clientul (terminal nou)
```bash
docker compose run --rm chat-client
```

### Pasul 4 — Testeaza

**Mesaj text (broadcast):**
```
> MSG Buna ziua tuturor!
```
Vei vedea in logurile alice/bob: `<<< Mesaj de la client: Buna ziua tuturor!`

**Trimitere fisier:**
```
> FILE alice /test-files/hello.txt
[Client] Trimit 'hello.txt' (89 bytes) catre alice prin StreamProcessor...
[Client] Raspuns StreamProcessor: RESULT OK
```
In logurile alice: `<<< Fisier primit de la client: hello.txt (89 bytes)`
Fisierul e salvat in containerul alice la `/tmp/received_alice/hello.txt`

**Fisier catre utilizator inexistent:**
```
> FILE carol /test-files/hello.txt
[Client] Raspuns StreamProcessor: RESULT TARGET_NOT_FOUND
```

**Istoricul transferurilor:**
```
> HISTORY
[Client] --- Istoric transferuri ---
  client -> alice | hello.txt | 89B | OK
  client -> carol | hello.txt | 89B | TARGET_NOT_FOUND
```

**Iesire:**
```
> EXIT
```

---

## Erori frecvente

| Simptom | Cauza | Rezolvare |
|---|---|---|
| "reincerc..." la pornire | serviciul dependenta nu e ready | normal, asteapta ~5s |
| `TARGET_NOT_FOUND` | utilizatorul nu s-a inregistrat inca in StreamProcessor | asteapta pornirea completa |
| Port ocupat | alt proces pe host | modifica portul in docker-compose.yml |
