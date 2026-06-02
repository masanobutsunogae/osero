package ap26.league.proxy;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import ap26.*;

/**
 * プレイヤープロキシ（サーバ側） - Socket通信でPlayerMainプロセスを制御
 * 
 * 正しい設計：
 * - PlayerProxy（ゲーム側）がServerSocketでサーバ
 * - PlayerMain（プレイヤー側）がSocketクライアント
 * - ポートプールからポートを借用・返却
 * - SO_REUSEADDR + SO_LINGER設定で異常終了対策
 * - delta=0.5秒ベースの動的タイムアウト設定
 * 
 * 主な機能:
 * - サーバポート確保とPlayerMainプロセス起動
 * - 残り時間ベースの動的Socket設定（timeout + SO_LINGER）
 * - プロセスkillによる確実な停止
 * - ブロッキングI/Oでポーリング排除
 */
public class PlayerProxy extends Player implements AutoCloseable {
    
    // プロセス管理
    private Process playerProcess;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private BufferedReader in;
    private int assignedPort;
    
    // 設定
    private final String playerId;
    private final String playerClass;
    private final String classPath;
    private final long timeoutMs;
    private final ProxyLogger logger;
    
    // 状態管理
    private volatile boolean initialized = false;
    private volatile boolean terminated = false;
    private final ProcessState processState;
    
    public PlayerProxy(String playerId, String playerClass, String classPath, long timeoutMs) {
        super(playerClass + "_" + playerId, Color.NONE); // Playerコンストラクタ呼び出し
        this.playerId = playerId;
        this.playerClass = playerClass;
        this.classPath = classPath;
        this.timeoutMs = timeoutMs;
        this.logger = new ProxyLogger("PlayerProxy-" + playerId);
        this.assignedPort = -1; // 未割り当て
        this.processState = new ProcessState(playerId);
    }
    
    /**
     * プレイヤー初期化
     * 1. ポートプールからサーバポート借用
     * 2. ServerSocket開始（SO_REUSEADDR設定）
     * 3. PlayerMainプロセス起動（ポート番号を引数で渡す）
     * 4. PlayerMainからの接続待機（無限タイムアウト）
     * 5. Socket設定（SO_LINGER、初期タイムアウト）
     * 6. 初期化要求送信
     */
    public InitResult init(InitRequest request) {
        if (terminated) {
            return InitResult.error("Proxy has been terminated");
        }
        
        try {
            logger.info("Initializing player: " + playerId);
            
            // 1. ポートプールからサーバポート借用
            assignedPort = PortManager.borrowPort();
            logger.info("Borrowed server port " + assignedPort + " from pool for player: " + playerId);
            
            // 2. ServerSocket開始（SO_REUSEADDR設定）
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true); // 異常終了時の即座ポート再利用
            serverSocket.setSoTimeout(0); // accept()無限タイムアウト（プロセス起動待ち）
            serverSocket.bind(new InetSocketAddress(assignedPort));
            logger.info("ServerSocket listening on port " + assignedPort + " (SO_REUSEADDR=true)");
            
            // 3. PlayerMainプロセス起動（ポート番号を引数で渡す）
            startPlayerProcess();
            
            // 4. PlayerMainからの接続待機（無限タイムアウト）
            logger.info("Waiting for PlayerMain client connection on port " + assignedPort);
            clientSocket = serverSocket.accept();
            logger.info("PlayerMain connected from " + clientSocket.getRemoteSocketAddress());
            
            // 5. Socket設定（SO_LINGER、初期タイムアウト）
            int clientTimeoutMs = SocketConfig.calculateClientTimeout(timeoutMs);
            clientSocket.setSoTimeout(clientTimeoutMs);
            
            int lingerTimeSeconds = SocketConfig.calculateLingerTimeout(timeoutMs);
            clientSocket.setSoLinger(true, lingerTimeSeconds);
            logger.info("Socket configured: timeout=" + clientTimeoutMs + "ms, SO_LINGER=" + lingerTimeSeconds + "s");
            
            // 6. 通信ストリーム確立 (JSON line-delimited over UTF-8、PROTOCOL.md 参照)
            out = new PrintWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8),
                true);  // autoFlush=true (println の度に flush)
            in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            logger.info("Communication streams established for player: " + playerId);
            
            // 7. 初期化要求送信
            logger.info("Sending init request to player: " + playerId);
            InitResult result = executeWithTimeout(() -> {
                try {
                    return sendInitRequest(request);
                } catch (Exception e) {
                    logger.error("Init communication failed for player: " + playerId + " - " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                    throw new RuntimeException("Init communication failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
            }, timeoutMs);
            
            initialized = true;
            logger.info("Player initialization completed: " + playerId);
            return result;
            
        } catch (TimeoutException e) {
            cleanup();
            return InitResult.error("Init timeout after " + timeoutMs + "ms");
        } catch (Exception e) {
            cleanup();
            logger.error("Init failed for player: " + playerId, e);
            return InitResult.error("Init failed: " + e.getMessage());
        }
    }
    
    /**
     * 手番要求処理
     * 1. 残り時間ベースでSocket設定を動的更新（timeout + SO_LINGER）
     * 2. 統一タイムアウト処理（通信+思考時間を統合）
     */
    public MoveResult requestMove(MoveRequest request) {
        if (!initialized || terminated) {
            return MoveResult.error("Player not initialized or terminated");
        }
        
        try {
            logger.debug("Requesting move from player: " + playerId);
            
            // 1. 残り時間ベースでSocket設定を動的更新
            long remainingTimeMs = request.getRemainingTimeMs();
            int clientSocketTimeoutMs = SocketConfig.calculateClientTimeout(remainingTimeMs);
            
            synchronized (this) {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    try {
                        // サーバ側クライアント通信タイムアウト = 残り時間 + delta秒
                        clientSocket.setSoTimeout(clientSocketTimeoutMs);
                        
                        // SO_LINGER = 2×残り時間 + delta秒（異常終了対策）
                        int lingerTimeSeconds = SocketConfig.calculateLingerTimeout(remainingTimeMs);
                        clientSocket.setSoLinger(true, lingerTimeSeconds);
                        
                        logger.debug("Dynamic socket update: timeout=" + clientSocketTimeoutMs + "ms, SO_LINGER=" + lingerTimeSeconds + "s (remaining=" + remainingTimeMs + "ms)");
                    } catch (Exception e) {
                        logger.warn("Failed to update socket settings: " + e.getMessage());
                    }
                }
            }
            
            // 2. 統一タイムアウト処理（通信+思考時間を統合）
            return executeWithTimeout(() -> {
                try {
                    return sendMoveRequest(request);
                } catch (Exception e) {
                    throw new RuntimeException("Move communication failed", e);
                }
            }, remainingTimeMs);
            
        } catch (TimeoutException e) {
            // 統一タイムアウト処理 - タイムアウト手を生成
            logger.warn("Move timeout for player: " + playerId + " after " + e.timeout + "ms");
            return MoveResult.success(Move.ofTimeout(request.getBoard().getTurn()), e.timeout);
        } catch (Exception e) {
            logger.error("Move request failed for player: " + playerId, e);
            return MoveResult.error("Move failed: " + e.getMessage());
        }
    }
    
    /**
     * ゲーム終了通知
     */
    public GameEndResult notifyGameEnd(GameEndNotification notification) {
        if (!initialized || terminated) {
            return GameEndResult.error("Player not initialized or terminated");
        }
        
        try {
            logger.info("Notifying game end to player: " + playerId);
            
            return executeWithTimeout(() -> {
                try {
                    return sendGameEndNotification(notification);
                } catch (Exception e) {
                    throw new RuntimeException("Game end communication failed", e);
                }
            }, timeoutMs);
            
        } catch (TimeoutException e) {
            logger.warn("Game end notification timeout for player: " + playerId);
            return GameEndResult.error("Game end timeout after " + timeoutMs + "ms");
        } catch (Exception e) {
            logger.error("Game end notification failed for player: " + playerId, e);
            return GameEndResult.error("Game end failed: " + e.getMessage());
        }
    }
    
    /**
     * プレイヤー終了処理
     */
    public void terminate() {
        if (terminated) {
            return;
        }
        
        logger.info("Terminating player: " + playerId);
        terminated = true;
        processState.terminate();
        
        try {
            // 終了シグナル送信
            if (out != null && initialized) {
                sendTerminateSignal();
            }
        } catch (Exception e) {
            logger.warn("Failed to send terminate signal to player: " + playerId + " - " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    @Override
    public void close() {
        terminate();
    }
    
    /**
     * プレイヤープロセス起動（ポート番号を引数で渡す）
     */
    private void startPlayerProcess() throws IOException, InterruptedException {
        logger.debug("Starting player process: " + playerId + " with port " + assignedPort);
        
        // プロセス起動（ポート番号を引数で渡す）
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-cp", classPath,
            "-Dgame.timeout.seconds=" + (timeoutMs / 1000),
            "ap26.league.proxy.PlayerMain", playerId, playerClass, String.valueOf(assignedPort)
        );
        
        // 標準出力・標準エラー出力をログファイルにリダイレクト（unit2内に作成）
        Path logDir = Paths.get(".", "logs", "proxy");
        Files.createDirectories(logDir);
        pb.redirectOutput(logDir.resolve(playerId + "_stdout.log").toFile());
        pb.redirectError(logDir.resolve(playerId + "_stderr.log").toFile());
        
        playerProcess = pb.start();
        logger.info("Started player process: " + playerId + " (PID: " + playerProcess.pid() + ") on port " + assignedPort);
        
        // プロセスが実際に起動しているかチェック
        try {
            Thread.sleep(500); // 接続準備時間
            if (!playerProcess.isAlive()) {
                int exitValue = playerProcess.exitValue();
                throw new IOException("Player process terminated immediately with exit code: " + exitValue);
            }
            logger.info("Player process confirmed alive: " + playerId);
        } catch (IllegalThreadStateException e) {
            // プロセスがまだ実行中（正常）
            logger.info("Player process still running: " + playerId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking process status", e);
        }
    }
    
    /**
     * タイムアウト付き操作実行
     */
    private <T> T executeWithTimeout(Supplier<T> operation, long timeoutMs) throws TimeoutException {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(operation);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true); // 実行中の操作をキャンセル
            throw new TimeoutException(timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else {
                throw new RuntimeException("Execution failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Operation interrupted", e);
        }
    }
    
    /**
     * 初期化要求送信 (JSON line, PROTOCOL.md §3.2/3.3)
     */
    private InitResult sendInitRequest(InitRequest request) throws IOException {
        synchronized (this) {
            try {
                out.println(JsonCodec.encode(request));
                if (out.checkError()) throw new IOException("write failed");
                logger.debug("Init request sent");

                String line = in.readLine();
                if (line == null) throw new IOException("connection closed by player");
                logger.debug("Init response received");

                return JsonCodec.decodeInitResult(line);

            } catch (JsonCodec.JsonException e) {
                throw new IOException("Failed to process init response", e);
            }
        }
    }

    /**
     * 手番要求送信 (JSON line, PROTOCOL.md §3.4/3.5)
     */
    private MoveResult sendMoveRequest(MoveRequest request) throws IOException {
        synchronized (this) {
            try {
                out.println(JsonCodec.encode(request));
                if (out.checkError()) throw new IOException("write failed");
                logger.debug("Move request sent");

                String line = in.readLine();
                if (line == null) throw new IOException("connection closed by player");
                logger.debug("Move response received");

                return JsonCodec.decodeMoveResult(line, this.getColor());

            } catch (JsonCodec.JsonException e) {
                throw new IOException("Failed to process move response", e);
            }
        }
    }

    /**
     * ゲーム終了通知送信 (JSON line, PROTOCOL.md §3.6/3.7)
     */
    private GameEndResult sendGameEndNotification(GameEndNotification notification) throws IOException {
        synchronized (this) {
            try {
                out.println(JsonCodec.encode(notification));
                if (out.checkError()) throw new IOException("write failed");
                logger.debug("Game end notification sent");

                String line = in.readLine();
                if (line == null) throw new IOException("connection closed by player");
                logger.debug("Game end response received");

                return JsonCodec.decodeGameEndResult(line);

            } catch (JsonCodec.JsonException e) {
                throw new IOException("Failed to process game end response", e);
            }
        }
    }

    /**
     * 終了シグナル送信 (PROTOCOL.md §3.8)
     */
    private void sendTerminateSignal() throws IOException {
        synchronized (this) {
            out.println("{\"type\":\"shutdown\"}");
            if (out.checkError()) {
                logger.warn("Failed to send shutdown signal");
                throw new IOException("write failed during shutdown");
            }
            logger.info("Shutdown signal sent");
        }
    }
    
    /**
     * リソースクリーンアップ
     */
    private void cleanup() {
        logger.debug("Cleaning up resources for player: " + playerId);
        
        // 通信ストリームクローズ
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (clientSocket != null) clientSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            logger.warn("Failed to close communication resources for player: " + playerId + " - " + e.getMessage());
        }
        
        // ポートプールに返却
        if (assignedPort != -1) {
            try {
                PortManager.returnPort(assignedPort);
                logger.info("Returned port " + assignedPort + " to pool for player: " + playerId);
            } catch (Exception e) {
                logger.warn("Failed to return port " + assignedPort + " to pool for player: " + playerId + " - " + e.getMessage());
            }
            assignedPort = -1;
        }
        
        // プロセス終了
        if (playerProcess != null && playerProcess.isAlive()) {
            try {
                // 正常終了を待つ（短時間）
                if (!playerProcess.waitFor(1000, TimeUnit.MILLISECONDS)) {
                    // 強制終了
                    playerProcess.destroyForcibly();
                    logger.warn("Force killed player process: " + playerId);
                }
            } catch (InterruptedException e) {
                playerProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            playerProcess = null;
        }
        
        logger.info("Cleanup completed for player: " + playerId);
    }
    
    /**
     * タイムアウト例外
     */
    public static class TimeoutException extends Exception {
        public final long timeout;
        
        public TimeoutException(long timeoutMs) {
            super("Operation timed out after " + timeoutMs + "ms");
            this.timeout = timeoutMs;
        }
    }
    
    // 状態確認メソッド
    public boolean isInitialized() {
        return initialized && !terminated;
    }
    
    public boolean isTerminated() {
        return terminated;
    }
    
    public String getPlayerId() {
        return playerId;
    }
    
    public String getPlayerClass() {
        return playerClass;
    }

    // ===== Player継承での透過的プロセス実行 =====
    
    /**
     * 透過的なプロセス実行 - 残り時間指定版
     * 通常のPlayerとの差異：Move.ofTimeout()を返す可能性
     */
    @Override
    public Move think(Board board, long remainingTimeMs) {
        if (!initialized || terminated) {
            logger.warn("PlayerProxy not ready for think(): initialized=" + initialized + 
                       ", terminated=" + terminated);
            return Move.ofTimeout(getColor());
        }
        
        try {
            MoveRequest moveRequest = new MoveRequest(board.clone(), remainingTimeMs);
            MoveResult moveResult = requestMove(moveRequest);
            
            if (moveResult.isSuccess()) {
                Move move = moveResult.getMove().colored(getColor());
                logger.debug("Think successful: " + move + " (think time: " + moveResult.getThinkTimeMs() + "ms)");
                return move;
            } else {
                logger.warn("Think failed: " + moveResult.getErrorMessage());
                return Move.ofTimeout(getColor());
            }
        } catch (Exception e) {
            logger.error("Think exception: " + e.getMessage(), e);
            return Move.ofTimeout(getColor());
        }
    }
    
    /**
     * 透過的なプロセス実行 - 時間制限なし版
     */
    @Override
    public Move think(Board board) {
        return think(board, timeoutMs); // デフォルトタイムアウトを使用
    }

    // ===== ProcessStateへの委譲メソッド =====
    
    /**
     * プロセス取得（再利用時）- READY → BUSY
     */
    public synchronized PlayerProxy acquire() {
        if (!processState.acquire()) {
            throw new IllegalStateException("Process not ready for use (state=" + processState.getState() + "): " + playerId);
        }
        return this;
    }
    
    /**
     * ゲーム完了マーク（再利用準備）- BUSY → READY
     */
    public synchronized void markCompleted(long thinkTime) {
        processState.release(thinkTime);
    }
    
    /**
     * 再利用可能判定
     */
    public boolean isAvailable() {
        return processState.isAvailable() && !terminated;
    }
    
    /**
     * エラー状態への遷移
     */
    public synchronized void markError(String reason) {
        processState.markError(reason);
    }
    
    /**
     * 統計情報取得
     */
    public int getGamesPlayed() {
        return processState.getGamesPlayed();
    }
    
    public long getAverageThinkTime() {
        return processState.getAverageThinkTime();
    }
    
    public long getUptimeMs() {
        return processState.getUptimeMs();
    }
    
    public double getProcessEfficiency() {
        return processState.getProcessEfficiency();
    }
    
    public ProcessState.State getState() {
        return processState.getState();
    }
}
