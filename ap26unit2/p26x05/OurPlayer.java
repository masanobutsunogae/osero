package p26x05;

import static ap26.Color.BLACK;
import static ap26.Color.WHITE;

import ap26.Board;
import ap26.Color;
import ap26.Move;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * α-β 法で次の一手を決めるオセロプレイヤー。
 *
 * <p>ビットボード表現 ({@link OurBoard}) による高速な盤面操作と、 ルートノードでの並列探索 ({@link CompletableFuture}) を組み合わせている。
 */
public class OurPlayer extends ap26.Player {

  /** プレイヤー名（リーグ戦で識別用、ASCII 4文字）。 */
  static final String MY_NAME = "EFOR";

  /** 評価関数。 */
  MyEval eval;

  /** 探索の最大深さ。 */
  int depthLimit;

  /** ルートで決定した最善手。 */
  Move move;

  /** 探索用の内部盤面。 */
  OurBoard board;

  // マルチスレッド化用に探索の結果を保持する型
  record EvalResult(Move move, float score) {}

  // nps計算用
  static final boolean ENABLE_NPS_COUNT = true;
  AtomicLong nodeCount = new AtomicLong();
  double seconds;

  /** デフォルトコンストラクタ。深さ 6 で構築。 */
  public OurPlayer(Color color) {
    this(MY_NAME, color, new MyEval(), 9);
  }

  /** 全パラメータを明示するコンストラクタ。 */
  public OurPlayer(String name, Color color, MyEval eval, int depthLimit) {
    super(name, color);
    this.eval = eval;
    this.depthLimit = depthLimit;
    this.board = new OurBoard();
  }

  /** 名前と深さを指定するコンストラクタ。 */
  public OurPlayer(String name, Color color, int depthLimit) {
    this(name, color, new MyEval(), depthLimit);
  }

  /** ゲーム開始時に呼ばれる。渡された盤面を内部の OurBoard に複製する。 */
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
   * 次の一手を返す。
   *
   * <ol>
   *   <li>相手の直前手を内部盤面に反映
   *   <li>合法手が無ければパス
   *   <li>あれば α-β 探索で最善手を決定
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
      OurBoard searchBoard = isBlack() ? this.board.clone() : this.board.flipped();
      this.move = null;

      long startTime = System.nanoTime();

      // 副作用で this.move に最善手が記録される
      maxSearch(searchBoard, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0);

      long endTime = System.nanoTime();
      seconds += (endTime - startTime) / 1000000000.0;

      // 反転して探索したので、最善手の色を自分の色に戻す
      this.move = this.move.colored(getColor());
    }

    // 4. 自分の指した手も内部盤面に反映
    this.board = this.board.placed(this.move);
    return this.move;
  }

  /** α-β 探索の max 側。ルート (depth == 0) では並列探索を行う。 */
  float maxSearch(Board currentBoard, float alpha, float beta, int depth) {
    if (ENABLE_NPS_COUNT) nodeCount.incrementAndGet();

    if (isTerminal(currentBoard, depth)) {
      return this.eval.value(currentBoard);
    }

    // 探索は常に黒視点なので、ここでは黒の合法手を生成
    List<Move> moves = currentBoard.findLegalMoves(BLACK);
    moves = order(currentBoard, moves, BLACK, true);

    if (depth == 0) {
      // 各合法手に対して並列で探索
      List<CompletableFuture<EvalResult>> futures =
          moves.stream()
              .map(
                  nextMove ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            Board nextBoard = currentBoard.clone().placed(nextMove);
                            float childValue =
                                minSearch(
                                    nextBoard,
                                    Float.NEGATIVE_INFINITY,
                                    Float.POSITIVE_INFINITY,
                                    depth + 1);
                            return new EvalResult(nextMove, childValue);
                          }))
              .toList();

      // 全部が終わるのを待つ
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      // 結果からscoreが最大のものを見つける
      EvalResult bestResult =
          futures.stream()
              .map(CompletableFuture::join)
              .max((r1, r2) -> Float.compare(r1.score(), r2.score()))
              .orElseThrow();

      this.move = bestResult.move();
      return bestResult.score();
    } else {

      // ループ変数は this.move フィールドと混同しないよう nextMove と命名
      for (Move nextMove : moves) {
        Board nextBoard = currentBoard.placed(nextMove);
        float childValue = minSearch(nextBoard, alpha, beta, depth + 1);

        if (childValue > alpha) {
          alpha = childValue;
          if (depth == 0) {
            this.move = nextMove;
          }
        }

        if (alpha >= beta) {
          break;
        }
      }

      return alpha;
    }
  }

  /** α-β 探索の min 側。白（= 相手）の手を生成する。 */
  float minSearch(Board currentBoard, float alpha, float beta, int depth) {
    if (ENABLE_NPS_COUNT) nodeCount.incrementAndGet();

    if (isTerminal(currentBoard, depth)) {
      return this.eval.value(currentBoard);
    }

    List<Move> moves = currentBoard.findLegalMoves(WHITE);
    moves = order(currentBoard, moves, WHITE, false);

    for (Move nextMove : moves) {
      Board nextBoard = currentBoard.placed(nextMove);
      float childValue = maxSearch(nextBoard, alpha, beta, depth + 1);
      beta = Math.min(beta, childValue);

      if (alpha >= beta) {
        break;
      }
    }

    return beta;
  }

  /** 探索打ち切り判定。 */
  boolean isTerminal(Board currentBoard, int depth) {
    return currentBoard.isEnd() || depth > this.depthLimit;
  }

  /** 探索する手順を並び替える。 同じ評価値の手が複数あったとき、毎回同じ手を選んで単調になるのを避ける。 */
  List<Move> order(Board board, List<Move> moves, Color color, Boolean descending) {
    List<Move> ordered = new ArrayList<>(moves);

    ordered.sort(
        (m1, m2) -> {
          float v1 = eval.value(board.placed(m1.colored(color)));
          float v2 = eval.value(board.placed(m2.colored(color)));

          if (descending) {
            return Float.compare(v2, v1);
          } else {
            return Float.compare(v1, v2);
          }
        });

    return ordered;
  }

  public long getNodeCount() {
    return nodeCount.get();
  }

  public double getSeconds() {
    return seconds;
  }
}
