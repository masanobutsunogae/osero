package myplayer;

import static ap26.Color.BLACK;
import static ap26.Color.WHITE;

import ap26.Board;
import ap26.Color;
import ap26.Move;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * α-β 法で次の一手を決めるオセロプレイヤー。
 *
 * <p>unit0 の {@link AlphaBetaPlayer} と探索アルゴリズムの構造はまったく同じ だが、ノード = オセロ盤面 ({@link Board})、ムーブ =
 * オセロの着手 ({@link Move}) という具体に置き換わっている。本クラスは「unit0 で学んだ抽象例を 実ゲームに適用する」具体例である。
 *
 * <h2>クラスの責務</h2>
 *
 * <ul>
 *   <li>{@link ap26.Player} を継承し、{@link #think(Board)} で次の一手を返す
 *   <li>内部で {@link MyEval} と α-β 探索を組み合わせる
 *   <li>探索の最大深さ {@link #depthLimit} を持つ
 * </ul>
 *
 * <h2>実装上のトリック</h2>
 *
 * <h3>1. 白番のときの盤面反転</h3>
 *
 * {@link #think(Board)} 内で、自分が白番のときは {@link Board#flipped()} で 盤面の色を反転して「常に黒視点で探索する」ようにしている。これにより
 * {@link #maxSearch} は常に黒の手を選ぶ、{@link #minSearch} は常に白の手を 選ぶ、と固定でき、探索コードに色の分岐を入れずに済む。
 *
 * <p>反転は対称性を利用したトリックで、評価関数 {@link MyEval#value(Board)} が黒視点で書かれていれば、反転後の評価値もまた「反転視点での黒（=
 * 元の白）」の優劣を正しく表す。
 *
 * <h3>2. 最善手の記録 {@code if (depth == 0) this.move = move;}</h3>
 *
 * α-β 探索は普通「評価値」だけを戻り値で返すが、ゲームプレイには 「どの手が最善か」も必要である。本実装では <b>ルートノード (depth == 0) でのみ</b>、α
 * が更新されたタイミングで該当ムーブをフィールド {@link #move} に保存している。
 *
 * <p>戻り値ではなく副作用で持ち回るのは、探索本体のシグネチャを unit0 と同じに保つための簡略化。本格的な実装では「評価値 + 最善手」 を構造体で返す方が綺麗。
 *
 * <h3>3. 同点時の手番揺らぎ {@link #order(List)}</h3>
 *
 * α-β は決定的で、同じ評価値の手が複数あると常に同じ手が選ばれる。 すると「相手が同じ局面に来たら必ず同じ手」と読まれて単調になる ため、{@link Collections#shuffle}
 * で手順をランダム化している。 本格的な実装では「最善手を優先的に探索する手順並び替え」を入れて カットを最大化するが、本教材では学習しやすさを優先。
 *
 * <h3>4. 全枝同点時のフォールバック {@code this.move = moves.get(0);}</h3>
 *
 * 全子ノードの評価値が初期 α と一致した場合、α 更新が一度も起きず、 {@link #move} が {@code null} のままになる。これを防ぐため、ループ前に
 * リストの先頭を仮の最善手として登録している。
 */
public class MyPlayer extends ap26.Player {

  /** デフォルトのプレイヤー名（リーグ戦で識別用）。 */
  static final String MY_NAME = "MY24";

  /** 評価関数。{@link MyEval} を参照。 */
  MyEval eval;

  /** 探索の最大深さ。深いほど強いが計算時間が指数的に増える。 */
  int depthLimit;

  /** ルートで決定した最善手（戻り値ではなく副作用で持ち回る）。 */
  Move move;

  /** 探索用の内部盤面。相手の手番を逐次反映する。 */
  MyBoard board;

  /** デフォルトコンストラクタ。深さ 2 で構築。 */
  public MyPlayer(Color color) {
    this(MY_NAME, color, new MyEval(), 2);
  }

  /** 全パラメータを明示するコンストラクタ。 */
  public MyPlayer(String name, Color color, MyEval eval, int depthLimit) {
    super(name, color);
    this.eval = eval;
    this.depthLimit = depthLimit;
    this.board = new MyBoard();
  }

  /** 名前と深さを指定するコンストラクタ（評価関数はデフォルト）。 */
  public MyPlayer(String name, Color color, int depthLimit) {
    this(name, color, new MyEval(), depthLimit);
  }

  /** ゲーム開始時に呼ばれる。リーグ戦システムから渡される {@link Board} を 内部の {@link MyBoard} に複製する。 */
  @Override
  public void setBoard(Board board) {
    for (int k = 0; k < ap26.Board.LENGTH; k++) {
      this.board.set(k, board.get(k));
    }
  }

  /** 自分が黒番か。 */
  boolean isBlack() {
    return getColor() == BLACK;
  }

  /**
   * 次の一手を返す。ゲームシステムから手番が来るたびに呼ばれる。
   *
   * <p>処理手順:
   *
   * <ol>
   *   <li>相手の直前手 ({@code board.getMove()}) を内部盤面に反映
   *   <li>合法手が無ければパス
   *   <li>あれば α-β 探索で最善手 {@link #move} を決定
   *   <li>決定した手を内部盤面にも反映して返す
   * </ol>
   */
  @Override
  public Move think(Board board) {
    // 1. 相手の直前手を反映
    this.board = this.board.placed(board.getMove());

    if (this.board.findNoPassLegalIndexes(getColor()).size() == 0) {
      // 2. 合法手なし → パス
      this.move = Move.ofPass(getColor());
    } else {
      // 3. 黒視点で探索するため、白番のときは盤面を反転
      MyBoard searchBoard = isBlack() ? this.board.clone() : this.board.flipped();
      this.move = null;

      // 副作用で this.move に最善手が記録される
      maxSearch(searchBoard, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0);

      // 反転して探索したので、最善手の色を自分の色に戻す
      this.move = this.move.colored(getColor());
    }

    // 4. 自分の指した手も内部盤面に反映
    this.board = this.board.placed(this.move);
    return this.move;
  }

  /**
   * α-β 探索の max 側。unit0 の {@link AlphaBetaPlayer#maxSearch} と ロジックは同じ。違いは「ルート (depth == 0) で最善手を
   * {@link #move} に保存する」点だけ。
   */
  float maxSearch(Board currentBoard, float alpha, float beta, int depth) {
    if (isTerminal(currentBoard, depth)) {
      return this.eval.value(currentBoard);
    }

    // 探索は常に黒視点なので、ここでは黒の合法手を生成
    List<Move> moves = currentBoard.findLegalMoves(BLACK);
    moves = order(moves);

    // フォールバック: 全枝が初期 α と同点だった場合に備えて
    // 暫定の最善手を仮登録（後でループ内の更新が一度も起きないと困るため）
    if (depth == 0) {
      this.move = moves.get(0);
    }

    // ループ変数は this.move フィールドと混同しないよう nextMove と命名
    for (Move nextMove : moves) {
      Board nextBoard = currentBoard.placed(nextMove);
      float childValue = minSearch(nextBoard, alpha, beta, depth + 1);

      if (childValue > alpha) {
        alpha = childValue;
        if (depth == 0) {
          // ルートでの最善手を記録（戻り値ではなく副作用で）
          this.move = nextMove;
        }
      }

      if (alpha >= beta) {
        // β カット: 祖先の min はこれ以上の値を許さない
        break;
      }
    }

    return alpha;
  }

  /**
   * α-β 探索の min 側。unit0 の {@link AlphaBetaPlayer#minSearch} と同じ。 探索は黒視点で進めるので、min 側は白（= 相手）の手を生成する。
   */
  float minSearch(Board currentBoard, float alpha, float beta, int depth) {
    if (isTerminal(currentBoard, depth)) {
      return this.eval.value(currentBoard);
    }

    List<Move> moves = currentBoard.findLegalMoves(WHITE);
    moves = order(moves);

    for (Move nextMove : moves) {
      Board nextBoard = currentBoard.placed(nextMove);
      float childValue = maxSearch(nextBoard, alpha, beta, depth + 1);
      beta = Math.min(beta, childValue);

      if (alpha >= beta) {
        // α カット
        break;
      }
    }

    return beta;
  }

  /** 探索打ち切り判定。unit0 と同じ。 */
  boolean isTerminal(Board currentBoard, int depth) {
    return currentBoard.isEnd() || depth > this.depthLimit;
  }

  /**
   * 探索する手順を並び替える。
   *
   * <p>本実装ではランダムシャッフルだけ。同じ評価値の手が複数あったとき、 毎回同じ手を選んで単調になるのを避ける。
   *
   * <p>本格的な実装では「過去の探索で良かった手を先に試す」など、 α-β カットを最大化する並び替えを入れる。
   */
  List<Move> order(List<Move> moves) {
    List<Move> shuffled = new ArrayList<>(moves);
    Collections.shuffle(shuffled);
    return shuffled;
  }
}
