package ap26.league.proxy;

import java.io.*;

/**
 * {@link GameEndNotification} への確認応答 (ack)。{@link PlayerMain} → {@link PlayerProxy}。
 *
 * <p>正常に通知を受信した場合は {@code acknowledged = true}、後処理で失敗した場合は
 * {@code false} とエラーメッセージを返す。
 */
public class GameEndResult implements Serializable {
    private static final long serialVersionUID = 1L;
    private final boolean acknowledged;
    private final String errorMessage;

    public GameEndResult(boolean acknowledged, String errorMessage) {
        this.acknowledged = acknowledged;
        this.errorMessage = errorMessage;
    }

    public static GameEndResult acknowledged() {
        return new GameEndResult(true, null);
    }

    public static GameEndResult failed() {
        return new GameEndResult(false, "Game end failed");
    }

    public static GameEndResult error(String errorMessage) {
        return new GameEndResult(false, errorMessage);
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        if (acknowledged) {
            return "GameEndResult{acknowledged=true}";
        } else {
            return String.format("GameEndResult{acknowledged=false, errorMessage='%s'}", errorMessage);
        }
    }
}
