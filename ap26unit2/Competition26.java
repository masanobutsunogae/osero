import static ap26.Color.*;

import ap26.*;
import ap26.league.*;
import java.util.function.*;

/**
 * 2026 年度プログラミング応用杯のエントリポイント (学生向け配布版)。
 *
 * <p>参加プレイヤーと盤面数を設定して {@link League} を起動するだけの薄いクラス。 リーグの実行ロジック・対戦進行・結果集計はすべて {@link League} 以下の
 * ap26.league パッケージが担う。
 *
 * <h2>使い方</h2>
 *
 * <pre>
 *   javac -d bin $(find . -name "*.java")
 *   java -cp "bin:." Competition26
 * </pre>
 *
 * <h2>対戦相手の構成</h2>
 *
 * {@code builder} の {@code Player[]} に参加させたい AI を並べる。 各プレイヤーは {@code Player} クラスを継承し、{@code Color}
 * を受け取る コンストラクタを持っていなければならない (Socket 越しの reflection で生成される)。
 *
 * <h2>本ファイルのデフォルト構成</h2>
 *
 * <ul>
 *   <li>{@code p26x00.OurPlayer} : サンプル学生プレイヤー
 *   <li>{@code ap26.league.RandomPlayer} × 3 : ベースライン
 * </ul>
 *
 * 自チームの実装を試したいときは {@code new p26x_dd_.OurPlayer(color)} を追加する。
 *
 * <h2>関連設定</h2>
 *
 * <ul>
 *   <li>{@link #NUM_BOARD} : 使用する盤面の総数 (標準盤 1 + 変形盤 N-1)
 *   <li>{@link #TIME_LIMIT_SECONDS} : 1 ゲームあたりの持ち時間 (秒)
 * </ul>
 *
 * 本番のレギュレーション (勝ち点、変形盤生成方式等) は演習指示書の 「プログラミング応用杯のルール」を参照。
 */
public class Competition26 {

  /**
   * 使用する盤面の総数。{@link League} 内で標準盤 1 枚 + 変形盤 ({@code NUM_BOARD} - 1) 枚を生成する。 本番レギュレーション: 3 (標準 1 +
   * 変形 2)。
   */
  static final int NUM_BOARD = 3;

  /** 1 ゲームあたりの持ち時間 (秒)。プレイヤーの自手番合計時間がこれを超えると時間切れ反則。 本番レギュレーション: 60。 */
  static final long TIME_LIMIT_SECONDS = 60;

  public static void main(String args[]) {
    Function<Color, Player[]> builder =
        (Color color) -> {
          return new Player[] {
            // サンプル学生プレイヤー
            //            new p26x00.OurPlayer(color),

            // ベースライン
            // new ap26.league.RandomPlayer(color),
            // new ap26.league.RandomPlayer(color),
            // new ap26.league.RandomPlayer(color),

            // 自チームの実装を追加 (例):
            new p26x03.OurPlayer(color), new p26x04.OurPlayer(color),
          };
        };

    var league = new ap26.league.League(NUM_BOARD, builder, TIME_LIMIT_SECONDS);
    league.run();
  }
}
