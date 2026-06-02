package ap26.league.proxy;

/**
 * プレイヤープロセス状態管理
 *
 * 責務:
 * - 個別プロセスの状態追跡
 * - 再利用可能性の判定
 * - プロセス使用統計の記録
 */
public class PlayerProcess {
    public enum State {
        READY,     // 使用可能
        BUSY,      // ゲーム実行中
        ERROR,     // 通信エラー（復旧可能）
        DEAD       // 終了済み（復旧不可）
    }

    private final PlayerProxy proxy;
    private final String playerId;
    private State state = State.READY;
    private int gamesPlayed = 0;
    private long totalThinkTime = 0;
    private final long createdTime;
    private final ProxyLogger logger;

    public PlayerProcess(PlayerProxy proxy) {
        this.proxy = proxy;
        this.playerId = proxy.getPlayerId();
        this.createdTime = System.currentTimeMillis();
        this.logger = new ProxyLogger("PlayerProcess-" + playerId);

        logger.debug("PlayerProcess created for " + playerId);
    }

    /**
     * プロキシ取得（再利用時）- READY → BUSY
     */
    public synchronized PlayerProxy getProxy() {
        if (state != State.READY) {
            throw new IllegalStateException("Process not ready for use (state=" + state + "): " + playerId);
        }

        // READY → BUSY
        state = State.BUSY;
        logger.debug("State transition: READY -> BUSY (games played: " + gamesPlayed + ")");
        return proxy;
    }

    /**
     * ゲーム完了マーク（再利用準備）- BUSY → READY
     */
    public synchronized void markCompleted(long thinkTime) {
        State oldState = state;
        if (state == State.DEAD) {
            logger.warn("Attempting to mark completed on dead process: " + playerId);
            return;
        }

        if (state == State.READY) {
            logger.warn("Process already marked as completed: " + playerId + " (ignoring duplicate completion)");
            return;
        }

        if (state != State.BUSY) {
            logger.warn("Unexpected state during completion: " + state + " for " + playerId + " (forcing transition to READY)");
        }

        this.gamesPlayed++;
        this.totalThinkTime += thinkTime;
        
        // BUSY → READY (正常完了)
        this.state = State.READY;
        
        logger.info("State transition: " + oldState + " -> READY (games=" + gamesPlayed + 
                    ", avg_think=" + (gamesPlayed > 0 ? totalThinkTime / gamesPlayed : 0) + "ms) for " + playerId);
    }

    /**
     * プロセス終了 - Any → DEAD
     */
    public void terminate() {
        if (state == State.DEAD) {
            logger.debug("Process already dead: " + playerId);
            return;
        }

        State oldState = state;
        logger.info("Terminating process: " + playerId + " (state: " + oldState + ", games: " + gamesPlayed + ")");

        try {
            proxy.terminate();
            logger.debug("PlayerProxy.terminate() completed for " + playerId);
        } catch (Exception e) {
            logger.warn("Error during proxy termination for " + playerId + ": " + e.getMessage());
        } finally {
            // Any → DEAD
            this.state = State.DEAD;
            logger.debug("State transition: " + oldState + " -> DEAD for " + playerId);
        }
    }

    /**
     * 再利用可能判定
     */
    public boolean isAvailable() {
        boolean ready = (state == State.READY);
        logger.debug("Current state: " + state + " (isAvailable=" + ready + ")");
        return ready;
    }
    
    /**
     * エラー状態への遷移 - BUSY/READY → ERROR
     */
    public synchronized void markError(String reason) {
        if (state == State.DEAD) {
            logger.debug("Cannot mark error on dead process: " + playerId);
            return;
        }
        
        State oldState = state;
        state = State.ERROR;
        logger.warn("State transition: " + oldState + " -> ERROR (" + reason + ") for " + playerId);
    }
    
    /**
     * エラーからの復旧 - ERROR → READY
     */
    public synchronized boolean recoverFromError() {
        if (state != State.ERROR) {
            logger.warn("Cannot recover from non-error state: " + state + " for " + playerId);
            return false;
        }
        
        state = State.READY;
        logger.info("State transition: ERROR -> READY (recovered) for " + playerId);
        return true;
    }

    /**
     * 実行ゲーム数取得
     */
    public int getGamesPlayed() {
        return gamesPlayed;
    }

    /**
     * 平均思考時間取得（ミリ秒）
     */
    public long getAverageThinkTime() {
        return gamesPlayed > 0 ? totalThinkTime / gamesPlayed : 0;
    }

    /**
     * プロセス稼働時間取得（ミリ秒）
     */
    public long getUptimeMs() {
        return System.currentTimeMillis() - createdTime;
    }

    /**
     * プロセス効率指標取得
     */
    public double getProcessEfficiency() {
        // ゲーム数 / 稼働時間（分） = ゲーム/分
        long uptimeMinutes = getUptimeMs() / (60 * 1000);
        return uptimeMinutes > 0 ? (double) gamesPlayed / uptimeMinutes : 0.0;
    }

    @Override
    public String toString() {
        return String.format("PlayerProcess{playerId='%s', state=%s, games=%d, avgThink=%dms, uptime=%dms}",
                playerId, state, gamesPlayed, getAverageThinkTime(), getUptimeMs());
    }
}
