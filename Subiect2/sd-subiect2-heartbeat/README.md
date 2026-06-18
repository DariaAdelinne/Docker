# Subiect 2 — microservicii chat cu HeartbeatProcessor si ReplicationProcessor

Proiect Kotlin/JVM + Docker Compose, pornit de la modelul din Laboratorul 8: `TeacherMicroservice`, `StudentMicroservice` si `MessageManagerMicroservice` comunica prin socket-uri TCP. Modificarea ceruta la examen este inclusa prin:

1. `HeartbeatProcessor` — procesor de flux care trimite mesaje false (`__healthcheck__`) si ping-uri catre microservicii.
2. `ReplicationProcessor` — procesor dedicat care primeste cereri de replicare cand HeartbeatProcessor detecteaza un serviciu picat.
3. `HealthServer` — server mic de sanatate inclus in microserviciile verificabile, ca sa se poata testa individual fiecare container.

## Structura

```text
src/main/kotlin/com/sd/laborator/
  common/        cod comun minim: Env, Ports, SocketLine, HealthServer, ServiceEndpoint
  manager/       MessageManagerMicroservice
  teacher/       TeacherMicroservice
  student/       StudentMicroservice
  heartbeat/     HeartbeatProcessor
  replication/   ReplicationProcessor
  client/        client de test pentru intrebari
diagrams/        diagrame Mermaid + PNG pentru diagrama de clase
scripts/         scripturi de test
Dockerfile
docker-compose.yml
pom.xml
```

## Cerinte pe masina virtuala Debian

Verifica:

```bash
java -version
mvn -version
docker --version
docker compose version
```

Daca lipseste Maven:

```bash
sudo apt-get update
sudo apt-get install -y maven
```

Daca Docker cere `sudo`, ruleaza comenzile cu `sudo` sau adauga userul in grupul Docker:

```bash
sudo usermod -aG docker $USER
# apoi logout/login sau restart
```

## Rulare rapida

Din folderul proiectului, varianta recomandata pe VM-ul Debian:

```bash
docker compose up --build
```

Nu mai este obligatoriu sa rulezi `mvn clean package` pe masina gazda. Imaginea Docker construieste JAR-ul intr-un container Maven cu JDK 17, ca sa nu te incurce o versiune prea noua de Java instalata pe VM.

Lasa terminalul cu `docker compose up` deschis. In al doilea terminal, din acelasi folder:

```bash
./scripts/client.sh "Care e sensul vietii?"
```

Raspuns asteptat:

```text
Intrebare: Care e sensul vietii?
Raspuns: 42
```

Alte teste:

```bash
./scripts/client.sh "Cat face 2+2?"
./scripts/client.sh "De ce a trecut gaina strada?"
./scripts/client.sh "Ce este SOLID?"
./scripts/client.sh "Intrebare fara raspuns"
```

Pentru intrebarea fara raspuns, raspunsul corect este mesajul de timeout: `Nu a raspuns nimeni la intrebare`.

## Demonstrare heartbeat + replicare

Cu proiectul pornit, opreste un serviciu:

```bash
docker stop sd2_student1
```

In logurile lui `heartbeat-processor` trebuie sa apara `FAIL student1/student`, apoi o cerere catre ReplicationProcessor. Verifica:

```bash
docker logs sd2_heartbeat_processor

docker logs sd2_replication_processor
```

Cererea este salvata si in volumul Docker:

```bash
docker exec sd2_replication_processor cat /data/replication_requests.log
```

Porneste serviciul la loc:

```bash
docker start sd2_student1
```

Dupa reconectare, HeartbeatProcessor revine la mesaje `OK`.

## Oprire si curatare

```bash
docker compose down
```

Daca vrei sa stergi si volumul cu cererile de replicare:

```bash
docker compose down -v
```

## Probleme frecvente si rezolvare

### 0. Eroare Maven cu `IllegalArgumentException: 25.0.2`

Asta inseamna ca Maven ruleaza cu Java 25, iar versiunea veche de Kotlin nu poate interpreta versiunea JDK-ului. In arhiva corectata am trecut compilarea in Docker cu JDK 17 si am actualizat Kotlin. Ruleaza direct:

```bash
docker compose down
docker compose up --build
```

Daca vrei neaparat sa rulezi si Maven local, instaleaza JDK 17 si seteaza `JAVA_HOME`:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
mvn -version
mvn clean package
```

### 1. Port already allocated / portul e ocupat

Verifica cine foloseste porturile:

```bash
docker ps -a
sudo ss -ltnp | grep -E '1500|1501|1600|1601|1800'
```

Opreste containerele vechi:

```bash
docker compose down
# sau, punctual:
docker stop sd2_teacher sd2_message_manager sd2_replication_processor
```

### 2. `docker compose` nu exista

Unele instalari mai vechi au `docker-compose`:

```bash
docker-compose up --build
```

### 3. Studentul nu raspunde la intrebari

Verifica daca baza lui de intrebari e incarcata:

```bash
docker logs sd2_student1
docker logs sd2_student2
```

`student1` raspunde la `__healthcheck__`, `Care e sensul vietii?`, `Cat face 2+2?`.
`student2` raspunde la `De ce a trecut gaina strada?`, `Ce este SOLID?`.

### 4. Heartbeat raporteaza FAIL imediat dupa pornire

La pornire, unele servicii au nevoie de cateva secunde sa se conecteze. Asteapta 10-15 secunde si verifica din nou:

```bash
docker logs -f sd2_heartbeat_processor
```

### 5. Maven nu descarca dependintele

Verifica internetul in VM si ruleaza din nou:

```bash
mvn -U clean package
```

## Explicatie pentru prezentare

Flux normal:

1. `TeacherMicroservice`, `StudentMicroservice` se inregistreaza la `MessageManagerMicroservice` cu mesaj `REGISTER`.
2. Clientul trimite o intrebare catre `TeacherMicroservice` pe portul 1600.
3. Profesorul trimite `QUESTION` catre `MessageManagerMicroservice`.
4. Managerul face broadcast catre studenti.
5. Studentul care stie raspunsul trimite `ANSWER` inapoi prin manager.
6. Profesorul intoarce raspunsul clientului.

Flux heartbeat:

1. `HeartbeatProcessor` verifica individual serviciile prin `PING` pe porturile de health.
2. Pentru verificarea functionala a intregului lant, trimite intrebarea falsa `__healthcheck__` la profesor.
3. Daca primeste `OK_HEARTBEAT`, lantul Teacher -> MessageManager -> Student -> MessageManager -> Teacher functioneaza.
4. Daca apare timeout sau eroare, trimite `REPLICATE ...` catre `ReplicationProcessor`.

## Respectarea SOLID

- **Single Responsibility**: fiecare microserviciu are o singura responsabilitate: rutare mesaje, raspuns la intrebari, adaptor client/profesor, monitorizare, respectiv replicare.
- **Open/Closed**: se pot adauga studenti noi doar prin alt container si alt fisier de intrebari, fara modificarea codului `MessageManager`.
- **Liskov Substitution**: toate serviciile verificabile expun acelasi contract simplu de health: `PING -> PONG`.
- **Interface Segregation**: clientul foloseste doar portul profesorului; Heartbeat foloseste contractul minim de health si contractul de replicare, fara sa cunoasca baza de date a studentilor.
- **Dependency Inversion**: serviciile depind de protocoale text/TCP si de variabile de mediu (`MESSAGE_MANAGER_HOST`, `SERVICES_TO_CHECK`), nu de instante concrete hardcodate.

## Diagrame

- `diagrams/class-diagram.png` — diagrama de clase pentru prezentare.
- `diagrams/class-diagram.mmd` — varianta Mermaid editabila.
- `diagrams/sequence-heartbeat.mmd` — secventa heartbeat si replicare.
- `diagrams/components.mmd` — diagrama componentelor/microserviciilor.
