package ap26.league.proxy;

import ap26.*;
import ap26.league.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * ゲーム結果統計管理（従来のMatch.Stat機能を統合）
 *
 * 責務:
 * - ゲーム結果統計収集
 * - プレイヤーランキング生成
 * - 最終結果出力
 *
 * 設計:
 * - Match.Statクラスの機能を統合
 * - 並行アクセス安全
 * - リアルタイム統計更新
 */
public class GameStatistics {
    private final Map<Integer, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private final AtomicInteger completedGames = new AtomicInteger(0);
    private final ProxyLogger logger = new ProxyLogger("GameStats");
    private PlayerProcessManager processManager; // Player ID → 名前変換用

    /**
     * PlayerProcessManager設定（Player ID → 名前変換用）
     */
    public void setProcessManager(PlayerProcessManager processManager) {
        this.processManager = processManager;
    }

    /**
     * ゲーム結果記録（Player IDベース）
     */
    public void recordGameResult(ProxyGame game) {
        if (processManager == null) {
            logger.warn("ProcessManager not set - using fallback player identification");
            recordGameResultFallback(game);
            return;
        }

        // Player IDベースで取得
        int blackId = processManager.getPlayerId(game.getBlackPlayer());
        int whiteId = processManager.getPlayerId(game.getWhitePlayer());

        // ゲーム結果解析
        var score = game.getBoard().score();
        Move lastMove = getLastMove(game);
        Map<Color, Float> times = game.getTimes();
        long blackTime = (long) (times.get(Color.BLACK) * 1000); // 秒→ミリ秒
        long whiteTime = (long) (times.get(Color.WHITE) * 1000);

        // 各プレイヤーの統計更新（Player IDベース）
        updatePlayerStats(blackId, score, lastMove, blackTime);
        updatePlayerStats(whiteId, -score, lastMove, whiteTime);

        int completed = completedGames.incrementAndGet();
        logger.debug("Recorded game result: " + blackId + " vs " + whiteId + " (total: " + completed + ")");
    }

    /**
     * フォールバック用ゲーム結果記録（従来のString IDベース）
     */
    private void recordGameResultFallback(ProxyGame game) {
        String blackId = game.getBlackPlayer().toString();
        String whiteId = game.getWhitePlayer().toString();

        // プレイヤー名をInteger IDに変換（フォールバック）
        int blackIdInt = blackId.hashCode() % 1000; // 簡易変換
        int whiteIdInt = whiteId.hashCode() % 1000;

        // ゲーム結果解析
        var score = game.getBoard().score();
        Move lastMove = getLastMove(game);
        Map<Color, Float> times = game.getTimes();
        long blackTime = (long) (times.get(Color.BLACK) * 1000);
        long whiteTime = (long) (times.get(Color.WHITE) * 1000);

        // 統計更新
        updatePlayerStats(blackIdInt, score, lastMove, blackTime);
        updatePlayerStats(whiteIdInt, -score, lastMove, whiteTime);

        int completed = completedGames.incrementAndGet();
        logger.debug("Recorded game result (fallback): " + blackId + " vs " + whiteId + " (total: " + completed + ")");
    }

    /**
     * プレイヤー統計更新（Player IDベース）
     *
     * 勝ち点計算 (2026 年度レギュレーション 標準版):
     *   - 勝ち:           10 + min(石差, 10)  (最小 10、最大 20)
     *   - 引き分け:        5
     *   - 負け:            0
     *   - 即時負け (失格): 0 (相手側は 20 を獲得)
     *
     * 即時負けの判定は lastMove に isTimeout / isIllegal / isError のいずれかが
     * 立っているかで行う。失格者側の score は -36 (相手が全マス支配) として
     * 渡されてくるため、相手側の +36 が自動的に上限 20 にクランプされる。
     */
    private void updatePlayerStats(int playerId, int score, Move lastMove, long thinkTime) {
        PlayerStats stats = playerStats.computeIfAbsent(playerId, k -> new PlayerStats());

        synchronized (stats) {
            if (score > 0) {
                stats.wins++;
                // 勝ち: 基礎点 10 + 石差ボーナス (上限 10)
                int bonus = Math.min(score, 10);
                stats.totalScore += 10 + bonus;
            } else if (score < 0) {
                stats.losses++;
                // 負け: 勝ち点 0。失格種別は別途カウント
                if (lastMove != null) {
                    if (lastMove.isTimeout())
                        stats.timeouts++;
                    if (lastMove.isIllegal())
                        stats.illegals++;
                    if (lastMove.isError())
                        stats.errors++;
                }
            } else {
                stats.draws++;
                // 引き分け: 5 点 (旧版は 1 点だった)
                stats.totalScore += 5;
            }

            stats.totalGames++;
            stats.totalThinkTime += thinkTime;
        }
    }

    /**
     * 最終結果・ランキング出力（Player IDベース集計、プレイヤー名表示）
     */
    public void printFinalResults() {
        System.out.println("\\n=== FINAL RESULTS ===");

        // プレイヤーを得点順でソート（Player IDベース）
        List<Map.Entry<Integer, PlayerStats>> ranking = playerStats.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().totalScore, e1.getValue().totalScore))
                .collect(Collectors.toList());

        System.out.printf("%-15s %6s %6s %6s %6s %8s %8s %8s %8s%n",
                "Player", "Games", "Wins", "Draws", "Losses", "Score", "Timeouts", "Illegals", "AvgTime");
        System.out.println("=".repeat(95));

        for (var entry : ranking) {
            int playerId = entry.getKey();
            PlayerStats stats = entry.getValue();
            double avgTime = stats.totalGames > 0 ? (double) stats.totalThinkTime / stats.totalGames / 1000.0 : 0.0;

            // 表示用プレイヤー名を取得（Player ID → 名前変換）
            String displayName = getPlayerDisplayName(playerId);

            System.out.printf("%-15s %6d %6d %6d %6d %8d %8d %8d %8.2fs%n",
                    displayName, stats.totalGames, stats.wins, stats.draws, stats.losses,
                    stats.totalScore, stats.timeouts, stats.illegals, avgTime);
        }

        System.out.println("=".repeat(95));
        System.out.println("Total games completed: " + completedGames.get());
    }

    /**
     * Player ID → 表示名変換
     */
    private String getPlayerDisplayName(int playerId) {
        if (processManager != null) {
            try {
                return processManager.getPlayerDisplayName(playerId);
            } catch (Exception e) {
                logger.warn("Failed to get display name for player ID " + playerId + ": " + e.getMessage());
            }
        }
        return "Player" + playerId; // フォールバック
    }

    /**
     * 現在の統計サマリー取得
     */
    public GameStatisticsSummary getSummary() {
        return new GameStatisticsSummary(
                completedGames.get(),
                playerStats.size(),
                playerStats.values().stream().mapToInt(s -> s.wins).sum(),
                playerStats.values().stream().mapToInt(s -> s.draws).sum(),
                playerStats.values().stream().mapToInt(s -> s.losses).sum(),
                playerStats.values().stream().mapToInt(s -> s.timeouts).sum());
    }

    /**
     * 特定プレイヤーの統計取得（Player IDベース）
     */
    public PlayerStats getPlayerStats(int playerId) {
        PlayerStats stats = playerStats.get(playerId);
        return stats != null ? stats.copy() : new PlayerStats();
    }

    /**
     * 全プレイヤーの統計取得（防御的コピー、Player IDベース）
     */
    public Map<Integer, PlayerStats> getAllPlayerStats() {
        Map<Integer, PlayerStats> result = new HashMap<>();
        playerStats.forEach((playerId, stats) -> result.put(playerId, stats.copy()));
        return result;
    }

    /**
     * 最後の手を取得
     */
    private Move getLastMove(ProxyGame game) {
        List<Move> moves = game.getMoves();
        if (moves.isEmpty())
            return null;
        return moves.get(moves.size() - 1);
    }

    /**
     * 統計リセット（テスト用）
     */
    public void reset() {
        playerStats.clear();
        completedGames.set(0);
        logger.debug("Statistics reset");
    }

    /**
     * プレイヤー統計データ
     */
    public static class PlayerStats {
        public int totalGames = 0;
        public int wins = 0, draws = 0, losses = 0;
        public int totalScore = 0;
        public int timeouts = 0, illegals = 0, errors = 0;
        public long totalThinkTime = 0;

        /**
         * 防御的コピー作成
         */
        public PlayerStats copy() {
            PlayerStats copy = new PlayerStats();
            copy.totalGames = this.totalGames;
            copy.wins = this.wins;
            copy.draws = this.draws;
            copy.losses = this.losses;
            copy.totalScore = this.totalScore;
            copy.timeouts = this.timeouts;
            copy.illegals = this.illegals;
            copy.errors = this.errors;
            copy.totalThinkTime = this.totalThinkTime;
            return copy;
        }

        /**
         * 平均思考時間（秒）
         */
        public double getAverageThinkTime() {
            return totalGames > 0 ? (double) totalThinkTime / totalGames / 1000.0 : 0.0;
        }

        /**
         * 勝率
         */
        public double getWinRate() {
            return totalGames > 0 ? (double) wins / totalGames : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "PlayerStats{games=%d, wins=%d, draws=%d, losses=%d, score=%d, timeouts=%d, avgTime=%.2fs}",
                    totalGames, wins, draws, losses, totalScore, timeouts, getAverageThinkTime());
        }
    }

    /**
     * 統計サマリーデータ
     */
    public static class GameStatisticsSummary {
        public final int totalGames;
        public final int totalPlayers;
        public final int totalWins;
        public final int totalDraws;
        public final int totalLosses;
        public final int totalTimeouts;

        public GameStatisticsSummary(int totalGames, int totalPlayers, int totalWins,
                int totalDraws, int totalLosses, int totalTimeouts) {
            this.totalGames = totalGames;
            this.totalPlayers = totalPlayers;
            this.totalWins = totalWins;
            this.totalDraws = totalDraws;
            this.totalLosses = totalLosses;
            this.totalTimeouts = totalTimeouts;
        }

        @Override
        public String toString() {
            return String.format("Summary{games=%d, players=%d, wins=%d, draws=%d, losses=%d, timeouts=%d}",
                    totalGames, totalPlayers, totalWins, totalDraws, totalLosses, totalTimeouts);
        }
    }
}
