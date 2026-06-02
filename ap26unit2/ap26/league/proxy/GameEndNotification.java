package ap26.league.proxy;

import ap26.*;
import java.io.*;

/**
 * ゲーム終了通知。{@link PlayerProxy} → {@link PlayerMain} に Socket 経由で送る。
 *
 * <p>1 ゲーム終了時に呼ばれ、最終盤面・勝者・終了理由をプレイヤープロセスに伝える。
 * プレイヤー側はこれを受け取って学習等の後処理を行える (ただし本演習では未活用)。
 * 応答は {@link GameEndResult}。
 *
 * <p>通知後もプロセス自体は終了しない (次のゲームで再利用される)。プロセスの
 * 終了は {@link PlayerProcessManager#terminateAllProcesses()} で一括で行う。
 */
public class GameEndNotification implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Board finalBoard;
    private final Color winner;
    private final String reason;

    public GameEndNotification(Board finalBoard, Color winner, String reason) {
        this.finalBoard = finalBoard;
        this.winner = winner;
        this.reason = reason;
    }

    public Board getFinalBoard() {
        return finalBoard;
    }

    public Color getWinner() {
        return winner;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return String.format("GameEndNotification{finalBoard=%s, winner=%s, reason='%s'}",
                finalBoard, winner, reason);
    }
}
