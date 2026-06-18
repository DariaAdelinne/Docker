# Explicatie scurta pentru prezentare

## Ideea proiectului

Aplicatia pastreaza modelul din laborator: un client intreaba profesorul, profesorul trimite intrebarea catre MessageManager, MessageManager o distribuie studentilor, iar studentul care stie raspunsul raspunde inapoi prin MessageManager.

Modificarea pentru subiectul de examen este adaugarea unui `HeartbeatProcessor`. El trimite periodic mesaje false si ping-uri de sanatate pentru a verifica daca microserviciile merg. Daca un serviciu nu raspunde, HeartbeatProcessor trimite o cerere de replicare catre `ReplicationProcessor`.

## Microservicii

- `MessageManagerMicroservice` — broker/ruter de mesaje. Tine lista de subscriberi si face broadcast pentru intrebari.
- `TeacherMicroservice` — adaptor intre client si reteaua de microservicii. Primeste intrebari pe portul 1600.
- `StudentMicroservice` — raspunde doar daca are intrebarea in fisierul lui `questions_database`.
- `HeartbeatProcessor` — procesor de flux pentru heartbeat. Verifica starea serviciilor si fluxul real al aplicatiei cu intrebarea falsa `__healthcheck__`.
- `ReplicationProcessor` — procesor dedicat pentru cererile de replicare. In proiect logheaza cererea, ceea ce demonstreaza decuplarea dintre detectie si replicare.

## De ce mesajul fals verifica fluxul corect

`HeartbeatProcessor` nu face doar un ping simplu. El trimite catre profesor intrebarea falsa `__healthcheck__`. Aceasta trebuie sa parcurga lantul:

`HeartbeatProcessor -> Teacher -> MessageManager -> Student -> MessageManager -> Teacher -> HeartbeatProcessor`

Daca raspunsul este `OK_HEARTBEAT`, atunci stim ca Teacher, MessageManager si cel putin un Student functioneaza corect impreuna.

## Ce se intampla la defect

Daca opresc `student1`:

```bash
docker stop sd2_student1
```

HeartbeatProcessor observa timeout/eroare si trimite:

```text
REPLICATE student1 student host=student1 port=1701 reason=...
```

ReplicationProcessor raspunde:

```text
REPLICATION_REQUEST_ACCEPTED
```

## SOLID

### S — Single Responsibility
Fiecare microserviciu are o responsabilitate clara: managerul ruteaza, profesorul adapteaza cereri client, studentul raspunde din baza lui, heartbeat-ul monitorizeaza, replication-ul primeste cereri de replicare.

### O — Open/Closed
Pot adauga studenti noi fara sa modific `MessageManager`: pornesc alt container `StudentMicroservice` cu alt fisier de intrebari.

### L — Liskov Substitution
Serviciile monitorizabile respecta acelasi contract de health: primesc `PING` si raspund cu `PONG`. HeartbeatProcessor le poate trata uniform.

### I — Interface Segregation
Clientul stie doar de Teacher. Heartbeat stie doar de health si replicare. Studentul nu stie de client. Fiecare foloseste doar interfata de care are nevoie.

### D — Dependency Inversion
Serviciile depind de protocoale text/TCP si variabile de mediu, nu de clase concrete sau adrese hardcodate. In Docker, hostname-urile sunt injectate prin `docker-compose.yml`.
