import java.util.Locale;
import java.util.Scanner;

public final class Client {
    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Usage: java Client [clientId]");
            System.exit(2);
        }
        String clientId = args.length == 1 ? args[0] : "Client-1";
        try (CinemaClient client = new CinemaClient(clientId); Scanner input = new Scanner(System.in)) {
            System.out.println("Connected to POSIX request queue as [" + clientId + "]");
            System.out.println("Commands: LIST, STATUS <id>, RESERVE <id>, CANCEL <id>, QUIT");
            while (true) {
                System.out.print(clientId + "> ");
                if (!input.hasNextLine()) break;
                String line = input.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String command = parts[0].toUpperCase(Locale.ROOT);
                int seat = -1;
                if (parts.length > 2 || (parts.length == 2 && (command.equals("LIST") || command.equals("QUIT")))) {
                    System.out.println("FAILED: Invalid command format.");
                    continue;
                }
                if (parts.length == 2) {
                    try { seat = Integer.parseInt(parts[1]); }
                    catch (NumberFormatException e) {
                        System.out.println("FAILED: Seat ID must be a number from 1 to 20.");
                        continue;
                    }
                }
                if ((command.equals("STATUS") || command.equals("RESERVE") || command.equals("CANCEL"))
                        && (seat < 1 || seat > 20)) {
                    System.out.println("FAILED: Seat ID must be from 1 to 20.");
                    continue;
                }
                ResponseMessage response = client.request(command, seat);
                System.out.println("Server Response:\n" + response.text());
                if (command.equals("QUIT")) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Client interrupted.");
        } catch (RuntimeException e) {
            System.err.println("Client error: " + e.getMessage());
            System.exit(1);
        }
    }
}
