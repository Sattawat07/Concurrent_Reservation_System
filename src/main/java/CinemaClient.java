import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CinemaClient implements AutoCloseable {
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private final String clientId;
    private final String replyName;
    private final PosixMessageQueue replies;
    private final PosixMessageQueue requests;
    private final AtomicLong ids = new AtomicLong(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;

    public CinemaClient(String clientId) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Client ID must be 1-32 letters, digits, _ or -");
        this.clientId = clientId;
        replyName = PosixMessageQueue.uniqueResponseName();
        replies = PosixMessageQueue.createResponseQueue(replyName);
        try {
            requests = PosixMessageQueue.openForWrite(PosixMessageQueue.REQUEST_NAME, MessageCodec.REQUEST_SIZE);
        } catch (RuntimeException e) {
            replies.close();
            PosixMessageQueue.unlink(replyName);
            throw e;
        }
        shutdownHook = new Thread(this::close, "reply-queue-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public String responseQueueName() { return replyName; }

    public ResponseMessage request(String command, int resourceId) throws InterruptedException {
        if (closed.get()) throw new IllegalStateException("Client is closed");
        long id = ids.incrementAndGet();
        RequestMessage message = new RequestMessage(id, clientId, command, resourceId, replyName);
        requests.send(MessageCodec.encode(message));
        ResponseMessage response = MessageCodec.decodeResponse(replies.receive(RESPONSE_TIMEOUT));
        if (response.requestId() != id) throw new IllegalStateException("Response request ID mismatch");
        return response;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        try { requests.close(); } catch (RuntimeException e) { failure = e; }
        try { replies.close(); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        try { PosixMessageQueue.unlink(replyName); }
        catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
        }
        if (failure != null) throw failure;
    }
}
