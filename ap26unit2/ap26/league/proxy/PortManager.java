package ap26.league.proxy;

import java.io.*;
import java.net.*;

/**
 * PortManager - リングバッファ方式ポート管理
 *
 * 設計思想：
 * - 十分な範囲のポート番号（20000-20999）をリングバッファでローテーション
 * - 占有ポートは単純にスキップして次のポートを試行
 * - プール管理を廃止し、動的割り当てのみで簡素化
 */
public class PortManager {
    private static final int BASE_PORT = 20000;
    private static final int PORT_RANGE = 1000; // 20000-20999
    private static final ProxyLogger logger = new ProxyLogger("PortManager");

    // リングバッファ管理
    private static int currentPort = BASE_PORT;
    private static volatile boolean initialized = false;

    /**
     * リングバッファ初期化（簡素化）
     */
    public static synchronized void initializePool(int concurrency) throws PortPoolException {
        if (initialized) {
            throw new PortPoolException("Port manager already initialized");
        }

        initialized = true;
        logger.info(
                "Ring buffer port manager initialized. Port range: " + BASE_PORT + "-" + (BASE_PORT + PORT_RANGE - 1));
    }

    /**
     * ポートを借用
     * @return 利用可能なポート番号
     */
    public static synchronized int borrowPort() throws PortPoolException {
        if (!initialized) {
            throw new PortPoolException("Port manager not initialized");
        }

        int attempts = 0;
        int maxAttempts = PORT_RANGE; // 最大で全範囲を試行

        while (attempts < maxAttempts) {
            if (isPortAvailable(currentPort)) {
                int assignedPort = currentPort;
                // リングバッファ: 次のポートに進む（範囲を超えたら先頭に戻る）
                currentPort = ((currentPort - BASE_PORT + 1) % PORT_RANGE) + BASE_PORT;

                logger.debug("Assigned port " + assignedPort + " (next: " + currentPort + ")");
                return assignedPort;
            } else {
                logger.debug("Skipping occupied port " + currentPort);
                // 占有ポートをスキップして次へ
                currentPort = ((currentPort - BASE_PORT + 1) % PORT_RANGE) + BASE_PORT;
                attempts++;
            }
        }

        throw new PortPoolException("No available ports found in range " + BASE_PORT + "-"
                + (BASE_PORT + PORT_RANGE - 1) + " after " + attempts + " attempts");
    }

    /**
     * ポート返却（リングバッファ方式では実質不要）
     * @param port 返却するポート番号
     */
    public static void returnPort(int port) throws PortPoolException {
        if (!initialized) {
            throw new PortPoolException("Port manager not initialized");
        }

        // リングバッファ方式では特別な返却処理は不要
        // ポートは自動的にローテーションで再利用される
        logger.debug("Port " + port + " released (will be reused automatically in ring buffer)");
    }

    /**
     * ポートが利用可能かチェック
     */
    private static boolean isPortAvailable(int port) {
        try (ServerSocket testSocket = new ServerSocket()) {
            testSocket.setReuseAddress(true);
            testSocket.bind(new InetSocketAddress("localhost", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * ポートプール例外
     */
    public static class PortPoolException extends Exception {
        public PortPoolException(String message) {
            super(message);
        }

        public PortPoolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
