import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
	private static final int SERVER_PORT = 8080;

	public static void main(String[] args) {
		// Argument 1: ชื่อ Client ID
		String clientId = args.length > 0 ? args[0] : "Client-1";
		// Argument 2: IP Address ของเครื่อง Server (ถ้าไม่ระบุให้เป็น localhost)
		String serverHost = args.length > 1 ? args[1] : "localhost";

		try (Socket socket = new Socket(serverHost, SERVER_PORT);
				PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				Scanner scanner = new Scanner(System.in)) {

			System.out.println("Connected to Theater Server at " + serverHost + " as [" + clientId + "]");
			System.out.println("Commands: LIST, STATUS <id>, RESERVE <id>, CANCEL <id>, QUIT");

			while (true) {
				System.out.print(clientId + "> ");
				if (!scanner.hasNextLine())
					break;
				String commandLine = scanner.nextLine().trim();

				if (commandLine.isEmpty())
					continue;

				out.println(commandLine.split("\\s+")[0] + " " + clientId + " " +
						(commandLine.contains(" ") ? commandLine.substring(commandLine.indexOf(" ") + 1) : ""));

				String response = in.readLine();
				System.out.println("Server Response: " + response);

				if (commandLine.equalsIgnoreCase("QUIT")) {
					break;
				}
			}
		} catch (IOException e) {
			System.err.println("Connection error: " + e.getMessage());
		}
	}
}