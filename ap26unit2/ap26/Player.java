package ap26;

/**
 * オセロプレイヤーの基底クラス。
 *
 * <p>学生はこのクラスを継承して自分の AI を実装する。最低限、コンストラクタと
 * {@link #think(Board)} をオーバーライドすれば動作する。
 *
 * <h2>典型的な実装テンプレート</h2>
 * <pre>
 *   package p26x_dd_;   // dd は 2 桁チーム番号
 *
 *   public class OurPlayer extends ap26.Player {
 *       public OurPlayer(Color color) {
 *           super("26dd", color);   // プレイヤー名は 4 文字
 *       }
 *
 *       &#64;Override
 *       public Move think(Board board) {
 *           var moves = board.findLegalMoves(getColor());
 *           if (moves.isEmpty() || moves.get(0).isPass()) {
 *               return Move.ofPass(getColor());
 *           }
 *           // ここで探索アルゴリズムを実行
 *           return moves.get(0);   // 最低限、合法手を返す
 *       }
 *   }
 * </pre>
 *
 * <h2>呼び出しシーケンス</h2>
 * <ol>
 *   <li>コンストラクタ {@code OurPlayer(Color color)} で生成</li>
 *   <li>ゲーム開始時に {@link #setBoard(Board)} が呼ばれる (初期盤面通知)</li>
 *   <li>自分の手番ごとに {@link #think(Board)} が呼ばれる (次手を返す)</li>
 *   <li>ゲーム終了は別経路で通知される (Player には通知されない)</li>
 * </ol>
 *
 * <h2>注意点</h2>
 * <ul>
 *   <li>{@link #think(Board)} 内で例外を投げると失格扱い</li>
 *   <li>合法でない手を返すと失格扱い</li>
 *   <li>1 ゲームあたりの累積思考時間が 60 秒を超えると失格扱い</li>
 *   <li>渡される {@link Board} の具体実装に依存してはならない (将来差し替わる可能性)</li>
 * </ul>
 */
public abstract class Player {
    /** プレイヤー名 (4 文字)。リーグ結果表で表示される。*/
    String name;

    /** このプレイヤーが担当する色 (BLACK = 先手 / WHITE = 後手)。*/
    Color color;

    /** 内部で保持する盤面 (任意で活用)。{@link #setBoard(Board)} で更新される。*/
    Board board;

    /**
     * プレイヤーを構築する。
     * @param name  プレイヤー名 (英数字 4 文字推奨。例: "26AB", "X24A")
     * @param color 担当色 (BLACK = 先手, WHITE = 後手)
     */
    public Player(String name, Color color) {
        this.name = name;
        this.color = color;
    }

    /** 現在保持している内部盤面を返す。テスト用補助。*/
    public Board getBoard() {
        return board;
    }

    /**
     * ゲーム開始時にリーグシステムから呼ばれる。
     * 渡された盤面を内部状態に反映する (必要なら独自盤面表現にコピー)。
     */
    public void setBoard(Board board) {
        this.board = board;
    }

    /** 担当色を返す。{@link #think(Board)} 内で {@code getColor()} と多用する。*/
    public Color getColor() {
        return this.color;
    }

    /**
     * 担当色を変更する。通常は使わない (リーグシステムが先手・後手を入れ替えるときに
     * 内部で呼ぶことがある)。
     */
    public void setColor(Color color) {
        this.color = color;
    }

    @Override
    public String toString() {
        return this.name;
    }

    /**
     * 次の一手を返す。<b>学生は必ずこのメソッドをオーバーライドする。</b>
     *
     * @param board 現在の盤面 (この呼び出しのみ有効。後で再利用しないこと)
     * @return 自分が指す手。合法手を 1 つ選んで返す。
     *         合法手が無い場合は {@code Move.ofPass(getColor())} を返す。
     *         null や不正な手を返すと失格扱いになる。
     */
    public Move think(Board board) {
        return null;
    }

    /**
     * 残り思考時間を受け取るオーバーロード版。
     * リーグシステムは {@link #think(Board)} か本メソッドのいずれかを呼ぶ。
     * 残り時間を活用したい高度なプレイヤーはこちらをオーバーライドするとよい。
     * @param remainingTimeMs このゲームで残されている思考時間 (ミリ秒)
     */
    public Move think(Board board, long remainingTimeMs) {
        return null;
    }
}
