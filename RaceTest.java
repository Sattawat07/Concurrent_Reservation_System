import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/** Launches concurrent reservation clients for the race-condition experiments. */
public class RaceTest {
    private static final int PORT = 8080;
    private static final String END_MARKER = "END_RESPONSE";

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "localhost";
        int seatId = parsePositiveInt(args, 1, 10, "seatId");
        int clientCount = parsePositiveInt(args, 2, 5, "clientCount");

        CountDownLatch ready = new CountDownLatch(clientCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clientCount);

        for (int i = 1; i <= clientCount; i++) {
            final String clientId = "RaceClient-" + i;
            Thread thread = new Thread(() -> {
                boolean readySignaled = false;
                try (Socket socket = new Socket(host, PORT);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    ready.countDown();
                    readySignaled = true;
                    start.await();
                    out.println("RESERVE " + clientId + " " + seatId);
                    System.out.println(clientId + ": " + readResponse(in));
                    out.println("QUIT " + clientId);
                    readResponse(in);
                } catch (Exception e) {
                    System.err.println(clientId + ": ERROR: " + e.getMessage());
                } finally {
                    if (!readySignaled) {
                        ready.countDown();
                    }
                    done.countDown();
                }
            }, clientId);
            thread.start();
        }

        ready.await();
        System.out.println("Releasing " + clientCount + " clients to reserve seat " + seatId + "...");
        start.countDown();
        done.await();
    }

    private static int parsePositiveInt(String[] args, int index, int defaultValue, String name) {
        if (args.length <= index) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(args[index]);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.println("Invalid " + name + ": " + args[index]);
            System.exit(1);
            return defaultValue;
        }
    }

    private static String readResponse(BufferedReader in) throws Exception {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !line.equals(END_MARKER)) {
            if (response.length() > 0) {
                response.append(System.lineSeparator());
            }
            response.append(line);
        }
        return response.toString();
    }
}
