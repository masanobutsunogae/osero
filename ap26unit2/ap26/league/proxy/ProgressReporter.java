package ap26.league.proxy;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 進捗報告統合管理（従来のLeague進捗機能を統合）
 *
 * 責務:
 * - ゲーム・マッチ進捗追跡
 * - 定期進捗報告（3分間隔）
 * - プロセス数監視
 * - ポートプール状況報告
 *
 * 設計:
 * - League.javaの進捗機能を統合
 * - 並行アクセス安全
 * - リアルタイム進捗更新
 */
public class ProgressReporter {

    // 進捗追跡
    private int totalMatches;
    private int totalGames;
    private final AtomicInteger completedMatches = new AtomicInteger(0);
    private final AtomicInteger completedGames = new AtomicInteger(0);

    // 時間管理
    private long leagueStartTime = 0;
    private long lastReportedTime = 0;
    private final long REPORT_INTERVAL_MS = 3 * 60 * 1000; // 3分間隔

    // 設定
    private final int parallelism;
    private final ProxyLogger logger;

    public ProgressReporter(int parallelism) {
        this.parallelism = parallelism;
        this.logger = new ProxyLogger("ProgressReporter");
    }

    /**
     * リーグ開始時の初期化
     */
    public void initialize(int totalMatches, int totalGames) {
        this.totalMatches = totalMatches;
        this.totalGames = totalGames;
        this.leagueStartTime = System.currentTimeMillis();
        this.lastReportedTime = this.leagueStartTime;

        completedMatches.set(0);
        completedGames.set(0);

        logger.info("Progress tracking initialized: " + totalMatches + " matches, " + totalGames + " games");

        // 初期状況報告
        System.out.println("=== LEAGUE START ===");
        System.out.println("Total matches: " + totalMatches);
        System.out.println("Total games: " + totalGames);
        System.out.println("Progress reporting: Every 3 minutes");
        System.out.println("Parallelism: " + parallelism);
        System.out.println("===================");
    }

    /**
     * ゲーム完了時の進捗更新
     */
    public void updateGameProgress() {
        int completed = completedGames.incrementAndGet();
        logger.debug("Game completed: " + completed + "/" + totalGames);

        // ゲーム完了では報告しない（時間間隔またはマッチ完了時のみ）
    }

    /**
     * マッチ完了時の進捗更新・報告
     */
    public synchronized void updateMatchProgress() {
        int completed = completedMatches.incrementAndGet();
        logger.info("Match completed: " + completed + "/" + totalMatches);

        // マッチ完了時は必ず進捗報告
        reportProgress(true);
    }

    /**
     * 定期進捗報告（時間間隔ベース）
     */
    public void checkPeriodicReport() {
        long currentTime = System.currentTimeMillis();
        long elapsedSinceLastReport = currentTime - lastReportedTime;

        // 3分間隔での報告チェック
        if (elapsedSinceLastReport >= REPORT_INTERVAL_MS) {
            reportProgress(false);
        }
    }

    /**
     * 進捗報告実行
     */
    private void reportProgress(boolean isMatchCompletion) {
        long currentTime = System.currentTimeMillis();

        int currentMatches = completedMatches.get();
        int currentGames = completedGames.get();

        // 最終完了チェック
        boolean isFinalCompletion = (currentMatches == totalMatches);

        // 報告条件チェック
        if (!isMatchCompletion && !isFinalCompletion) {
            long elapsedSinceLastReport = currentTime - lastReportedTime;
            if (elapsedSinceLastReport < REPORT_INTERVAL_MS) {
                return; // まだ報告時間ではない
            }
        }

        // 進捗計算
        double matchProgressPercent = totalMatches > 0 ? (double) currentMatches / totalMatches * 100 : 0.0;
        double gameProgressPercent = totalGames > 0 ? (double) currentGames / totalGames * 100 : 0.0;
        long totalElapsedMinutes = (currentTime - leagueStartTime) / (60 * 1000);

        // 進捗報告出力
        String reportType = isFinalCompletion ? "FINAL" : (isMatchCompletion ? "MATCH" : "PERIODIC");
        System.out.printf("[%s] Progress: Match %d/%d (%.1f%%) - Game %d/%d (%.1f%%) - %d minutes elapsed\\n",
                reportType, currentMatches, totalMatches, matchProgressPercent,
                currentGames, totalGames, gameProgressPercent, totalElapsedMinutes);

        // 付加情報報告
        reportAdditionalInfo();

        // 最終完了以外は最終報告時刻を更新
        if (!isFinalCompletion) {
            lastReportedTime = currentTime;
        }

        logger.info("Progress reported: " + reportType + " - " + currentMatches + "/" + totalMatches + " matches");
    }

    /**
     * 付加情報報告（ポートプール・プロセス数）
     */
    private void reportAdditionalInfo() {
        try {
            // ポートプール状況
            System.out.println("Port pool status: Ring buffer port management active");

            // プロセス数監視
            checkProcessCount();

        } catch (Exception e) {
            logger.warn("Failed to report additional info: " + e.getMessage());
        }
    }

    /**
     * プロセス数監視
     */
    private void checkProcessCount() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pgrep", "-f", "ap26.league.proxy.PlayerMain");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                long processCount = reader.lines().count();
                int expectedConcurrent = parallelism * 2; // 最大同時プロセス数
                int warningThreshold = expectedConcurrent + 2; // より厳しい警告閾値

                if (processCount > warningThreshold) {
                    System.out.println("⚠️  WARNING: Excessive process count detected: " + processCount
                            + " processes (expected concurrent: " + expectedConcurrent + ", warning threshold: " + warningThreshold + ")");
                    logger.warn("Excessive process count: " + processCount + " PlayerMain processes running (threshold: " + warningThreshold + ")");
                } else if (processCount > expectedConcurrent) {
                    System.out.println("ℹ️ Process count: " + processCount + " PlayerMain processes (above concurrent limit " + expectedConcurrent + " due to process reuse)");
                    logger.info("Process count above concurrent limit due to reuse: " + processCount + " processes");
                } else if (processCount > 0) {
                    System.out.println("✓ Process count: " + processCount + " PlayerMain processes (within concurrent limit)");
                } else {
                    System.out.println("ℹ️ Process count: 0 PlayerMain processes (idle)");
                }
            }

            process.waitFor(5, TimeUnit.SECONDS); // 5秒でタイムアウト

        } catch (Exception e) {
            logger.warn("Failed to check process count: " + e.getMessage());
        }
    }

    /**
     * 最終報告
     */
    public void printFinalReport() {
        System.out.println("\\n=== PROGRESS FINAL REPORT ===");

        int finalMatches = completedMatches.get();
        int finalGames = completedGames.get();
        long totalDuration = System.currentTimeMillis() - leagueStartTime;

        System.out.printf("Completed: %d/%d matches (100.0%%) - %d/%d games (100.0%%)\\n",
                finalMatches, totalMatches, finalGames, totalGames);
        System.out.printf("Total duration: %.2f minutes\\n", totalDuration / 60000.0);

        if (finalMatches == totalMatches && finalGames == totalGames) {
            System.out.println("✅ All matches and games completed successfully");
        } else {
            System.out.println("⚠️  Incomplete execution detected");
            System.out.printf("   Missing matches: %d, Missing games: %d\\n",
                    totalMatches - finalMatches, totalGames - finalGames);
        }

        System.out.println("============================");
        logger.info("Final progress report completed");
    }

    /**
     * 現在の進捗状況取得
     */
    public ProgressStatus getStatus() {
        return new ProgressStatus(
                completedMatches.get(), totalMatches,
                completedGames.get(), totalGames,
                System.currentTimeMillis() - leagueStartTime);
    }

    /**
     * 進捗状況データ
     */
    public static class ProgressStatus {
        public final int completedMatches;
        public final int totalMatches;
        public final int completedGames;
        public final int totalGames;
        public final long elapsedTimeMs;

        public ProgressStatus(int completedMatches, int totalMatches,
                int completedGames, int totalGames, long elapsedTimeMs) {
            this.completedMatches = completedMatches;
            this.totalMatches = totalMatches;
            this.completedGames = completedGames;
            this.totalGames = totalGames;
            this.elapsedTimeMs = elapsedTimeMs;
        }

        public double getMatchProgressPercent() {
            return totalMatches > 0 ? (double) completedMatches / totalMatches * 100 : 0.0;
        }

        public double getGameProgressPercent() {
            return totalGames > 0 ? (double) completedGames / totalGames * 100 : 0.0;
        }

        public long getElapsedMinutes() {
            return elapsedTimeMs / (60 * 1000);
        }

        @Override
        public String toString() {
            return String.format("ProgressStatus{matches=%d/%d(%.1f%%), games=%d/%d(%.1f%%), elapsed=%dmin}",
                    completedMatches, totalMatches, getMatchProgressPercent(),
                    completedGames, totalGames, getGameProgressPercent(), getElapsedMinutes());
        }
    }
}
