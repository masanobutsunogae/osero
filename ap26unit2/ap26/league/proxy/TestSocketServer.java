package ap26.league.proxy;

import java.io.*;
import java.net.*;

/**
 * テスト用のSocketサーバ
 *
 * SocketCleanupTestでPlayerMainプロセスの異常終了をシミュレートするために使用。
 * 指定されたポートでServerSocketを開いて待機し、強制終了されることを想定。
 */
public class TestSocketServer {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: TestSocketServer <port>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);

        try {
            // ServerSocketを開いて接続待機
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("TestSocketServer listening on port " + port);

            // 1つの接続を受け入れ
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());

            // 入力ストリームを開いて待機（プロセスkillされるまで）
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));

            // ブロッキング読み込み（終了まで待機）
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break; // 接続終了
                }
                System.out.println("Received: " + line);
            }

        } catch (IOException e) {
            System.err.println("TestSocketServer error: " + e.getMessage());
            System.exit(1);
        }
    }
}
