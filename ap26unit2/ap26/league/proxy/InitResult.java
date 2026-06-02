package ap26.league.proxy;

import java.io.*;

/**
 * {@link InitRequest} への応答。{@link PlayerMain} → {@link PlayerProxy} に返す。
 *
 * <p>成功時はプレイヤー名を含む {@link #success(String)} を、
 * 失敗時 (クラス見つからず・コンストラクタ呼び出し失敗等) は {@link #error(String)}
 * を返す。失敗時はそのゲームは即時失格扱いになる。
 */
public class InitResult implements Serializable {
    private static final long serialVersionUID = 1L;
    private final boolean success;
    private final String playerName;
    private final String errorMessage;

    public InitResult(boolean success, String playerName, String errorMessage) {
        this.success = success;
        this.playerName = playerName;
        this.errorMessage = errorMessage;
    }

    public static InitResult success(String playerName) {
        return new InitResult(true, playerName, null);
    }

    public static InitResult error(String errorMessage) {
        return new InitResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("InitResult{success=true, playerName='%s'}", playerName);
        } else {
            return String.format("InitResult{success=false, errorMessage='%s'}", errorMessage);
        }
    }
}
