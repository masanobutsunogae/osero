package ap26.league.proxy;

import ap26.*;
import ap26.league.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * GameExecutor - キューベースゲーム実行エンジン
 *
 * 基本機能:
 * - 待機キューからの実行可能ゲーム選択
 * - プレイヤー排他制御（1プレイヤー=1ゲーム制約）
 * - デッドロック回避のためのノンブロッキング設計
 *
 * 特徴:
 * - シンプルなワーカーループ
 * - 確実なリソース管理
 * - 段階的機能拡張対応
 */
public class GameExecutor {
    private final Queue<ProxyGame> pendingGames = new ConcurrentLinkedQueue<>();
    private final Set<Integer> busyPlayerIds = ConcurrentHashMap.newKeySet(); // Player IDベース
    private final ExecutorService workers;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger concurrentGames = new AtomicInteger(0);
    private final ProxyLogger logger = new ProxyLogger("GameExecutor");
    private final PlayerProcessManager processManager; // Player ID取得用
    private volatile boolean shutdown = false;

    public GameExecutor(int parallelism, PlayerProcessManager processManager) {
        this.workers = Executors.newFixedThreadPool(parallelism);
        this.processManager = processManager;
        logger.info("GameExecutor initialized with parallelism: " + parallelism);
    }

    // 負荷分散用: プレイヤーID別累計ゲーム数カウンタ
    private final Map<Integer, Integer> playerGameCounts = new ConcurrentHashMap<>();

    /**
     * 全ゲーム実行（拡張版: 初期ランダム順序 + 負荷分散）
     * @param allGames 実行対象ゲームリスト
     * @param onGameCompleted ゲーム完了時のコールバック
     */
    public void executeAllGames(List<ProxyGame> allGames, Consumer<ProxyGame> onGameCompleted) {
        // 初期ランダム順序でエンターテイメント性向上
        List<ProxyGame> randomizedGames = new ArrayList<>(allGames);
        Collections.shuffle(randomizedGames);
        logger.info("Randomized " + allGames.size() + " games for entertainment");

        // 全ゲームをキューに投入
        pendingGames.addAll(randomizedGames);
        logger.info("Added " + allGames.size() + " games to execution queue");

        // ワーカー起動
        int workerCount = ((ThreadPoolExecutor) workers).getCorePoolSize();
        for (int i = 0; i < workerCount; i++) {
            workers.submit(() -> workerLoop(onGameCompleted));
        }

        // 全完了まで待機
        waitForCompletion();
    }

    /**
     * ワーカーループ - 実行可能ゲームを継続的に探索・実行
     */
    private void workerLoop(Consumer<ProxyGame> onGameCompleted) {
        activeWorkers.incrementAndGet();

        try {
            logger.debug("Worker started (active workers: " + activeWorkers.get() + ")");

            while (!shutdown && !pendingGames.isEmpty()) {
                ProxyGame game = findExecutableGame();

                if (game != null) {
                    executeGame(game, onGameCompleted);
                } else {
                    // 実行可能ゲームなし → 短時間待機
                    Thread.sleep(50);

                    // デッドロック回避: 他のワーカーが動いていない場合は終了
                    if (pendingGames.isEmpty()) {
                        break;
                    }
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Worker interrupted");
        } finally {
            int remaining = activeWorkers.decrementAndGet();
            logger.debug("Worker finished (active workers: " + remaining + ")");
        }
    }

    /**
     * 実行可能なゲームを検索・取得（Player IDベース）
     * - プレイヤーIDが両方とも利用可能なゲームを選択
     * - 累計ゲーム数の合計が最小のゲームを優先（貪欲法）
     * - キューから削除してプレイヤーIDを予約
     */
    private ProxyGame findExecutableGame() {
        synchronized (busyPlayerIds) {
            // 実行可能なゲームを探索
            Iterator<ProxyGame> iterator = pendingGames.iterator();
            while (iterator.hasNext()) {
                ProxyGame game = iterator.next();
                int blackId = getPlayerId(game, Color.BLACK);
                int whiteId = getPlayerId(game, Color.WHITE);

                // プレイヤーIDが両方とも利用可能かチェック
                if (!busyPlayerIds.contains(blackId) && !busyPlayerIds.contains(whiteId)) {
                    // 実行可能 → キューから削除・プレイヤーID予約
                    iterator.remove();
                    busyPlayerIds.add(blackId);
                    busyPlayerIds.add(whiteId);

                    int blackCount = playerGameCounts.getOrDefault(blackId, 0);
                    int whiteCount = playerGameCounts.getOrDefault(whiteId, 0);
                    String blackName = processManager.getPlayerDisplayName(blackId);
                    String whiteName = processManager.getPlayerDisplayName(whiteId);
                    logger.debug("Selected game: " + blackName + "(" + blackCount + ") vs " + whiteName + "(" + whiteCount
                            + ")" +
                            " total=" + (blackCount + whiteCount) + " (remaining: " + pendingGames.size() + ")");

                    return game;
                }
            }

            return null; // 実行可能ゲームなし
        }
    }

    /**
     * ゲームの両プレイヤーの累計ゲーム数合計を取得
     */
    private int getPlayerGameSum(ProxyGame game) {
        int blackId = getPlayerId(game, Color.BLACK);
        int whiteId = getPlayerId(game, Color.WHITE);
        return playerGameCounts.getOrDefault(blackId, 0) + playerGameCounts.getOrDefault(whiteId, 0);
    }

    /**
     * ゲーム実行・完了処理（Player IDベース）
     */
    private void executeGame(ProxyGame game, Consumer<ProxyGame> onGameCompleted) {
        int blackId = getPlayerId(game, Color.BLACK);
        int whiteId = getPlayerId(game, Color.WHITE);
        String blackName = processManager.getPlayerDisplayName(blackId);
        String whiteName = processManager.getPlayerDisplayName(whiteId);

        try {
            int currentConcurrent = concurrentGames.incrementAndGet();
            System.out.println("[CONCURRENT] Starting game: " + blackName + " vs " + whiteName + 
                             " (concurrent games: " + currentConcurrent + ")");
            logger.info("Executing game: " + blackName + " vs " + whiteName + 
                       " (concurrent games: " + currentConcurrent + ")");

            // ゲーム実行
            game.play();

            // ゲーム完了処理（統計更新等）
            onGameCompleted.accept(game);

            logger.debug("Completed game: " + blackName + " vs " + whiteName);

        } catch (Exception e) {
            logger.error("Game execution failed: " + blackName + " vs " + whiteName, e);
            // エラーでも完了処理は実行（統計に反映）
            onGameCompleted.accept(game);

        } finally {
            int remainingConcurrent = concurrentGames.decrementAndGet();
            
            // プレイヤーID解放
            synchronized (busyPlayerIds) {
                busyPlayerIds.remove(blackId);
                busyPlayerIds.remove(whiteId);
            }

            // プレイヤーゲーム数更新（負荷分散用）
            playerGameCounts.compute(blackId, (k, v) -> (v == null) ? 1 : v + 1);
            playerGameCounts.compute(whiteId, (k, v) -> (v == null) ? 1 : v + 1);

            System.out.println("[CONCURRENT] Completed game: " + blackName + " vs " + whiteName + 
                             " (concurrent games: " + remainingConcurrent + ", busy players: " + busyPlayerIds.size() + ")");
            logger.info("Completed game: " + blackName + " vs " + whiteName + 
                       " (concurrent games: " + remainingConcurrent + ", busy players: " + busyPlayerIds.size() + ")");
        }
    }

    /**
     * 全ゲーム完了まで待機
     */
    private void waitForCompletion() {
        logger.info("Waiting for all games to complete...");

        // 全ゲーム完了まで待機
        while (!pendingGames.isEmpty() || activeWorkers.get() > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Wait for completion interrupted");
                break;
            }
        }

        // ワーカー終了
        shutdown = true;
        workers.shutdown();

        try {
            if (!workers.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.warn("Workers did not terminate within 60 seconds, forcing shutdown");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Shutdown interrupted, forcing immediate shutdown");
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("All games completed, executor shutdown");
    }

    /**
     * プレイヤーID取得（Player instance → Player ID）
     */
    private int getPlayerId(ProxyGame game, Color color) {
        Player player = game.getPlayer(color);
        if (player == null) {
            throw new IllegalArgumentException("Player not found for color: " + color);
        }
        return processManager.getPlayerId(player);
    }

    /**
     * 現在の実行状況取得（デバッグ・監視用）
     */
    public ExecutionStatus getStatus() {
        return new ExecutionStatus(
                pendingGames.size(),
                busyPlayerIds.size(),
                activeWorkers.get(),
                concurrentGames.get(),
                shutdown,
                new HashMap<>(playerGameCounts));
    }

    /**
     * 実行状況データクラス（Player IDベース負荷分散情報含む）
     */
    public static class ExecutionStatus {
        public final int pendingGames;
        public final int busyPlayers;
        public final int activeWorkers;
        public final int concurrentGames;
        public final boolean shutdown;
        public final Map<Integer, Integer> playerGameCounts; // Player IDベース

        public ExecutionStatus(int pendingGames, int busyPlayers, int activeWorkers, int concurrentGames, boolean shutdown,
                Map<Integer, Integer> playerGameCounts) {
            this.pendingGames = pendingGames;
            this.busyPlayers = busyPlayers;
            this.activeWorkers = activeWorkers;
            this.concurrentGames = concurrentGames;
            this.shutdown = shutdown;
            this.playerGameCounts = playerGameCounts;
        }

        @Override
        public String toString() {
            int totalGames = playerGameCounts.values().stream().mapToInt(Integer::intValue).sum();
            return String.format(
                    "ExecutionStatus{pending=%d, busy=%d, workers=%d, concurrent=%d, shutdown=%s, totalGames=%d, players=%d}",
                    pendingGames, busyPlayers, activeWorkers, concurrentGames, shutdown, totalGames, playerGameCounts.size());
        }

        public String getLoadBalanceReport() {
            if (playerGameCounts.isEmpty())
                return "No games completed yet";

            int min = playerGameCounts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = playerGameCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double avg = playerGameCounts.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);

            return String.format("Load balance: min=%d, max=%d, avg=%.1f, variance=%d",
                    min, max, avg, max - min);
        }
    }
}
