package ap26.league;

import ap26.*;
import static ap26.Color.*;
import ap26.league.proxy.*;
import java.util.*;

/**
 * Socket 通信経由でプレイヤープロセスとやり取りしながらゲームを進行するクラス。
 *
 * <p>本番リーグ ({@link League}) で使われる。{@link Game} を継承して
 * {@link Game#play()} の流れは引き継ぎつつ、{@code think()} の呼び出しを
 * {@link PlayerProxy} 経由の Socket 通信に差し替える。
 *
 * <h2>{@link Game} (同一 JVM 版) との違い</h2>
 * <ul>
 *   <li>プレイヤーは独立 JVM プロセスで動作 ({@link PlayerProcessManager} 管理)</li>
 *   <li>each think は Socket RTT 数 ms のオーバーヘッドを伴う</li>
 *   <li>反則 (例外・時間切れ・不正手) の検出が Socket 通信エラーも含む形に拡張される</li>
 *   <li>ゲーム終了時に {@link PlayerProxy#gameEnd} で相手プロセスに通知し、プロセスをプールに返す</li>
 * </ul>
 *
 * <h2>シーケンス概要</h2>
 * <pre>
 *   play() の中で:
 *     1. processManager.acquirePlayer() で両プレイヤーのプロキシを取得 (= プロセス確保)
 *     2. 各プロキシに init() を送信 (新しいゲームの初期化)
 *     3. while 終局でない:
 *          turn の手番プロキシに move() を送り、返ってきた Move を盤面に反映
 *          反則・タイムアウト検出時は board.foul() で即時終了
 *     4. 両プロキシに gameEnd() を送信
 *     5. processManager.releasePlayer() でプロセスをプールに返す (次ゲームで再利用)
 * </pre>
 *
 * <h2>プロセスの再利用</h2>
 * 各プレイヤーは初回のみプロセスを起動し、以降のゲームでは同じプロセスを再利用する。
 * これにより、JVM 起動コスト (数百 ms) を償却できる。再利用は
 * {@link PlayerProcessManager} のステートマシン (READY → BUSY → READY) で管理される。
 */
public class ProxyGame extends Game {
    private final boolean proxyEnabled;
    private final String classPath;
    private final ProxyLogger logger;
    private PlayerProxy blackProxy;
    private PlayerProxy whiteProxy;
    private Map<Color, PlayerProxy> proxies;
    private long lastThinkTime = 0;
    private PlayerProcessManager processManager; // League から注入される
    private int blackPlayerId = -1; // 遅延初期化用
    private int whitePlayerId = -1; // 遅延初期化用

    public ProxyGame(Board board, Player black, Player white, long timeLimit, boolean proxyEnabled) {
        this(board, black, white, timeLimit, proxyEnabled, "bin:src");
    }

    public ProxyGame(Board board, Player black, Player white, long timeLimit, boolean proxyEnabled, String classPath) {
        super(board, black, white, timeLimit);
        this.proxyEnabled = proxyEnabled;
        this.classPath = classPath;
        this.logger = new ProxyLogger("ProxyGame");

        logger.info("Creating ProxyGame with proxy " + (proxyEnabled ? "enabled" : "disabled"));
        // setupProxies は setProcessManager で実行
    }

    /**
     * PlayerProcessManager設定（League から呼び出される）
     * Player IDのみを事前計算し、プロセス具現化は遅延させる
     */
    public void setProcessManager(PlayerProcessManager processManager) {
        this.processManager = processManager;
        if (proxyEnabled) {
            // Player IDのみを事前計算（プロセス具現化は遅延）
            this.blackPlayerId = processManager.getPlayerId(black);
            this.whitePlayerId = processManager.getPlayerId(white);
            logger.info("Player IDs assigned: black=" + blackPlayerId + ", white=" + whitePlayerId
                    + " (proxy setup deferred)");
        }
    }

    /**
     * プロキシ取得（集約された責任による簡素化）
     */
    private void acquireProxies() {
        if (processManager == null) {
            throw new IllegalStateException("PlayerProcessManager not set. Call setProcessManager first.");
        }

        if (blackPlayerId == -1 || whitePlayerId == -1) {
            throw new IllegalStateException("Player IDs not initialized. Call setProcessManager first.");
        }

        // 既に設定済みの場合はスキップ
        if (blackProxy != null && whiteProxy != null) {
            return;
        }

        // 集約された責任による単純なプロセス取得
        this.blackProxy = processManager.acquirePlayer(blackPlayerId);
        this.whiteProxy = processManager.acquirePlayer(whitePlayerId);

        this.proxies = Map.of(BLACK, blackProxy, WHITE, whiteProxy);

        logger.info("Acquired proxies from ProcessManager for " + processManager.getPlayerDisplayName(blackPlayerId)
                        + " vs " + processManager.getPlayerDisplayName(whitePlayerId));
    }


    @Override
    public void play() {
        if (!proxyEnabled) {
            // Use original Game implementation
            super.play();
            return;
        }

        try {
            // プロキシ取得（集約された責任）
            acquireProxies();

            // ゲーム開始時に色を設定
            black.setColor(BLACK);
            white.setColor(WHITE);

            // Initialize proxies
            initializeProxies();

            // Play with proxies
            playWithProxies();

        } finally {
            // 直接プロセス解放（簡素化）
            if (processManager != null && blackPlayerId != -1 && whitePlayerId != -1) {
                processManager.releasePlayer(blackPlayerId);
                processManager.releasePlayer(whitePlayerId);
                logger.debug("Released players: " + blackPlayerId + ", " + whitePlayerId);
            }
            
            // Cleanup proxies
            terminateProxies();
        }
    }

    private void initializeProxies() {
        Board initialBoard = this.board.clone();

        logger.info(
                "Initializing proxies for " + black.getClass().getSimpleName() + " vs "
                        + white.getClass().getSimpleName());

        // Initialize black proxy
        long totalTimeMs = timeLimit * 1000; // Total time for the game
        InitRequest blackInit = new InitRequest(black.getClass().getName(), BLACK, totalTimeMs, initialBoard);
        InitResult blackResult = blackProxy.init(blackInit);
        if (!blackResult.isSuccess()) {
            logger.error("Failed to initialize black player: " + blackResult.getErrorMessage());
            throw new RuntimeException("Failed to initialize black player: " + blackResult.getErrorMessage());
        }

        // Initialize white proxy
        InitRequest whiteInit = new InitRequest(white.getClass().getName(), WHITE, totalTimeMs, initialBoard);
        InitResult whiteResult = whiteProxy.init(whiteInit);
        if (!whiteResult.isSuccess()) {
            logger.error("Failed to initialize white player: " + whiteResult.getErrorMessage());
            throw new RuntimeException("Failed to initialize white player: " + whiteResult.getErrorMessage());
        }

        logger.info("Both proxies initialized successfully");
    }

    private void playWithProxies() {
        while (!this.board.isEnd()) {
            Color turn = this.board.getTurn();
            PlayerProxy proxy = this.proxies.get(turn);

            Throwable error = null;
            Move move = null;
            MoveResult moveResult = null;
            long tm = System.currentTimeMillis();

            // Request move from proxy
            try {
                long remainingTimeMs = (long) (1000 * ((float) timeLimit - times.get(turn)));

                // Ensure minimum time to avoid immediate timeout
                if (remainingTimeMs <= 0) {
                    logger.warn(
                            "Player " + turn + " has no remaining time: " + remainingTimeMs + "ms (used: "
                                    + times.get(turn) + "s)");
                    remainingTimeMs = 100; // Give 100ms minimum
                }

                MoveRequest moveRequest = new MoveRequest(this.board.clone(), remainingTimeMs);
                moveResult = proxy.requestMove(moveRequest);
                if (moveResult.isSuccess()) {
                    move = moveResult.getMove().colored(turn);
                    tm = moveResult.getThinkTimeMs();
                    lastThinkTime = tm; // 最後の思考時間を記録
                    logger.debug("Move: " + turn + " -> " + move + " (" + (tm / 1000.0) + "s)");
                } else {
                    error = new RuntimeException(moveResult.getErrorMessage());
                }
            } catch (Throwable e) {
                error = e;
            }

            if (error != null) {
                move = Move.ofError(turn);
                tm = System.currentTimeMillis() - tm;
            }

            final float t = (float) Math.max(tm, 1) / 1000.f;
            this.times.compute(turn, (k, v) -> v + t);

            // Check move validity
            move = check(turn, move, error);
            moves.add(move);

            // Update board
            if (move.isLegal()) {
                this.board = this.board.placed(move);
            } else {
                this.board.foul(turn);
                break;
            }
        }

        // Notify game end
        notifyGameEnd();

        // Log game result (this will print to stdout with [GAME] prefix)
        logger.gameResult(resultString(board, moves));
    }

    private void notifyGameEnd() {
        if (proxies != null) {
            Board finalBoard = this.board.clone();
            Color winner = finalBoard.winner();
            String reason = finalBoard.isEnd() ? "Game completed" : "Game ended by foul";

            GameEndNotification notification = new GameEndNotification(finalBoard, winner, reason);

            proxies.values().forEach(proxy -> {
                try {
                    proxy.notifyGameEnd(notification);
                } catch (Exception e) {
                    logger.error("Failed to notify game end", e);
                }
            });
        }
    }

    private void terminateProxies() {
        if (proxies != null && processManager != null) {
            logger.info("Releasing proxies to ProcessManager for reuse");

            // タイムアウト判定：実際にタイムアウト手が指されたかチェック
            boolean blackTimeout = moves.stream().anyMatch(move -> move.isTimeout() && move.getColor() == BLACK);
            boolean whiteTimeout = moves.stream().anyMatch(move -> move.isTimeout() && move.getColor() == WHITE);

            // ゲーム結果を作成（タイムアウトなしの場合は正常完了として通知）
            GameResult blackResult = new GameResult(blackTimeout, blackTimeout ? 0 : lastThinkTime, getBlackPlayer(),
                    BLACK);
            GameResult whiteResult = new GameResult(whiteTimeout, whiteTimeout ? 0 : lastThinkTime, getWhitePlayer(),
                    WHITE);

            // ProcessManagerに完了通知（再利用判定のため）
            processManager.onGameCompleted(getBlackPlayer(), blackResult);
            processManager.onGameCompleted(getWhitePlayer(), whiteResult);

            logger.info("Game completed - Black timeout: " + blackTimeout + ", White timeout: " + whiteTimeout);
        }
        logger.close();
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    /**
     * 最後の思考時間取得（PlayerProcessManager用）
     */
    public long getThinkTime() {
        return lastThinkTime;
    }

    /**
     * プレイヤー取得（PlayerProcessManager用）
     */
    public Player getBlackPlayer() {
        return black;
    }

    public Player getWhitePlayer() {
        return white;
    }

    /**
     * 色指定でプレイヤー取得
     */
    public Player getPlayer(Color color) {
        return (color == Color.BLACK) ? black : white;
    }

    /**
     * 手順リスト取得（PlayerProcessManager用）
     */
    public List<Move> getMoves() {
        return new ArrayList<>(moves); // 防御的コピー
    }

    /**
     * ボード取得（GameStatistics用）
     */
    public Board getBoard() {
        return board;
    }

    /**
     * 時間マップ取得（GameStatistics用）
     */
    public Map<Color, Float> getTimes() {
        return new HashMap<>(times); // 防御的コピー
    }

    @Override
    public String toString() {
        String modeStr = proxyEnabled ? "[PROXY] " : "";
        return modeStr + super.toString();
    }
}
