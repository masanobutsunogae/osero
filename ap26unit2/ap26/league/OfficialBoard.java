package ap26.league;

import ap26.*;
import static ap26.Color.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * リーグ運営側で使用する {@link Board} の標準実装。
 *
 * <p>学生は普通このクラスを直接使う必要はない (パッケージ ap26.league の中で
 * リーグシステムが自動的に使う)。{@link Board} インタフェースに対してプログラム
 * すれば、学生の実装も本クラスに対して動作する。
 *
 * <h2>内部データ表現</h2>
 * <ul>
 *   <li>{@code board[]}: 36 マスの色を {@link Color} 配列で保持</li>
 *   <li>{@code nones}: 空マスの番号集合 (合法手探索を高速化するキャッシュ)</li>
 *   <li>{@code move}: 直前に指された手</li>
 *   <li>{@code boardId}: 盤面の識別子 ("#0", "#1", ...)</li>
 * </ul>
 *
 * <h2>不変性 (immutability) について</h2>
 * {@link #placed(Move)} は新しい OfficialBoard を返し、元のインスタンスは変更しない。
 * これにより探索アルゴリズムが複数の局面候補を並行保持できる。
 * {@link Serializable} 実装は Socket 通信で盤面情報を別プロセスに転送するために必須。
 *
 * <h2>変形盤面 (BLOCK)</h2>
 * {@link Color#BLOCK} のマスはオセロのルール上、
 * 「石を置けない」「裏返し対象にならない」「合法手の候補から除外される」と扱う。
 * {@link League#makeBoard()} が変形盤面を生成し、{@link #set(int, Color)} で
 * BLOCK マスを配置する。
 */
public class OfficialBoard implements Board, Cloneable, Serializable {
    private static final long serialVersionUID = 1L;
    Color board[];
    Move move = new Move(Move.PASS, NONE);
    Set<Integer> nones = new TreeSet<>();
    private String boardId = null; // ボードID（#1, #2など）

    public OfficialBoard() {
        this.board = Stream.generate(() -> NONE).limit(LENGTH).toArray(Color[]::new);
        this.nones.addAll(IntStream.range(0, LENGTH).boxed().toList());
        init();
    }

    OfficialBoard(Color board[], Move move, Set<Integer> nones) {
        this.board = Arrays.copyOf(board, board.length);
        this.move = move;
        this.nones = new TreeSet<>(nones);
    }

    OfficialBoard(Color board[], Move move, Set<Integer> nones, String boardId) {
        this.board = Arrays.copyOf(board, board.length);
        this.move = move;
        this.nones = new TreeSet<>(nones);
        this.boardId = boardId;
    }

    /**
     * 36 マスの整数値配列から OfficialBoard を再構成する (JSON プロトコル用)。
     *
     * <p>JSON 通信で送られてくる {@code cells} 配列 (各要素 = Color.getValue()) と
     * 追加情報を組み合わせて、Java 上の OfficialBoard を構築する。
     *
     * @param cellValues 36 要素の整数配列。各要素は {@link Color#getValue()} と一致
     *                   ({@code BLACK=1, WHITE=-1, NONE=0, BLOCK=3})
     * @param lastMove   直前に指された手 (null の場合は初期状態相当)
     * @param boardId    盤面 ID ({@code "#0"} 等)、null 可
     * @return 復元された OfficialBoard インスタンス
     * @throws IllegalArgumentException 不正な値が含まれる場合
     */
    public static OfficialBoard fromCells(int[] cellValues, Move lastMove, String boardId) {
        if (cellValues == null || cellValues.length != LENGTH) {
            throw new IllegalArgumentException("cellValues must have length " + LENGTH);
        }
        Color[] cells = new Color[LENGTH];
        Set<Integer> nones = new TreeSet<>();
        for (int i = 0; i < LENGTH; i++) {
            cells[i] = switch (cellValues[i]) {
                case 1 -> BLACK;
                case -1 -> WHITE;
                case 0 -> NONE;
                case 3 -> BLOCK;
                default -> throw new IllegalArgumentException(
                    "Invalid cell value at index " + i + ": " + cellValues[i]);
            };
            if (cells[i] == NONE) {
                nones.add(i);
            }
        }
        Move move = (lastMove != null) ? lastMove : new Move(Move.PASS, NONE);
        return new OfficialBoard(cells, move, nones, boardId);
    }

    /**
     * 盤面の各マスの色値 (Color.getValue()) を 36 要素の int 配列で返す (JSON 用)。
     */
    public int[] toCellValues() {
        int[] result = new int[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            result[i] = this.board[i].getValue();
        }
        return result;
    }

    public OfficialBoard clone() {
        return new OfficialBoard(this.board, this.move, this.nones, this.boardId);
    }

    /**
     * ボードIDを設定
     */
    public void setBoardId(String boardId) {
        this.boardId = boardId;
    }

    /**
     * ボードIDを取得
     */
    public String getBoardId() {
        return this.boardId;
    }

    void init() {
        set(Move.parseIndex("c3"), BLACK);
        set(Move.parseIndex("d4"), BLACK);
        set(Move.parseIndex("d3"), WHITE);
        set(Move.parseIndex("c4"), WHITE);
    }

    public Color get(int k) {
        return this.board[k];
    }

    public Move getMove() {
        return this.move;
    }

    public Color getTurn() {
        return this.move.isNone() ? BLACK : this.move.getColor().flipped();
    }

    void set(int k, Color color) {
        this.board[k] = color;
        this.nones.remove(k);
    }

    void setAll(Color color) {
        IntStream.range(0, LENGTH).forEach(k -> this.board[k] = color);
    }

    public boolean equals(Object otherObj) {
        if (otherObj instanceof OfficialBoard) {
            var other = (OfficialBoard) otherObj;
            return Arrays.equals(this.board, other.board);
        }
        return false;
    }

    public String toString() {
        return OfficialBoardFormatter.format(this);
    }

    public int count(Color color) {
        return countAll().getOrDefault(color, 0L).intValue();
    }

    public boolean isEnd() {
        var lbs = findNoPassLegalIndexes(BLACK);
        var lws = findNoPassLegalIndexes(WHITE);
        return lbs.size() == 0 && lws.size() == 0;
    }

    public Color winner() {
        var v = score();
        if (isEnd() == false || v == 0)
            return NONE;
        return v > 0 ? BLACK : WHITE;
    }

    public void foul(Color color) {
        var winner = color.flipped();
        IntStream.range(0, LENGTH).forEach(k -> this.board[k] = winner);
    }

    public int score() {
        var cs = countAll();
        var bs = cs.getOrDefault(BLACK, 0L);
        var ws = cs.getOrDefault(WHITE, 0L);
        var ns = LENGTH - bs - ws;
        int score = (int) (bs - ws);

        if (bs == 0 || ws == 0)
            score += Integer.signum(score) * ns;

        return score;
    }

    Map<Color, Long> countAll() {
        return Arrays.stream(this.board).collect(
                Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    public List<Move> findLegalMoves(Color color) {
        return findLegalIndexes(color).stream()
                .map(k -> new Move(k, color)).toList();
    }

    List<Integer> findLegalIndexes(Color color) {
        var moves = findNoPassLegalIndexes(color);
        if (moves.size() == 0)
            moves.add(Move.PASS);
        return moves;
    }

    List<Integer> findNoPassLegalIndexes(Color color) {
        return this.nones.stream()
                .filter(k -> isLegal(k, color))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    boolean isLegal(int k, Color color) {
        return lines(k).stream()
                .anyMatch(line -> outflanked(line, color).size() > 0);
    }

    List<List<Integer>> lines(int k) {
        return IntStream.range(0, 8).boxed()
                .map(dir -> Move.line(k, dir)).toList();
    }

    List<Integer> outflanked(List<Integer> line, Color color) {
        if (line.size() > 1) {
            var flippables = new ArrayList<Integer>();
            for (var k : line) {
                var c = get(k);
                if (c == NONE || c == BLOCK)
                    break;
                if (c == color)
                    return flippables;
                flippables.add(k);
            }
        }
        return new ArrayList<>();
    }

    public OfficialBoard placed(Move move) {
        var b = clone();
        b.move = move;

        if (move.isPass() | move.isNone())
            return b;

        var k = move.getIndex();
        var color = move.getColor();
        b.lines(k).forEach(line -> {
            outflanked(line, color).forEach(k1 -> b.board[k1] = color);
        });
        b.set(k, color);

        return b;
    }

    public OfficialBoard flipped() {
        var b = clone();
        IntStream.range(0, LENGTH).forEach(k -> b.board[k] = b.board[k].flipped());
        b.move = this.move.flipped();
        return b;
    }
}
