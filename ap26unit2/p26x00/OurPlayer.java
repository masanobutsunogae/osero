package p26x00;

import ap26.*;
import static ap26.Board.*;
import static ap26.Color.*;
import java.util.*;
import java.util.stream.*;

class MyEval {
  static float[][] M = {
      { 10, 10, 10, 10, 10, 10 },
      { 10, -5, 1, 1, -5, 10 },
      { 10, 1, 1, 1, 1, 10 },
      { 10, 1, 1, 1, 1, 10 },
      { 10, -5, 1, 1, -5, 10 },
      { 10, 10, 10, 10, 10, 10 },
  };

  public float value(Board board) {
    if (board.isEnd())
      return 1000000 * board.score();

    return (float) IntStream.range(0, LENGTH)
        .mapToDouble(k -> score(board, k))
        .reduce(Double::sum).orElse(0);
  }

  float score(Board board, int k) {
    return M[k / SIZE][k % SIZE] * board.get(k).getValue();
  }
}

public class OurPlayer extends ap26.Player {
  static final String MY_NAME = "2500";
  MyEval eval;
  int depthLimit;
  Move move;
  OurBoard board;

  public OurPlayer(Color color) {
    this(MY_NAME, color, new MyEval(), 4);
  }

  public OurPlayer(String name, Color color, MyEval eval, int depthLimit) {
    super(name, color);
    this.eval = eval;
    this.depthLimit = depthLimit;
    this.board = new OurBoard();
  }

  public OurPlayer(String name, Color color, int depthLimit) {
    this(name, color, new MyEval(), depthLimit);
  }

  public void setBoard(Board board) {
    for (var i = 0; i < LENGTH; i++) {
      this.board.set(i, board.get(i));
    }
  }

  boolean isBlack() {
    return getColor() == BLACK;
  }

  public Move think(Board board) {
    // 引数のboardを使用してBLOCK情報を保持
    setBoard(board);

    if (this.board.findNoPassLegalIndexes(getColor()).isEmpty()) {
      this.move = Move.ofPass(getColor());
    } else {
      var newBoard = isBlack() ? this.board.clone() : this.board.flipped();
      this.move = null;

      var legals = this.board.findNoPassLegalIndexes(getColor());

      maxSearch(newBoard, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0);

      this.move = this.move.colored(getColor());

      if (legals.contains(this.move.getIndex()) == false) {
        System.err.println("**************");
        System.err.println("ILLEGAL MOVE DETECTED IN OURPLAYER:");
        System.err.println("Legals: " + legals);
        System.err.println("Chosen move: " + this.move);
        System.err.println("Move index: " + this.move.getIndex());
        System.err.println("Internal board: " + this.board);
        System.err.println("Search board: " + newBoard);
        System.err.println("**************");
        // 非合法手の場合は最初の合法手を選択
        if (!legals.isEmpty()) {
          this.move = new Move(legals.get(0), getColor());
          System.err.println("Fallback to first legal move: " + this.move);
        } else {
          this.move = Move.ofPass(getColor());
          System.err.println("No legal moves, using pass");
        }
      }
    }

    this.board = this.board.placed(this.move);
    return this.move;
  }

  float maxSearch(Board board, float alpha, float beta, int depth) {
    if (isTerminal(board, depth))
      return this.eval.value(board);

    var moves = board.findLegalMoves(BLACK);
    moves = order(moves);

    if (depth == 0)
      this.move = moves.get(0);

    for (var move : moves) {
      var newBoard = board.placed(move);
      float v = minSearch(newBoard, alpha, beta, depth + 1);

      if (v > alpha) {
        alpha = v;
        if (depth == 0)
          this.move = move;
      }

      if (alpha >= beta)
        break;
    }

    return alpha;
  }

  float minSearch(Board board, float alpha, float beta, int depth) {
    if (isTerminal(board, depth))
      return this.eval.value(board);

    var moves = board.findLegalMoves(WHITE);
    moves = order(moves);

    for (var move : moves) {
      var newBoard = board.placed(move);
      float v = maxSearch(newBoard, alpha, beta, depth + 1);
      beta = Math.min(beta, v);
      if (alpha >= beta)
        break;
    }

    return beta;
  }

  boolean isTerminal(Board board, int depth) {
    return board.isEnd() || depth > this.depthLimit;
  }

  List<Move> order(List<Move> moves) {
    var shuffled = new ArrayList<Move>(moves);
    Collections.shuffle(shuffled);
    return shuffled;
  }
}
