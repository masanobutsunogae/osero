package ap26.league;

import ap26.*;
import static ap26.Color.*;
import ap26.league.proxy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * リーグ全体の運営を司るクラス。
 *
 * <p>{@link Competition26} から起動され、参加プレイヤー、盤面数、持ち時間を受け取って
 * 総当たり戦を実行する。
 *
 * <h2>リーグの構造</h2>
 * <pre>
 *   N プレイヤー × (N-1) 相手 × NUM_BOARD 盤面 × 2 色 = 総ゲーム数
 *
 *   例: 4 プレイヤー × 3 盤面 = 4 × 3 × 3 × 2 = 72? いいえ。
 *   実際: 4 × 3 × 3 = 36 ゲーム (各 i, j ペアは黒・白を入れ替えて 2 回出現するため)
 *
 *   実装上は for(i) for(j != i) で全 (i, j) を生成。これにより
 *   (i=A, j=B) と (i=B, j=A) で「先手と後手を入れ替えた 2 試合」が
 *   自動的に生まれる。
 * </pre>
 *
 * <h2>盤面の生成</h2>
 * <ul>
 *   <li>盤面 #0: 標準オセロ盤 (中央 4 マスに c3=黒, d3=白, c4=白, d4=黒)</li>
 *   <li>盤面 #1, #2, ...: 変形盤 ({@link #makeBoard()} で BLOCK マスを 1〜3 個ランダム配置)</li>
 * </ul>
 * 同じリーグ内では全ペアが同じ盤面セットで対戦する。
 *
 * <h2>並列実行</h2>
 * {@link #PARALLELISM} 並列で {@link ProxyGame} を実行する。プレイヤーごとに
 * 1 つの専用プロセス ({@link PlayerProcessManager} 管理) を割り当て、
 * Socket 通信で think() を呼ぶ。これにより:
 * <ul>
 *   <li>学生プレイヤーが暴走してもリーグ全体は止まらない</li>
 *   <li>プレイヤー間で static フィールドが干渉しない</li>
 *   <li>同時実行は {@link GameExecutor} がプレイヤーの BUSY/READY を排他制御</li>
 * </ul>
 *
 * <h2>結果集計</h2>
 * 全ゲーム完了後、{@link GameStatistics} が勝敗・勝ち点を集計して
 * {@link #printResult()} で結果表を出力する。
 */
public class League {
    /**
     * 同時に実行するゲーム数の上限。Mac での安全値は 2。
     * cse 環境ではより大きな値 (8 など) に増やせる。
     * 1 ゲームあたり 2 プロセス (黒・白) なので、最大同時プロセス数は {@code PARALLELISM × 2}。
     */
    final int PARALLELISM = 2; // 安全な並列度

    int n;
    Player[] players;
    Function<Color, Player[]> builder;
    List<ProxyGame> allGames;
    List<OfficialBoard> boards;
    long timeLimit;

    // ゲーム→マッチ情報マッピング（進捗管理用）
    private Map<ProxyGame, MatchInfo> gameToMatchMap = new HashMap<>();
    private List<MatchInfo> matchInfos = new ArrayList<>();

    /**
     * マッチ情報（Matchクラス代替）
     */
    private static class MatchInfo {
        final Player black;
        final Player white;
        final List<ProxyGame> games;
        final AtomicInteger completedGames = new AtomicInteger(0);

        MatchInfo(Player black, Player white, List<ProxyGame> games) {
            this.black = black;
            this.white = white;
            this.games = new ArrayList<>(games);
        }

        boolean isCompleted() {
            return completedGames.get() == games.size();
        }

        void markGameCompleted() {
            completedGames.incrementAndGet();
        }

        @Override
        public String toString() {
            return black.toString() + " vs " + white.toString() + " (" + completedGames.get() + "/" + games.size()
                    + " games)";
        }
    }

    ForkJoinPool pool = new ForkJoinPool(PARALLELISM);
    private final ProxyLogger logger = new ProxyLogger("League");
    private PlayerProcessManager processManager; // setupで初期化
    private final GameStatistics gameStatistics = new GameStatistics();
    private final ProgressReporter progressReporter = new ProgressReporter(PARALLELISM);

    /**
     * リーグを構築する。
     *
     * @param boardNum  使用する盤面の総数 (標準盤 1 枚 + 変形盤 boardNum-1 枚)
     * @param builder   プレイヤー生成関数。Color を受け取り、その色に設定された
     *                  全プレイヤーの配列を返す。リーグでは builder を 1 回呼んで
     *                  全プレイヤーのインスタンスを取得する (色は後で設定する)。
     * @param timeLimit 1 ゲームあたりの持ち時間 (秒)
     */
    public League(int boardNum, Function<Color, Player[]> builder, long timeLimit) {
        this.builder = builder;
        // 注意: 一度だけ builder を呼んで全プレイヤーを生成する
        // 同じインスタンスが先手・後手の両方を担当する (色は対戦時に設定)
        this.players = this.builder.apply(Color.NONE);
        this.n = this.players.length;
        this.timeLimit = timeLimit;
        this.boards = new ArrayList<>();

        // 標準ボード（#0）: c3=BLACK, d3=WHITE, c4=WHITE, d4=BLACK の初期配置
        OfficialBoard standardBoard = new OfficialBoard();
        standardBoard.setBoardId("#0");
        this.boards.add(standardBoard);

        // 変形ボード（#1, #2, ...）: makeBoard() で BLOCK マスを 1〜3 個ランダム配置
        for (int i = 0; i < boardNum - 1; i++) {
            OfficialBoard variantBoard = makeBoard();
            variantBoard.setBoardId("#" + (i + 1));
            this.boards.add(variantBoard);
        }

        // 生成した全盤面を表示 (デバッグ用)。学生・教員ともに対戦盤面を視覚確認できる
        this.boards.forEach(b -> {
            System.out.println(b.getBoardId() + ":");
            System.out.println(b);
            System.out.println();
        });
    }

    /**
     * 変形盤を 1 枚生成する。
     *
     * <p>標準盤に対して、以下の候補位置から 1〜3 個のマスをランダムに選び、
     * そこを {@link Color#BLOCK} で埋める:
     * <pre>
     *   候補: a1(0), b1(1), c1(2), d1(3), e1(4), f1(5),
     *         a2(6), a3(12), a4(18), a5(24), a6(30)
     *   ── 角と上辺・左辺の周辺に偏らせている (戦略の幅を維持しつつ盤面を変える)
     * </pre>
     *
     * <p>BLOCK マスはオセロのルール上「裏返し対象にならない」「合法手の対象外」と
     * 扱われる ({@link OfficialBoard#findLegalMoves} 等の実装を参照)。
     */
    OfficialBoard makeBoard() {
        var candidates = List.of(0, 1, 2, 3, 4, 5, 6, 12, 18, 24, 30);
        List<Integer> xs = new ArrayList<>(candidates);
        Collections.shuffle(xs);
        Random rand = new Random();
        var b = new OfficialBoard();

        // 1〜3 個 (nextInt(3) は 0,1,2 を返すので +1)
        for (var x : xs.subList(0, rand.nextInt(3) + 1)) {
            b.set(x, BLOCK);
        }

        return b;
    }

    /**
     * リーグを実行する。{@link #setup()} → {@link #executeAsync()} → {@link #printResult()} の順。
     * setup が失敗した場合のみ stderr に例外を出力するが、実行は続行する (printResult で結果 0 件として表示される)。
     */
    public void run() {
        try {
            setup();
        } catch (Exception e) {
            System.err.println(e);
        }

        executeAsync();
        printResult();
    }

    void setup() throws Exception {
        // Initialize PlayerProcessManager with fixed player list
        System.out.println("Initializing PlayerProcessManager...");
        this.processManager = new PlayerProcessManager(this.players);
        System.out.println("PlayerProcessManager initialized for " + this.players.length + " players");
        
        // GameStatisticsにProcessManagerを設定
        this.gameStatistics.setProcessManager(this.processManager);
        
        // Initialize port pool
        System.out.println("Initializing port pool...");
        try {
            PortManager.initializePool(PARALLELISM);
            System.out.println("Port pool initialized successfully using ring buffer approach");
            int expectedPorts = PARALLELISM * 2 * 2;
            System.out.println("Expected port pool size: " + expectedPorts + " ports for PARALLELISM=" + PARALLELISM);
            System.out.println("Max concurrent processes: " + (PARALLELISM * 2) + " (matches × players)");
        } catch (PortManager.PortPoolException e) {
            System.err.println("Failed to initialize port pool: " + e.getMessage());
            throw new RuntimeException("Port pool initialization failed", e);
        }

        this.allGames = new ArrayList<>();
        this.matchInfos = new ArrayList<>();


        // プレイヤーペアのゲーム生成（Matchクラス廃止）
        // 注意: 登録済みプレイヤーインスタンスを直接使用（新規作成しない）
        for (int i = 0; i < this.n; i++) {
            for (int j = 0; j < this.n; j++) {
                if (i == j)
                    continue;
                // 登録済みプレイヤーインスタンスを使用
                // 注意: 同じインスタンスを使用するが、色設定はゲーム実行時に行う
                var black = this.players[i];
                var white = this.players[j];

                // マッチ用ゲームリスト作成
                List<ProxyGame> matchGames = new ArrayList<>();
                for (OfficialBoard board : this.boards) {
                    ProxyGame game = new ProxyGame(board, black, white, this.timeLimit, true);
                    game.setProcessManager(this.processManager); // ProcessManager設定
                    matchGames.add(game);
                    this.allGames.add(game);
                }

                // マッチ情報作成
                MatchInfo matchInfo = new MatchInfo(black, white, matchGames);
                this.matchInfos.add(matchInfo);

                // ゲーム→マッチ情報マッピング
                for (ProxyGame game : matchGames) {
                    gameToMatchMap.put(game, matchInfo);
                }
            }
        }

        Collections.shuffle(this.allGames);

        // ProgressReporter初期化
        int totalMatches = this.matchInfos.size();
        int totalGames = this.allGames.size();
        progressReporter.initialize(totalMatches, totalGames);

        System.out.println("=== SETUP COMPLETED ===");
        System.out.println("Players: " + this.n);
        System.out.println("Boards per match: " + this.boards.size());
        System.out.println("Total matches generated: " + this.matchInfos.size());
        System.out.println("Total games generated: " + this.allGames.size());
        System.out.println("========================");
    }

    void execute() {
        // 同期実行（非推奨 - デバッグ用）
        for (var game : allGames) {
            game.play();
            onGameCompleted(game);
        }
    }

    void executeAsync() {
        try {
            // GameExecutor使用: デッドロック回避のキューベース実行（Player IDベース管理）
            GameExecutor gameExecutor = new GameExecutor(PARALLELISM, processManager);

            logger.info("Using GameExecutor for " + allGames.size() + " games from " + matchInfos.size() + " matches");

            // ゲーム実行（キューベース）
            gameExecutor.executeAllGames(allGames, this::onGameCompleted);

        } catch (Exception e) {
            System.err.println("Game execution error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ゲーム完了時の処理（統計更新・進捗報告）
     * プロセス管理はProxyGameに集約済み
     */
    private void onGameCompleted(ProxyGame game) {
        // ゲーム完了をカウント（ProgressReporter使用）
        progressReporter.updateGameProgress();

        // ゲーム統計記録（GameStatistics使用）
        gameStatistics.recordGameResult(game);

        // マッチ情報更新（Matchクラス代替）
        MatchInfo matchInfo = gameToMatchMap.get(game);
        if (matchInfo != null) {
            matchInfo.markGameCompleted();

            // マッチ内の全ゲーム完了チェック
            if (matchInfo.isCompleted()) {
                // Match完了として進捗更新（ProgressReporter使用）
                progressReporter.updateMatchProgress();
                logger.info("Completed match: " + matchInfo.toString());
            }
        } else {
            logger.warn("Game not found in match mapping: " + game.toString());
        }

        // 定期進捗報告チェック
        progressReporter.checkPeriodicReport();
    }


    void printResult() {
        System.out.println("\n=== LEAGUE COMPLETED ===");

        // ProgressReporter最終報告
        progressReporter.printFinalReport();

        // Print final port pool statistics
        System.out.println("Final port pool stats: Ring buffer port management completed");

        // Print process reuse efficiency report
        processManager.printEfficiencyReport();

        // Print unified game statistics
        gameStatistics.printFinalResults();

        // Terminate all remaining processes
        processManager.terminateAllProcesses();

        System.out.println("========================");

    }

}
