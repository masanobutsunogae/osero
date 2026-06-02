package ap26.league;

import ap26.*;
import static ap26.Color.*;
import java.util.*;
import java.util.stream.*;

/**
 * 同一 JVM 内でゲームを進行させるドライバ (テスト・デバッグ用)。
 *
 * <p>本番のリーグ戦では {@link ProxyGame} (Socket 通信版) が使われるが、
 * 単体プレイヤーの動作確認や、unit1 教材内の {@code MyGame} に相当する
 * ローカル動作確認を行いたい場合は本クラスを直接使う。
 *
 * <h2>{@link ProxyGame} との違い</h2>
 * <ul>
 *   <li>プロセス分離なし: 両プレイヤーが同 JVM で動作するので static 変数で干渉する可能性</li>
 *   <li>Socket 通信なし: think() を直接呼ぶ</li>
 *   <li>時間制限の単位: 各 think 呼び出しの実時間を累積し、{@link #timeLimit} 秒を
 *       超えた時点で時間切れ判定</li>
 * </ul>
 *
 * <h2>反則検出</h2>
 * {@link #check(Color, Move, Throwable)} で以下を検査する:
 * <ul>
 *   <li>{@link Player#think} 内での例外発生 → Move.ofError</li>
 *   <li>時間制限超過 → Move.ofTimeout</li>
 *   <li>合法でない手の返却 → Move.ofIllegal</li>
 * </ul>
 * 反則を検出した場合は {@link Board#foul(Color)} で勝者に全マスを与えて即時終了する。
 */
public class Game {
    /** 現在の盤面。{@link Board#placed(Move)} で毎手新しい盤面に差し替わる。*/
    Board board;

    /** 黒担当のプレイヤー。先手。*/
    Player black;

    /** 白担当のプレイヤー。後手。*/
    Player white;

    /** 色 → プレイヤーの逆引きマップ。現手番から該当プレイヤーを取り出す用。*/
    Map<Color, Player> players;

    /** 全手番の履歴。終局時に棋譜として表示。*/
    List<Move> moves = new ArrayList<>();

    /** プレイヤーごとの累積思考時間 (秒)。{@link #timeLimit} と比較。*/
    Map<Color, Float> times = new HashMap<>(Map.of(BLACK, 0f, WHITE, 0f));

    /** 1 プレイヤーあたりの持ち時間 (秒)。超過すると時間切れ反則扱い。*/
    long timeLimit;

    /**
     * 新しいゲームを構築する。
     * @param board     初期盤面 (内部でクローンするので呼び出し元の盤面は影響を受けない)
     * @param black     先手プレイヤー
     * @param white     後手プレイヤー
     * @param timeLimit プレイヤーあたりの持ち時間 (秒)
     */
    public Game(Board board, Player black, Player white, long timeLimit) {
        this.board = board.clone();
        this.black = black;
        this.white = white;
        this.players = Map.of(BLACK, black, WHITE, white);
        this.timeLimit = timeLimit;
    }

    public void play() {
        this.players.entrySet().forEach(i -> setBoard(i, this.board.clone()));

        while (this.board.isEnd() == false) {
            var turn = this.board.getTurn();
            var player = this.players.get(turn);

            Throwable error = null;
            long tm = System.currentTimeMillis();
            Move move;

            // play
            try {
                move = player.think(this.board.clone()).colored(turn);
            } catch (Throwable e) {
                error = e;
                move = Move.ofError(turn);
            }

            // record time
            tm = System.currentTimeMillis() - tm;
            final var t = (float) Math.max(tm, 1) / 1000.f;
            this.times.compute(turn, (k, v) -> v + t);

            // check
            move = check(turn, move, error);
            moves.add(move);

            // update board
            if (move.isLegal()) {
                this.board = this.board.placed(move);
            } else {
                this.board.foul(turn);
                break;
            }
        }

        printResult(this.board, moves);
    }

    void setBoard(Map.Entry<Color, Player> entry, Board board) {
        var color = entry.getKey();
        var player = entry.getValue();
        long tm = System.currentTimeMillis();
        try {
            player.setBoard(board);
        } catch (Throwable e) {
            System.err.printf("setBoard failed: %s, %s\n", color, player);
            System.err.println(board);
        }
        tm = System.currentTimeMillis() - tm;
        final var t = (float) tm / 1000.f;
        this.times.compute(color, (k, v) -> v + t);
    }

    Move check(Color turn, Move move, Throwable error) {
        if (move.isError()) {
            System.err.printf("error: %s %s\n", turn, error);
            System.err.println(board);
            return move;
        }

        if (move.isTimeout() || this.times.get(turn) > this.timeLimit) {
            System.err.printf("timeout: %s %.2f\n", turn, this.times.get(turn));
            System.err.println(board);
            return Move.ofTimeout(turn);
        }

        var legals = board.findLegalMoves(turn);
        if (legals.contains(move) == false) {
            System.err.printf("illegal move: %s %s\n", turn, move);
            System.err.println(board);
            return Move.ofIllegal(move);
        }

        return move;
    }

    public Player getWinner(Board board) {
        return this.players.get(board.winner());
    }

    public Player getPlayer(Color color) {
        return this.players.get(color);
    }

    public String resultString(Board board, List<Move> moves) {
        var result = String.format("%5s%-9s", "", "draw");
        var score = Math.abs(board.score());
        if (score > 0)
            result = String.format("%-4s won by %-2d", getWinner(board), score);

        // プレイヤー名部分を取得（[PROXY]プレフィックスを除く）
        String playerString = getPlayerString();

        // ボードIDがある場合は結果に含める
        String boardIdPrefix = "";
        if (board.getBoardId() != null) {
            boardIdPrefix = board.getBoardId() + ": ";
        }

        return boardIdPrefix + playerString + " -> " + result + "\t| " + toString(moves);
    }

    /**
     * プレイヤー名部分のみを取得（[PROXY]等のプレフィックスを除く）
     */
    protected String getPlayerString() {
        return String.format("%4s vs %4s", this.black, this.white);
    }

    public void printResult(Board board, List<Move> moves) {
        System.out.println(resultString(board, moves));
    }

    public String toString() {
        return String.format("%4s vs %4s", this.black, this.white);
    }

    public static String toString(List<Move> moves) {
        return moves.stream().map(x -> x.toString()).collect(Collectors.joining());
    }
}
