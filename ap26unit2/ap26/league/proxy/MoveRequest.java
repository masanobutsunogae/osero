package ap26.league.proxy;

import ap26.*;
import java.io.*;

/**
 * 手番リクエスト。{@link PlayerProxy} → {@link PlayerMain} に Socket 経由で送る。
 *
 * <p>{@link ProxyGame} が手番ごとに本リクエストを生成し、現在の盤面と
 * 残り思考時間 (このゲームでの) を一緒に送る。受信側 ({@link PlayerMain}) は
 * Player の {@code think(Board)} (または think(Board, long)) を呼んで結果を
 * {@link MoveResult} として返す。
 *
 * <p>残り時間は学生プレイヤーが時間配分の判断に使える参考情報だが、
 * 実際の時間計測はリーグシステム側で行うため、申告した値とは無関係に
 * 60 秒を超えると時間切れ反則になる。
 */
public class MoveRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Board board;
    private final long remainingTimeMs;

    public MoveRequest(Board board, long remainingTimeMs) {
        this.board = board;
        this.remainingTimeMs = remainingTimeMs;
    }

    public Board getBoard() {
        return board;
    }

    public long getRemainingTimeMs() {
        return remainingTimeMs;
    }

    @Override
    public String toString() {
        return String.format("MoveRequest{board=%s, remainingTimeMs=%d}",
                board, remainingTimeMs);
    }
}
