package myplayer;

import static ap26.Color.BLACK;
import static ap26.Color.WHITE;

import ap26.Board;
import ap26.Color;
import ap26.Move;
import ap26.Player;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 1 ゲームを進行させるドライバ。本格的なリーグ戦システム (unit2 の Competition26) の簡略版で、unit1 のローカル動作確認用。
 *
 * <h2>動作</h2>
 *
 * <ol>
 *   <li>2 人のプレイヤー (黒・白) と初期盤面を受け取る
 *   <li>{@link #play()} を呼ぶと、終局まで交互に手を進める
 *   <li>各手番で {@link Player#think(Board)} を呼び、合法性と時間を検査
 *   <li>反則 (時間切れ・不正手・例外) があれば即座に試合終了
 * </ol>
 *
 * <h2>使い方 (main)</h2>
 *
 * 既定では {@link MyPlayer} (黒) と {@link RandomPlayer} (白) を対戦させる。 自分の AI の動作確認には、{@link
 * #main(String[])} を編集して両プレイヤーを 差し替える。
 *
 * <h2>本格システム (unit2/Competition26) との違い</h2>
 *
 * <ul>
 *   <li>1 ゲームだけ実行 (リーグ戦・複数盤面は無し)
 *   <li>プロセス分離なし (両プレイヤーが同 JVM 内で動く)
 *   <li>結果集計は最小限
 * </ul>
 */
public class MyGame {

  /** エントリポイント。MyPlayer (黒) vs RandomPlayer (白) を 1 ゲーム実行。 自分のプレイヤーを試したい場合はこのメソッドを編集する。 */
  public static void main(String args[]) {
    Player player1 = new myplayer.HumanPlayer(BLACK);
    Player player2 = new myplayer.RandomPlayer(WHITE);
    Board board = new MyBoard();
    MyGame game = new MyGame(board, player1, player2);
    game.play();
  }

  /** 1 プレイヤーあたりの持ち時間 (秒)。超過すると反則負け。 */
  static final float TIME_LIMIT_SECONDS = 60;

  /** 現在の盤面。各手番で {@link Board#placed(Move)} により進む。 */
  Board board;

  /** 黒・白の各プレイヤー。 */
  Player black;

  Player white;

  /** 色 → プレイヤーの引き(turn から player を取り出す用)。 */
  Map<Color, Player> players;

  /** 全手番の履歴。終了時に表示用。 */
  List<Move> moves = new ArrayList<>();

  /** プレイヤーごとの累積思考時間 (秒)。{@link #TIME_LIMIT_SECONDS} と比較。 */
  Map<Color, Float> times = new HashMap<>(Map.of(BLACK, 0f, WHITE, 0f));

  /**
   * 新しい試合を構築する。
   *
   * @param initialBoard 初期盤面 (内部でクローンするので呼び出し元の盤面は変化しない)
   * @param black 黒担当のプレイヤー
   * @param white 白担当のプレイヤー
   */
  public MyGame(Board initialBoard, Player black, Player white) {
    this.board = initialBoard.clone();
    this.black = black;
    this.white = white;
    this.players = Map.of(BLACK, black, WHITE, white);
  }

  /**
   * 終局まで試合を進める。各手番で:
   *
   * <ul>
   *   <li>{@link Player#think(Board)} で次手を取得 (時間計測込み)
   *   <li>{@link #check(Color, Move, Error)} で時間切れ・不正手を検出
   *   <li>反則があれば {@link Board#foul(Color)} で即座に試合終了
   *   <li>正常なら {@link Board#placed(Move)} で盤面を進める
   * </ul>
   *
   * 終了後に {@link #printResult(Board, List)} で結果を表示する。
   */
  public void play() {
    // 全プレイヤーに初期盤面を通知 (内部状態の初期化に使われる)
    this.players.values().forEach(p -> p.setBoard(this.board.clone()));

    while (this.board.isEnd() == false) {
      Color turn = this.board.getTurn();
      Player player = this.players.get(turn);

      Error error = null;
      long t0 = System.currentTimeMillis();
      Move move;

      // think() を呼んで次手を取得。Error は捕捉して反則扱いにする
      // (StackOverflowError 等の致命例外で試合全体が止まるのを防ぐため)
      try {
        move = player.think(board.clone()).colored(turn);
      } catch (Error e) {
        error = e;
        move = Move.ofError(turn);
      }

      // 思考時間を記録 (最低 1 ミリ秒として加算)
      long t1 = System.currentTimeMillis();
      final float t = (float) Math.max(t1 - t0, 1) / 1000.f;
      this.times.compute(turn, (k, v) -> v + t);

      // 合法性・時間制限の検査。反則時は Move.ofXxx に置換される
      move = check(turn, move, error);
      moves.add(move);

      // 反則手なら foul() で勝者に全マスを与えて試合終了
      if (move.isLegal()) {
        board = board.placed(move);
      } else {
        board.foul(turn);
        break;
      }

      System.out.println(board);
    }

    printResult(board, moves);
  }

  /**
   * 手の合法性・時間制限を検査して、反則なら適切な反則手に置換する。
   *
   * @param turn 現在の手番色
   * @param move プレイヤーが返した手
   * @param error think() で発生した致命例外 (なければ null)
   * @return 検査済みの手。反則時は {@link Move#ofError(Color)} などに置換される。
   */
  Move check(Color turn, Move move, Error error) {
    if (move.isError()) {
      System.err.printf("error: %s %s", turn, error);
      System.err.println(board);
      return move;
    }

    if (this.times.get(turn) > TIME_LIMIT_SECONDS) {
      System.err.printf("timeout: %s %.2f", turn, this.times.get(turn));
      System.err.println(board);
      return Move.ofTimeout(turn);
    }

    List<Move> legals = board.findLegalMoves(turn);
    if (move == null || legals.contains(move) == false) {
      System.err.printf("illegal move: %s %s", turn, move);
      System.err.println(board);
      return Move.ofIllegal(turn);
    }

    return move;
  }

  /** 終局時の勝者を返す。引き分けなら NONE 担当の Player (= null)。 */
  public Player getWinner(Board finishedBoard) {
    return this.players.get(finishedBoard.winner());
  }

  /**
   * 試合結果を 1 行で表示する。例:
   *
   * <pre>
   *   MY24 vs    R -> MY24 won by 12   | d3c3e3...
   * </pre>
   */
  public void printResult(Board finishedBoard, List<Move> moveHistory) {
    String result = String.format("%5s%-9s", "", "draw");
    int score = Math.abs(finishedBoard.score());
    if (score > 0) {
      result = String.format("%-4s won by %-2d", getWinner(finishedBoard), score);
    }
    String s = toString() + " -> " + result + "\t| " + toString(moveHistory);
    System.out.println(s);
  }

  /** 対戦カードの表示用。例: "MY24 vs R"。 */
  @Override
  public String toString() {
    return String.format("%4s vs %4s", this.black, this.white);
  }

  /** 手順リストを連結して 1 文字列にする。例: "d3c3e3..."。 */
  public static String toString(List<Move> moveHistory) {
    return moveHistory.stream().map(Move::toString).collect(Collectors.joining());
  }
}
