package ap26;

import java.util.*;

/**
 * オセロの石の色とマスの状態を表す列挙型。
 *
 * <p>「色」と呼ぶが、実際には次の 4 状態を表す:
 * <ul>
 *   <li>{@link #BLACK} (先手, 値 +1) — 黒石。表示記号 "o"</li>
 *   <li>{@link #WHITE} (後手, 値 -1) — 白石。表示記号 "x"</li>
 *   <li>{@link #NONE} (空, 値 0) — 何も置かれていないマス。表示記号 " "</li>
 *   <li>{@link #BLOCK} (障害, 値 +3) — 通行不可マス (変形盤面で使用)。表示記号 "#"</li>
 * </ul>
 *
 * <h2>{@code value} の役割</h2>
 * 評価関数で「マスの石の符号」を取り出すために {@code getValue()} を使う。
 * 例えば <pre>{@code score += weight[k] * board.get(k).getValue();}</pre>
 * とすれば、黒石は +weight、白石は -weight、空マスと BLOCK は 0 として
 * 1 行で集計できる。
 *
 * <p>注意: {@link #BLOCK} の値は +3 で、+1 や -1 ではないことに留意。
 * BLOCK マスを評価対象にしたくない場合は明示的に除外すること。
 */
public enum Color {
    /** 黒石 (先手)。評価値計算用の符号 = +1。*/
    BLACK(1),
    /** 白石 (後手)。評価値計算用の符号 = -1。*/
    WHITE(-1),
    /** 空マス。評価値計算用の符号 = 0。*/
    NONE(0),
    /** 通行不可マス。変形盤面で石を置けない位置を表す。値 = +3 (注意)。*/
    BLOCK(3);

    /** 盤面表示で各色に対応する記号。デバッグ・棋譜表示で使用。*/
    static Map<Color, String> SYMBOLS = Map.of(BLACK, "o", WHITE, "x", NONE, " ", BLOCK, "#");

    private final int value;

    private Color(int value) {
        this.value = value;
    }

    /** 評価関数等で使う符号値を返す。BLACK=+1, WHITE=-1, NONE=0, BLOCK=+3。*/
    public int getValue() {
        return this.value;
    }

    /**
     * BLACK と WHITE を入れ替える。NONE と BLOCK はそのまま返す。
     * 「次の手番のプレイヤー色を得る」「対称性のために色を反転する」用途。
     */
    public Color flipped() {
        return switch (this) {
            case BLACK -> WHITE;
            case WHITE -> BLACK;
            default -> this;
        };
    }

    @Override
    public String toString() {
        return SYMBOLS.get(this);
    }

    /**
     * "o" / "x" などの文字列から色を逆引きする。
     * 該当しない文字列は {@link #NONE} として扱う (BLOCK は逆引き対象外)。
     */
    public Color parse(String str) {
        return Map.of("o", BLACK, "x", WHITE).getOrDefault(str, NONE);
    }
}
