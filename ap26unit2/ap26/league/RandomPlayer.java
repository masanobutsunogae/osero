package ap26.league;

import ap26.*;
import java.util.*;

/**
 * 合法手の中から無作為に 1 手を選ぶ最弱クラスのプレイヤー。
 *
 * <p>主な用途:
 * <ul>
 *   <li>動作確認のベースライン (新しいプレイヤーが少なくとも RandomPlayer に勝てるか)</li>
 *   <li>リーグ運営の妥当性確認</li>
 *   <li>変則盤面でも問題なく動作するか確認する基準</li>
 * </ul>
 *
 * <p>典型的なオセロ AI に対しては勝率 5% 未満となるが、{@link #findLegalMoves}
 * が返すリストの順序や乱数の偏りによっては稀に勝つこともある。
 * 強い AI が RandomPlayer に「稀に負ける」現象は、評価関数の盲点を発見する
 * 手がかりとして観察に値する。
 */
public class RandomPlayer extends Player {
    /** 乱択用の乱数源。インスタンスごとに独立 (シード未固定なので試行ごとに異なる)。*/
    Random rand = new Random();

    /** プレイヤー名 "R" で構築する。リーグ結果表に "R" として表示される。*/
    public RandomPlayer(Color color) {
        super("R", color);
    }

    /**
     * 合法手のリストから一様乱択で 1 手を選んで返す。
     * 合法手が無い場合 (パスのみが返るケース) は、{@code moves.get(0)} がパスになる
     * 想定で同様に取り扱う。
     */
    public Move think(Board board) {
        var moves = board.findLegalMoves(getColor());
        var i = this.rand.nextInt(moves.size());
        return moves.get(i);
    }
}
