import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class Server {
    private static final int NUM_SEATS = 20;
    private static final int DEFAULT_WORKERS = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final Seat[] seats = new Seat[NUM_SEATS + 1];
    private final Semaphore mutex = new Semaphore(1);
    private final AtomicLong sequence = new AtomicLong();
    private final boolean sync;
    private final int workers;

    private static final class Seat {
        boolean reserved;
        String owner;
        String describe() { return reserved ? "RESERVED by " + owner : "AVAILABLE"; }
    }

    private Server(boolean sync, int workers) {
        this.sync = sync;
        this.workers = workers;
        for (int i = 1; i <= NUM_SEATS; i++) seats[i] = new Seat();
    }

    private synchronized void log(int workerId, String event, String details) {
        System.out.printf(Locale.ROOT, "[%03d %s] W%d %s %s%n",
                sequence.incrementAndGet(), LocalTime.now().format(TIME), workerId, event, details);
    }

    private static String details(RequestMessage request) {
        String text = request.clientId() + " " + request.command();
        return request.command().equals("LIST") || request.command().equals("QUIT")
                ? text : text + " seat=" + request.resourceId();
    }

    private static String seatState(Seat seat) {
        return seat.reserved ? "owner=" + seat.owner : "AVAILABLE";
    }

    private static String resultReason(RequestMessage request, ResponseMessage response) {
        if (!response.success()) {
            if (response.text().contains("already reserved")) return "already reserved";
            if (response.text().contains("do not own")) return "not owner";
            if (response.text().contains("Unknown command")) return "unknown command";
            if (response.text().contains("interrupted")) return "interrupted";
            return "invalid seat/format";
        }
        return switch (request.command()) {
            case "LIST" -> "20 seats";
            case "STATUS" -> response.text().substring(response.text().indexOf("status: ") + 8);
            case "RESERVE" -> "reserved";
            case "CANCEL" -> "cancelled";
            case "QUIT" -> "bye";
            default -> "done";
        };
    }

    public static void main(String[] args) {
        if (args.length > 2 || (args.length > 0 && !args[0].equals("sync") && !args[0].equals("nosync"))) {
            System.err.println("Usage: java Server [sync|nosync] [workerCount]");
            System.exit(2);
        }
        int workerCount = DEFAULT_WORKERS;
        if (args.length == 2) {
            try { workerCount = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { workerCount = 0; }
            if (workerCount < 1 || workerCount > 100) {
                System.err.println("Worker count must be between 1 and 100");
                System.exit(2);
            }
        }
        Server server = new Server(args.length == 0 || args[0].equals("sync"), workerCount);
        try {
            server.run();
        } catch (RuntimeException e) {
            System.err.println("Server error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run() {
        PosixMessageQueue requests = PosixMessageQueue.createRequestQueue();
        Thread cleanup = new Thread(() -> {
            try { requests.close(); }
            finally { PosixMessageQueue.unlink(PosixMessageQueue.REQUEST_NAME); }
        }, "request-queue-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        log(0, "RESULT", "SUCCESS server ready mode=" + (sync ? "sync" : "nosync") + " workers=" + workers
                + " seats=" + NUM_SEATS + " queue=" + PosixMessageQueue.REQUEST_NAME
                + " capacity=" + PosixMessageQueue.CAPACITY);
        for (int i = 1; i <= workers; i++) {
            final int id = i;
            Thread thread = new Thread(() -> workerLoop(id, requests), "Worker-" + id);
            thread.start();
        }
    }

    private void workerLoop(int workerId, PosixMessageQueue requests) {
        while (true) {
            try {
                byte[] raw = requests.receive();
                RequestMessage request;
                try { request = MessageCodec.decodeRequest(raw); }
                catch (IllegalArgumentException e) {
                    log(workerId, "ERROR", "malformed message bytes=" + raw.length + " cause=" + e.getMessage());
                    continue;
                }
                log(workerId, "RECV", details(request));
                ResponseMessage response = process(workerId, request);
                try (PosixMessageQueue reply = PosixMessageQueue.openForWrite(
                        request.responseQueue(), MessageCodec.RESPONSE_SIZE)) {
                    reply.send(MessageCodec.encode(response));
                    log(workerId, "RESULT", details(request) + " "
                            + (response.success() ? "SUCCESS" : "FAILED") + " " + resultReason(request, response));
                } catch (RuntimeException e) {
                    log(workerId, "ERROR", "response delivery " + details(request) + " reply="
                            + request.responseQueue() + " request=" + request.requestId() + " cause=" + e.getMessage());
                }
            } catch (RuntimeException e) {
                log(workerId, "ERROR", "queue receive failed: " + e.getMessage());
                return;
            }
        }
    }

    private ResponseMessage process(int workerId, RequestMessage request) {
        String command = request.command();
        int id = request.resourceId();
        if (switch (command) {
            case "LIST", "QUIT" -> id != -1;
            case "STATUS", "RESERVE", "CANCEL" -> id < 1 || id > NUM_SEATS;
            default -> false;
        }) return new ResponseMessage(request.requestId(), false,
                "FAILED: Invalid seat ID or command format. Seats are 1-20.");
        if (!switch (command) {
            case "LIST", "STATUS", "RESERVE", "CANCEL", "QUIT" -> true;
            default -> false;
        }) return new ResponseMessage(request.requestId(), false, "FAILED: Unknown command.");
        boolean acquired = false;
        try {
            if (sync && !command.equals("QUIT")) {
                mutex.acquire();
                acquired = true;
                if (command.equals("RESERVE") || command.equals("CANCEL"))
                    log(workerId, "LOCK", details(request));
            }
            String text = switch (command) {
                case "LIST" -> list();
                case "STATUS" -> "Seat " + id + " status: " + seats[id].describe();
                case "RESERVE" -> reserve(workerId, request);
                case "CANCEL" -> cancel(workerId, request);
                case "QUIT" -> "Goodbye!";
                default -> throw new IllegalStateException("Unreachable command");
            };
            return new ResponseMessage(request.requestId(), !text.startsWith("FAILED:"), text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ResponseMessage(request.requestId(), false, "FAILED: Worker interrupted.");
        } finally {
            if (acquired) {
                if (command.equals("RESERVE") || command.equals("CANCEL"))
                    log(workerId, "UNLOCK", details(request));
                mutex.release();
            }
        }
    }

    private String list() {
        StringBuilder text = new StringBuilder("=== Movie Theater Seats (20) ===");
        for (int i = 1; i <= NUM_SEATS; i++) text.append("\nSeat ").append(i).append(": ").append(seats[i].describe());
        return text.toString();
    }

    private String reserve(int workerId, RequestMessage request) throws InterruptedException {
        int id = request.resourceId();
        Seat seat = seats[id];
        log(workerId, "CHECK", details(request) + " " + seatState(seat));
        if (seat.reserved) return "FAILED: Seat " + id + " is already reserved.";
        Thread.sleep(ThreadLocalRandom.current().nextInt(50, 501));
        seat.owner = request.clientId();
        seat.reserved = true;
        log(workerId, "UPDATE", details(request) + " owner=" + seat.owner);
        return "SUCCESS: Seat " + id + " reserved successfully.";
    }

    private String cancel(int workerId, RequestMessage request) {
        int id = request.resourceId();
        Seat seat = seats[id];
        log(workerId, "CHECK", details(request) + " " + seatState(seat));
        if (!seat.reserved || !request.clientId().equals(seat.owner))
            return "FAILED: You do not own this reservation.";
        seat.reserved = false;
        seat.owner = null;
        log(workerId, "UPDATE", details(request) + " AVAILABLE");
        return "SUCCESS: Reservation for Seat " + id + " cancelled.";
    }
}
