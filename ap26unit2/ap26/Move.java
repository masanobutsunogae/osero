package ap26;

import static ap26.Board.*;
import java.io.*;
import java.util.*;

/**
 * オセロの 1 手を表すクラス。
 *
 * <p>1 手は「マス番号 ({@link #index})」と「指した色 ({@link #color})」で表現される。
 * 通常の合法手の {@code index} は 0〜35 ({@link Board#LENGTH} 未満) だが、
 * 特殊な手を表すために負の値も使う。
 *
 * <h2>特殊値の意味</h2>
 * <table>
 *   <tr><th>定数</th><th>値</th><th>意味</th></tr>
 *   <tr><td>{@link #PASS}</td><td>-1</td><td>パス (合法手だが石を置けない)</td></tr>
 *   <tr><td>{@link #ERROR}</td><td>-2</td><td>例外発生 (失格)</td></tr>
 *   <tr><td>{@link #TIMEOUT}</td><td>-3</td><td>時間切れ (失格)</td></tr>
 *   <tr><td>{@link #ILLEGAL}</td><td>-100</td><td>不正手 (合法でない手を指した、失格)</td></tr>
 * </table>
 *
 * <h2>ファクトリメソッド</h2>
 * 通常はコンストラクタを直接呼ばず、ファクトリメソッドを使う:
 * <pre>
 *   Move m1 = Move.of(15, BLACK);     // マス番号 15 (d3) に黒を置く
 *   Move m2 = Move.of("d3", BLACK);   // 棋譜表記 "d3" に黒を置く
 *   Move m3 = Move.ofPass(BLACK);     // 黒のパス
 *   Move m4 = Move.ofTimeout(BLACK);  // 黒の時間切れ
 * </pre>
 *
 * <h2>判定メソッド</h2>
 * <pre>
 *   move.isLegal();   // PASS 含む合法手か
 *   move.isPass();    // パスか
 *   move.isFoul();    // 反則手か (ERROR/TIMEOUT/ILLEGAL)
 *   move.isTimeout(); // 時間切れか
 * </pre>
 *
 * <p>このクラスは {@link Serializable} を実装している。Socket 通信で
 * {@link ap26.league.proxy.MoveResult} 内に乗せて転送するため。
 */
public class Move implements Serializable {
    private static final long serialVersionUID = 1L;

    /** パス (合法手だが石を置けない場合)。{@code index = -1}。*/
    public final static int PASS = -1;

    /** 例外発生による失格。*/
    final static int ERROR = -2;

    /** 時間切れによる失格。*/
    final static int TIMEOUT = -3;

    /** 不正手 (合法でない手を指した) による失格。元の手番号 +Index は ILLEGAL - origIndex で符号化。*/
    final static int ILLEGAL = -100;

    /** マス番号 (0〜35 が合法、負値は特殊状態を表す)。*/
    int index;

    /** この手を指したプレイヤー色。*/
    Color color;

    /** マス番号と色から手を生成する。最も基本的なファクトリ。*/
    public static Move of(int index, Color color) {
        return new Move(index, color);
    }

    /** 棋譜表記 ("d3" 等) と色から手を生成する。*/
    public static Move of(String pos, Color color) {
        return new Move(parseIndex(pos), color);
    }

    /** 指定色のパスを生成する。*/
    public static Move ofPass(Color color) {
        return new Move(PASS, color);
    }

    /** 指定色の時間切れ手を生成する (失格扱い)。*/
    public static Move ofTimeout(Color color) {
        return new Move(TIMEOUT, color);
    }

    /**
     * 不正手を生成する。元の手番号を {@code ILLEGAL - origIndex} に符号化することで、
     * 「どのマスに置こうとして失格になったか」を後から取り出せる。
     */
    public static Move ofIllegal(Move origMove) {
        return new Move(ILLEGAL - origMove.index, origMove.color);
    }

    /** 指定色の例外発生手を生成する (失格扱い)。*/
    public static Move ofError(Color color) {
        return new Move(ERROR, color);
    }

    public Move(int index, Color color) {
        this.index = index;
        this.color = color;
    }

    /** マス番号を返す。負値の場合は特殊状態。*/
    public int getIndex() {
        return this.index;
    }

    /** 行番号 (0〜5)。{@code index / 6}。*/
    public int getRow() {
        return this.index / SIZE;
    }

    /** 列番号 (0〜5)。{@code index % 6}。*/
    public int getCol() {
        return this.index % SIZE;
    }

    public Color getColor() {
        return this.color;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.index, this.color);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Move other = (Move) obj;
        return this.index == other.index && this.color == other.color;
    }

    public boolean isNone() {
        return this.color == Color.NONE;
    }

    public boolean isLegal() {
        return this.index >= PASS;
    }

    public boolean isPass() {
        return this.index == PASS;
    }

    public boolean isFoul() {
        return this.index < PASS;
    }

    public boolean isTimeout() {
        return this.index == TIMEOUT;
    }

    public boolean isIllegal() {
        return this.index == ILLEGAL;
    }

    public boolean isError() {
        return this.index == ERROR;
    }

    public Move flipped() {
        return new Move(this.index, this.color.flipped());
    }

    public Move colored(Color color) {
        return new Move(this.index, color);
    }

    public static boolean isValid(int col, int row) {
        return 0 <= col && col < SIZE && 0 <= row && row < SIZE;
    }

    static int[][] offsets(int dist) {
        return new int[][] {
                { -dist, 0 }, { -dist, dist }, { 0, dist }, { dist, dist },
                { dist, 0 }, { dist, -dist }, { 0, -dist }, { -dist, -dist } };
    }

    public static List<Integer> adjacent(int k) {
        var ps = new ArrayList<Integer>();
        int col0 = k % SIZE, row0 = k / SIZE;

        for (var o : offsets(1)) {
            int col = col0 + o[0], row = row0 + o[1];
            if (Move.isValid(col, row))
                ps.add(index(col, row));
        }

        return ps;
    }

    public static List<Integer> line(int k, int dir) {
        var line = new ArrayList<Integer>();
        int col0 = k % SIZE, row0 = k / SIZE;

        for (int dist = 1; dist < SIZE; dist++) {
            var o = offsets(dist)[dir];
            int col = col0 + o[0], row = row0 + o[1];
            if (Move.isValid(col, row) == false)
                break;
            line.add(index(col, row));
        }

        return line;
    }

    public static int index(int col, int row) {
        return SIZE * row + col;
    }

    @Override
    public String toString() {
        return toIndexString(this.index);
    }

    public static int parseIndex(String pos) {
        // 処理エラー時の処理
        if ("#".equals(pos)) {
            return ERROR;
        } else if ("@".equals(pos)) {
            return TIMEOUT;
        } else if ("..".equals(pos)) {
            return PASS;
        } else if (pos.startsWith("!")) {
            return ILLEGAL - parseIndex(pos.substring(1));
        }

        return SIZE * (pos.charAt(1) - '1') + pos.charAt(0) - 'a';
    }

    public static String toIndexString(int index) {
        if (index < 0) {
            if (index == PASS)
                return "..";
            else if (index == TIMEOUT)
                return "@";
            else if (index == ERROR)
                return "#";
            else if (index <= ILLEGAL) {
                return "!" + toIndexString(-(index - ILLEGAL));
            }
        }
        return toColString(index % SIZE) + toRowString(index / SIZE);
    }

    public static String toColString(int col) {
        return Character.toString('a' + col);
    }

    public static String toRowString(int row) {
        return Character.toString('1' + row);
    }

    public static List<String> toStringList(List<Integer> moves) {
        return moves.stream().map(k -> toIndexString(k)).toList();
    }
}
