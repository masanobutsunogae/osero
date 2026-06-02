package ap26.league.proxy;

import ap26.*;
import java.io.*;

/**
 * ゲーム初期化リクエスト。{@link PlayerProxy} → {@link PlayerMain} に Socket 経由で送る。
 *
 * <p>送信側 ({@link ProxyGame}) は新しいゲーム開始時に本リクエストを生成し、
 * プレイヤープロセスに送る。受信側 ({@link PlayerMain}) は
 * 指定されたクラスを reflection で生成し ({@code Class.forName(playerClass)})、
 * {@code (Color)} コンストラクタを呼んで Player インスタンスを得る。
 * 続いて {@code setBoard(initialBoard)} を呼んで初期盤面を伝える。
 *
 * <p>応答は {@link InitResult}。
 */
public class InitRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String playerClass;
    private final Color color;
    private final long totalTimeMs;
    private final Board initialBoard;

    public InitRequest(String playerClass, Color color, long totalTimeMs, Board initialBoard) {
        this.playerClass = playerClass;
        this.color = color;
        this.totalTimeMs = totalTimeMs;
        this.initialBoard = initialBoard;
    }

    public String getPlayerClass() {
        return playerClass;
    }

    public Color getColor() {
        return color;
    }

    public long getTotalTimeMs() {
        return totalTimeMs;
    }

    public Board getInitialBoard() {
        return initialBoard;
    }

    @Override
    public String toString() {
        return String.format("InitRequest{playerClass='%s', color=%s, totalTimeMs=%d, initialBoard=%s}",
                playerClass, color, totalTimeMs, initialBoard);
    }
}
