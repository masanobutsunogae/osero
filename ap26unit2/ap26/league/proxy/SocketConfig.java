package ap26.league.proxy;

/**
 * Socket通信設定定数
 *
 * delta=0.5秒を基準とした統一的なSocket設定値を管理
 */
public class SocketConfig {

    /**
     * Delta値（秒）- 基準となるマージン時間
     */
    public static final double DELTA_SECONDS = 0.5;

    /**
     * Delta値（ミリ秒）
     */
    public static final int DELTA_MS = (int) (DELTA_SECONDS * 1000);

    /**
     * サーバのクライアント通信タイムアウト計算
     * = 残り持ち時間 + delta秒
     */
    public static int calculateClientTimeout(long remainingTimeMs) {
        return (int) (remainingTimeMs + DELTA_MS);
    }

    /**
     * SO_LINGER設定値計算（秒単位）
     * = 2×残り持ち時間 + delta秒
     */
    public static int calculateLingerTimeout(long remainingTimeMs) {
        return (int) ((2 * remainingTimeMs + DELTA_MS) / 1000);
    }

    /**
     * SO_LINGER設定値計算（ミリ秒単位）
     * = 2×残り持ち時間 + delta秒
     */
    public static long calculateLingerTimeoutMs(long remainingTimeMs) {
        return 2 * remainingTimeMs + DELTA_MS;
    }

    /**
     * サーバソケットプール数計算
     * SO_LINGERの最悪ケースを考慮：
     * - 同時実行数 × 2（黒白） × 2（異常終了リスク倍率）
     */
    public static int calculateSocketPoolSize(int parallelism) {
        return parallelism * 2 * 2; // 同時実行数 × 黒白 × 異常終了対策倍率
    }

    /**
     * 設定値の説明文字列生成
     */
    public static String formatSocketSettings(long remainingTimeMs) {
        return String.format(
                "SocketConfig: remaining=%dms, clientTimeout=%dms, lingerTimeout=%ds",
                remainingTimeMs,
                calculateClientTimeout(remainingTimeMs),
                calculateLingerTimeout(remainingTimeMs));
    }
}
