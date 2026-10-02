import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

public final class MessageCodec {
    public static final int REQUEST_SIZE = 512;
    public static final int RESPONSE_SIZE = 4096;
    private static final int MAGIC = 0x434D5131; // CMQ1
    private static final int REQUEST = 1;
    private static final int RESPONSE = 2;

    private MessageCodec() {}

    public static byte[] encode(RequestMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(REQUEST);
            out.writeLong(message.requestId());
            writeString(out, message.clientId());
            writeString(out, message.command());
            out.writeInt(message.resourceId());
            writeString(out, message.responseQueue());
            out.flush();
            return bounded(bytes.toByteArray(), REQUEST_SIZE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RequestMessage decodeRequest(byte[] bytes) {
        bounded(bytes, REQUEST_SIZE);
        try {
            DataInputStream in = input(bytes, REQUEST);
            RequestMessage value = new RequestMessage(in.readLong(), readString(in), readString(in),
                    in.readInt(), readString(in));
            complete(in);
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid or truncated request", e);
        }
    }

    public static byte[] encode(ResponseMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(RESPONSE);
            out.writeLong(message.requestId());
            out.writeBoolean(message.success());
            writeString(out, message.text());
            out.flush();
            return bounded(bytes.toByteArray(), RESPONSE_SIZE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ResponseMessage decodeResponse(byte[] bytes) {
        bounded(bytes, RESPONSE_SIZE);
        try {
            DataInputStream in = input(bytes, RESPONSE);
            long id = in.readLong();
            int status = in.readUnsignedByte();
            if (status > 1) throw new IllegalArgumentException("Invalid response status");
            ResponseMessage value = new ResponseMessage(id, status == 1, readString(in));
            complete(in);
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid or truncated response", e);
        }
    }

    private static DataInputStream input(byte[] bytes, int type) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC || in.readUnsignedByte() != type)
            throw new IllegalArgumentException("Invalid message header");
        return in;
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 2048) throw new IllegalArgumentException("String too long");
        out.writeShort(utf8.length);
        out.write(utf8);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > 2048 || length > in.available()) throw new IllegalArgumentException("Invalid string length");
        byte[] utf8 = new byte[length];
        in.readFully(utf8);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8", e);
        }
    }

    private static void complete(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IllegalArgumentException("Trailing message data");
    }

    private static byte[] bounded(byte[] bytes, int limit) {
        if (bytes == null || bytes.length == 0 || bytes.length > limit)
            throw new IllegalArgumentException("Message size must be 1-" + limit + " bytes");
        return bytes;
    }
}
