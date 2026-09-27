# Concurrent Cinema Reservation System

A Java producer-consumer project that demonstrates message queues, worker threads, shared data, race conditions, and semaphore-based synchronization in a cinema reservation scenario.

> Course assumption: the original handout names C/C++ and System V/POSIX message queues. The instructor approved Java for this project. This implementation maps the message-queue concept to a bounded Java `BlockingQueue<RequestMessage>` inside the server. Confirm that this interpretation also satisfies the instructor's IPC expectation before final submission.

## Architecture

`ClientHandler` threads are producers. They parse socket commands and enqueue `RequestMessage` objects. Three or more `WorkerTask` consumers dequeue requests and access a shared table of 20 seats. A binary `Semaphore` protects the check-and-update critical section in synchronized mode.

The queue coordinates request delivery, but it does not make the reservation logic atomic. Without the semaphore, multiple workers can observe the same seat as available before any worker updates it.

## Build

Requires JDK 17 or newer.

```text
javac Server.java Client.java RaceTest.java
```

## Run

Start one server:

```text
java Server sync 3
```

Start an interactive client in another terminal:

```text
java Client Client-1 localhost
```

Supported commands:

- `LIST`
- `STATUS <seat_id>`
- `RESERVE <seat_id>`
- `CANCEL <seat_id>`
- `QUIT`

Seat IDs range from 1 to 20. A client may cancel only its own reservation.

## Required Experiments

Compile once, then restart the server before each experiment so every run begins with all seats available.

### Experiment 1: sequential baseline

```text
java Server sync 1
```

Use one or more interactive clients. With one worker, requests are processed sequentially.

### Experiment 2: concurrent without synchronization

```text
java Server nosync 3
java RaceTest localhost 10 5
```

Run `RaceTest` from a second terminal. Multiple clients can report success because workers perform check-delay-update without mutual exclusion. Repeat after restarting the server if scheduling does not expose the race on the first run.

### Experiment 3: concurrent with synchronization

```text
java Server sync 3
java RaceTest localhost 10 5
```

Exactly one client should succeed. The other clients should report that seat 10 is already reserved. Server logs show critical-section `ENTER` and `LEAVE` events.

## Docker

Build and run the server:

```text
docker build -t cinema-reservation .
docker run --rm --name cinema-server -p 8080:8080 cinema-reservation
```

Run clients from the host after compiling the Java files:

```text
java Client Client-1 localhost
java RaceTest localhost 10 5
```

Alternatively, open a shell in the running Alpine container:

```text
docker exec -it cinema-server sh
java Client Client-1 localhost
```

## Protocol and Logs

Every server response ends with `END_RESPONSE`; this allows `LIST` to return multiple lines without corrupting the next response. Logs include a sequence number, timestamp, client ID, worker ID, command, queue size, seat ID, and critical-section boundaries.

See `DEMO.md` for a longer demonstration script, `REQUIREMENTS.md` for the requirement mapping, and `TODO.md` for verification status.
