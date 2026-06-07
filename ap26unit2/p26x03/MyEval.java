package p26x03;

import static ap26.Board.LENGTH;
import static ap26.Board.SIZE;

import java.util.stream.IntStream;

import ap26.Board;

/**
 * オセロ盤面の評価関数。
 *
 * <p>マスごとの重み行列で盤面を評価する（黒視点）。
 * 終局時は score() に大きな係数を掛けて、確実な勝敗を最優先する。
 */
public class MyEval {

  /**
   * マスごとの重み行列。M[row][col] でアクセスする。
   * 角・辺は大きな正の値、角の斜め隣 (X マス) は負の値。
   */
  static final float[][] M = {
      { 50,  -5, 10, 10,  -5, 50 },
      { -5, -10,  1,  1, -10, -5 },
      { 10,   1,  1,  1,   1, 10 },
      { 10,   1,  1,  1,   1, 10 },
      { -5, -10,  1,  1, -10, -5 },
      { 50,  -5, 10, 10,  -5, 50 },
  };

  /**
   * 盤面の評価値を返す（黒視点）。正なら黒有利、負なら白有利。
   */
  public float value(Board board) {
    if (board.isEnd()) {
      return 1_000_000 * board.score();
    }

    return (float) IntStream.range(0, LENGTH)
        .mapToDouble(k -> cellScore(board, k))
        .sum();
  }

  /** 1 マス分の評価値。黒石なら +M[r][c]、白石なら -M[r][c]、空マスは 0。*/
  float cellScore(Board board, int k) {
    int row = k / SIZE;
    int col = k % SIZE;
    return M[row][col] * board.get(k).getValue();
  }
}
