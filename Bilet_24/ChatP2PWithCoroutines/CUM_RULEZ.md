# Bilet 24 — Chat P2P cu Corutine (fara MessageManager central)



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

in cazul pun probabil in care nu il ai si nu iti merg anumite comenzi cu docker faci asta
(Pasul 1 — Instalează docker-compose separat
sudo apt-get install -y docker-compose

Pasul 2 — Verifici că Docker merge
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

INCHIDERE DOCKER: sudo docker stop $(sudo docker ps -q)

Pas 4 : dupa asta deschizi alt terminal si rulezi

sudo docker-compose run --rm chat-client
si apoi ce apare in lista aia 

> LIST
[Client] USERS bob:user-bob:3001,alice:user-alice:3001
> SEND alice Salut!
[Client] alice gasit @ user-alice:3001. Trimit P2P direct...
[Client] Raspuns: ACK
> SEND bob Buna Bob!
[Client] bob gasit @ user-bob:3001. Trimit P2P direct...
[Client] Raspuns: ACK



























## Arhitectura

```
          +-------------------+
          |   UserRegistry    |  port 2000
          |  (lista activi)   |
          +--------+----------+
                   ^
        REGISTER   |   LOOKUP
                   |
         +---------+---------+
         |                   |
   [user-alice]          [user-bob]
   port 3001              port 3001
         |                   |
         +-------------------+
              MESSAGE direct
              (P2P, fara router)
                   ^
                   |
            [chat-client]
```

**Diferenta fata de lab 8:**
- In lab 8: MessageManager ruteaza FIECARE mesaj prin el (bottleneck central)
- Aici: UserRegistry stie doar CINE e activ si UNDE. Mesajele merg DIRECT intre utilizatori (P2P).

---

## Diagrama de clase (text)

```
<<interface>>
IUserRegistryService
+register(name, host, port)
+lookup(name): UserInfo?
+unregister(name)
+listAll(): List<UserInfo>
        |
        | implements
        v
UserRegistryServiceImpl
- users: ConcurrentHashMap<String, UserInfo>

<<pojo>>
UserInfo(name: String, host: String, port: Int)

UserRegistryMicroservice
- registryService: IUserRegistryService   <- DIP
+ handleClient(socket): suspend
+ run()   <- runBlocking { launch(IO) { ... } }

---

<<interface>>
IChatService
+sendMessage(targetUser, message): suspend
+onMessageReceived(from, text)
        |
        | implements
        v
ChatMicroservice
- myName, myHost, myPort
- registryHost
+ registerWithRetry(): suspend   <- delay() coroutine
+ sendMessage(targetUser, message): suspend
+ onMessageReceived(from, text)
+ handleIncoming(socket): suspend
+ run()   <- runBlocking { launch(IO) { ... } }

---

ChatClient
- myName, registryHost
+ sendMessage(targetUser, message): suspend
+ listUsers(): suspend
+ run()   <- runBlocking { ... }
```

---

## Principii SOLID

| Principiu | Cum e respectat |
|---|---|
| **S** (Single Responsibility) | `UserRegistry` stocheaza DOAR lista activi. `ChatMicroservice` face DOAR chat P2P. `ChatClient` face DOAR interfata cu utilizatorul. |
| **O** (Open/Closed) | Pot schimba `UserRegistryServiceImpl` cu o varianta bazata pe DB implementand `IUserRegistryService`, fara sa modific serverul TCP sau clientul. |
| **L** (Liskov) | `UserRegistryServiceImpl` substituie complet `IUserRegistryService`. `ChatMicroservice` substituie complet `IChatService`. |
| **I** (Interface Segregation) | `IUserRegistryService` (operatii registru) e separata de `IChatService` (trimitere/primire mesaje). |
| **D** (Dependency Inversion) | `UserRegistryMicroservice` depinde de `IUserRegistryService`, nu de `UserRegistryServiceImpl` direct. |

---

## Corutine — unde si cum

| Loc | Cum se foloseste |
|---|---|
| `UserRegistryMicroservice.run()` | `runBlocking { while(true) { launch(Dispatchers.IO) { handleClient(client) } } }` |
| `ChatMicroservice.run()` | identic — fiecare mesaj P2P primit e o corutina separata |
| `ChatMicroservice.registerWithRetry()` | `delay(2000)` (suspend function) in loc de `Thread.sleep` |
| `ChatMicroservice.sendMessage()` | `withContext(Dispatchers.IO)` pentru operatii blocking (socket) |
| `ChatClient.run()` | `withContext(Dispatchers.IO)` pentru readline si socketuri |

---

## Cum rulez

### Cerinte
- Docker + Docker Compose instalate pe Linux

### Pasul 1 — Porneste registry si utilizatorii
```bash
cd ChatP2PWithCoroutines
docker compose up --build
```

Vei vedea:
```
user-registry  | [Registry] UserRegistry pornit pe portul 2000
user-alice     | [alice] Inregistrat in UserRegistry (user-registry:2000)
user-alice     | [alice] Ascult conexiuni P2P pe portul 3001
user-bob       | [bob] Inregistrat in UserRegistry (user-registry:2000)
user-bob       | [bob] Ascult conexiuni P2P pe portul 3001
```

### Pasul 2 — Deschide clientul interactiv (terminal nou)
```bash
docker compose run --rm chat-client
```

### Pasul 3 — Trimite mesaje
```
> LIST
[Client] USERS alice:user-alice:3001,bob:user-bob:3001

> SEND alice Salut Alice, esti acolo?
[Client] alice gasit @ user-alice:3001. Trimit P2P direct...
[Client] Raspuns: ACK

> SEND bob Buna Bob!
[Client] bob gasit @ user-bob:3001. Trimit P2P direct...
[Client] Raspuns: ACK

> SEND carol Exista?
[Client] Utilizatorul 'carol' nu este activ.

> EXIT
[Client] La revedere!
```

In terminalul cu `docker compose up` vei vedea la alice/bob:
```
user-alice  | [alice] <<< Mesaj de la client: Salut Alice, esti acolo?
user-bob    | [bob]   <<< Mesaj de la client: Buna Bob!
```

---

## Erori frecvente

| Simptom | Cauza | Rezolvare |
|---|---|---|
| `user-alice` - "reincerc in 2s..." | registry nu e pornit inca | normal, `registerWithRetry` asteapta |
| `NOT_FOUND` la SEND | utilizatorul nu s-a inregistrat inca | asteapta cateva secunde dupa `up` |
| Port 2000/3001 ocupat | alt proces pe host | opreste-l sau modifica portul |
