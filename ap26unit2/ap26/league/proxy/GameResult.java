package ap26.league.proxy;

import ap26.*;
import ap26.league.*;
import java.util.*;

/**
 * ゲーム結果情報
 *
 * PlayerProcessManager用の統一インターフェース
 * - タイムアウト判定
 * - 思考時間情報
 * - プロセス再利用判定のための情報
 */
public class GameResult {
    private final boolean isTimeout;
    private final long thinkTime;
    private final Player player;
    private final Color color;

    public GameResult(boolean isTimeout, long thinkTime, Player player, Color color) {
        this.isTimeout = isTimeout;
        this.thinkTime = thinkTime;
        this.player = player;
        this.color = color;
    }

    /**
     * ProxyGameから結果を生成
     */
    public static GameResult fromProxyGame(ProxyGame game, Color color) {
        Player player = (color == Color.BLACK) ? game.getBlackPlayer() : game.getWhitePlayer();

        // タイムアウト判定: 最後の手がタイムアウト手または反則負け
        boolean isTimeout = false;
        List<Move> moves = game.getMoves();
        if (!moves.isEmpty()) {
            Move lastMove = moves.get(moves.size() - 1);
            if (lastMove.isTimeout() || lastMove.isError()) {
                // 最後の手がタイムアウトまたはエラー → プロセス終了が必要
                isTimeout = true;
            }
        }

        // 思考時間: ProxyGameから取得
        long thinkTime = game.getThinkTime();

        return new GameResult(isTimeout, thinkTime, player, color);
    }

    public boolean isTimeout() {
        return isTimeout;
    }

    public long getThinkTime() {
        return thinkTime;
    }

    public Player getPlayer() {
        return player;
    }

    public Color getColor() {
        return color;
    }

    @Override
    public String toString() {
        return String.format("GameResult{player=%s, color=%s, timeout=%s, thinkTime=%dms}",
                player.toString(), color, isTimeout, thinkTime);
    }
}
