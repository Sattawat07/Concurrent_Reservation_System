import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PosixMessageQueue implements AutoCloseable {
    public static final String REQUEST_NAME = "/cinema_requests";
    public static final int CAPACITY = 10;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = 0100;
    private static final int O_EXCL = 0200;
    private static final int O_NONBLOCK = 04000;
    private static final int EAGAIN = 11;
    private static final int ENOENT = 2;
    private static final int EEXIST = 17;

    private interface MQ extends Library {
        int mq_open(String name, int flags);
        int mq_open(String name, int flags, int mode, Attributes attr);
        int mq_send(int descriptor, byte[] message, NativeLong length, int priority);
        NativeLong mq_receive(int descriptor, byte[] buffer, NativeLong length, int[] priority);
        int mq_close(int descriptor);
        int mq_unlink(String name);
    }

    public static class Attributes extends Structure {
        public NativeLong flags = new NativeLong(0);
        public NativeLong maxMessages = new NativeLong(CAPACITY);
        public NativeLong messageSize = new NativeLong(MessageCodec.RESPONSE_SIZE);
        public NativeLong currentMessages = new NativeLong(0);
        public NativeLong[] reserved = new NativeLong[] {
                new NativeLong(0), new NativeLong(0), new NativeLong(0), new NativeLong(0)};

        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("flags", "maxMessages", "messageSize", "currentMessages", "reserved");
        }
    }

    private interface C extends Library { String strerror(int errno); }
    private static final MQ MQ_LIB = Native.load("rt", MQ.class);
    private static final C C_LIB = Native.load("c", C.class);

    private final String name;
    private final int descriptor;
    private final int messageSize;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PosixMessageQueue(String name, int descriptor, int messageSize) {
        this.name = name;
        this.descriptor = descriptor;
        this.messageSize = messageSize;
    }

    public static String uniqueResponseName() {
        return "/cinema_reply_" + ProcessHandle.current().pid() + "_"
                + UUID.randomUUID().toString().replace("-", "");
    }

    public static void validateName(String name) {
        if (name == null || !name.matches("/[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Queue name must start with / and have 1-100 safe characters");
    }

    public static PosixMessageQueue createRequestQueue() {
        return create(REQUEST_NAME, MessageCodec.REQUEST_SIZE, false);
    }

    public static PosixMessageQueue createResponseQueue(String name) {
        return create(name, MessageCodec.RESPONSE_SIZE, true);
    }

    private static PosixMessageQueue create(String name, int size, boolean nonblocking) {
        validateName(name);
        Attributes attr = new Attributes();
        attr.messageSize = new NativeLong(size);
        attr.write();
        int flags = O_CREAT | O_EXCL | O_RDONLY | (nonblocking ? O_NONBLOCK : 0);
        int descriptor = MQ_LIB.mq_open(name, flags, 0600, attr);
        if (descriptor == -1) {
            int errno = Native.getLastError();
            String hint = errno == EEXIST ? " (already exists; check for an active owner before removing stale state)" : "";
            throw error("mq_open create " + name, errno, hint);
        }
        return new PosixMessageQueue(name, descriptor, size);
    }

    public static PosixMessageQueue openForWrite(String name, int size) {
        validateName(name);
        int descriptor = MQ_LIB.mq_open(name, O_WRONLY | O_NONBLOCK);
        if (descriptor == -1) throw error("mq_open write " + name, Native.getLastError(), "");
        return new PosixMessageQueue(name, descriptor, size);
    }

    public void send(byte[] message) {
        if (closed.get()) throw new IllegalStateException("Queue already closed: " + name);
        if (message == null || message.length == 0 || message.length > messageSize)
            throw new IllegalArgumentException("Message exceeds queue size " + messageSize);
        if (MQ_LIB.mq_send(descriptor, message, new NativeLong(message.length), 0) == -1)
            throw error("mq_send " + name, Native.getLastError(), "");
    }

    public byte[] receive() {
        if (closed.get()) throw new IllegalStateException("Queue already closed: " + name);
        byte[] buffer = new byte[messageSize];
        NativeLong count = MQ_LIB.mq_receive(descriptor, buffer, new NativeLong(buffer.length), null);
        if (count.longValue() == -1) throw error("mq_receive " + name, Native.getLastError(), "");
        return Arrays.copyOf(buffer, count.intValue());
    }

    public byte[] receive(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException("Interrupted while waiting for " + name);
            if (closed.get()) throw new IllegalStateException("Queue already closed: " + name);
            byte[] buffer = new byte[messageSize];
            NativeLong count = MQ_LIB.mq_receive(descriptor, buffer, new NativeLong(buffer.length), null);
            if (count.longValue() >= 0) return Arrays.copyOf(buffer, count.intValue());
            int errno = Native.getLastError();
            if (errno != EAGAIN) throw error("mq_receive " + name, errno, "");
            if (System.nanoTime() >= end) throw new IllegalStateException("Timed out waiting for response on " + name);
            Thread.sleep(10);
        }
    }

    public static void unlink(String name) {
        validateName(name);
        if (MQ_LIB.mq_unlink(name) == -1) {
            int errno = Native.getLastError();
            if (errno != ENOENT) throw error("mq_unlink " + name, errno, "");
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true) && MQ_LIB.mq_close(descriptor) == -1)
            throw error("mq_close " + name, Native.getLastError(), "");
    }

    private static IllegalStateException error(String operation, int errno, String hint) {
        return new IllegalStateException(operation + " failed: errno " + errno + " ("
                + C_LIB.strerror(errno) + ")" + hint);
    }
}
