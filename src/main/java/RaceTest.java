import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class RaceTest {
    public static void main(String[] args) throws InterruptedException {
        int seat = parse(args, 0, 10, "seatId");
        int clients = parse(args, 1, 5, "clientCount");
        int attempts = parse(args, 2, 1, "attempts");
        if (seat + attempts - 1 > 20) throw new IllegalArgumentException("Attempts exceed seat 20");
        for (int attempt = 0; attempt < attempts; attempt++) {
            int currentSeat = seat + attempt;
            CountDownLatch ready = new CountDownLatch(clients);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(clients);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            for (int i = 1; i <= clients; i++) {
                String id = "RaceClient-" + i + "-Run" + (attempt + 1);
                new Thread(() -> {
                    boolean signaled = false;
                    try (CinemaClient client = new CinemaClient(id)) {
                        ready.countDown();
                        signaled = true;
                        start.await();
                        ResponseMessage response = client.request("RESERVE", currentSeat);
                        if (response.success()) successes.incrementAndGet(); else failures.incrementAndGet();
                        System.out.println(id + ": " + response.text());
                        client.request("QUIT", -1);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println(id + ": TRANSPORT ERROR: " + e.getMessage());
                    } finally {
                        if (!signaled) ready.countDown();
                        done.countDown();
                    }
                }, id).start();
            }
            ready.await();
            System.out.println("Releasing " + clients + " clients to reserve seat " + currentSeat + "...");
            start.countDown();
            done.await();
            System.out.printf("Attempt %d seat %d: successes=%d failures=%d transportErrors=%d%n",
                    attempt + 1, currentSeat, successes.get(), failures.get(), errors.get());
            if (errors.get() != 0) System.exit(1);
            if (successes.get() > 1) break;
        }
    }

    private static int parse(String[] args, int index, int fallback, String name) {
        if (args.length <= index) return fallback;
        try {
            int value = Integer.parseInt(args[index]);
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name + ": " + args[index]);
        }
    }
}
