import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {
    @Test void roundTripsAndBoundaries() {
        RequestMessage request = new RequestMessage(42, "Client_1", "RESERVE", 20, "/reply_123");
        assertEquals(request, MessageCodec.decodeRequest(MessageCodec.encode(request)));
        ResponseMessage response = new ResponseMessage(42, true, "Seat 20 reserved — café");
        assertEquals(response, MessageCodec.decodeResponse(MessageCodec.encode(response)));
        String max = "x".repeat(2048);
        assertEquals(max, MessageCodec.decodeResponse(MessageCodec.encode(new ResponseMessage(1, true, max))).text());
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.encode(new ResponseMessage(1, true, max + "x")));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decodeRequest(new byte[MessageCodec.REQUEST_SIZE + 1]));
        byte[] encoded = MessageCodec.encode(request);
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decodeRequest(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decodeRequest(Arrays.copyOf(encoded, encoded.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.decodeResponse(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> new RequestMessage(1, "bad id", "LIST", -1, "/reply"));
        assertThrows(IllegalArgumentException.class, () -> new RequestMessage(1, "Good", "LIST", -1, "/bad/name"));
    }
}
