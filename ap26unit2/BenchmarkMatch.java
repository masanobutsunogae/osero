import static ap26.Color.*;

import ap26.*;
import ap26.league.OfficialBoard;

/**
 * 2 つの AI を先行・後攻を交互に入れ替えながら指定局数だけ対戦させ、 勝率と処理時間を計測するベンチマーク。
 *
 * <h2>使い方</h2>
 *
 * <pre>
 *   javac -d bin $(find . -name "*.java")
 *   java -cp "bin:." BenchmarkMatch
 * </pre>
 *
 * <h2>設定</h2>
 *
 * <ul>
 *   <li>{@link #NUM_GAMES} : 総対戦局数（先後均等にするため偶数推奨）
 *   <li>{@link #TIME_LIMIT_S} : 1 ゲームあたりの持ち時間（秒）
 * </ul>
 */
public class BenchmarkMatch {

  /** 総対戦局数。先後均等にするため偶数を推奨。 */
  static final int NUM_GAMES = 100;

  /** 1 ゲームあたりの持ち時間（秒）。超過すると時間切れ扱い。 */
  static final long TIME_LIMIT_S = 60;

  // ── 結果カウンタ ──────────────────────────────────────────
  static int winsA = 0;
  static int winsB = 0;
  static int draws = 0;

  /** プレイヤーごとの累積思考時間 (ms)。 */
  static long totalThinkMsA = 0;

  static long totalThinkMsB = 0;

  public static void main(String[] args) {

    // プレイヤー名を事前取得（表示用）
    String nameA = makePlayerA(BLACK).toString();
    String nameB = makePlayerB(WHITE).toString();

    System.out.println("=".repeat(60));
    System.out.printf(" Benchmark  : A=%s  vs  B=%s%n", nameA, nameB);
    System.out.printf(" Games      : %d  (time limit: %ds / game)%n", NUM_GAMES, TIME_LIMIT_S);
    System.out.println("=".repeat(60));

    long globalStart = System.nanoTime();

    for (int i = 0; i < NUM_GAMES; i++) {
      // 偶数ゲーム → A が先手(BLACK)、奇数ゲーム → B が先手(BLACK)
      boolean aIsBlack = (i % 2 == 0);

      Player playerA = makePlayerA(aIsBlack ? BLACK : WHITE);
      Player playerB = makePlayerB(aIsBlack ? WHITE : BLACK);

      Player black = aIsBlack ? playerA : playerB;
      Player white = aIsBlack ? playerB : playerA;

      System.out.printf("[Game %3d/%d] %s(B) vs %s(W) ... ", i + 1, NUM_GAMES, black, white);
      System.out.flush();

      long[] thinkMs = new long[2]; // [0]=black, [1]=white の思考時間
      long gameStart = System.nanoTime();
      Board finalBoard = playGame(black, white, thinkMs);
      long gameMs = (System.nanoTime() - gameStart) / 1_000_000;

      // プレイヤー A / B の思考時間を振り分けて累積
      long thinkMsForA = aIsBlack ? thinkMs[0] : thinkMs[1];
      long thinkMsForB = aIsBlack ? thinkMs[1] : thinkMs[0];
      totalThinkMsA += thinkMsForA;
      totalThinkMsB += thinkMsForB;

      // 勝敗判定
      Color winColor = finalBoard.winner();
      int score = finalBoard.score(); // 黒石数 - 白石数

      if (winColor == NONE) {
        // 引き分け
        draws++;
        System.out.printf(
            "Draw  (score=%+d)  [%,dms | A:%,dms B:%,dms]%n",
            score, gameMs, thinkMsForA, thinkMsForB);
      } else {
        Player winner = (winColor == BLACK) ? black : white;
        if (winner == playerA) {
          winsA++;
          System.out.printf(
              "A(%s) wins! (score=%+d)  [%,dms | A:%,dms B:%,dms]%n",
              playerA, score, gameMs, thinkMsForA, thinkMsForB);
        } else {
          winsB++;
          System.out.printf(
              "B(%s) wins! (score=%+d)  [%,dms | A:%,dms B:%,dms]%n",
              playerB, score, gameMs, thinkMsForA, thinkMsForB);
        }
      }
    }

    long totalMs = (System.nanoTime() - globalStart) / 1_000_000;

    // ── 集計 ─────────────────────────────────────────────────
    System.out.println();
    System.out.println("=".repeat(60));
    System.out.println(" RESULTS");
    System.out.println("=".repeat(60));
    System.out.printf(
        " Player A : %-10s  wins=%3d  (%.1f%%)%n", nameA, winsA, 100.0 * winsA / NUM_GAMES);
    System.out.printf(
        " Player B : %-10s  wins=%3d  (%.1f%%)%n", nameB, winsB, 100.0 * winsB / NUM_GAMES);
    System.out.printf(
        " Draws    :              draws=%3d  (%.1f%%)%n", draws, 100.0 * draws / NUM_GAMES);
    System.out.println("-".repeat(60));
    System.out.printf(" Total games : %d%n", NUM_GAMES);
    System.out.printf(" Total time  : %,d ms  (%.2f s)%n", totalMs, totalMs / 1000.0);
    System.out.printf(" Avg / game  : %.0f ms%n", (double) totalMs / NUM_GAMES);
    System.out.println("-".repeat(60));
    System.out.println(" Think time (total / avg per game)");
    System.out.printf(
        "   Player A %-10s : %,d ms  (avg %.0f ms/game)%n",
        nameA, totalThinkMsA, (double) totalThinkMsA / NUM_GAMES);
    System.out.printf(
        "   Player B %-10s : %,d ms  (avg %.0f ms/game)%n",
        nameB, totalThinkMsB, (double) totalThinkMsB / NUM_GAMES);
    System.out.println("=".repeat(60));
  }

  /**
   * 1 局を進行させ、終局後の盤面を返す。
   *
   * <p>Board を外部で保持しながらゲームループを回す（calNps スタイル）。 時間切れ・例外が発生した場合は {@link Board#foul} で即時終了する。
   *
   * @param black 先手プレイヤー
   * @param white 後手プレイヤー
   * @param thinkMs 出力用: [0]=黒の累積思考時間(ms), [1]=白の累積思考時間(ms)
   * @return 終局後の盤面
   */
  static Board playGame(Player black, Player white, long[] thinkMs) {
    Board board = new OfficialBoard();

    // 初期盤面を両プレイヤーに通知
    black.setBoard(board.clone());
    white.setBoard(board.clone());

    // 累積思考時間（ミリ秒）
    long blackMs = 0;
    long whiteMs = 0;
    thinkMs[0] = 0;
    thinkMs[1] = 0;

    while (!board.isEnd()) {
      Color turn = board.getTurn();
      Player player = (turn == BLACK) ? black : white;

      Move move;
      long t0 = System.currentTimeMillis();
      try {
        move = player.think(board.clone()).colored(turn);
      } catch (Throwable e) {
        System.err.printf("  [ERROR] %s threw: %s%n", player, e);
        board.foul(turn);
        break;
      }
      long elapsed = System.currentTimeMillis() - t0;

      // 累積時間を更新し、時間切れチェック
      if (turn == BLACK) {
        blackMs += elapsed;
        thinkMs[0] = blackMs;
        if (blackMs > TIME_LIMIT_S * 1000) {
          System.err.printf("  [TIMEOUT] %s (%.1fs)%n", black, blackMs / 1000.0);
          board.foul(BLACK);
          break;
        }
      } else {
        whiteMs += elapsed;
        thinkMs[1] = whiteMs;
        if (whiteMs > TIME_LIMIT_S * 1000) {
          System.err.printf("  [TIMEOUT] %s (%.1fs)%n", white, whiteMs / 1000.0);
          board.foul(WHITE);
          break;
        }
      }

      // 合法手チェック
      if (!board.findLegalMoves(turn).contains(move)) {
        System.err.printf("  [ILLEGAL] %s played %s%n", player, move);
        board.foul(turn);
        break;
      }

      board = board.placed(move);
    }

    return board;
  }

  // ── AI ファクトリ ─────────────────────────────────────────
  // 対戦させたい AI をここで変更する。

  /** AI-A を生成する。 */
  static Player makePlayerA(Color color) {
    return new p26x04.OurPlayer(color);
  }

  /** AI-B を生成する。 */
  static Player makePlayerB(Color color) {
    return new p26x05.OurPlayer(color);
  }
}
