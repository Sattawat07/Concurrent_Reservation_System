# REQUIREMENTS

## Scope

Project: Concurrent Reservation System for a movie theater.

The original assignment document says C/C++ and System V or POSIX Message Queue. For this project, the student confirmed with the instructor that Java is allowed. This document therefore treats Java as accepted, but the final README/report must explicitly state this approval and explain how the Java implementation maps to the assignment concepts.

## Required Scenario

- Use a movie theater seat reservation scenario.
- Provide at least 20 reservable resources.
- Seat IDs are `1` to `20`.
- Multiple clients must be able to send requests at around the same time.
- The shared reservation table must be owned by the server and accessed by worker threads.

## Required Commands

The client/server protocol must support:

- `LIST`
- `STATUS <seat_id>`
- `RESERVE <seat_id>`
- `CANCEL <seat_id>`
- `QUIT`

Expected behavior:

- `LIST` returns the status of all seats.
- `STATUS <seat_id>` returns one seat status.
- `RESERVE <seat_id>` succeeds only if the seat is currently available.
- `CANCEL <seat_id>` succeeds only if the requesting client owns the reservation.
- Invalid commands or invalid seat IDs return clear error messages without disconnecting the client.

## Message Queue Requirement

The current design uses a Java `BlockingQueue<RequestMessage>` as a bounded producer-consumer queue inside the server:

- Producers: `ClientHandler` threads receive client commands and enqueue request messages.
- Consumers: worker threads take messages from the queue and process them.

Because the original assignment mentions System V/POSIX Message Queue, the report and README should clearly explain the instructor-approved Java interpretation:

- Socket connections are used for client-server transport.
- The server converts client commands into request messages.
- The Java blocking queue is the request message queue between producers and workers.
- The queue alone does not protect shared seat data because several workers can still process different queue messages concurrently.

## Concurrency Requirements

- Server must support at least 3 worker threads.
- Server must support a sequential baseline mode with 1 worker.
- Server must support a no-synchronization mode for race-condition demonstration.
- Server must support a synchronized mode using a mutex or semaphore.
- Random delay of about 50-500 ms must occur between check and update in the `RESERVE` operation to enlarge the race window.

## Shared Data and Critical Section

Shared data:

- Reservation table for all seats.
- Seat owner information.

Critical section:

- The check-and-update sequence in `RESERVE`.
- The owner-check-and-clear sequence in `CANCEL`.
- Prefer protecting `LIST` and `STATUS` reads too, or document why a read snapshot can be temporarily stale.

Synchronization:

- Use a binary semaphore or mutex around the critical section.
- In synchronized mode, only one worker may check/update the same reservation table at a time.

## Required Experiments

### Experiment 1 - Sequential Baseline

- Run server with 1 worker.
- Use multiple clients.
- Show that commands are processed correctly without race condition.

### Experiment 2 - Concurrent Without Synchronization

- Run server with at least 3 workers.
- Disable semaphore/mutex.
- Use at least 5 clients trying to reserve the same seat, for example `RESERVE 10`.
- Show evidence of race behavior, such as multiple workers observing the same seat as `AVAILABLE` before updates happen.

### Experiment 3 - Concurrent With Synchronization

- Run server with the same worker count and same random delay.
- Enable semaphore/mutex.
- Repeat the same 5-client reservation test.
- Show that only one client succeeds and the final owner is exactly one client.

## Logging Requirements

Server logs should include:

- Client ID.
- Worker ID.
- Command.
- Seat/resource ID.
- Approximate timestamp or sequence number.
- Queue enqueue/dequeue activity.
- Critical-section entry and exit.
- Check phase and update phase.

## Docker Requirements

The project must compile and run in Docker.

Required files:

- Server source file.
- Client source file.
- `Dockerfile`.
- `README.md`.

README must include:

- How to build the Docker image.
- How to run the container.
- How to start the server.
- How to open multiple clients.
- Message queue design.
- Supported commands.
- How to run the race-condition experiment.
- How to enable and disable synchronization.

## Report Requirements

The report should be about 6-10 pages and include:

- Project title, member names, and IDs.
- Short project description.
- System architecture.
- Message queue design.
- Message structure.
- Server concurrency model.
- Shared resource and critical section.
- Cause of race condition.
- Role of random delay.
- Synchronization mechanism.
- Results of all 3 experiments.
- Limitations and future improvements.

