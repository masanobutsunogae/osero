package ap26.league.proxy;

/**
 * プロセス状態管理クラス
 * 
 * 責務:
 * - プロセスライフサイクル状態の管理
 * - 統計情報の追跡
 * - 状態遷移の制御
 * 
 * PlayerProxyの肥大化防止のため分離
 */
public class ProcessState {
    public enum State {
        READY,     // 使用可能
        BUSY,      // ゲーム実行中
        ERROR,     // 通信エラー（復旧可能）
        DEAD       // 終了済み（復旧不可）
    }
    
    private State state = State.READY;
    private int gamesPlayed = 0;
    private long totalThinkTime = 0;
    private final long createdTime;
    private final String playerId;
    private final ProxyLogger logger;
    
    public ProcessState(String playerId) {
        this.playerId = playerId;
        this.createdTime = System.currentTimeMillis();
        this.logger = new ProxyLogger("ProcessState-" + playerId);
        logger.debug("ProcessState created for " + playerId);
    }
    
    /**
     * 使用開始（READY → BUSY）
     */
    public synchronized boolean acquire() {
        if (state != State.READY) {
            logger.warn("Cannot acquire - current state: " + state);
            return false;
        }
        
        state = State.BUSY;
        logger.debug("State transition: READY -> BUSY (games played: " + gamesPlayed + ")");
        return true;
    }
    
    /**
     * 使用終了（BUSY → READY）
     */
    public synchronized void release(long thinkTime) {
        State oldState = state;
        if (state == State.DEAD) {
            logger.warn("Attempting to release dead process");
            return;
        }
        
        if (state == State.READY) {
            logger.warn("Process already released (ignoring duplicate release)");
            return;
        }
        
        if (state != State.BUSY) {
            logger.warn("Unexpected state during release: " + state + " (forcing transition to READY)");
        }
        
        this.gamesPlayed++;
        this.totalThinkTime += thinkTime;
        this.state = State.READY;
        
        logger.info("State transition: " + oldState + " -> READY (games=" + gamesPlayed + 
                    ", avg_think=" + (gamesPlayed > 0 ? totalThinkTime / gamesPlayed : 0) + "ms)");
    }
    
    /**
     * プロセス終了（Any → DEAD）
     */
    public synchronized void terminate() {
        if (state == State.DEAD) {
            logger.debug("Process already dead");
            return;
        }
        
        State oldState = state;
        state = State.DEAD;
        logger.info("State transition: " + oldState + " -> DEAD (games: " + gamesPlayed + ")");
    }
    
    /**
     * エラー状態設定（BUSY/READY → ERROR）
     */
    public synchronized void markError(String reason) {
        if (state == State.DEAD) {
            logger.debug("Cannot mark error on dead process");
            return;
        }
        
        State oldState = state;
        state = State.ERROR;
        logger.warn("State transition: " + oldState + " -> ERROR (" + reason + ")");
    }
    
    /**
     * 利用可能判定
     */
    public boolean isAvailable() {
        return state == State.READY;
    }
    
    /**
     * 現在の状態取得
     */
    public State getState() {
        return state;
    }
    
    /**
     * 統計情報取得
     */
    public int getGamesPlayed() {
        return gamesPlayed;
    }
    
    public long getAverageThinkTime() {
        return gamesPlayed > 0 ? totalThinkTime / gamesPlayed : 0;
    }
    
    public long getUptimeMs() {
        return System.currentTimeMillis() - createdTime;
    }
    
    public double getProcessEfficiency() {
        long uptimeMinutes = getUptimeMs() / (60 * 1000);
        return uptimeMinutes > 0 ? (double) gamesPlayed / uptimeMinutes : 0.0;
    }
    
    @Override
    public String toString() {
        return String.format("ProcessState{playerId='%s', state=%s, games=%d, avgThink=%dms, uptime=%dms}",
                playerId, state, gamesPlayed, getAverageThinkTime(), getUptimeMs());
    }
}