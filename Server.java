import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public class Server {
	private static final int PORT = 8080;
	private static final int NUM_SEATS = 20;
	private static final int DEFAULT_WORKERS = 3;
	private static final int QUEUE_CAPACITY = 50;

	private static final String END_MARKER = "END_RESPONSE";

	private static final int NO_SEAT_ID = -1;

	private static int numWorkers = DEFAULT_WORKERS;

	private static final Seat[] seats = new Seat[NUM_SEATS + 1];

	private static final Semaphore mutex = new Semaphore(1);
	private static boolean useSynchronization = true;

	private static final BlockingQueue<RequestMessage> messageQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

	private static final AtomicLong logSeq = new AtomicLong(0);
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	static class Seat {
		boolean reserved;
		String owner;

		String describe() {
			return reserved ? "RESERVED by " + owner : "AVAILABLE";
		}
	}

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

	private static synchronized void log(String message) {
		System.out
				.println("[seq=" + logSeq.incrementAndGet() + " " + LocalTime.now().format(TIME_FMT) + "] " + message);
	}

	private static void sendLine(PrintWriter out, String line) {
		out.println(line);
	}

	private static void sendEnd(PrintWriter out) {
		out.println(END_MARKER);
	}

	public static void main(String[] args) {
		String mode = args.length > 0 ? args[0].toLowerCase() : "sync";
		if (!mode.equals("sync") && !mode.equals("nosync")) {
			System.out.println("Usage: java Server [sync|nosync] [workerCount]");
			System.exit(1);
		}
		useSynchronization = mode.equals("sync");

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

		log("[Server] Initializing " + numWorkers + " Worker thread(s)...");
		for (int i = 1; i <= numWorkers; i++) {
			new Thread(new WorkerTask(i)).start();
		}

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

					if (cmd.equals("QUIT")) {
						log("[Queue] client " + (tokens.length > 1 ? tokens[1] : "Unknown") + " disconnected (QUIT)");
						sendLine(out, "Goodbye!");
						sendEnd(out);
						break;
					}

					String clientId = tokens.length > 1 ? tokens[1] : "Unknown";

					int resourceId = NO_SEAT_ID;
					if (tokens.length > 2) {
						try {
							resourceId = Integer.parseInt(tokens[2]);
						} catch (NumberFormatException e) {
							resourceId = NO_SEAT_ID;
							log("[Queue] non-numeric seat id '" + tokens[2] + "' from " + clientId);
						}
					}

					RequestMessage msg = new RequestMessage(clientId, cmd, resourceId, out);
					if (messageQueue.offer(msg)) {
						log("[Queue] ENQUEUE " + cmd + " seat=" + (resourceId > 0 ? resourceId : "-")
								+ " from " + clientId + " (size=" + messageQueue.size() + "/" + QUEUE_CAPACITY + ")");
					} else {
						log("[Queue] FULL, rejecting " + cmd + " from " + clientId);
						sendLine(out, "FAILED: Server busy (request queue is full). Please retry.");
						sendEnd(out);
					}
				}
			} catch (Exception e) {
			}
		}
	}

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

		private void lock(String op) throws InterruptedException {
			if (!useSynchronization)
				return;
			mutex.acquire();
			log("[Worker-" + workerId + "] ENTER critical section (" + op + ")");
		}

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
					log("[Worker-" + workerId + "] CHECK seat " + id + ": " + seats[id].describe());

					if (!seats[id].reserved) {
						Thread.sleep(50 + random.nextInt(451));

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

					if (seats[id].reserved && msg.clientId.equals(seats[id].owner)) {
						seats[id].reserved = false;
						seats[id].owner = null;
						log("[Worker-" + workerId + "] UPDATE seat " + id + " cancelled by " + msg.clientId);
						response = "SUCCESS: Reservation for Seat " + id + " cancelled.";
					} else {
						log("[Worker-" + workerId + "] CANCEL failed for seat " + id + " (client " + msg.clientId
								+ ")");
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
