public record RequestMessage(long requestId, String clientId, String command, int resourceId,
                             String responseQueue) {
    public RequestMessage {
        if (requestId <= 0) throw new IllegalArgumentException("requestId must be positive");
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Client ID must be 1-32 letters, digits, _ or -");
        if (command == null || !command.matches("[A-Z]{1,16}"))
            throw new IllegalArgumentException("Invalid command token");
        if (resourceId < -1 || resourceId > 9999)
            throw new IllegalArgumentException("Invalid resource ID");
        PosixMessageQueue.validateName(responseQueue);
    }
}
