package ap26.league.proxy;

import ap26.*;
import java.io.*;

/**
 * {@link MoveRequest} への応答。{@link PlayerMain} → {@link PlayerProxy} に返す。
 *
 * <p>含まれる情報:
 * <ul>
 *   <li>{@code move}: プレイヤーが選んだ手 (失敗時は null)</li>
 *   <li>{@code thinkTimeMs}: プレイヤープロセス側で計測した思考時間 (ms)</li>
 *   <li>{@code success}: think 呼び出しが正常終了したか</li>
 *   <li>{@code errorMessage}: 失敗時のエラー文 (例外メッセージ)</li>
 * </ul>
 *
 * <p>think で例外が発生した場合は {@code success = false} と
 * {@code move = Move.ofError(color)} が設定される。
 */
public class MoveResult implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Move move;
    private final long thinkTimeMs;
    private final boolean success;
    private final String errorMessage;

    public MoveResult(Move move, long thinkTimeMs, boolean success, String errorMessage) {
        this.move = move;
        this.thinkTimeMs = thinkTimeMs;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static MoveResult success(Move move, long thinkTimeMs) {
        return new MoveResult(move, thinkTimeMs, true, null);
    }

    public static MoveResult error(String errorMessage) {
        return new MoveResult(null, 0, false, errorMessage);
    }

    public Move getMove() {
        return move;
    }

    public long getThinkTimeMs() {
        return thinkTimeMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isTimeout() {
        return "TIMEOUT".equals(errorMessage);
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("MoveResult{success=true, move=%s, thinkTimeMs=%d}",
                    move, thinkTimeMs);
        } else if (isTimeout()) {
            return String.format("MoveResult{timeout=true, thinkTimeMs=%d}", thinkTimeMs);
        } else {
            return String.format("MoveResult{success=false, errorMessage='%s'}", errorMessage);
        }
    }
}
