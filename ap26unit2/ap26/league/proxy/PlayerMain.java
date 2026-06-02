package ap26.league.proxy;

import ap26.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * プレイヤーメイン（クライアント側） - PlayerProxyサーバに接続して実行
 *
 * 正しい設計：
 * - PlayerProxy（ゲーム側）がServerSocketサーバ
 * - PlayerMain（プレイヤー側）がSocketクライアント
 * - 引数でサーバポート番号を受け取り接続
 * - サーバ側でタイムアウト管理（クライアント側は監視なし）
 *
 * 主な機能:
 * - プレイヤーインスタンス管理
 * - リクエスト処理ループ（INIT/MOVE/GAME_END）
 * - ブロッキングI/Oでポーリング排除
 * - サーバ切断による確実な停止
 */
public class PlayerMain {
    private final String playerId;
    private final String playerClassName;
    private final int serverPort;
    private final ProxyLogger logger;

    // Communication and player management
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    private Player playerInstance;

    // Execution control
    private volatile boolean running = false;

    public PlayerMain(String playerId, String playerClassName, int serverPort) {
        this.playerId = playerId;
        this.playerClassName = playerClassName;
        this.serverPort = serverPort;
        this.logger = new ProxyLogger("PlayerMain-" + playerId);
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: PlayerMain <playerId> <playerClass> <serverPort>");
            System.exit(1);
        }

        String playerId = args[0];
        String playerClass = args[1];
        int serverPort = Integer.parseInt(args[2]);

        PlayerMain playerMain = new PlayerMain(playerId, playerClass, serverPort);

        try {
            playerMain.start();
        } catch (Exception e) {
            System.err.println("PlayerMain startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * PlayerMain起動プロセス - PlayerProxyサーバへ接続
     */
    public void start() throws Exception {
        logger.info("Starting PlayerMain: playerId=" + playerId +
                ", playerClass=" + playerClassName +
                ", serverPort=" + serverPort);

        try {
            // 1. Initialize player instance
            initializePlayer();

            // 2. Connect to PlayerProxy server
            connectToServer();

            // 3. Add shutdown hook
            setupShutdownHook();

            logger.info("Connected to PlayerProxy on port " + serverPort);
            running = true;

            // 5. Start request processing loop
            processRequests();

        } catch (Exception e) {
            logger.error("PlayerMain startup failed", e);
            cleanup();
            System.exit(1); // Force exit on any startup failure
        }
    }

    /**
     * Initialize player instance
     */
    private void initializePlayer() throws Exception {
        logger.info("Initializing player class: " + playerClassName);

        try {
            // Load player class
            Class<?> playerClass = Class.forName(playerClassName);

            // Check if extends ap26.Player
            if (!Player.class.isAssignableFrom(playerClass)) {
                throw new Exception("Player class must extend ap26.Player: " + playerClassName);
            }

            // Initialize with Color.NONE (will be changed later via setColor)
            Constructor<?> constructor = playerClass.getConstructor(Color.class);
            playerInstance = (Player) constructor.newInstance(Color.NONE);

            logger.info("Player instance created successfully: " + playerInstance.getClass().getSimpleName());

        } catch (Exception e) {
            throw new Exception("Failed to initialize player class: " + playerClassName, e);
        }
    }

    /**
     * Connect to PlayerProxy server
     */
    private void connectToServer() throws Exception {
        logger.info("Connecting to PlayerProxy server on port " + serverPort);

        try {
            clientSocket = new Socket("localhost", serverPort);

            // Socket設定：クライアント側タイムアウトなし（サーバ側で制御）
            clientSocket.setSoTimeout(0); // 無限タイムアウト

            // JSON line-delimited over UTF-8 (PROTOCOL.md 参照)
            out = new PrintWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8),
                true);  // autoFlush=true
            in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

            logger.info("Connected to PlayerProxy server " + serverPort);

        } catch (Exception e) {
            throw new Exception("Failed to connect to PlayerProxy on port " + serverPort, e);
        }
    }

    /**
     * Main request processing loop (blocking I/O - no polling!)
     *
     * <p>JSON プロトコル (PROTOCOL.md v1.0) でメッセージを受信し、
     * {@code type} フィールドでディスパッチする。
     */
    private void processRequests() {
        logger.info("Starting request processing loop with blocking I/O");

        while (running && isConnected()) {
            try {
                String line = readRequestLine();
                if (line == null) {
                    logger.info("No more requests or server disconnected");
                    break;
                }

                String type;
                try {
                    type = JsonCodec.peekType(line);
                } catch (JsonCodec.JsonException e) {
                    logger.warn("Malformed JSON received: " + e.getMessage());
                    continue;
                }
                logger.debug("Processing request type: " + type);

                switch (type) {
                    case "init":
                        handleInit(JsonCodec.decodeInitRequest(line));
                        break;
                    case "move":
                        handleMove(JsonCodec.decodeMoveRequest(line));
                        break;
                    case "game_end":
                        handleGameEnd(JsonCodec.decodeGameEndNotification(line));
                        break;
                    case "shutdown":
                        logger.info("Received shutdown signal");
                        running = false;
                        return;
                    default:
                        logger.warn("Unknown request type: " + type);
                }

            } catch (Exception e) {
                // Socket通信エラーは即座にループ終了
                if (e instanceof java.net.SocketException || e instanceof java.io.IOException) {
                    logger.warn("Connection lost: " + e.getMessage() + " - terminating process");
                    running = false; // 確実に停止
                    break;
                }

                if (running && isConnected()) {
                    logger.error("Error processing request", e);
                } else {
                    logger.info("Request processing stopped");
                    break;
                }
            }
        }

        logger.info("Request processing loop ended");
    }

    /**
     * Read one JSON line from PlayerProxy.
     * @return JSON 文字列。EOF / 切断時は null。
     */
    private String readRequestLine() throws IOException {
        try {
            String line = in.readLine();
            if (line == null) {
                logger.info("Server disconnected (EOF)");
                return null;
            }
            return line;
        } catch (IOException e) {
            if (running) {
                logger.error("Failed to read request", e);
            }
            throw e;
        }
    }

    /**
     * Check if still connected to server
     */
    private boolean isConnected() {
        return clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
    }

    /**
     * Write response to PlayerProxy (JSON line, dispatched by runtime type).
     */
    private void writeResponse(Object response) throws IOException {
        String json;
        if (response instanceof InitResult) {
            json = JsonCodec.encode((InitResult) response);
        } else if (response instanceof MoveResult) {
            json = JsonCodec.encode((MoveResult) response);
        } else if (response instanceof GameEndResult) {
            json = JsonCodec.encode((GameEndResult) response);
        } else {
            throw new IllegalArgumentException("Unknown response type: " + response.getClass());
        }

        synchronized (this) {
            out.println(json);
            if (out.checkError()) {
                if (running) {
                    logger.error("Failed to send response");
                }
                throw new IOException("write failed for " + response.getClass().getSimpleName());
            }
            logger.debug("Response sent: " + response.getClass().getSimpleName());
        }
    }

    /**
     * Handle INIT request
     */
    private void handleInit(InitRequest request) {
        try {
            logger.info("Handling INIT request for player: " + request.getPlayerClass() +
                    " color: " + request.getColor() +
                    " totalTime: " + request.getTotalTimeMs() + "ms");

            // Set player color using reflection
            Class<?> playerClass = playerInstance.getClass();
            while (playerClass != null && !playerClass.equals(Player.class)) {
                playerClass = playerClass.getSuperclass();
            }

            if (playerClass != null) {
                Field colorField = playerClass.getDeclaredField("color");
                colorField.setAccessible(true);
                colorField.set(playerInstance, request.getColor());
            }

            // Set initial board
            playerInstance.setBoard(request.getInitialBoard());

            // Create success response
            InitResult result = InitResult.success("Player initialized: " + playerInstance.toString());

            // Send response
            writeResponse(result);

            logger.info("INIT request completed successfully for color: " + request.getColor());

        } catch (Exception e) {
            logger.error("INIT request failed", e);
            try {
                InitResult errorResult = InitResult.error("Init failed: " + e.getMessage());
                writeResponse(errorResult);
            } catch (Exception responseError) {
                logger.error("Failed to send error response for INIT", responseError);
            }
        }
    }

    /**
     * Handle MOVE request
     */
    private void handleMove(MoveRequest request) {
        try {
            logger.debug("Handling MOVE request, remaining time: " + request.getRemainingTimeMs() + "ms");

            long startTime = System.currentTimeMillis();

            // Call player's think method
            Move move = playerInstance.think(request.getBoard());
            long thinkTime = System.currentTimeMillis() - startTime;

            // Create success response
            MoveResult result = MoveResult.success(move, thinkTime);
            logger.debug(
                    "Created MoveResult: success=" + result.isSuccess() + ", move=" + move + ", time=" + thinkTime);

            // Send response
            writeResponse(result);

            logger.debug("MOVE request completed in " + thinkTime + "ms: " + move);

        } catch (Exception e) {
            logger.error("MOVE request failed", e);
            try {
                MoveResult errorResult = MoveResult.error("Move failed: " + e.getMessage());
                writeResponse(errorResult);
            } catch (Exception responseError) {
                logger.error("Failed to send error response for MOVE", responseError);
            }
        }
    }

    /**
     * Handle GAME_END request
     */
    private void handleGameEnd(GameEndNotification notification) {
        try {
            logger.info("Handling GAME_END notification: winner=" + notification.getWinner() +
                    ", reason=" + notification.getReason());

            // Create acknowledgment response
            GameEndResult result = GameEndResult.acknowledged();

            // Send response
            writeResponse(result);

            logger.info("GAME_END notification completed");

        } catch (Exception e) {
            logger.error("GAME_END notification failed", e);
            try {
                GameEndResult errorResult = GameEndResult.error("Game end failed: " + e.getMessage());
                writeResponse(errorResult);
            } catch (Exception responseError) {
                logger.error("Failed to send error response for GAME_END", responseError);
            }
        }
    }

    /**
     * Setup shutdown hook
     */
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down via shutdown hook");
            cleanup();
        }));
    }

    /**
     * Resource cleanup
     */
    private void cleanup() {
        logger.info("Cleaning up PlayerMain resources");

        running = false;

        // Close client socket connection
        try {
            if (out != null)
                out.close();
            if (in != null)
                in.close();
            if (clientSocket != null)
                clientSocket.close();
        } catch (Exception e) {
            logger.warn("Error closing client socket: " + e.getMessage());
        }

        logger.info("PlayerMain cleanup completed");
        logger.close();
    }

}
