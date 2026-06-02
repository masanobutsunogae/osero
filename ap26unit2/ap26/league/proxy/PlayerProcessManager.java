package ap26.league.proxy;

import ap26.*;
import ap26.league.OfficialBoard;
import java.util.concurrent.*;
import java.util.*;

/**
 * プレイヤープロセス管理（シンプル版）
 *
 * 責務:
 * - 固定プレイヤーリストに基づくプロセス管理
 * - プレイヤーID（一意）、名称、クラス名の3点管理
 * - タイムアウト有無による再利用判定
 * - プロセス効率統計の提供
 *
 * 設計:
 * - Leagueから固定プレイヤーリストを受け取り
 * - プレイヤー数は事前確定（動的管理不要）
 * - 各プレイヤーに1つのプロセスを対応
 */
public class PlayerProcessManager {
    /**
     * プレイヤー情報（3点セット）
     */
    private static class PlayerInfo {
        final int playerId;         // 一意ID（整数）
        final String playerName;    // 名称（toString()値）
        final String playerClass;   // クラス名

        PlayerInfo(int playerId, String playerName, String playerClass) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.playerClass = playerClass;
        }

        @Override
        public String toString() {
            return String.format("PlayerInfo{id=%d, name='%s', class='%s'}",
                               playerId, playerName, playerClass);
        }

        /**
         * 通常表示用フォーマット（名称のみ）
         */
        public String getName() {
            return playerName;
        }

        /**
         * デバッグ表示用フォーマット「名称(ID)」
         */
        public String getDisplayName() {
            return String.format("%s(%d)", playerName, playerId);
        }
    }

    private final Map<Integer, PlayerProxy> processes = new ConcurrentHashMap<>();
    private final Map<Integer, ProcessStats> statistics = new ConcurrentHashMap<>();
    private final Map<Player, PlayerInfo> playerToInfoMap = new ConcurrentHashMap<>(); // インスタンス→情報 マッピング
    private final Map<Integer, Player> idToPlayerMap = new ConcurrentHashMap<>(); // ID→インスタンス マッピング（逆引き用）
    private final ProxyLogger logger = new ProxyLogger("ProcessManager");
    private final Player[] players; // 固定プレイヤーリスト
    private final int maxProcesses; // 最大プロセス数（プレイヤー数）

    public PlayerProcessManager(Player[] players) {
        this.players = players;
        this.maxProcesses = players.length;
        logger.info("PlayerProcessManager initialized for " + maxProcesses + " players");

        // プレイヤー情報（3点セット）を作成してマッピング
        for (int i = 0; i < players.length; i++) {
            int playerId = i;                                   // 一意ID（整数）
            String playerName = players[i].toString();          // 名称
            String playerClass = players[i].getClass().getName(); // クラス名

            PlayerInfo info = new PlayerInfo(playerId, playerName, playerClass);
            playerToInfoMap.put(players[i], info);
            idToPlayerMap.put(playerId, players[i]); // 逆引きマッピング追加

            logger.info("Player[" + i + "]: " + info.getDisplayName() + " class=" + info.playerClass + " @" + System.identityHashCode(players[i]));
        }
    }


    /**
     * 登録プレイヤー検証（整数IDベース）
     */
    private boolean isValidPlayerId(int playerId) {
        return playerId >= 0 && playerId < maxProcesses;
    }

    /**
     * プレイヤーIDから情報を検索
     */
    private PlayerInfo findPlayerInfoById(int playerId) {
        return playerToInfoMap.values().stream()
               .filter(info -> info.playerId == playerId)
               .findFirst()
               .orElse(null);
    }

    /**
     * プレイヤーの通常表示名を取得（名称のみ）
     */
    public String getPlayerName(Player player) {
        PlayerInfo info = playerToInfoMap.get(player);
        return info != null ? info.getName() : player.toString();
    }

    /**
     * プレイヤーのデバッグ表示名を取得（名称(ID)）
     */
    public String getPlayerDisplayName(Player player) {
        PlayerInfo info = playerToInfoMap.get(player);
        return info != null ? info.getDisplayName() : player.toString();
    }
    
    /**
     * プレイヤーIDからクラス名を取得
     */
    public String getPlayerClass(int playerId) {
        PlayerInfo info = findPlayerInfoById(playerId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown player ID: " + playerId);
        }
        return info.playerClass;
    }
    
    /**
     * プレイヤーIDから表示名を取得
     */
    public String getPlayerDisplayName(int playerId) {
        PlayerInfo info = findPlayerInfoById(playerId);
        return info != null ? info.getDisplayName() : "Player" + playerId;
    }
    
    /**
     * プレイヤーインスタンスからIDを取得
     */
    public int getPlayerId(Player player) {
        PlayerInfo info = playerToInfoMap.get(player);
        if (info == null) {
            throw new IllegalArgumentException("Unknown player instance: " + player);
        }
        return info.playerId;
    }

    /**
     * プロセス取得（READY → BUSY）
     * - 利用可能なプロセスを再利用
     * - 自動的にBUSY状態に遷移
     * - 必要に応じて新規プロセス作成
     */
    public synchronized PlayerProxy acquirePlayer(int playerId) {
        if (!isValidPlayerId(playerId)) {
            throw new IllegalArgumentException("Unknown player ID: " + playerId);
        }

        PlayerInfo info = findPlayerInfoById(playerId);
        String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);
        
        // 既存プロセスの再利用チェック
        PlayerProxy existingProxy = processes.get(playerId);
        if (existingProxy != null) {
            logger.debug("Found existing proxy for " + displayName + ", checking availability...");
            if (existingProxy.isAvailable()) {
                logger.info("REUSING existing proxy for " + displayName);
                try {
                    PlayerProxy proxy = existingProxy.acquire(); // READY → BUSY
                    recordProcessEvent(playerId, "PROCESS_REUSED"); // 統計記録
                    logger.info("Successfully reused proxy for " + displayName);
                    return proxy;
                } catch (Exception e) {
                    logger.warn("Failed to reuse existing proxy for " + displayName + ": " + e.getMessage());
                    // 再利用失敗時は削除して新規作成へ進む
                    try {
                        existingProxy.terminate();
                    } catch (Exception te) {
                        logger.warn("Error terminating failed proxy: " + te.getMessage());
                    }
                    processes.remove(playerId);
                }
            } else {
                // プロセスが BUSY 状態 - 他のゲームで使用中
                // これは正常な状況ではないが、デバッグのためログ出力して新規作成
                logger.warn("Existing proxy for " + displayName + " is BUSY - creating new proxy instead of waiting");
                logger.warn("This may indicate a timing issue between release and acquire");
                
                // 既存プロセスはそのまま残し、新規プロセスを作成
                // （Concurrent accessを避けるため、terminateしない）
            }
        } else {
            logger.debug("No existing proxy found for " + displayName + " - will create new proxy");
        }

        // 新規プロセス作成・取得（既存プロセスがない場合のみ）
        // 注意: 既存プロセスがBUSYの場合、ここに到達してはいけない
        if (processes.containsKey(playerId)) {
            throw new IllegalStateException("Cannot create new process for player " + playerId + " - existing process still in map!");
        }
        
        logger.info("Creating NEW proxy for " + displayName);
        PlayerProxy newProxy = createNewProxy(String.valueOf(playerId), info.playerClass);
        processes.put(playerId, newProxy);
        recordProcessEvent(playerId, "PROCESS_CREATED"); // 統計記録
        
        try {
            PlayerProxy proxy = newProxy.acquire(); // READY → BUSY
            logger.info("Successfully acquired NEW proxy for " + displayName);
            return proxy;
        } catch (Exception e) {
            // 取得失敗時はプロセス削除
            processes.remove(playerId);
            throw new RuntimeException("Failed to acquire player " + displayName, e);
        }
    }

    /**
     * プロセス解放（BUSY → READY）
     * - プロセスを再利用可能状態に戻す
     * - エラー発生時は自動的に破棄
     */
    public synchronized void releasePlayer(int playerId) {
        PlayerProxy proxy = processes.get(playerId);
        if (proxy != null) {
            PlayerInfo info = findPlayerInfoById(playerId);
            String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);
            logger.info("RELEASING player: " + displayName);
            try {
                proxy.markCompleted(0); // BUSY → READY
                logger.info("Successfully released player: " + displayName + " (now available for reuse)");
                recordProcessEvent(playerId, "REUSE_READY"); // 統計記録
            } catch (Exception e) {
                // 解放失敗時はプロセス終了
                logger.warn("Error releasing player " + displayName + ", terminating: " + e.getMessage());
                terminatePlayer(playerId);
            }
        } else {
            PlayerInfo info = findPlayerInfoById(playerId);
            String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);
            logger.warn("No proxy found to release for player: " + displayName);
        }
    }

    /**
     * プロセス強制終了（Any → DEAD）
     * - タイムアウトやエラー時の処理
     * - リソースクリーンアップ
     */
    public synchronized void terminatePlayer(int playerId) {
        PlayerProxy proxy = processes.get(playerId);
        if (proxy != null) {
            PlayerInfo info = findPlayerInfoById(playerId);
            String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);
            logger.debug("Terminating player: " + displayName);
            try {
                proxy.terminate();
            } catch (Exception e) {
                logger.warn("Error during termination: " + e.getMessage());
            }
            processes.remove(playerId);
        }
    }

    /**
     * プロセス状態確認（読み取り専用）
     */
    public boolean isPlayerAvailable(int playerId) {
        PlayerProxy proxy = processes.get(playerId);
        return proxy != null && proxy.isAvailable();
    }

    /**
     * ゲーム完了通知（プレイヤーIDベース）- 推奨
     */
    public synchronized void onGameCompleted(int playerId, GameResult result) {
        logger.info("ゲーム完了通知: Player ID=" + playerId + ", timeout=" + result.isTimeout() + ", thinkTime=" + result.getThinkTime() + "ms");
        onGameCompletedById(playerId, result);
    }
    
    /**
     * ゲーム完了通知（プレイヤーインスタンスベース）- レガシー互換
     */
    public synchronized void onGameCompleted(Player player, GameResult result) {
        PlayerInfo info = playerToInfoMap.get(player);
        if (info == null) {
            throw new IllegalArgumentException("Unknown player instance: " + player + " (use player ID instead)");
        }
        onGameCompleted(info.playerId, result);
    }

    /**
     * ゲーム完了通知・再利用判定（整数IDベース）
     */
    private synchronized void onGameCompletedById(int playerId, GameResult result) {
        PlayerProxy proxy = processes.get(playerId);
        if (proxy == null) {
            PlayerInfo info = findPlayerInfoById(playerId);
            String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);
            logger.warn("Proxy not found during completion: " + displayName + " (may have been terminated earlier)");
            return;
        }

        PlayerInfo info = findPlayerInfoById(playerId);
        String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);

        // プロセス状態チェックは削除（BUSY状態で getProxy().isInitialized() は失敗するため）
        // ProxyGame の finally ブロックで releasePlayer() が適切に呼ばれる

        if (result.isTimeout()) {
            // タイムアウト → プロセス終了・削除
            logger.warn("Player " + displayName + " timed out - terminating proxy");
            try {
                proxy.terminate();
            } catch (Exception e) {
                logger.error("Error terminating timed out proxy for " + displayName + ": " + e.getMessage());
            }
            processes.remove(playerId);
            recordProcessEvent(playerId, "TIMEOUT_TERMINATED");

        } else {
            // 正常完了 → 再利用可能状態に
            logger.debug("Player " + displayName + " completed normally - marking for reuse (think time: " + result.getThinkTime() + "ms)");
            try {
                proxy.markCompleted(result.getThinkTime());
                recordProcessEvent(playerId, "REUSE_READY");
            } catch (Exception e) {
                logger.warn("Error marking proxy completion for " + displayName + ": " + e.getMessage() + " - removing from pool");
                processes.remove(playerId);
            }
        }
    }

    /**
     * プロセス統計記録（整数IDベース）
     */
    private void recordProcessEvent(int playerId, String event) {
        ProcessStats stats = statistics.computeIfAbsent(playerId, k -> new ProcessStats());

        switch (event) {
            case "PROCESS_CREATED":
                stats.processCreations++;
                break;
            case "PROCESS_REUSED":
                stats.processReuses++;
                break;
            case "TIMEOUT_TERMINATED":
                stats.timeoutTerminations++;
                break;
            case "REUSE_READY":
                stats.gamesCompleted++;
                break;
        }

        PlayerInfo info = findPlayerInfoById(playerId);
        String displayName = info != null ? info.getDisplayName() : String.valueOf(playerId);
        logger.debug("Process event for " + displayName + ": " + event + " (stats: created=" + stats.processCreations + ", reused=" + stats.processReuses + ", completed=" + stats.gamesCompleted + ")");
    }

    private PlayerProxy createNewProxy(String playerId, String playerClass) {
        try {
            PlayerProxy proxy = new PlayerProxy(playerId, playerClass,
                                              System.getProperty("java.class.path"),
                                              30000L); // 30秒タイムアウト

            // 重要：プロセス作成時に初期化も実行
            // ダミーのInitRequestで初期化（実際のゲーム時に正しい色が設定される）
            InitRequest dummyInit = new InitRequest(
                playerClass,           // プレイヤークラス名
                Color.NONE,           // ダミー色（実ゲーム時に再設定）
                1800000L,             // 全体制限時間(ms) = 30分
                new OfficialBoard()   // 初期ボード状態
            );
            InitResult initResult = proxy.init(dummyInit);

            if (!initResult.isSuccess()) {
                throw new RuntimeException("Proxy initialization failed: " + initResult.getErrorMessage());
            }

            logger.info("Proxy created and initialized successfully for player: " + playerId);
            return proxy;

        } catch (Exception e) {
            logger.error("Failed to create proxy for player: " + playerId, e);
            throw new RuntimeException("Proxy creation failed", e);
        }
    }

    /**
     * プロセス効率統計出力
     */
    public void printEfficiencyReport() {
        int totalProcessStarts = getTotalProcessStarts();
        int totalGames = getTotalGamesPlayed();
        double reuseEfficiency = totalGames > 0 ? 1.0 - (double) totalProcessStarts / totalGames : 0.0;

        System.out.println("\n=== Process Reuse Efficiency ===");
        System.out.printf("Total Games: %d\n", totalGames);
        System.out.printf("Process Starts: %d\n", totalProcessStarts);
        System.out.printf("Reuse Efficiency: %.1f%% (1.0 = perfect reuse)\n",
                         reuseEfficiency * 100);

        // プレイヤー別統計
        if (!statistics.isEmpty()) {
            System.out.println("\nPlayer Statistics:");
            System.out.printf("%-15s %8s %8s %8s %8s %10s\n",
                             "Player", "Created", "Reused", "Games", "Timeouts", "Efficiency");
            System.out.println("=".repeat(75));

            statistics.forEach((playerId, stats) -> {
                PlayerInfo info = findPlayerInfoById(playerId);
                String playerName = info != null ? info.getName() : "Player" + playerId;
                double playerEfficiency = stats.gamesCompleted > 0 ?
                    1.0 - (double) stats.processCreations / stats.gamesCompleted : 0.0;

                System.out.printf("%-15s %8d %8d %8d %8d %9.1f%%\n",
                                 playerName, stats.processCreations, stats.processReuses,
                                 stats.gamesCompleted, stats.timeoutTerminations,
                                 playerEfficiency * 100);
            });
        }
    }

    /**
     * 現在のプロセス状況表示
     */
    public void printCurrentStatus() {
        int availableProcesses = (int) processes.values().stream().filter(PlayerProxy::isAvailable).count();
        int terminatedProcesses = processes.size() - availableProcesses;

        System.out.printf("Process Status: %d available, %d terminated (total: %d, max players: %d)\n",
                         availableProcesses, terminatedProcesses, processes.size(), maxProcesses);

        if (processes.size() > maxProcesses) {
            System.out.println("⚠️  WARNING: Process count exceeds player count!");
        }
    }

    private int getTotalProcessStarts() {
        return statistics.values().stream().mapToInt(s -> s.processCreations).sum();
    }

    private int getTotalGamesPlayed() {
        return statistics.values().stream().mapToInt(s -> s.gamesCompleted).sum();
    }

    /**
     * 全プロセス終了（League終了時）
     */
    public void terminateAllProcesses() {
        logger.info("Terminating all player processes");

        processes.values().forEach(process -> {
            try {
                process.terminate();
            } catch (Exception e) {
                logger.warn("Error terminating process: " + e.getMessage());
            }
        });

        processes.clear();
        logger.info("All player processes terminated");
    }

    /**
     * プロセス統計データ
     */
    static class ProcessStats {
        int processCreations = 0;
        int processReuses = 0;
        int gamesCompleted = 0;
        int timeoutTerminations = 0;
    }
}
