public record ResponseMessage(long requestId, boolean success, String text) {
    public ResponseMessage {
        if (requestId <= 0) throw new IllegalArgumentException("requestId must be positive");
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("Response text is empty");
    }
}
