# Bilet 26 — Chat cu Procesor de Flux Multicast (coroutine)





Ce am facut eu:

Pas 1:  java si SDK
- poti rula jav -version sa vezi ce veriune activa ai
  proiectul nostru cere java 17 si ai 2 variante
1. Nu e recomadat, te duci in pom si schimbi in <jvmTarget>1.8</jvmTarget> sau ce versiune ai tu
2. Recomandat te duci in file -> project structure si setezisdk la 17
   (daca nu il gasesti rulezi aceste comenzi ca sa il instalezi
   sudo apt update
   sudo apt install openjdk-17-jdk)
TREBUIE SA AI IN PROJECT STRUCTURE PE LANGA JDK 17 si PROJECT LANGAGE LA 17
si apoi dai invalid cache  and restart din file 

Pas 2:  Docker
verifici daca ai docker instalat si ce versiune
(docker --version si docker-compose --version )

in cazul putin probabil in care nu il ai si nu iti merg anumite comenzi cu docker faci asta
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



Pas 4 : dupa asta deschizi alt terminal si rulezi

sudo docker-compose run --rm chat-client
si apoi ce apare in lista aia

MSG salut
CREATE grupa1
JOIN grupa1 alice
JOIN grupa1 bob
MCAST grupa1 Salut tuturor din grupa1!
GROUPS
MEMBERS grupa1
LEAVE grupa1 alice
EXIT



sudo docker-compose down
sudo docker rmi chatwithmulticast-message-manager chatwithmulticast-multicast-processor chatwithmulticast-user-alice chatwithmulticast-user-bob chatwithmulticast-user-carol
sudo docker-compose build --no-cache
sudo docker-compose up

























## Arhitectura

```
[chat-client (profesor)]
        |
        |-- MSG <text> ---------> [MessageManager :1500] ----> alice, bob, carol
        |
        |-- CREATE grupa-A -----> [MulticastProcessor :1700]
        |-- JOIN grupa-A alice ->        |
        |-- JOIN grupa-A bob  ->         |
        |                                |
        |-- MCAST grupa-A text ->        | launch(IO) parallel
                                         |---> [user-alice :3001]  MULTICAST...
                                         |---> [user-bob   :3001]  MULTICAST...
                                         (carol nu e in grup, nu primeste)
```

**Ce e nou fata de lab 8:**
- `MulticastProcessor` — microserviciu nou care mentine o lista de grupuri (subgrupuri de studenti)
  si la un MCAST trimite mesajul **simultan** catre toti membrii grupului, in **corutine paralele**
- Coroutine in loc de thread-uri peste tot

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
IMulticastProcessorService
+registerUser(name, host, port)
+unregisterUser(name)
+getUser(name): UserEndpoint?
+createGroup(groupName, creatorName): Boolean
+joinGroup(groupName, userName): Boolean
+leaveGroup(groupName, userName): Boolean
+deleteGroup(groupName): Boolean
+getGroupMembers(groupName): Set<String>?
+listGroups(): Map<String, Set<String>>
+multicast(groupName, fromUser, message): MulticastResult    <- suspend!
        |
        | implements
        v
MulticastProcessorServiceImpl
- users:  ConcurrentHashMap<String, UserEndpoint>
- groups: ConcurrentHashMap<String, MutableSet<String>>
+ multicast():  coroutineScope { members.forEach { launch(IO) { ... } } }
                ^--- trimitere SIMULTANA catre toti membrii (nu secventiala)

<<pojo>>
UserEndpoint(name, host, port)
MulticastResult(group, fromUser, message, delivered: List, failed: List)

MulticastProcessorMicroservice
- service: IMulticastProcessorService   <- DIP
+ handleClient(socket): suspend
+ run()   <- runBlocking { launch(IO) }

---

ChatMicroservice
- myName, myHost, myChatPort
+ registerWithRetry(): suspend       <- delay() in loc de Thread.sleep
+ handleIncoming(socket): suspend    <- trateaza MULTICAST sau MESSAGE
+ run()   <- runBlocking { launch(IO) per conexiune }

ChatClient
- myName, managerHost, multicastHost
+ multicastCmd(command): suspend
+ run()   <- runBlocking, comenzi: MSG/CREATE/JOIN/LEAVE/MCAST/GROUPS/MEMBERS
```

---

## Principii SOLID

| Principiu | Cum e respectat |
|---|---|
| **S** (Single Responsibility) | `MessageManager` ruteaza DOAR text broadcast. `MulticastProcessor` gestioneaza DOAR grupuri + multicast. `ChatMicroservice` primeste DOAR mesaje. `ChatClient` face DOAR interfata utilizator. |
| **O** (Open/Closed) | Pot inlocui `MulticastProcessorServiceImpl` cu o versiune cu persistenta in DB implementand `IMulticastProcessorService`, fara sa modific serverul TCP. |
| **L** (Liskov) | `MessageRouterServiceImpl` si `MulticastProcessorServiceImpl` substituie complet interfetele lor. |
| **I** (Interface Segregation) | `IMessageRouterService` (text routing) e complet separata de `IMulticastProcessorService` (grupuri + multicast). |
| **D** (Dependency Inversion) | `MessageManagerMicroservice` depinde de `IMessageRouterService`. `MulticastProcessorMicroservice` depinde de `IMulticastProcessorService`. |

---

## Corutine — unde si cum

| Loc | Cum se foloseste |
|---|---|
| `MessageManagerMicroservice.run()` | `runBlocking { launch(IO) { handleClient(c) } }` |
| `MulticastProcessorMicroservice.run()` | identic |
| **`MulticastProcessorServiceImpl.multicast()`** | `coroutineScope { members.forEach { launch(IO) { sendToMember } } }` — **trimitere SIMULTANA in corutine paralele** |
| `ChatMicroservice.registerWithRetry()` | `delay(2000)` (suspend) in loc de `Thread.sleep` |
| `ChatMicroservice.run()` | `launch(IO)` per conexiune entranta |
| `ChatClient.multicastCmd()` | `withContext(Dispatchers.IO)` pentru socket |

**Coroutine scope pentru multicast:**
```kotlin
coroutineScope {               // asteapta TOATE corutinele copil
    members.forEach { member ->
        launch(Dispatchers.IO) {   // fiecare livrare = corutina separata
            SocketLine.sendAndRead(endpoint.host, endpoint.port, "MULTICAST ...")
        }
    }
}   // returneaza abia cand TOTI membrii au primit (sau au dat timeout)
```

---

## Cum rulez

### Pasul 1 — Porneste serviciile
```bash
cd ChatWithMulticast
docker compose up --build
```

### Pasul 2 — Deschide clientul (terminal nou)
```bash
docker compose run --rm chat-client
```

### Pasul 3 — Scenariu complet

```
# Listeaza utilizatorii disponibili (inregistrati in multicast)
> GROUPS
[Client] GROUPS (none)

# Creeaza un subgrup de studenti
> CREATE grupa-sd
[Client] GROUP_CREATED grupa-sd

# Adauga studenti in grup
> JOIN grupa-sd alice
[Client] JOINED alice -> grupa-sd

> JOIN grupa-sd bob
[Client] JOINED bob -> grupa-sd

# Carol NU e adaugata in grup

# Verifica membrii
> MEMBERS grupa-sd
[Client] MEMBERS grupa-sd:[profesor,alice,bob]

# Trimite mesaj multicast DOAR la grupa-sd (carol nu primeste)
> MCAST grupa-sd Buna dimineata, grupa!
[Client] Trimit multicast la grupul 'grupa-sd'...
[Client] MULTICAST_DONE group=grupa-sd delivered=[alice, bob] failed=[]
```

In logurile containerelor:
```
user-alice | [alice] <<< [MULTICAST grup=grupa-sd] profesor: Buna dimineata, grupa!
user-bob   | [bob]   <<< [MULTICAST grup=grupa-sd] profesor: Buna dimineata, grupa!
# carol nu vede nimic
```

```
# Mesaj text normal (toti il vad prin MessageManager)
> MSG Mesaj pentru toata lumea
user-alice | [alice] <<< [TEXT] profesor: Mesaj pentru toata lumea
user-bob   | [bob]   <<< [TEXT] profesor: Mesaj pentru toata lumea
user-carol | [carol] <<< [TEXT] profesor: Mesaj pentru toata lumea

# Scoate bob din grup
> LEAVE grupa-sd bob
[Client] LEFT bob <- grupa-sd

# Sterge grupul
> DELETE grupa-sd
[Client] GROUP_DELETED grupa-sd

> EXIT
```

---

## Erori frecvente

| Simptom | Cauza | Rezolvare |
|---|---|---|
| "reincerc..." la pornire | serviciu dependent nu e ready | normal, asteapta ~5s |
| `failed=[alice(...)]` | alice nu s-a inregistrat in multicast | asteapta pornirea completa |
| `GROUP_EXISTS` | grupul deja exista | foloseste alt nume |
| `GROUP_NOT_FOUND` | grupul nu exista | fa CREATE inainte |
