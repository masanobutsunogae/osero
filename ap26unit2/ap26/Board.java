package ap26;

import java.util.*;

/**
 * 6×6 オセロ盤面のインタフェース。
 *
 * <p>本演習で配布する全プレイヤー実装はこのインタフェースに対してプログラムする。
 * 具象実装には {@link ap26.league.OfficialBoard}（リーグ運営側で使用される標準実装）が
 * あるが、学生プレイヤーは {@code OfficialBoard} に依存せず、本インタフェースの
 * メソッドだけを使って動作するように書くこと。
 *
 * <h2>盤面の座標系</h2>
 * 各マスは 0〜35 の整数 {@code k} で表す。
 * <pre>
 *   k = 6 × row + col
 *
 *   col:  0  1  2  3  4  5     (記号: a b c d e f)
 *   row 0: 0  1  2  3  4  5    (記号: 1)
 *   row 1: 6  7  8  9 10 11    (記号: 2)
 *   row 2:12 13 14 15 16 17    (記号: 3)
 *   row 3:18 19 20 21 22 23    (記号: 4)
 *   row 4:24 25 26 27 28 29    (記号: 5)
 *   row 5:30 31 32 33 34 35    (記号: 6)
 * </pre>
 * 棋譜表記では列を a〜f、行を 1〜6 で表す (例: d3 = (col=3, row=2) = k=15)。
 *
 * <h2>主要メソッドの使い方</h2>
 * <pre>
 *   // 合法手の取得
 *   List&lt;Move&gt; moves = board.findLegalMoves(Color.BLACK);
 *
 *   // 着手して新しい盤面を得る (元の盤面は不変)
 *   Board newBoard = board.placed(moves.get(0));
 *
 *   // マスの状態取得
 *   Color c = board.get(15);   // d3 マスの色 (BLACK/WHITE/NONE/BLOCK)
 *
 *   // 終局判定
 *   if (board.isEnd()) {
 *       Color winner = board.winner();   // 勝者 (引き分けなら NONE)
 *   }
 * </pre>
 *
 * <h2>不変性 (immutability) の約束</h2>
 * {@link #placed(Move)}, {@link #flipped()}, {@link #clone()} は<b>必ず新しいオブジェクト
 * を返し、元の盤面は変更しない</b>。これにより、探索中に複数の局面候補を並行して
 * 保持できる。
 */
public interface Board {
    /** 盤面の一辺の長さ。6×6 オセロなので 6。*/
    int SIZE = 6;

    /** 盤面のマス総数。SIZE × SIZE = 36。*/
    int LENGTH = SIZE * SIZE;

    /**
     * 指定マスの状態を返す。
     * @param k マス番号 (0 〜 {@link #LENGTH}-1)
     * @return {@link Color#BLACK}, {@link Color#WHITE}, {@link Color#NONE} (空), {@link Color#BLOCK} (通行不可)
     */
    Color get(int k);

    /** 直前に指された手を返す。初期盤面では "なし" (PASS で color が NONE) を返す。*/
    Move getMove();

    /** 次に指す手番のプレイヤー色を返す。*/
    Color getTurn();

    /** 指定色の石数を返す。*/
    int count(Color color);

    /** 終局しているか。双方とも合法手がなくなった場合に true。*/
    boolean isEnd();

    /** 終局時の勝者を返す。引き分けまたは未終局なら {@link Color#NONE}。*/
    Color winner();

    /**
     * 指定色の即時敗北 (反則) を盤面に反映する。
     * 全マスを相手色で埋めることで、相手に最大スコアを与える。
     */
    void foul(Color color);

    /**
     * 黒石数 − 白石数を返す。
     * 終局時に片方が 0 石の場合は、空マスも勝者が獲得するものとして加算する
     * (これは「全マス支配」のオセロ慣習に従う)。
     */
    int score();

    /**
     * 指定色の合法手を全列挙する。合法手が無い場合は {@link Move#PASS} を 1 要素含む
     * リストが返る実装もあるので、呼び出し側で空チェックよりも
     * {@code findLegalMoves(color).get(0).isPass()} 等での確認が安全。
     */
    List<Move> findLegalMoves(Color color);

    /**
     * 指定の手を適用した新しい盤面を返す。元の盤面は変更しない。
     * 石を裏返す処理 (オセロの基本ルール) もこの中で行われる。
     */
    Board placed(Move move);

    /**
     * 黒と白を入れ替えた新しい盤面を返す。元の盤面は変更しない。
     * 「常に黒視点で探索する」対称性トリックに使う。
     */
    Board flipped();

    /** 盤面の独立コピーを返す。*/
    Board clone();

    /**
     * 盤面 ID を設定する (デフォルト実装は何もしない)。
     * リーグ運営側で複数盤面 (標準盤・変形盤) を識別するための補助情報。
     * 学生実装は通常意識しなくてよい。
     */
    default void setBoardId(String boardId) {
    }

    /**
     * 盤面 ID を返す (デフォルト実装は null)。
     * 例: 標準盤 = "#0", 変形盤 = "#1", "#2"。
     */
    default String getBoardId() {
        return null;
    }
}
