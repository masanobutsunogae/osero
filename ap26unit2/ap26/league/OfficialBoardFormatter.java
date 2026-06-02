package ap26.league;

import ap26.*;
import static ap26.Board.*;
import static ap26.Color.*;
import java.util.*;

/**
 * {@link OfficialBoard} を人間可読な文字列に整形するユーティリティ。
 *
 * <p>出力例:
 * <pre>
 *     abcdef
 *   1|      | ..
 *   2|   .  | o: [d2, e3, b4, c5]
 *   3|  ox. | x: [d2, e3, b4, c5]
 *   4| .xo  |
 *   5|  .   |
 *   6|      |
 * </pre>
 * 各行末には、その手番の合法手リストを表示する (デバッグ用)。
 * {@code o} = BLACK, {@code x} = WHITE, {@code #} = BLOCK, 空白 = 空マス、
 * {@code .} = 現手番側の合法手位置。
 */
class OfficialBoardFormatter {
    static String format(OfficialBoard board) {
        var player = board.getTurn();
        var move = board.getMove();
        var blacks = board.findNoPassLegalIndexes(BLACK);
        var whites = board.findNoPassLegalIndexes(WHITE);
        var legals = Map.of(BLACK, blacks, WHITE, whites);

        var buf = new StringBuilder("  ");
        for (int k = 0; k < SIZE; k++)
            buf.append(Move.toColString(k));
        buf.append("\n");

        for (int k = 0; k < SIZE * SIZE; k++) {
            int col = k % SIZE;
            int row = k / SIZE;

            if (col == 0)
                buf.append((row + 1) + "|");

            if (board.get(k) == NONE) {
                boolean legal = false;
                var b = blacks.contains(k);
                var w = whites.contains(k);
                if (player == BLACK && b)
                    legal = true;
                if (player == WHITE && w)
                    legal = true;
                buf.append(legal ? '.' : ' ');
            } else {
                var s = board.get(k).toString();
                if (move != null && k == move.getIndex())
                    s = s.toUpperCase();
                buf.append(s);
            }

            if (col == SIZE - 1) {
                buf.append("| ");
                if (row == 0 && move != null) {
                    buf.append(move);
                } else if (row == 1) {
                    buf.append(player + ": " + Move.toStringList(legals.get(player)));
                }
                buf.append("\n");
            }
        }

        buf.setLength(buf.length() - 1);
        return buf.toString();
    }
}
