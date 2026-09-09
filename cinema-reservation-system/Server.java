import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

public class Server {
	private static final int PORT = 8080;
	private static int numWorkers = 3; // ค่าเริ่มต้นเป็น 3 ตัว (ตามข้อกำหนดอย่างน้อย 3 Worker)
	private static final int NUM_SEATS = 20;

	// Shared Resource (ตารางที่นั่ง)
	private static final String[] seats = new String[NUM_SEATS + 1];

	// Concurrency Control Tools (ตาม slide 40, 48, 57)
	private static final Semaphore mutex = new Semaphore(1); // คุม Critical Section
	private static boolean useSynchronization = true; // โหมด Sync/No-Sync

	// Message Queue (Bounded Buffer)
	private static final BlockingQueue<RequestMessage> messageQueue = new ArrayBlockingQueue<>(50);

	// โครงสร้างข้อความใน Queue
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

	public static void main(String[] args) {
		// Argument 1: สลับโหมด sync / nosync
		if (args.length > 0 && args[0].equalsIgnoreCase("nosync")) {
			useSynchronization = false;
			System.out.println("=== SERVER STARTED IN UN-SYNCHRONIZED MODE (FOR RACE CONDITION DEMO) ===");
		} else {
			useSynchronization = true;
			System.out.println("=== SERVER STARTED IN SYNCHRONIZED MODE (PROTECTED BY SEMAPHORE) ===");
		}

		// Argument 2: กำหนดจำนวน Worker (เช่น 1 สำหรับ Exp 1 หรือ 3 สำหรับ Exp 2, 3)
		if (args.length > 1) {
			try {
				numWorkers = Integer.parseInt(args[1]);
			} catch (NumberFormatException e) {
				System.out.println("[Server] Invalid worker count argument, defaulting to " + numWorkers);
			}
		}

		// Initialize ที่นั่งทั้งหมดให้เป็น AVAILABLE
		for (int i = 1; i <= NUM_SEATS; i++) {
			seats[i] = "AVAILABLE";
		}

		// สร้างและเริ่ม Worker Threads ตามจำนวนที่กำหนด
		System.out.println("[Server] Initializing " + numWorkers + " Worker thread(s)...");
		for (int i = 1; i <= numWorkers; i++) {
			new Thread(new WorkerTask(i)).start();
		}

		// Start ServerSocket เพื่อรับ Client
		try (ServerSocket serverSocket = new ServerSocket(PORT)) {
			System.out.println("[Server] Listening on port " + PORT + "...");
			while (true) {
				Socket socket = serverSocket.accept();
				new Thread(new ClientHandler(socket)).start();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// ClientHandler: อ่านคำสั่งจาก Client แล้ว push เข้า Message Queue
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
					String[] tokens = inputLine.trim().split("\\s+");
					if (tokens.length == 0)
						continue;

					String cmd = tokens[0].toUpperCase();
					if (cmd.equals("QUIT")) {
						out.println("Goodbye!");
						break;
					}

					String clientId = tokens.length > 1 ? tokens[1] : "Unknown";
					int resourceId = tokens.length > 2 ? Integer.parseInt(tokens[2]) : -1;

					// Push เข้า Message Queue (Producer Pattern - slide 40)
					messageQueue.put(new RequestMessage(clientId, cmd, resourceId, out));
				}
			} catch (Exception e) {
				// Client disconnected
			}
		}
	}

	// WorkerTask: Worker Thread ดึงคำสั่งจาก Queue ไปประมวลผล (Consumer Pattern - slide 40)
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
					// Consumer ดึงงานจากคิว
					RequestMessage msg = messageQueue.take();
					processRequest(msg);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		private void processRequest(RequestMessage msg) {
			System.out.println("[Worker-" + workerId + "] received " + msg.command + " " +
					(msg.resourceId > 0 ? msg.resourceId : "") + " from " + msg.clientId);

			switch (msg.command) {
				case "LIST":
					StringBuilder sb = new StringBuilder("=== Movie Theater Seats ===\n");
					for (int i = 1; i <= NUM_SEATS; i++) {
						sb.append("Seat ").append(i).append(": ").append(seats[i]).append("\n");
					}
					msg.outputWriter.println(sb.toString());
					break;

				case "STATUS":
					if (msg.resourceId >= 1 && msg.resourceId <= NUM_SEATS) {
						msg.outputWriter.println("Seat " + msg.resourceId + " status: " + seats[msg.resourceId]);
					} else {
						msg.outputWriter.println("Invalid Seat ID!");
					}
					break;

				case "RESERVE":
					handleReserve(msg);
					break;

				case "CANCEL":
					handleCancel(msg);
					break;

				default:
					msg.outputWriter.println("Unknown command.");
			}
		}

		private void handleReserve(RequestMessage msg) {
			int id = msg.resourceId;
			if (id < 1 || id > NUM_SEATS) {
				msg.outputWriter.println("FAILED: Invalid seat number.");
				return;
			}

			try {
				if (useSynchronization) {
					// เข้า Critical Section ด้วย Semaphore acquire (slide 48)
					mutex.acquire();
					System.out.println("[Worker-" + workerId + "] entering critical section");
				}

				// Check phase
				System.out.println("[Worker-" + workerId + "] check Resource " + id + ": " + seats[id]);

				if (seats[id].equals("AVAILABLE")) {
					// Random delay 50-500 ms เพื่อขยาย Race Window (ตามโจทย์กำหนด)
					Thread.sleep(50 + random.nextInt(450));

					// Update phase
					seats[id] = "RESERVED by " + msg.clientId;
					System.out.println("[Worker-" + workerId + "] Resource " + id + " reserved by " + msg.clientId);
					msg.outputWriter.println("SUCCESS: Seat " + id + " reserved successfully.");
				} else {
					System.out.println("[Worker-" + workerId + "] Resource " + id + " already reserved");
					msg.outputWriter.println("FAILED: Seat " + id + " is already reserved.");
				}

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				if (useSynchronization) {
					// ออกจาก Critical Section ด้วย Semaphore release (slide 40, 48)
					System.out.println("[Worker-" + workerId + "] leaving critical section");
					mutex.release();
				}
			}
		}

		private void handleCancel(RequestMessage msg) {
			int id = msg.resourceId;
			if (id < 1 || id > NUM_SEATS) {
				msg.outputWriter.println("FAILED: Invalid seat number.");
				return;
			}

			try {
				if (useSynchronization) {
					mutex.acquire();
					System.out.println("[Worker-" + workerId + "] entering critical section");
				}

				System.out.println("[Worker-" + workerId + "] check Resource " + id + ": " + seats[id]);

				if (seats[id].contains(msg.clientId)) {
					seats[id] = "AVAILABLE";
					System.out.println("[Worker-" + workerId + "] Resource " + id + " cancelled by " + msg.clientId);
					msg.outputWriter.println("SUCCESS: Reservation for Seat " + id + " cancelled.");
				} else {
					System.out.println("[Worker-" + workerId + "] CANCEL failed for Resource " + id);
					msg.outputWriter.println("FAILED: You do not own this reservation.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				if (useSynchronization) {
					System.out.println("[Worker-" + workerId + "] leaving critical section");
					mutex.release();
				}
			}
		}
	}
}