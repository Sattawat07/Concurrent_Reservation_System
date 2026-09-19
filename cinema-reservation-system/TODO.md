# TODO

> Status update: all Must Fix and Should Fix items below are DONE.
> `RaceTest.java` automates simultaneous clients, while `README.md` and `DEMO.md`
> contain the build, Docker, command, and experiment instructions.

## Must Fix Before Submission

1. Fix multi-line responses from `LIST`.
   - Current client reads only one line after each command.
   - The server sends `LIST` as multiple lines, so leftover lines are incorrectly treated as later command responses.
   - Best fix: send a clear end marker such as `END_RESPONSE`, and make the client read until that marker.
   - Alternative fix: make every server response a single line, but this is less readable for `LIST`.
   - [DONE] All responses (single and multi-line) now end with `END_RESPONSE` via `sendLine()` / `sendEnd()`.
     `Client.readResponse()` loops until the marker, so `LIST` output can no longer leak into the next command's reply.

2. Replace string-based seat ownership with structured data.
   - Current seat state is stored as strings like `RESERVED by Client-1`.
   - `CANCEL` uses `contains(clientId)`, which can accidentally match the wrong owner, such as `Client-1` matching `Client-10`.
   - Best fix: store `status` and `owner` separately, for example `Seat { boolean reserved; String owner; }`.
   - [DONE] `static class Seat { boolean reserved; String owner; }` replaces `String[] seats`.
     `CANCEL` now uses exact comparison `msg.clientId.equals(seats[id].owner)`.

3. Validate command input safely.
   - Current server can throw an exception when a command needs a seat ID but receives invalid text.
   - Bad input should return `FAILED: Invalid command format.` instead of silently disconnecting the client.
   - Validate missing IDs, non-numeric IDs, and out-of-range IDs.
   - [DONE] `ClientHandler` parses the seat id with try/catch and falls back to the sentinel `NO_SEAT_ID = -1`.
     Workers answer `FAILED: Invalid command format. Usage: ...` for missing/non-numeric ids and
     `FAILED: Invalid seat number...` for out-of-range ids. The connection stays open.

4. Improve `QUIT` handling.
   - The client currently sends `QUIT <clientId>`.
   - That works, but the protocol should clearly define whether commands include the client ID or not.
   - Keep the internal server message as `clientId + command + resourceId`, but keep user-facing client commands simple.
   - [DONE] Wire format stays `<COMMAND> <clientId> <seatId?>`; the user still types only `QUIT`.
     `QUIT` is handled in `ClientHandler` (not queued) and replies `Goodbye!` + `END_RESPONSE`. Protocol is documented in the header comment of `Server.java`.

5. Add timestamps or sequence numbers to server logs.
   - The assignment asks for approximate time or sequence number.
   - Add a global `AtomicLong sequenceNumber` or timestamp in every important log line.
   - [DONE] Both: every log line starts with `[seq=N HH:mm:ss.SSS]` (`AtomicLong logSeq` + `DateTimeFormatter`).

6. Add repeatable race-condition testing.
   - Manual terminals are useful for demo, but a small script or test mode should fire 5 clients at the same seat at nearly the same time.
   - This makes Experiment 2 and Experiment 3 easier to prove.
   - [DONE] `RaceTest.java` connects the requested number of clients, waits until every client
     is ready, then releases them together with a `CountDownLatch`.

7. Add a real `README.md`.
   - Include build/run commands, Docker instructions, client commands, sync/no-sync modes, and experiment steps.
   - Explicitly state that Java was approved by the instructor.
   - [DONE] `README.md` documents architecture, build/run commands, Docker, protocol, and all three experiments.

## Should Fix If Time Allows

1. Protect read operations or document read behavior.
   - `LIST` and `STATUS` read shared data while `RESERVE` and `CANCEL` may write it.
   - For clean correctness, acquire the same semaphore for reads too.
   - [DONE] `handleList()` and `handleStatus()` acquire the mutex in sync mode, take a snapshot,
     then release the lock before writing to the socket. In nosync mode reads may still be stale (by design).

2. Make queue activity visible in logs.
   - Log when a request is enqueued by `ClientHandler`.
   - Log when a worker dequeues a request.
   - This helps show the producer-consumer message queue design.
   - [DONE] `[Queue] ENQUEUE ... (size=n/50)` and `[Worker-x] DEQUEUE ... (size=n/50)` are logged.

3. Make command-line modes clearer.
   - Suggested server usage:
     - `java Server sync 3`
     - `java Server nosync 3`
     - `java Server sync 1`
   - Print the selected mode and worker count at startup.
   - [DONE] `java Server [sync|nosync] [workerCount]`; an unknown mode prints usage and exits 1,
     a bad worker count falls back to 3, and a startup banner prints mode / workers / seats / queue capacity.

4. Add queue capacity behavior.
   - Current queue capacity is 50.
   - Document it, and optionally return a busy response if the queue is full instead of blocking a client handler forever.
   - [DONE] Capacity is the constant `QUEUE_CAPACITY = 50`. `ClientHandler` uses `offer()`;
     when the queue is full it replies `FAILED: Server busy (request queue is full). Please retry.`

5. Make Docker easier for multi-terminal demo.
   - Use a container name in README examples.
   - Use `docker exec -it <container-name> sh` or `bash` depending on the base image.
   - If the image is Alpine, use `sh`; if Ubuntu/Debian, use `bash`.
   - [DONE] `README.md` uses a named container and the Alpine-compatible `sh` command.

## Experiment Checklist

### Experiment 1 - Sequential Baseline

- Start server with 1 worker.
- Start at least 5 clients.
- Run a mix of `LIST`, `STATUS`, `RESERVE`, and `CANCEL`.
- Capture server logs and client outputs.
- Expected result: no race condition because only one worker processes requests.

### Experiment 2 - Concurrent Without Synchronization

- Start server with `nosync` and at least 3 workers.
- Start at least 5 clients.
- Send `RESERVE 10` from all clients at nearly the same time.
- Capture server logs showing several workers checking seat 10 as `AVAILABLE`.
- Expected result: race behavior is visible in the logs. Depending on timing, multiple clients may receive success or the final owner may be overwritten.

### Experiment 3 - Concurrent With Synchronization

- Start server with `sync` and at least 3 workers.
- Repeat the same 5-client `RESERVE 10` test.
- Capture critical-section entry and exit logs.
- Expected result: exactly one client succeeds; all others fail because seat 10 is already reserved.

## Verified Test Results (all cases)

Run on host with `javac`. Docker build/run was not executed because the local Docker API denied access.

| # | Case | Mode | Result |
|---|------|------|--------|
| A | Compile `Server.java` + `Client.java` | - | PASS |
| B | `LIST` multi-line + next command (no response leak) | sync 3 | PASS |
| C | Missing seat id -> invalid format, client stays connected | sync 3 | PASS |
| D | Non-numeric seat id -> invalid format | sync 3 | PASS |
| E | Out-of-range seat id (0 / 21 / 999) -> invalid seat number | sync 3 | PASS |
| F | Unknown command -> clear error, no disconnect | sync 3 | PASS |
| G | Owner check `Client-1` vs `Client-10` on `CANCEL` | sync 3 | PASS |
| H | `CANCEL` a seat you do not own -> FAILED | sync 3 | PASS |
| I | `STATUS` / `LIST` show `RESERVED by <owner>` | sync 3 | PASS |
| J | Race, 5 clients same seat -> exactly 1 SUCCESS | sync 3 | PASS |
| K | `QUIT` -> `Goodbye!` + `END_RESPONSE`, socket closed | sync 3 | PASS |
| L | Real `Client.java` end-to-end (LIST/STATUS/RESERVE/unknown/QUIT) | sync 3 | PASS |
| M | Race visible: multiple workers see AVAILABLE, >1 SUCCESS | nosync 3 | PASS |
| N | Experiment 1 baseline: mix of commands with 1 worker | sync 1 | PASS |
| O | 6 concurrent clients reserving distinct seats | sync 3 | PASS |
| P | Queue full (60 requests fired at once) -> 9 BUSY, 20 SUCCESS, no blocking | sync 1 | PASS |
| Q | Bad mode arg -> usage + exit 1; bad worker count -> fallback 3 | - | PASS |
| R | 5 concurrent clients on DIFFERENT seats -> all SUCCESS | sync 1 / sync 3 / nosync 3 | PASS |

Total: 25/25 checks PASS. Race evidence for Experiment 2: 3 of 5 clients got SUCCESS on seat 20
and all 3 workers logged `CHECK seat 20: AVAILABLE` before any UPDATE (last write wins).
The local Docker API denied access, so the image build/run itself was not executed here.

## Suggested Fix Order

1. Fix response protocol for `LIST`.
2. Refactor seat data into structured owner/status fields.
3. Add input validation.
4. Add sequence-numbered logs.
5. Use `RaceTest.java` and preserve output from all three experiments.
6. Finish the 6-10 page report using the captured evidence.

