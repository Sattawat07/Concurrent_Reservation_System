# DEMO — Concurrent Reservation System (Movie Theater)

Command cheat sheet for running the three required experiments.
Everything here is copy-paste friendly. Run the server in one terminal and the
clients in other terminals (or background them as shown).

---

## 0. Files and prerequisites

| File | Role |
|------|------|
| `Server.java` | Multi-threaded server: producer-consumer queue + worker threads + seat table |
| `Client.java` | Interactive client (adds the client id to the wire format for you) |
| `Dockerfile` | Builds a JDK image that compiles and runs the server |
| `REQUIREMENTS.md` / `TODO.md` | Assignment notes and fix checklist |
| `RaceTest.java` | Starts concurrent clients for repeatable race experiments |

Prerequisites: JDK 17+ (`javac`, `java`) and, if you want the container path, Docker.

---

## 1. Build (host, no Docker)

```bash
cd cinema-reservation-system
javac -d out Server.java Client.java
```

If you prefer classes in the current directory (same as the Dockerfile does):

```bash
javac Server.java Client.java
```

---

## 2. Server CLI reference

```bash
java -cp out Server [sync|nosync] [workerCount]
```

| Command | Meaning |
|---------|---------|
| `java -cp out Server sync 1` | Experiment 1 — sequential baseline, semaphore on |
| `java -cp out Server nosync 3` | Experiment 2 — 3 workers, semaphore OFF (race demo) |
| `java -cp out Server sync 3` | Experiment 3 — 3 workers, semaphore ON (race fixed) |
| `java -cp out Server` | Default: `sync 3` |

Notes:

- Mode must be `sync` or `nosync`; anything else prints usage and exits with code 1.
- A non-numeric worker count falls back to 3 automatically.
- Startup banner prints mode / workers / seats / queue capacity.
- Queue capacity is 50. When full the server answers
  `FAILED: Server busy (request queue is full). Please retry.` instead of hanging the client.

---

## 3. Client CLI reference

```bash
java -cp out Client [clientId] [serverHost]
```

```bash
java -cp out Client Client-1              # connects to localhost
java -cp out Client Client-2 127.0.0.1
```

| You type | Meaning |
|----------|---------|
| `LIST` | Show all 20 seats with status / owner |
| `STATUS 10` | Show one seat |
| `RESERVE 10` | Reserve seat 10 if available |
| `CANCEL 10` | Cancel your own reservation for seat 10 |
| `QUIT` | Close the connection |

Invalid input never disconnects you:

- missing or non-numeric id -> `FAILED: Invalid command format. Usage: ...`
- id outside 1..20 -> `FAILED: Invalid seat number. Seat must be between 1 and 20.`
- unknown command -> `FAILED: Unknown command.`

Protocol note: every server reply ends with the marker line `END_RESPONSE`.
`Client.java` reads until that marker, which is why the multi-line `LIST` output
can no longer leak into the next command's response.

---

## 4. Experiment 1 — Sequential baseline (1 worker)

Terminal A:

```bash
java -cp out Server sync 1
```

Terminal B (repeat for Client-2 .. Client-5):

```bash
java -cp out Client Client-1
```

Then type this mix in the client:

```text
LIST
STATUS 3
RESERVE 3
RESERVE 3
STATUS 3
CANCEL 10
CANCEL 3
LIST
QUIT
```

Single-shot version of the same test:

```bash
printf 'LIST\nSTATUS 3\nRESERVE 3\nRESERVE 3\nSTATUS 3\nCANCEL 3\nLIST\nQUIT\n' | java -cp out Client Client-1
```

Expected:

- The second `RESERVE 3` fails with `FAILED: Seat 3 is already reserved.`
- `LIST` shows `Seat 3: RESERVED by Client-1` then `AVAILABLE` after the cancel.
- Only one worker exists, so requests are handled one at a time: no race.

---

## 5. Experiment 2 — Concurrent WITHOUT synchronization (race)

Terminal A:

```bash
java -cp out Server nosync 3
```

Terminal B — open 5 clients (or 5 terminals):

```bash
java -cp out RaceTest localhost 10 5
```

The test waits until all five connections are ready and then releases them together. Manual shell alternative:

```bash
for i in 1 2 3 4 5; do
  ( printf 'RESERVE 10\nQUIT\n' | java -cp out Client "C$i" ) &
done
wait
```

Race it a few times because timing decides the outcome:

```bash
for round in 1 2 3; do
  echo "--- round $round ---"
  for i in 1 2 3 4 5; do
    ( printf 'RESERVE 10\nQUIT\n' | java -cp out Client "R${round}C$i" | grep -E 'SUCCESS|FAILED' ) &
  done
  wait
done
```

Expected (race evidence):

- More than one client receives `SUCCESS: Seat 10 reserved successfully.`
- In the server log several workers log
  `CHECK seat 10: AVAILABLE` before any `UPDATE`, and the final owner is the
  last `UPDATE` writer (earlier winners get overwritten).
- The reservation table is shared data; the queue only serialises *delivery*,
  not the check-and-update inside `RESERVE`.

Useful log filter:

```bash
java -cp out Server nosync 3 2>&1 | grep -E 'ENQUEUE|DEQUEUE|CHECK seat 10|UPDATE seat 10'
```

---

## 6. Experiment 3 — Concurrent WITH synchronization (fixed)

Terminal A (same worker count and same random delay):

```bash
java -cp out Server sync 3
```

Terminal B (same 5-client test as Experiment 2):

```bash
java -cp out RaceTest localhost 10 5
```

Manual shell alternative:

```bash
for i in 1 2 3 4 5; do
  ( printf 'RESERVE 10\nQUIT\n' | java -cp out Client "D$i" ) &
done
wait
```

Expected:

- Exactly **one** client gets `SUCCESS`; the other four get
  `FAILED: Seat 10 is already reserved.`
- The server log shows `ENTER critical section (RESERVE seat 10)` /
  `LEAVE critical section (RESERVE seat 10)` pairs with no overlap, because the
  binary semaphore admits one worker at a time.
- The random delay (50-500 ms) still happens inside the critical section, so
  the race window exists but is now protected.

---

## 7. Extra checks worth showing in the demo

Ownership is exact (no substring matching), so `Client-1` cannot cancel
`Client-10`'s seat:

```bash
printf 'RESERVE 4\nQUIT\n'  | java -cp out Client Client-1
printf 'CANCEL 4\nQUIT\n'   | java -cp out Client Client-10   # FAILED: You do not own this reservation.
printf 'CANCEL 4\nQUIT\n'   | java -cp out Client Client-1    # SUCCESS
```

Bad input keeps the connection alive:

```bash
printf 'STATUS\nRESERVE abc\nSTATUS 99\nFOO\nSTATUS 1\nQUIT\n' | java -cp out Client Client-1
```

Queue-full behaviour (1 worker + many simultaneous requests):

```bash
java -cp out Server sync 1
# in another terminal, fire more requests than the queue capacity (50)
for i in $(seq 1 60); do
  ( printf 'RESERVE %d\nQUIT\n' "$(( (i % 20) + 1 ))" | java -cp out Client "Q$i" \
      | grep -E 'SUCCESS|FAILED' | sed "s/^/Q$i: /" ) &
done
wait
```

Some clients should get `FAILED: Server busy (request queue is full). Please retry.`

Log fields to point at during the demo:

- `[seq=N HH:mm:ss.SSS]` — sequence number + timestamp on every line
- `[Queue] ENQUEUE ... (size=n/50)` — producer side (`ClientHandler`)
- `[Worker-x] DEQUEUE ... (size=n/50)` — consumer side
- `CHECK seat k: ...` / `UPDATE seat k ...` — check phase vs update phase
- `ENTER` / `LEAVE critical section (...)` — critical section boundaries
- `[Worker-x] FAILED/SUCCESS ...` — validation and ownership results

---

## 8. Docker (build and run)

The image is `eclipse-temurin:17-jdk-alpine`, so use `sh` (not `bash`) inside it.
The Dockerfile compiles `Server.java` and `Client.java` and runs the server.

Build:

```bash
cd cinema-reservation-system
docker build -t cinema-reservation .
```

Run Experiment 3 (server in a named container):

```bash
docker run --rm --name cinema-server -p 8080:8080 cinema-reservation java Server sync 3
```

Run in the background so you can exec clients into the same container:

```bash
docker run -d --name cinema-server -p 8080:8080 cinema-reservation java Server sync 3
```

Terminal 1 — client inside the container:

```bash
docker exec -it cinema-server sh
java Client Client-1 localhost
```

Terminal 2 — a second simultaneous client:

```bash
docker exec -it cinema-server sh
java Client Client-2 localhost
```

Each `docker exec` opens a separate shell, so 5 execs = 5 simultaneous clients.

Race demo inside Docker:

```bash
docker rm -f cinema-server
docker run -d --name cinema-server -p 8080:8080 cinema-reservation java Server nosync 3
for i in 1 2 3 4 5; do
  docker exec cinema-server sh -c "printf 'RESERVE 10\nQUIT\n' | java Client C$i localhost" &
done
wait
docker logs cinema-server | grep -E 'CHECK seat 10|UPDATE seat 10'
```

Client from the host (instead of `docker exec`):

```bash
java Client Client-1 127.0.0.1
```

Stop and clean up:

```bash
docker rm -f cinema-server
docker rmi cinema-reservation
```

---

## 9. One-shot recording script (optional)

Copy-paste to produce evidence for the report in one go:

```bash
cd cinema-reservation-system
javac -d out Server.java Client.java

# Experiment 3 (sync 3): capture log + all client results
java -cp out Server sync 3 > exp3_server.log 2>&1 &
SRV=$!
sleep 2

PIDS=""
for i in 1 2 3 4 5; do
  ( printf 'RESERVE 10\nQUIT\n' | java -cp out Client "D$i" | grep -E 'SUCCESS|FAILED' | sed "s/^/D$i: /" ) &
  PIDS="$PIDS $!"
done
for p in $PIDS; do wait "$p"; done   # wait only for the clients

kill $SRV
grep -E 'ENTER|LEAVE|CHECK seat 10|UPDATE seat 10' exp3_server.log
```

Important: when the server runs in the same shell, do **not** use a bare `wait` —
it would also wait for the server process, which never exits. Always collect the
client PIDs as above (or run the server in a separate terminal).

Repeat the same block with `nosync` into `exp2_server.log`, and with `1` worker
into `exp1_server.log`. The per-client result lines plus the filtered server log
are exactly the evidence the report asks for.
