import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PosixIntegrationTest {
    @Test void queueLifecycleAndRouting() throws Exception {
        String first = PosixMessageQueue.uniqueResponseName();
        String second = PosixMessageQueue.uniqueResponseName();
        assertNotEquals(first, second);
        try (PosixMessageQueue a = PosixMessageQueue.createResponseQueue(first);
             PosixMessageQueue b = PosixMessageQueue.createResponseQueue(second);
             PosixMessageQueue aw = PosixMessageQueue.openForWrite(first, MessageCodec.RESPONSE_SIZE);
             PosixMessageQueue bw = PosixMessageQueue.openForWrite(second, MessageCodec.RESPONSE_SIZE)) {
            bw.send(MessageCodec.encode(new ResponseMessage(2, true, "two")));
            aw.send(MessageCodec.encode(new ResponseMessage(1, true, "one")));
            assertEquals("one", MessageCodec.decodeResponse(a.receive(Duration.ofSeconds(1))).text());
            assertEquals("two", MessageCodec.decodeResponse(b.receive(Duration.ofSeconds(1))).text());
            byte[] maximum = new byte[MessageCodec.RESPONSE_SIZE];
            aw.send(maximum);
            assertArrayEquals(maximum, a.receive(Duration.ofSeconds(1)));
            for (int i = 0; i < PosixMessageQueue.CAPACITY; i++) aw.send(new byte[] {1});
            assertThrows(IllegalStateException.class, () -> aw.send(new byte[] {1}));
        } finally {
            PosixMessageQueue.unlink(first);
            PosixMessageQueue.unlink(second);
        }
        assertFalse(Files.exists(Path.of("/dev/mqueue" + first)));
        assertFalse(Files.exists(Path.of("/dev/mqueue" + second)));
        assertThrows(IllegalStateException.class, () -> PosixMessageQueue.openForWrite(first, MessageCodec.RESPONSE_SIZE));
    }

    @Test void commandsOwnershipInvalidInputsAndRestart() throws Exception {
        try (RunningServer server = new RunningServer("sync", 3)) {
            try (CinemaClient owner = new CinemaClient("Owner"); CinemaClient other = new CinemaClient("Other")) {
                String list = owner.request("LIST", -1).text();
                assertTrue(list.contains("Seat 1: AVAILABLE"));
                assertTrue(list.contains("Seat 20: AVAILABLE"));
                assertEquals(20, list.lines().filter(s -> s.startsWith("Seat ")).count());
                assertTrue(owner.request("STATUS", 1).text().contains("AVAILABLE"));
                assertTrue(owner.request("RESERVE", 1).success());
                assertTrue(owner.request("STATUS", 1).text().contains("RESERVED by Owner"));
                assertFalse(other.request("RESERVE", 1).success());
                assertFalse(other.request("CANCEL", 1).success());
                assertTrue(owner.request("CANCEL", 1).success());
                assertTrue(owner.request("STATUS", 1).text().contains("AVAILABLE"));
                assertFalse(owner.request("STATUS", 21).success());
                assertFalse(owner.request("UNKNOWN", -1).success());
                assertTrue(owner.request("QUIT", -1).success());
            }
        }
        assertFalse(Files.exists(Path.of("/dev/mqueue/cinema_requests")));
        try (RunningServer restarted = new RunningServer("sync", 1);
             CinemaClient client = new CinemaClient("AfterRestart")) {
            assertTrue(client.request("LIST", -1).success());
        }
    }

    @Test void requiredExperimentsAndInterruptedClientCleanup() throws Exception {
        Path syncOneLog;
        try (RunningServer server = new RunningServer("sync", 1)) {
            syncOneLog = server.log;
            assertEquals(1, burst(5, 5));
        }
        assertLogFormat(syncOneLog);
        assertTrue(Files.readString(syncOneLog).contains(" LOCK "));
        boolean raceSeen = false;
        Path noSyncLog;
        try (RunningServer server = new RunningServer("nosync", 3)) {
            noSyncLog = server.log;
            for (int seat = 6; seat <= 10; seat++) {
                if (burst(seat, 5) > 1) { raceSeen = true; break; }
            }
        }
        assertTrue(raceSeen, "No race in five attempts; inspect server logs and retry");
        assertLogFormat(noSyncLog);
        String noSync = Files.readString(noSyncLog);
        assertTrue(noSync.contains(" CHECK ") && noSync.contains(" UPDATE "));
        assertFalse(noSync.contains(" LOCK ") || noSync.contains(" UNLOCK "));
        Path syncThreeLog;
        try (RunningServer server = new RunningServer("sync", 3)) {
            syncThreeLog = server.log;
            for (int seat = 11; seat <= 13; seat++) assertEquals(1, burst(seat, 5));
            AtomicReference<String> name = new AtomicReference<>();
            CountDownLatch sent = new CountDownLatch(1);
            Thread interrupted = new Thread(() -> {
                try (CinemaClient client = new CinemaClient("Interrupted")) {
                    name.set(client.responseQueueName());
                    sent.countDown();
                    client.request("RESERVE", 19);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            interrupted.start();
            assertTrue(sent.await(5, TimeUnit.SECONDS));
            interrupted.interrupt();
            interrupted.join(5000);
            assertFalse(interrupted.isAlive());
            assertFalse(Files.exists(Path.of("/dev/mqueue" + name.get())));
        }
        assertLogFormat(syncThreeLog);
        assertTrue(Files.readString(syncThreeLog).contains(" UNLOCK "));
    }

    private static void assertLogFormat(Path log) throws Exception {
        for (String line : Files.readAllLines(log)) {
            assertTrue(line.matches("\\[\\d{3,} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}] W\\d+ "
                    + "(RECV|LOCK|CHECK|UPDATE|UNLOCK|RESULT|ERROR) .+"), line);
            if (line.contains(" RESULT "))
                assertTrue(line.matches(".* RESULT .*\\b(SUCCESS|FAILED)\\b.*"), line);
            if (!line.contains(" ERROR ")) {
                assertFalse(line.contains("reply="), line);
                assertFalse(line.contains("request="), line);
            }
        }
    }

    private static int burst(int seat, int count) throws Exception {
        CountDownLatch ready = new CountDownLatch(count), go = new CountDownLatch(1), done = new CountDownLatch(count);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "Burst" + seat + "_" + i;
            new Thread(() -> {
                try (CinemaClient client = new CinemaClient(id)) {
                    ready.countDown();
                    go.await();
                    if (client.request("RESERVE", seat).success()) successes.incrementAndGet();
                } catch (Throwable t) { synchronized (errors) { errors.add(t); } }
                finally { done.countDown(); }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), errors.toString());
        return successes.get();
    }

    private static final class RunningServer implements AutoCloseable {
        final Process process;
        final Path log;
        RunningServer(String mode, int workers) throws Exception {
            log = Files.createTempFile("cinema-server-", ".log");
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", cp, "Server", mode, Integer.toString(workers))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) fail("Server exited: " + Files.readString(log));
                try (PosixMessageQueue ignored = PosixMessageQueue.openForWrite(
                        PosixMessageQueue.REQUEST_NAME, MessageCodec.REQUEST_SIZE)) { return; }
                catch (IllegalStateException e) { Thread.sleep(20); }
            }
            fail("Server did not create request queue: " + Files.readString(log));
        }
        @Override public void close() throws Exception {
            process.destroy();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Server did not terminate");
            assertTrue(process.exitValue() == 0 || process.exitValue() == 143, Files.readString(log));
            assertFalse(Files.exists(Path.of("/dev/mqueue/cinema_requests")), Files.readString(log));
            System.out.println("Server experiment log: " + log);
        }
    }
}
