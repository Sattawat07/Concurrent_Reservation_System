import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent Reservation System - Movie Theater (Server side).
 *
 * Architecture (producer-consumer message queue):
 *   ClientHandler threads = producers, they translate client commands into
 *   RequestMessage objects and push them into the bounded BlockingQueue.
 *   WorkerTask threads = consumers, they take messages and touch the shared
 *   seat table.
 *
 * Protocol between Client and Server (line based, plain text):
 *   Client -> Server : "<COMMAND> <clientId> <seatId?>"
 *                      e.g. "LIST Client-1", "RESERVE Client-1 10", "QUIT Client-1"
 *   Server -> Client : one or more response lines, always terminated by the
 *                      marker line "END_RESPONSE". The client reads until it
 *                      sees that marker, which is what makes the multi-line
 *                      LIST response safe to send.
 *   The user never types the client id; Client.java adds it automatically so
 *   the user facing commands stay simple (LIST, STATUS <id>, RESERVE <id>,
 *   CANCEL <id>, QUIT).
 *
 * Usage:
 *   java Server [sync|nosync] [workerCount]
 *   java Server sync 1     -> Experiment 1 (sequential baseline)
 *   java Server nosync 3   -> Experiment 2 (race condition demo)
 *   java Server sync 3     -> Experiment 3 (semaphore protected)
 */
public class Server {
	private static final int PORT = 8080;
	private static final int NUM_SEATS = 20;
	private static final int DEFAULT_WORKERS = 3;
	private static final int QUEUE_CAPACITY = 50;

	// End-of-response marker for the multi-line protocol.
	private static final String END_MARKER = "END_RESPONSE";

	// Sentinel used when a command needs a seat id but the client did not send
	// a usable numeric value (missing or non-numeric). The worker turns this
	// into a clear "Invalid command format." response instead of crashing.
	private static final int NO_SEAT_ID = -1;

	private static int numWorkers = DEFAULT_WORKERS;

	// Shared Resource: the reservation table. Index 1..NUM_SEATS is used.
	// Status and owner are kept separately so ownership checks compare the
	// owner exactly (no substring / contains matching).
	private static final Seat[] seats = new Seat[NUM_SEATS + 1];

	// Concurrency Control Tools: binary semaphore = mutex around the critical
	// section (check-and-update in RESERVE, check-and-clear in CANCEL, and the
	// read snapshots of LIST / STATUS when synchronization is enabled).
	private static final Semaphore mutex = new Semaphore(1);
	private static boolean useSynchronization = true; // sync / nosync mode

	// Message Queue (Bounded Buffer) between producers and consumers.
	private static final BlockingQueue<RequestMessage> messageQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

	// Global log sequence number + timestamp, as required for the report.
	private static final AtomicLong logSeq = new AtomicLong(0);
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	/** One seat: status and owner are stored separately. */
	static class Seat {
		boolean reserved;
		String owner;

		String describe() {
			return reserved ? "RESERVED by " + owner : "AVAILABLE";
		}
	}

	/** Message structure inside the queue: clientId + command + resourceId. */
	static class RequestMessage {
		String clientId;
		String command;
		int resourceId;
		PrintWriter outputWriter;

		public RequestMessage(String clientId, String command, int resourceId, PrintWriter outputWriter) {
			this.clientId = clientId;
			this.command = command;
			this.resourceId = resourceId;
			this.outputWriter = outputWriter;
		}
	}

	/** Every important log line carries a sequence number and a timestamp. */
	private static synchronized void log(String message) {
		System.out.println("[seq=" + logSeq.incrementAndGet() + " " + LocalTime.now().format(TIME_FMT) + "] " + message);
	}

	private static void sendLine(PrintWriter out, String line) {
		out.println(line);
	}

	private static void sendEnd(PrintWriter out) {
		out.println(END_MARKER);
	}

	public static void main(String[] args) {
		// Argument 1 (optional): "sync" or "nosync". Defaults to sync.
		String mode = args.length > 0 ? args[0].toLowerCase() : "sync";
		if (!mode.equals("sync") && !mode.equals("nosync")) {
			System.out.println("Usage: java Server [sync|nosync] [workerCount]");
			System.exit(1);
		}
		useSynchronization = mode.equals("sync");

		// Argument 2 (optional): number of worker (consumer) threads.
		if (args.length > 1) {
			try {
				numWorkers = Integer.parseInt(args[1]);
				if (numWorkers < 1) {
					System.out.println("[Server] Worker count must be >= 1, using " + DEFAULT_WORKERS);
					numWorkers = DEFAULT_WORKERS;
				}
			} catch (NumberFormatException e) {
				System.out.println("[Server] Invalid worker count '" + args[1] + "', using " + DEFAULT_WORKERS);
				numWorkers = DEFAULT_WORKERS;
			}
		}

		// Initialize every seat as AVAILABLE.
		for (int i = 1; i <= NUM_SEATS; i++) {
			seats[i] = new Seat();
		}

		System.out.println("=====================================================");
		System.out.println("[Server] Mode       : " + (useSynchronization
				? "SYNCHRONIZED (semaphore/mutex ENABLED)"
				: "UN-SYNCHRONIZED (race condition demo)"));
		System.out.println("[Server] Workers    : " + numWorkers);
		System.out.println("[Server] Seats      : " + NUM_SEATS);
		System.out.println("[Server] Queue cap. : " + QUEUE_CAPACITY);
		System.out.println("=====================================================");

		// Create and start worker (consumer) threads.
		log("[Server] Initializing " + numWorkers + " Worker thread(s)...");
		for (int i = 1; i <= numWorkers; i++) {
			new Thread(new WorkerTask(i)).start();
		}

		// Start ServerSocket to accept clients.
		try (ServerSocket serverSocket = new ServerSocket(PORT)) {
			log("[Server] Listening on port " + PORT + "...");
			while (true) {
				Socket socket = serverSocket.accept();
				new Thread(new ClientHandler(socket)).start();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// ClientHandler: reads commands from a client and pushes them into the queue.
	static class ClientHandler implements Runnable {
		private final Socket socket;

		public ClientHandler(Socket socket) {
			this.socket = socket;
		}

		@Override
		public void run() {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
					PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					if (inputLine.trim().isEmpty())
						continue;

					String[] tokens = inputLine.trim().split("\\s+");
					String cmd = tokens[0].toUpperCase();

					// QUIT is a connection control command, not a queue request.
					// The client still sends "<QUIT> <clientId>" on the wire so
					// the wire format stays uniform, but the user only types QUIT.
					if (cmd.equals("QUIT")) {
						log("[Queue] client " + (tokens.length > 1 ? tokens[1] : "Unknown") + " disconnected (QUIT)");
						sendLine(out, "Goodbye!");
						sendEnd(out);
						break;
					}

					String clientId = tokens.length > 1 ? tokens[1] : "Unknown";

					// Safe parsing: a missing or non-numeric seat id becomes
					// NO_SEAT_ID and is rejected later with a clear error, so a
					// bad command never disconnects the client.
					int resourceId = NO_SEAT_ID;
					if (tokens.length > 2) {
						try {
							resourceId = Integer.parseInt(tokens[2]);
						} catch (NumberFormatException e) {
							resourceId = NO_SEAT_ID;
							log("[Queue] non-numeric seat id '" + tokens[2] + "' from " + clientId);
						}
					}

					// Push into the Message Queue (Producer pattern).
					RequestMessage msg = new RequestMessage(clientId, cmd, resourceId, out);
					if (messageQueue.offer(msg)) {
						log("[Queue] ENQUEUE " + cmd + " seat=" + (resourceId > 0 ? resourceId : "-")
								+ " from " + clientId + " (size=" + messageQueue.size() + "/" + QUEUE_CAPACITY + ")");
					} else {
						// Queue is full: answer immediately instead of blocking
						// this client handler forever.
						log("[Queue] FULL, rejecting " + cmd + " from " + clientId);
						sendLine(out, "FAILED: Server busy (request queue is full). Please retry.");
						sendEnd(out);
					}
				}
			} catch (Exception e) {
				// Client disconnected or I/O error.
			}
		}
	}

	// WorkerTask: worker thread takes a request from the queue and processes it.
	static class WorkerTask implements Runnable {
		private final int workerId;
		private final Random random = new Random();

		public WorkerTask(int workerId) {
			this.workerId = workerId;
		}

		@Override
		public void run() {
			while (true) {
				try {
					// Consumer takes a job from the queue.
					RequestMessage msg = messageQueue.take();
					log("[Worker-" + workerId + "] DEQUEUE " + msg.command + " seat="
							+ (msg.resourceId > 0 ? msg.resourceId : "-") + " from " + msg.clientId
							+ " (size=" + messageQueue.size() + "/" + QUEUE_CAPACITY + ")");
					processRequest(msg);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		private void processRequest(RequestMessage msg) {
			switch (msg.command) {
				case "LIST":
					handleList(msg);
					break;

				case "STATUS":
					handleStatus(msg);
					break;

				case "RESERVE":
					handleReserve(msg);
					break;

				case "CANCEL":
					handleCancel(msg);
					break;

				default:
					fail(msg, "FAILED: Unknown command.");
			}
		}

		/** Acquire the mutex (no-op when synchronization is disabled). */
		private void lock(String op) throws InterruptedException {
			if (!useSynchronization)
				return;
			mutex.acquire();
			log("[Worker-" + workerId + "] ENTER critical section (" + op + ")");
		}

		/** Release the mutex (no-op when synchronization is disabled). */
		private void unlock(String op) {
			if (!useSynchronization)
				return;
			log("[Worker-" + workerId + "] LEAVE critical section (" + op + ")");
			mutex.release();
		}

		private void fail(RequestMessage msg, String reason) {
			log("[Worker-" + workerId + "] " + reason + " (client " + msg.clientId + ")");
			sendLine(msg.outputWriter, reason);
			sendEnd(msg.outputWriter);
		}

		private void handleList(RequestMessage msg) {
			String op = "LIST";
			String response;
			try {
				lock(op);
				try {
					StringBuilder sb = new StringBuilder();
					sb.append("=== Movie Theater Seats (").append(NUM_SEATS).append(") ===");
					for (int i = 1; i <= NUM_SEATS; i++) {
						sb.append("\nSeat ").append(i).append(": ").append(seats[i].describe());
					}
					response = sb.toString();
				} finally {
					unlock(op);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				response = "FAILED: Request interrupted.";
			}
			sendLine(msg.outputWriter, response);
			sendEnd(msg.outputWriter);
		}

		private void handleStatus(RequestMessage msg) {
			int id = msg.resourceId;
			if (id == NO_SEAT_ID) {
				fail(msg, "FAILED: Invalid command format. Usage: STATUS <seat_id>");
				return;
			}
			if (id < 1 || id > NUM_SEATS) {
				fail(msg, "FAILED: Invalid seat number. Seat must be between 1 and " + NUM_SEATS + ".");
				return;
			}

			String op = "STATUS seat " + id;
			String response;
			try {
				lock(op);
				try {
					response = "Seat " + id + " status: " + seats[id].describe();
				} finally {
					unlock(op);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				response = "FAILED: Request interrupted.";
			}
			sendLine(msg.outputWriter, response);
			sendEnd(msg.outputWriter);
		}

		private void handleReserve(RequestMessage msg) {
			int id = msg.resourceId;
			if (id == NO_SEAT_ID) {
				fail(msg, "FAILED: Invalid command format. Usage: RESERVE <seat_id>");
				return;
			}
			if (id < 1 || id > NUM_SEATS) {
				fail(msg, "FAILED: Invalid seat number. Seat must be between 1 and " + NUM_SEATS + ".");
				return;
			}

			String op = "RESERVE seat " + id;
			String response;
			try {
				lock(op);
				try {
					// Check phase.
					log("[Worker-" + workerId + "] CHECK seat " + id + ": " + seats[id].describe());

					if (!seats[id].reserved) {
						// Random delay 50-500 ms to widen the race window.
						Thread.sleep(50 + random.nextInt(451));

						// Update phase.
						seats[id].reserved = true;
						seats[id].owner = msg.clientId;
						log("[Worker-" + workerId + "] UPDATE seat " + id + " reserved by " + msg.clientId);
						response = "SUCCESS: Seat " + id + " reserved successfully.";
					} else {
						log("[Worker-" + workerId + "] seat " + id + " already reserved by " + seats[id].owner);
						response = "FAILED: Seat " + id + " is already reserved.";
					}
				} finally {
					unlock(op);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				response = "FAILED: Request interrupted.";
			}
			sendLine(msg.outputWriter, response);
			sendEnd(msg.outputWriter);
		}

		private void handleCancel(RequestMessage msg) {
			int id = msg.resourceId;
			if (id == NO_SEAT_ID) {
				fail(msg, "FAILED: Invalid command format. Usage: CANCEL <seat_id>");
				return;
			}
			if (id < 1 || id > NUM_SEATS) {
				fail(msg, "FAILED: Invalid seat number. Seat must be between 1 and " + NUM_SEATS + ".");
				return;
			}

			String op = "CANCEL seat " + id;
			String response;
			try {
				lock(op);
				try {
					log("[Worker-" + workerId + "] CHECK seat " + id + ": " + seats[id].describe());

					// Exact owner comparison (never a substring match).
					if (seats[id].reserved && msg.clientId.equals(seats[id].owner)) {
						seats[id].reserved = false;
						seats[id].owner = null;
						log("[Worker-" + workerId + "] UPDATE seat " + id + " cancelled by " + msg.clientId);
						response = "SUCCESS: Reservation for Seat " + id + " cancelled.";
					} else {
						log("[Worker-" + workerId + "] CANCEL failed for seat " + id + " (client " + msg.clientId + ")");
						response = "FAILED: You do not own this reservation.";
					}
				} finally {
					unlock(op);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				response = "FAILED: Request interrupted.";
			}
			sendLine(msg.outputWriter, response);
			sendEnd(msg.outputWriter);
		}
	}
}
